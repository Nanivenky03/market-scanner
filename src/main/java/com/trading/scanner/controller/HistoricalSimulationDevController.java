package com.trading.scanner.controller;

import com.trading.scanner.model.SimulationRunGroup;
import com.trading.scanner.model.SimulationTrade;
import com.trading.scanner.model.SimulationVariant;
import com.trading.scanner.service.simulation.HistoricalSimulationExecutionService;
import com.trading.scanner.service.simulation.HistoricalSimulationManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/dev/simulations")
@Profile("simulation")
@RequiredArgsConstructor
@Tag(name = "Historical Simulations Dev", description = "Simulation-only endpoints for creating, executing, and comparing historical simulation runs")
public class HistoricalSimulationDevController {

    private final HistoricalSimulationManagementService historicalSimulationManagementService;
    private final HistoricalSimulationExecutionService historicalSimulationExecutionService;

    public record CreateRunGroupRequest(
            @Schema(example = "breakout_15m_hist_run_1") String name,

            @Schema(example = "First real historical simulation run") String description,

            @Schema(example = "2026-05-20") LocalDate fromDate,

            @Schema(example = "2026-06-10") LocalDate toDate,

            @Schema(example = "10000.0") Double capitalPerTrade,

            @Schema(example = "0") Integer slippageBps,

            @Schema(example = "20.0") Double chargePerTrade,

            @Schema(example = "20") Integer minTradingDays,

            @Schema(example = "10") Integer minTradeCount,

            @Schema(example = "15m breakout first execution") String notes,

            @Schema(example = "venky") String createdBy,

            @Schema(example = "[\"breakout_v1\"]") List<String> strategyIds) {
    }

    @Operation(summary = "Create a historical simulation run group", description = "Creates one run group and the corresponding base variants for selected strategies")
    @PostMapping("/run-groups")
    public ResponseEntity<HistoricalSimulationManagementService.CreateRunGroupResult> createRunGroup(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(examples = @ExampleObject(value = """
                    {
                      "name": "breakout_15m_hist_run_1",
                      "description": "First real historical simulation run",
                      "fromDate": "2026-05-20",
                      "toDate": "2026-06-10",
                      "capitalPerTrade": 10000.0,
                      "slippageBps": 0,
                      "chargePerTrade": 20.0,
                      "minTradingDays": 20,
                      "minTradeCount": 10,
                      "notes": "15m breakout first execution",
                      "createdBy": "venky",
                      "strategyIds": ["breakout_v1"]
                    }
                    """))) @RequestBody CreateRunGroupRequest request) {
        HistoricalSimulationManagementService.CreateRunGroupResult result = historicalSimulationManagementService
                .createRunGroup(
                        new HistoricalSimulationManagementService.CreateRunGroupRequest(
                                request.name(),
                                request.description(),
                                request.fromDate(),
                                request.toDate(),
                                request.capitalPerTrade() != null ? request.capitalPerTrade() : 10000.0,
                                request.slippageBps() != null ? request.slippageBps() : 0,
                                request.chargePerTrade() != null ? request.chargePerTrade() : 0.0,
                                request.minTradingDays() != null ? request.minTradingDays() : 20,
                                request.minTradeCount() != null ? request.minTradeCount() : 10,
                                request.notes(),
                                request.createdBy(),
                                request.strategyIds()));

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Execute one historical simulation run group")
    @PostMapping("/run-groups/{runGroupId}/execute")
    public ResponseEntity<HistoricalSimulationExecutionService.ExecuteRunGroupResult> executeRunGroup(
            @PathVariable Integer runGroupId) {
        return ResponseEntity.ok(
                historicalSimulationExecutionService.executeRunGroup(runGroupId));
    }

    @Operation(summary = "List all historical simulation run groups")
    @GetMapping("/run-groups")
    public ResponseEntity<List<SimulationRunGroup>> allRunGroups() {
        return ResponseEntity.ok(historicalSimulationManagementService.allRunGroups());
    }

    @Operation(summary = "List variants for one run group")
    @GetMapping("/run-groups/{runGroupId}/variants")
    public ResponseEntity<List<SimulationVariant>> variantsForGroup(@PathVariable Integer runGroupId) {
        return ResponseEntity.ok(historicalSimulationManagementService.variantsForGroup(runGroupId));
    }

    @Operation(summary = "List trades for one simulation variant")
    @GetMapping("/variants/{variantId}/trades")
    public ResponseEntity<List<SimulationTrade>> tradesForVariant(@PathVariable Integer variantId) {
        return ResponseEntity.ok(historicalSimulationExecutionService.tradesForVariant(variantId));
    }

    @Operation(summary = "Get summary report for one run group", description = "Returns aggregated run-group level metrics plus per-variant summary")
    @GetMapping("/run-groups/{runGroupId}/report")
    public ResponseEntity<HistoricalSimulationManagementService.RunGroupReport> reportForGroup(
            @PathVariable Integer runGroupId) {
        return ResponseEntity.ok(historicalSimulationManagementService.reportForGroup(runGroupId));
    }

    @Operation(summary = "Compare simulation variants within one run group", description = "Returns sortable variant-level metrics for comparing strategies such as 5m vs 15m")
    @GetMapping("/run-groups/{runGroupId}/compare")
    public ResponseEntity<List<HistoricalSimulationManagementService.VariantReport>> compareVariants(
            @PathVariable Integer runGroupId) {
        return ResponseEntity.ok(historicalSimulationManagementService.compareVariants(runGroupId));
    }
}