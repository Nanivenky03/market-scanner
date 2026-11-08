package com.trading.scanner.service.instrument;

import com.trading.scanner.model.StockSectorClassification;
import com.trading.scanner.repository.StockSectorClassificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing stock-to-sector and sector-index classifications.
 * Fetches official NSE index constituent data dynamically, provides resilient
 * local classpath fallbacks, and serves lock-free O(1) in-memory lookups.
 *
 * One-time ingestion behavior:
 * If stock_sector_classification already contains records, startup download is skipped.
 */
@Service
@Slf4j
public class SectorClassificationSyncService {

    public record SyncResult(
            boolean successful,
            int totalProcessed,
            int insertedOrUpdated,
            String sourceUsed,
            String message
    ) {
        public static SyncResult skipped(long existingCount) {
            return new SyncResult(true, (int) existingCount, 0, "DATABASE",
                    "Classification table already populated with " + existingCount + " records. Re-download skipped.");
        }
    }

    private final StockSectorClassificationRepository classificationRepository;
    private final MarketIndexRegistry marketIndexRegistry;
    private final ResourceLoader resourceLoader;

    @Value("${market.sector.nse-url:https://niftyindices.com/IndexConstituent/ind_niftytotalmarket_list.csv}")
    private String nseTotalMarketCsvUrl = "https://niftyindices.com/IndexConstituent/ind_niftytotalmarket_list.csv";

    @Value("${market.sector.fallback-resource:classpath:data/ind_niftytotalmarket_list.csv}")
    private String fallbackResourcePath = "classpath:data/ind_niftytotalmarket_list.csv";

    @Value("${market.sector.auto-sync-on-empty:true}")
    private boolean autoSyncOnEmpty = true;

    private final Map<String, StockSectorClassification> inMemoryCache = new ConcurrentHashMap<>();

    public SectorClassificationSyncService(
            StockSectorClassificationRepository classificationRepository,
            MarketIndexRegistry marketIndexRegistry,
            ResourceLoader resourceLoader) {
        this.classificationRepository = classificationRepository;
        this.marketIndexRegistry = marketIndexRegistry;
        this.resourceLoader = resourceLoader;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (autoSyncOnEmpty) {
            initializeIfEmpty();
        } else {
            warmUpCacheFromDatabase();
        }
    }

    public synchronized SyncResult initializeIfEmpty() {
        long count = classificationRepository.count();
        if (count > 0) {
            warmUpCacheFromDatabase();
            log.info("Stock sector classification already populated ({} records). Skipping auto-download.", count);
            return SyncResult.skipped(count);
        }

        log.info("Stock sector classification table is empty. Performing initial ingestion from official NSE constituent data...");
        return syncSectorClassification(false);
    }

    @Transactional
    public synchronized SyncResult syncSectorClassification(boolean forceRefresh) {
        long currentCount = classificationRepository.count();
        if (!forceRefresh && currentCount > 0) {
            warmUpCacheFromDatabase();
            return SyncResult.skipped(currentCount);
        }

        String csvContent = null;
        String sourceUsed = "NSE_OFFICIAL_URL";

        try {
            csvContent = fetchFromNseHttp(nseTotalMarketCsvUrl);
            log.info("Successfully fetched live sector constituent CSV from NSE URL: {}", nseTotalMarketCsvUrl);
        } catch (Exception ex) {
            log.warn("Failed to fetch live CSV from NSE URL ({}: {}). Falling back to bundled classpath resource: {}",
                    ex.getClass().getSimpleName(), ex.getMessage(), fallbackResourcePath);
            try {
                csvContent = readFromClasspathResource(fallbackResourcePath);
                sourceUsed = "BUNDLED_CLASSPATH_CSV";
            } catch (Exception fallbackEx) {
                log.error("Failed to load bundled fallback resource: {}", fallbackEx.getMessage(), fallbackEx);
                return new SyncResult(false, 0, 0, "NONE",
                        "Failed to ingest sector classifications from both HTTP and bundled fallback: " + fallbackEx.getMessage());
            }
        }

        if (csvContent == null || csvContent.isBlank()) {
            return new SyncResult(false, 0, 0, sourceUsed, "Empty CSV content received");
        }

        List<StockSectorClassification> parsedEntities = parseCsvContent(csvContent, sourceUsed);
        if (parsedEntities.isEmpty()) {
            return new SyncResult(false, 0, 0, sourceUsed, "No valid classification records parsed from CSV");
        }

        // Upsert existing vs new entities
        Map<String, StockSectorClassification> existingMap = new HashMap<>();
        for (StockSectorClassification existing : classificationRepository.findAll()) {
            existingMap.put(existing.getSymbol().toUpperCase(Locale.ROOT), existing);
        }

        List<StockSectorClassification> toSave = new ArrayList<>();
        String now = LocalDateTime.now().toString();

        for (StockSectorClassification parsed : parsedEntities) {
            String key = parsed.getSymbol().toUpperCase(Locale.ROOT);
            StockSectorClassification target = existingMap.get(key);
            if (target != null) {
                target.setCompanyName(parsed.getCompanyName());
                target.setMacroSector(parsed.getMacroSector());
                target.setSectorIndexSymbol(parsed.getSectorIndexSymbol());
                target.setIndustry(parsed.getIndustry());
                target.setIsin(parsed.getIsin());
                target.setSource(sourceUsed);
                target.setUpdatedAt(now);
                toSave.add(target);
            } else {
                toSave.add(parsed);
            }
        }

        classificationRepository.saveAll(toSave);
        warmUpCacheFromDatabase();

        log.info("Stock sector classification synchronized successfully: totalProcessed={}, source={}",
                toSave.size(), sourceUsed);

        return new SyncResult(true, parsedEntities.size(), toSave.size(), sourceUsed,
                "Successfully synchronized " + toSave.size() + " stock sector classifications from " + sourceUsed);
    }

    private void warmUpCacheFromDatabase() {
        inMemoryCache.clear();
        for (StockSectorClassification classification : classificationRepository.findAll()) {
            if (classification.getSymbol() != null) {
                inMemoryCache.put(classification.getSymbol().toUpperCase(Locale.ROOT), classification);
            }
        }
        log.debug("Stock sector classification in-memory cache warmed up with {} symbols", inMemoryCache.size());
    }

    public Optional<String> getSectorIndexSymbol(String symbol) {
        if (symbol == null) return Optional.empty();
        StockSectorClassification classification = inMemoryCache.get(symbol.trim().toUpperCase(Locale.ROOT));
        if (classification != null) {
            return Optional.of(classification.getSectorIndexSymbol());
        }
        return classificationRepository.findBySymbol(symbol.trim().toUpperCase(Locale.ROOT))
                .map(StockSectorClassification::getSectorIndexSymbol);
    }

    public Optional<StockSectorClassification> getClassification(String symbol) {
        if (symbol == null) return Optional.empty();
        StockSectorClassification classification = inMemoryCache.get(symbol.trim().toUpperCase(Locale.ROOT));
        if (classification != null) {
            return Optional.of(classification);
        }
        return classificationRepository.findBySymbol(symbol.trim().toUpperCase(Locale.ROOT));
    }

    public List<String> getSymbolsForSector(String sectorIndexSymbol) {
        if (sectorIndexSymbol == null) return Collections.emptyList();
        String normalizedSector = sectorIndexSymbol.trim().toUpperCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (StockSectorClassification c : inMemoryCache.values()) {
            if (normalizedSector.equalsIgnoreCase(c.getSectorIndexSymbol())) {
                result.add(c.getSymbol());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private String fetchFromNseHttp(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/csv,text/plain,*/*")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP fetch failed with status code " + response.statusCode());
        }
        return response.body();
    }

    private String readFromClasspathResource(String resourcePath) throws Exception {
        Resource resource = resourceLoader.getResource(resourcePath);
        try (InputStream is = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    List<StockSectorClassification> parseCsvContent(String csvText, String source) {
        List<StockSectorClassification> list = new ArrayList<>();
        if (csvText == null || csvText.isBlank()) {
            return list;
        }

        String now = LocalDateTime.now().toString();
        String[] lines = csvText.split("\\r?\\n");
        if (lines.length <= 1) {
            return list;
        }

        // Expected header: Company Name,Industry,Symbol,Series,ISIN Code
        int symbolIdx = 2;
        int companyIdx = 0;
        int industryIdx = 1;
        int isinIdx = 4;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            List<String> tokens = parseCsvLine(line);
            if (tokens.size() <= symbolIdx) continue;

            String symbol = tokens.get(symbolIdx).trim();
            if (symbol.isEmpty()) continue;

            String companyName = tokens.size() > companyIdx ? tokens.get(companyIdx).trim() : symbol;
            String industry = tokens.size() > industryIdx ? tokens.get(industryIdx).trim() : "Diversified";
            String isin = tokens.size() > isinIdx ? tokens.get(isinIdx).trim() : null;

            String sectorIndex = mapIndustryToSectorIndex(industry, symbol, companyName);
            String macroSector = mapIndustryToMacroSector(industry);

            StockSectorClassification entity = StockSectorClassification.builder()
                    .symbol(symbol)
                    .companyName(companyName)
                    .macroSector(macroSector)
                    .sectorIndexSymbol(sectorIndex)
                    .industry(industry)
                    .isin(isin)
                    .isActive(true)
                    .source(source)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            list.add(entity);
        }

        return list;
    }

    public String mapIndustryToSectorIndex(String industry, String symbol, String companyName) {
        if (industry == null || industry.isBlank()) {
            return "NIFTY 500";
        }

        String ind = industry.trim();
        String sym = symbol != null ? symbol.trim().toUpperCase(Locale.ROOT) : "";
        String comp = companyName != null ? companyName.trim().toUpperCase(Locale.ROOT) : "";

        if ("Information Technology".equalsIgnoreCase(ind)) {
            return "NIFTY IT";
        }
        if ("Automobile and Auto Components".equalsIgnoreCase(ind)) {
            return "NIFTY AUTO";
        }
        if ("Fast Moving Consumer Goods".equalsIgnoreCase(ind)) {
            return "NIFTY FMCG";
        }
        if ("Metals & Mining".equalsIgnoreCase(ind)) {
            return "NIFTY METAL";
        }
        if ("Healthcare".equalsIgnoreCase(ind)) {
            return "NIFTY PHARMA";
        }
        if ("Oil Gas & Consumable Fuels".equalsIgnoreCase(ind) || "Power".equalsIgnoreCase(ind) || "Utilities".equalsIgnoreCase(ind)) {
            return "NIFTY ENERGY";
        }
        if ("Construction".equalsIgnoreCase(ind) || "Construction Materials".equalsIgnoreCase(ind) || "Capital Goods".equalsIgnoreCase(ind)) {
            return "NIFTY INFRA";
        }
        if ("Realty".equalsIgnoreCase(ind)) {
            return "NIFTY REALTY";
        }
        if ("Media Entertainment & Publication".equalsIgnoreCase(ind)) {
            return "NIFTY MEDIA";
        }
        if ("Consumer Durables".equalsIgnoreCase(ind) || "Consumer Services".equalsIgnoreCase(ind) || "Textiles".equalsIgnoreCase(ind)) {
            return "NIFTY CONSUMPTION";
        }
        if ("Chemicals".equalsIgnoreCase(ind) || "Forest Materials".equalsIgnoreCase(ind)) {
            return "NIFTY COMMODITIES";
        }
        if ("Telecommunication".equalsIgnoreCase(ind) || "Services".equalsIgnoreCase(ind)) {
            return "NIFTY SERV SECTOR";
        }
        if ("Financial Services".equalsIgnoreCase(ind)) {
            boolean isBank = (sym.contains("BANK") || comp.contains("BANK")
                    || isCoreBankSymbol(sym)) && !"FEDFINA".equals(sym);
            return isBank ? "NIFTY BANK" : "NIFTY FIN SERVICE";
        }

        return "NIFTY 500";
    }

    private boolean isCoreBankSymbol(String sym) {
        return "CANBK".equals(sym) || "INDIANB".equals(sym) || "IOB".equals(sym)
                || "SBIN".equals(sym) || "PNB".equals(sym) || "CUB".equals(sym)
                || "IDBI".equals(sym) || "IDFCFIRSTB".equals(sym)
                || "MAHABANK".equals(sym) || "CENTRALBK".equals(sym)
                || "UCOBANK".equals(sym) || "SOUTHBANK".equals(sym);
    }

    private String mapIndustryToMacroSector(String industry) {
        if (industry == null || industry.isBlank()) {
            return "Diversified";
        }
        return industry.trim();
    }

    private List<String> parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens;
    }
}
