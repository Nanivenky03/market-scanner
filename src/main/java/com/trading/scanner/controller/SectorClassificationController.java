package com.trading.scanner.controller;

import com.trading.scanner.model.StockSectorClassification;
import com.trading.scanner.service.instrument.MarketIndexRegistry;
import com.trading.scanner.service.instrument.SectorClassificationSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sectors")
@RequiredArgsConstructor
@Tag(name = "Sector Classification", description = "Stock-to-Sector and Market Indices Diagnostic APIs")
public class SectorClassificationController {

    private final SectorClassificationSyncService sectorClassificationSyncService;
    private final MarketIndexRegistry marketIndexRegistry;

    @GetMapping("/summary")
    @Operation(summary = "Get sector classification summary and distribution across all stocks")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<MarketIndexRegistry.IndexDefinition> registered = marketIndexRegistry.getAllRegisteredIndices();
        response.put("totalRegisteredIndices", registered.size());

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MarketIndexRegistry.IndexDefinition idx : registered) {
            List<String> symbols = sectorClassificationSyncService.getSymbolsForSector(idx.canonicalSymbol());
            if (!symbols.isEmpty()) {
                counts.put(idx.canonicalSymbol(), symbols.size());
            }
        }
        response.put("sectorDistribution", counts);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/indices")
    @Operation(summary = "Get all registered benchmark, sector, and thematic indices")
    public ResponseEntity<List<MarketIndexRegistry.IndexDefinition>> getAllIndices() {
        return ResponseEntity.ok(marketIndexRegistry.getAllRegisteredIndices());
    }

    @GetMapping("/{symbol}")
    @Operation(summary = "Get sector classification for a specific symbol")
    public ResponseEntity<?> getClassification(@PathVariable String symbol) {
        return sectorClassificationSyncService.getClassification(symbol)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/sync")
    @Operation(summary = "Manually trigger or force re-sync of sector classification from official NSE data")
    public ResponseEntity<SectorClassificationSyncService.SyncResult> syncSectors(
            @RequestParam(defaultValue = "false") boolean force) {
        SectorClassificationSyncService.SyncResult result = sectorClassificationSyncService.syncSectorClassification(force);
        return ResponseEntity.ok(result);
    }
}
