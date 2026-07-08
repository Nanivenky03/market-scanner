package com.trading.scanner.controller;

import com.trading.scanner.service.runtime.RuntimeAlertService;
import com.trading.scanner.service.runtime.RuntimeAutomationService;
import com.trading.scanner.service.runtime.RuntimeReadinessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
@Tag(name = "Runtime Health", description = "Basic health and readiness endpoints for cloud/runtime checks")
public class RuntimeHealthController {

    private final RuntimeAutomationService runtimeAutomationService;
    private final RuntimeReadinessService runtimeReadinessService;
    private final RuntimeAlertService runtimeAlertService;

    public RuntimeHealthController(
            RuntimeAutomationService runtimeAutomationService,
            RuntimeReadinessService runtimeReadinessService,
            RuntimeAlertService runtimeAlertService) {
        this.runtimeAutomationService = runtimeAutomationService;
        this.runtimeReadinessService = runtimeReadinessService;
        this.runtimeAlertService = runtimeAlertService;
    }

    @Operation(summary = "Simple liveness check")
    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> live() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString()));
    }

    @Operation(summary = "Runtime readiness summary")
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        RuntimeReadinessService.ReadinessStatus readiness = runtimeReadinessService.status();
        long blockingOpenHighAlerts = runtimeAlertService.openHighAlertCount();

        boolean ready = readiness.readyForLiveRuntime() && blockingOpenHighAlerts == 0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "READY" : "NOT_READY");
        body.put("businessDate", readiness.businessDate());
        body.put("tradingDay", readiness.tradingDay());
        body.put("marketSessionOpen", readiness.marketSessionOpen());
        body.put("activeUniverseCount", readiness.activeUniverseCount());
        body.put("missingBrokerTokenCount", readiness.missingBrokerTokenCount());
        body.put("websocketConnected", readiness.websocketConnected());
        body.put("autoRunEnabled", readiness.autoRunEnabled());
        body.put("subscriptionMode", readiness.subscriptionMode());
        body.put("parserFailures", readiness.websocketParserFailures());
        body.put("blockingOpenHighAlerts", blockingOpenHighAlerts);

        return ResponseEntity.status(ready ? 200 : 503).body(body);
    }

    @Operation(summary = "Operational status summary")
    @GetMapping("/status")
    public ResponseEntity<RuntimeAutomationService.RuntimeStatus> status() {
        return ResponseEntity.ok(runtimeAutomationService.runtimeStatus());
    }
}