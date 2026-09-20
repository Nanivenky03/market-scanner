package com.trading.scanner.service.instrument;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.service.provider.angelone.AngelOneApiExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AngelOneInstrumentCatalogSyncServiceTest {

    private InstrumentMasterRepository repository;
    private AngelOneApiExecutor apiExecutor;
    private AngelOneProperties properties;
    private AngelOneInstrumentCatalogSyncService service;

    @BeforeEach
    void setUp() {
        repository = mock(InstrumentMasterRepository.class);

        apiExecutor = mock(AngelOneApiExecutor.class);

        properties = mock(AngelOneProperties.class);

        service = new AngelOneInstrumentCatalogSyncService(
                new ObjectMapper(),
                apiExecutor,
                properties,
                repository);

        ReflectionTestUtils.setField(
                service,
                "instrumentMasterUrl",
                "https://test.local/OpenAPIScripMaster.json");

    }

    @Test
    void syncNseCatalog_shouldImportEquityAndNiftyIndex() {
        when(properties.enabled())
                .thenReturn(true);

        when(repository.findAll())
                .thenReturn(List.of());

        when(apiExecutor.getJsonForBody(
                eq(AngelOneApiExecutor.ApiEndpoint.INSTRUMENT_MASTER),
                anyString(),
                anyMap()))
                .thenReturn("""
                        [
                          {
                            "token": "3045",
                            "symbol": "SBIN-EQ",
                            "name": "SBIN",
                            "expiry": "",
                            "strike": "-1.000000",
                            "lotsize": "1",
                            "instrumenttype": "EQ",
                            "exch_seg": "NSE",
                            "tick_size": "5.000000"
                          },
                          {
                            "token": "99926000",
                            "symbol": "Nifty 50",
                            "name": "NIFTY",
                            "expiry": "",
                            "strike": "-1.000000",
                            "lotsize": "1",
                            "instrumenttype": "AMXIDX",
                            "exch_seg": "NSE",
                            "tick_size": "0.000000"
                          },
                          {
                            "token": "99926001",
                            "symbol": "BANKNIFTY",
                            "name": "NIFTY BANK",
                            "expiry": "",
                            "strike": "-1.000000",
                            "lotsize": "1",
                            "instrumenttype": "AMXIDX",
                            "exch_seg": "NSE",
                            "tick_size": "0.000000"
                          }
                        ]
                        """);

        when(repository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AngelOneInstrumentCatalogSyncService.CatalogSyncResult result = service.syncNseCatalog();

        assertEquals(3, result.importedRecords());
        assertEquals(3, result.inserted());
        assertTrue(result.niftyImported());

        verify(repository).saveAll(argThat(rows -> {
            if (rows == null) {
                return false;
            }

            for (InstrumentMaster row : rows) {
                if ("NIFTY".equals(row.getSymbol())
                        && "INDEX".equals(row.getInstrumentType())
                        && "INDEX".equals(row.getSegment())
                        && "99926000".equals(row.getBrokerToken())) {
                    return true;
                }
            }

            return false;
        }));

    }
}
