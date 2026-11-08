package com.trading.scanner.service.instrument;

import com.trading.scanner.model.StockSectorClassification;
import com.trading.scanner.repository.StockSectorClassificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SectorClassificationSyncServiceTest {

    private StockSectorClassificationRepository repository;
    private MarketIndexRegistry marketIndexRegistry;
    private ResourceLoader resourceLoader;
    private SectorClassificationSyncService service;

    private static final String SAMPLE_CSV = """
            Company Name,Industry,Symbol,Series,ISIN Code
            Infosys Ltd.,Information Technology,INFY,EQ,INE009A01021
            Tata Motors Ltd.,Automobile and Auto Components,TATAMOTORS,EQ,INE155A01022
            HDFC Bank Ltd.,Financial Services,HDFCBANK,EQ,INE040A01034
            Bajaj Finance Ltd.,Financial Services,BAJFINANCE,EQ,INE296A01024
            Tata Steel Ltd.,Metals & Mining,TATASTEEL,EQ,INE081A01020
            Sun Pharmaceutical Industries Ltd.,Healthcare,SUNPHARMA,EQ,INE044A01036
            ITC Ltd.,Fast Moving Consumer Goods,ITC,EQ,INE154A01025
            Reliance Industries Ltd.,Oil Gas & Consumable Fuels,RELIANCE,EQ,INE002A01018
            DLF Ltd.,Realty,DLF,EQ,INE271C01023
            Larsen & Toubro Ltd.,Capital Goods,LT,EQ,INE018A01030
            Zee Entertainment Enterprises Ltd.,Media Entertainment & Publication,ZEEL,EQ,INE256A01028
            Pidilite Industries Ltd.,Chemicals,PIDILITIND,EQ,INE318A01026
            Titan Company Ltd.,Consumer Durables,TITAN,EQ,INE280A01028
            Blue Dart Express Ltd.,Services,BLUEDART,EQ,INE233B01017
            """;

    @BeforeEach
    void setUp() {
        repository = mock(StockSectorClassificationRepository.class);
        marketIndexRegistry = new MarketIndexRegistry();
        resourceLoader = mock(ResourceLoader.class);

        when(resourceLoader.getResource(anyString()))
                .thenReturn(new ByteArrayResource(SAMPLE_CSV.getBytes()));

        service = new SectorClassificationSyncService(repository, marketIndexRegistry, resourceLoader);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "nseTotalMarketCsvUrl", "https://test.invalid/dummy.csv");
    }

    @Test
    void initializeIfEmpty_whenTableAlreadyHasRecords_shouldSkipDownload() {
        when(repository.count()).thenReturn(500L);
        when(repository.findAll()).thenReturn(List.of(
                StockSectorClassification.builder()
                        .symbol("INFY")
                        .sectorIndexSymbol("NIFTY IT")
                        .macroSector("Information Technology")
                        .isActive(true)
                        .source("DATABASE")
                        .build()
        ));

        SectorClassificationSyncService.SyncResult result = service.initializeIfEmpty();

        assertTrue(result.successful());
        assertEquals("DATABASE", result.sourceUsed());
        assertEquals(0, result.insertedOrUpdated());
        assertTrue(result.message().contains("already populated"));

        verify(repository, never()).saveAll(anyList());
        assertEquals(Optional.of("NIFTY IT"), service.getSectorIndexSymbol("INFY"));
    }

    @Test
    void initializeIfEmpty_whenTableIsEmpty_shouldSyncFromResourceAndSave() {
        when(repository.count()).thenReturn(0L);
        List<StockSectorClassification> savedRecords = new ArrayList<>();
        when(repository.saveAll(anyList())).thenAnswer(inv -> {
            List<StockSectorClassification> list = inv.getArgument(0);
            savedRecords.addAll(list);
            return list;
        });

        SectorClassificationSyncService.SyncResult result = service.initializeIfEmpty();

        assertTrue(result.successful());
        assertFalse(savedRecords.isEmpty());
        assertEquals(14, savedRecords.size());

        // Verify specific sector mappings
        assertMapping(savedRecords, "INFY", "NIFTY IT");
        assertMapping(savedRecords, "TATAMOTORS", "NIFTY AUTO");
        assertMapping(savedRecords, "HDFCBANK", "NIFTY BANK");
        assertMapping(savedRecords, "BAJFINANCE", "NIFTY FIN SERVICE");
        assertMapping(savedRecords, "TATASTEEL", "NIFTY METAL");
        assertMapping(savedRecords, "SUNPHARMA", "NIFTY PHARMA");
        assertMapping(savedRecords, "ITC", "NIFTY FMCG");
        assertMapping(savedRecords, "RELIANCE", "NIFTY ENERGY");
        assertMapping(savedRecords, "DLF", "NIFTY REALTY");
        assertMapping(savedRecords, "LT", "NIFTY INFRA");
        assertMapping(savedRecords, "ZEEL", "NIFTY MEDIA");
        assertMapping(savedRecords, "PIDILITIND", "NIFTY COMMODITIES");
        assertMapping(savedRecords, "TITAN", "NIFTY CONSUMPTION");
        assertMapping(savedRecords, "BLUEDART", "NIFTY SERV SECTOR");
    }

    @Test
    void mapIndustryToSectorIndex_shouldCorrectlyDifferentiateBanksFromNonBanks() {
        assertEquals("NIFTY BANK", service.mapIndustryToSectorIndex("Financial Services", "HDFCBANK", "HDFC Bank Ltd."));
        assertEquals("NIFTY BANK", service.mapIndustryToSectorIndex("Financial Services", "ICICIBANK", "ICICI Bank Ltd."));
        assertEquals("NIFTY BANK", service.mapIndustryToSectorIndex("Financial Services", "SBIN", "State Bank of India"));
        assertEquals("NIFTY BANK", service.mapIndustryToSectorIndex("Financial Services", "CANBK", "Canara Bank"));
        assertEquals("NIFTY BANK", service.mapIndustryToSectorIndex("Financial Services", "PNB", "Punjab National Bank"));

        assertEquals("NIFTY FIN SERVICE", service.mapIndustryToSectorIndex("Financial Services", "BAJFINANCE", "Bajaj Finance Ltd."));
        assertEquals("NIFTY FIN SERVICE", service.mapIndustryToSectorIndex("Financial Services", "BAJAJFINSV", "Bajaj Finserv Ltd."));
        assertEquals("NIFTY FIN SERVICE", service.mapIndustryToSectorIndex("Financial Services", "HDFCLIFE", "HDFC Life Insurance Co."));
        assertEquals("NIFTY FIN SERVICE", service.mapIndustryToSectorIndex("Financial Services", "SBILIFE", "SBI Life Insurance Co."));
        assertEquals("NIFTY FIN SERVICE", service.mapIndustryToSectorIndex("Financial Services", "FEDFINA", "Fedbank Financial Services Ltd."));
    }

    @Test
    void mapIndustryToSectorIndex_shouldFallbackToNifty500ForUnknown() {
        assertEquals("NIFTY 500", service.mapIndustryToSectorIndex("Diversified", "UNKNOWN", "Unknown Co"));
        assertEquals("NIFTY 500", service.mapIndustryToSectorIndex(null, "UNKNOWN", "Unknown Co"));
    }

    private void assertMapping(List<StockSectorClassification> records, String symbol, String expectedSector) {
        StockSectorClassification record = records.stream()
                .filter(r -> symbol.equalsIgnoreCase(r.getSymbol()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Record missing for " + symbol));
        assertEquals(expectedSector, record.getSectorIndexSymbol(), "Sector mismatch for " + symbol);
    }
}
