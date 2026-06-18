package com.trading.scanner.controller;

import com.trading.scanner.strategy.StrategyCatalogService;
import com.trading.scanner.strategy.StrategyScoringModels;
import com.trading.scanner.strategy.StrategyScoringService;
import com.trading.scanner.strategy.StrategyYamlDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dev/strategies")
@Profile("simulation")
@RequiredArgsConstructor
@Tag(name = "Strategy Dev", description = "Simulation-only strategy catalog and score preview endpoints")
public class StrategyDevController {

    private final StrategyCatalogService strategyCatalogService;
    private final StrategyScoringService strategyScoringService;

    public record ScorePreviewRequest(Map<String, Double> factorValues) {
    }

    @Operation(summary = "List all loaded strategy definitions")
    @GetMapping
    public ResponseEntity<List<StrategyYamlDefinition>> all() {
        return ResponseEntity.ok(strategyCatalogService.all());
    }

    @Operation(summary = "List simulation-enabled strategies")
    @GetMapping("/simulation-enabled")
    public ResponseEntity<List<StrategyYamlDefinition>> simulationEnabled() {
        return ResponseEntity.ok(strategyCatalogService.simulationEnabled());
    }

    @Operation(summary = "List live-enabled strategies")
    @GetMapping("/live-enabled")
    public ResponseEntity<List<StrategyYamlDefinition>> liveEnabled() {
        return ResponseEntity.ok(strategyCatalogService.liveEnabled());
    }

    @Operation(summary = "Get one strategy definition by strategyId")
    @GetMapping("/{strategyId}")
    public ResponseEntity<StrategyYamlDefinition> byId(@PathVariable String strategyId) {
        return ResponseEntity.ok(strategyCatalogService.getRequired(strategyId));
    }

    @Operation(summary = "Preview score for one strategy", description = "Runs scoring logic using manually supplied factor values")
    @PostMapping("/{strategyId}/score-preview")
    public ResponseEntity<StrategyScoringModels.StrategyScoreResult> scorePreview(
            @PathVariable String strategyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(examples = @ExampleObject(value = """
                    {
                      "factorValues": {
                        "breakoutPercent": 0.35,
                        "volumeRatio": 2.1,
                        "rsi": 61.0
                      }
                    }
                    """))) @RequestBody ScorePreviewRequest request) {
        StrategyYamlDefinition definition = strategyCatalogService.getRequired(strategyId);
        return ResponseEntity.ok(
                strategyScoringService.score(definition, request.factorValues()));
    }
}