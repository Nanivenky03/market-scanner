package com.trading.scanner.config;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockUniverseSeeder {

        private static final LocalTime MORNING_MAINTENANCE_CUTOFF = LocalTime.of(7, 0);

        private final StockUniverseRepository stockUniverseRepository;
        private final InstrumentMasterRepository instrumentMasterRepository;
        private final TimeProvider timeProvider;
        private final TradingCalendar tradingCalendar;

        @Transactional
        public SeedResult seedIfNeeded() {
                long existing = stockUniverseRepository.count();

                if (existing > 0L) {
                        log.info(
                                        "Stock universe already initialized. Skipping.");

                        return new SeedResult(
                                        0,
                                        "Stock universe already initialized. Skipping.");
                }

                List<String> symbols = loadConfiguredSymbols();

                if (symbols.isEmpty()) {
                        throw new IllegalStateException(
                                        "Stock universe CSV contains no symbols");
                }

                LocalDate activeFrom = resolveInitialActiveFromDate();

                List<StockUniverse> rows = new ArrayList<>();

                for (String symbol : symbols) {
                        InstrumentMaster instrument = instrumentMasterRepository
                                        .findBySymbolAndExchange(
                                                        symbol,
                                                        "NSE")
                                        .orElseThrow(() -> new IllegalStateException(
                                                        "Configured stock symbol was not found "
                                                                        + "in instrument_master: "
                                                                        + symbol));

                        if (!Boolean.TRUE.equals(
                                        instrument.getIsActive())) {
                                throw new IllegalStateException(
                                                "Configured stock symbol is inactive in "
                                                                + "instrument_master: "
                                                                + symbol);
                        }

                        if (!"EQUITY".equalsIgnoreCase(
                                        instrument.getInstrumentType())) {
                                throw new IllegalStateException(
                                                "Configured stock symbol is not an equity: "
                                                                + symbol
                                                                + ", type="
                                                                + instrument.getInstrumentType());
                        }

                        rows.add(
                                        StockUniverse.builder()
                                                        .symbol(instrument.getSymbol())
                                                        .exchange(Exchange.NSE)
                                                        .companyName(instrument.getCompanyName())
                                                        .sector(null)
                                                        .isActive(true)
                                                        .isTradable(false)
                                                        .activeFrom(activeFrom)
                                                        .build());
                }

                stockUniverseRepository.saveAll(rows);

                log.info(
                                "Initialized stock universe from instrument_master. "
                                                + "configured={}, inserted={}, tradable=false, activeFrom={}",
                                symbols.size(),
                                rows.size(),
                                activeFrom);

                return new SeedResult(
                                rows.size(),
                                "Stock universe initialized from instrument_master");
        }

        private LocalDate resolveInitialActiveFromDate() {
                LocalDate today = timeProvider.today();
                LocalTime now = timeProvider.nowDateTime().toLocalTime();

                if (now.isBefore(MORNING_MAINTENANCE_CUTOFF)) {
                        return today;
                }
                return tradingCalendar.nextTradingDay(today);
        }

        private List<String> loadConfiguredSymbols() {
                try {
                        ClassPathResource resource = new ClassPathResource(
                                        "bootstrap/simulation-universe-nse.csv");

                        Map<String, String> uniqueSymbols = new LinkedHashMap<>();

                        try (BufferedReader reader = new BufferedReader(
                                        new InputStreamReader(
                                                        resource.getInputStream(),
                                                        StandardCharsets.UTF_8))) {

                                String line;
                                boolean header = true;

                                while ((line = reader.readLine()) != null) {
                                        if (header) {
                                                header = false;
                                                continue;
                                        }

                                        if (line.isBlank()) {
                                                continue;
                                        }

                                        String[] parts = line.split(",", 2);

                                        String symbol = parts[0]
                                                        .trim()
                                                        .toUpperCase(Locale.ROOT);

                                        if (symbol.isBlank()) {
                                                continue;
                                        }

                                        if (uniqueSymbols.putIfAbsent(
                                                        symbol,
                                                        symbol) != null) {
                                                throw new IllegalStateException(
                                                                "Duplicate configured stock symbol: "
                                                                                + symbol);
                                        }
                                }
                        }

                        return new ArrayList<>(
                                        uniqueSymbols.values());

                } catch (Exception ex) {
                        throw new IllegalStateException(
                                        "Failed to load configured stock symbols",
                                        ex);
                }
        }

        public record SeedResult(
                        int inserted,
                        String message) {
        }

}
