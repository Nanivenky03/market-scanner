package com.trading.scanner.controller;

import com.trading.scanner.model.StockSectorClassification;
import com.trading.scanner.service.instrument.MarketIndexRegistry;
import com.trading.scanner.service.instrument.SectorClassificationSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SectorClassificationControllerTest {

    private SectorClassificationSyncService syncService;
    private MarketIndexRegistry registry;
    private SectorClassificationController controller;

    @BeforeEach
    void setUp() {
        syncService = mock(SectorClassificationSyncService.class);
        registry = new MarketIndexRegistry();
        controller = new SectorClassificationController(syncService, registry);
    }

    @Test
    void getSummary_shouldReturnCounts() {
        when(syncService.getSymbolsForSector("NIFTY IT")).thenReturn(List.of("INFY", "TCS"));
        when(syncService.getSymbolsForSector("NIFTY BANK")).thenReturn(List.of("HDFCBANK", "SBIN"));

        ResponseEntity<Map<String, Object>> response = controller.getSummary();
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertTrue((int) body.get("totalRegisteredIndices") >= 25);
    }

    @Test
    void getAllIndices_shouldReturnRegisteredList() {
        ResponseEntity<List<MarketIndexRegistry.IndexDefinition>> response = controller.getAllIndices();
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertFalse(response.getBody().isEmpty());
    }

    @Test
    void getClassification_whenFound_shouldReturnOk() {
        StockSectorClassification entity = StockSectorClassification.builder()
                .symbol("INFY")
                .sectorIndexSymbol("NIFTY IT")
                .macroSector("Information Technology")
                .build();
        when(syncService.getClassification("INFY")).thenReturn(Optional.of(entity));

        ResponseEntity<?> response = controller.getClassification("INFY");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(entity, response.getBody());
    }

    @Test
    void getClassification_whenNotFound_shouldReturn404() {
        when(syncService.getClassification("UNKNOWN")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getClassification("UNKNOWN");
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void syncSectors_shouldDelegateToService() {
        SectorClassificationSyncService.SyncResult mockResult = new SectorClassificationSyncService.SyncResult(
                true, 754, 754, "NSE_OFFICIAL_URL", "Synced");
        when(syncService.syncSectorClassification(true)).thenReturn(mockResult);

        ResponseEntity<SectorClassificationSyncService.SyncResult> response = controller.syncSectors(true);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().successful());
    }
}
