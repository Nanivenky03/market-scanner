package com.trading.scanner.controller;

import com.trading.scanner.calendar.NseHolidayCalendar;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.ExchangeHoliday;
import com.trading.scanner.model.LiveSimulationSignal;
import com.trading.scanner.model.MarketMinuteSnapshot;
import com.trading.scanner.model.RuntimeAlertState;
import com.trading.scanner.model.RuntimeSetting;
import com.trading.scanner.service.engine.DailyStockContextService;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import com.trading.scanner.service.provider.angelone.WebSocketFrameCaptureService;
import com.trading.scanner.service.runtime.LiveSignalService;
import com.trading.scanner.service.runtime.MarketCalendarService;
import com.trading.scanner.service.runtime.RuntimeAlertService;
import com.trading.scanner.service.runtime.RuntimeAutomationService;
import com.trading.scanner.service.runtime.RuntimeHousekeepingService;
import com.trading.scanner.service.runtime.RuntimeReadinessService;
import com.trading.scanner.service.runtime.RuntimeSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/runtime")
@RequiredArgsConstructor
@Tag(name = "Runtime Admin", description = "Common runtime admin endpoints for production/live data operation")
public class RuntimeAdminController {

    private final RuntimeAutomationService runtimeAutomationService;
    private final RuntimeReadinessService runtimeReadinessService;
    private final DailyStockContextService dailyStockContextService;
    private final LiveSignalService liveSignalService;
    private final RuntimeSettingService runtimeSettingService;
    private final RuntimeHousekeepingService runtimeHousekeepingService;
    private final RuntimeAlertService runtimeAlertService;
    private final AngelOneSessionService angelOneSessionService;
    private final MarketCalendarService marketCalendarService;
    private final LiveMarketSnapshotService liveMarketSnapshotService;
    private final WebSocketFrameCaptureService webSocketFrameCaptureService;

    public record UpsertRuntimeSettingRequest(
            @Schema(example = "websocket.connect.time") String key,
            @Schema(example = "09:10") String value,
            @Schema(example = "TIME") String valueType,
            @Schema(example = "WebSocket connect time") String description,
            @Schema(example = "venky") String updatedBy) {
    }

    @Operation(summary = "Warm up broker session")
    @PostMapping("/broker/warmup")
    public ResponseEntity<RuntimeAutomationService.RuntimeActionResult> warmUpBrokerSession() {
        return ResponseEntity.ok(runtimeAutomationService.warmUpBrokerSession());
    }

    @Operation(summary = "Clear cached broker session")
    @PostMapping("/broker/clear")
    public ResponseEntity<RuntimeAutomationService.RuntimeActionResult> clearBrokerSession() {
        return ResponseEntity.ok(runtimeAutomationService.clearBrokerSession());
    }

    @Operation(summary = "Reconcile runtime state on startup logic")
    @PostMapping("/broker/reconcile-startup")
    public ResponseEntity<RuntimeAutomationService.RuntimeActionResult> reconcileStartup() {
        return ResponseEntity.ok(runtimeAutomationService.reconcileStartupState());
    }

    @Operation(summary = "Get broker session status")
    @GetMapping("/broker/status")
    public ResponseEntity<AngelOneSessionService.SessionStatus> brokerStatus() {
        return ResponseEntity.ok(angelOneSessionService.sessionStatus());
    }

    @Operation(summary = "Manually connect websocket and subscribe active universe")
    @PostMapping("/connect-and-subscribe")
    public ResponseEntity<RuntimeAutomationService.RuntimeActionResult> connectAndSubscribe() {
        return ResponseEntity.ok(runtimeAutomationService.connectAndSubscribe());
    }

    @Operation(summary = "Manually flush live candles and disconnect websocket")
    @PostMapping("/flush-and-disconnect")
    public ResponseEntity<RuntimeAutomationService.RuntimeActionResult> flushAndDisconnect() {
        return ResponseEntity.ok(runtimeAutomationService.flushAndDisconnect());
    }

    @Operation(summary = "Get runtime status")
    @GetMapping("/status")
    public ResponseEntity<RuntimeAutomationService.RuntimeStatus> runtimeStatus() {
        return ResponseEntity.ok(runtimeAutomationService.runtimeStatus());
    }

    @Operation(summary = "Get runtime readiness/preflight status")
    @GetMapping("/readiness")
    public ResponseEntity<RuntimeReadinessService.ReadinessStatus> runtimeReadiness() {
        return ResponseEntity.ok(runtimeReadinessService.status());
    }

    @Operation(summary = "List latest parsed live market snapshots")
    @GetMapping("/websocket/ticks/latest")
    public ResponseEntity<List<LiveMarketSnapshotService.SnapshotView>> latestTicks(
            @Parameter(description = "Maximum number of snapshots to return", example = "20") @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(liveMarketSnapshotService.latest(limit));
    }

    @Operation(summary = "Clear latest parsed live market snapshots")
    @PostMapping("/websocket/ticks/clear")
    public ResponseEntity<LiveMarketSnapshotService.ClearResult> clearLatestTicks() {
        return ResponseEntity.ok(liveMarketSnapshotService.clear());
    }

    @Operation(summary = "List recently captured websocket frame previews")
    @GetMapping("/websocket/frames")
    public ResponseEntity<List<WebSocketFrameCaptureService.FrameRecord>> recentFrames(
            @Parameter(description = "Maximum number of frames to return", example = "20") @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(webSocketFrameCaptureService.recent(limit));
    }

    @Operation(summary = "Clear captured websocket frame previews")
    @PostMapping("/websocket/frames/clear")
    public ResponseEntity<WebSocketFrameCaptureService.ClearResult> clearFrames() {
        return ResponseEntity.ok(webSocketFrameCaptureService.clear());
    }

    @Operation(summary = "List persisted minute market snapshots")
    @GetMapping("/websocket/minute-snapshots")
    public ResponseEntity<List<MarketMinuteSnapshot>> recentMinuteSnapshots(
            @Parameter(description = "Maximum number of persisted minute snapshots to return", example = "20") @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(liveMarketSnapshotService.recentPersisted(limit));
    }

    @Operation(summary = "Run housekeeping now")
    @PostMapping("/housekeeping/run")
    public ResponseEntity<RuntimeHousekeepingService.HousekeepingResult> runHousekeeping() {
        return ResponseEntity.ok(runtimeHousekeepingService.runHousekeeping());
    }

    @Operation(summary = "Get last housekeeping result")
    @GetMapping("/housekeeping/status")
    public ResponseEntity<RuntimeHousekeepingService.HousekeepingResult> housekeepingStatus() {
        return ResponseEntity.ok(runtimeHousekeepingService.lastResult());
    }

    @Operation(summary = "Evaluate runtime alerts now")
    @PostMapping("/alerts/evaluate")
    public ResponseEntity<RuntimeAlertService.AlertEvaluationResult> evaluateAlerts() {
        return ResponseEntity.ok(runtimeAlertService.evaluateNow());
    }

    @Operation(summary = "Send a test alert via webhook/email")
    @PostMapping("/alerts/test")
    public ResponseEntity<RuntimeAlertService.TestAlertResult> testAlertWebhook() {
        return ResponseEntity.ok(runtimeAlertService.sendTestAlert());
    }

    @Operation(summary = "List all runtime alerts")
    @GetMapping("/alerts")
    public ResponseEntity<List<RuntimeAlertState>> allAlerts() {
        return ResponseEntity.ok(runtimeAlertService.allAlerts());
    }

    @Operation(summary = "List open runtime alerts")
    @GetMapping("/alerts/open")
    public ResponseEntity<List<RuntimeAlertState>> openAlerts() {
        return ResponseEntity.ok(runtimeAlertService.openAlerts());
    }

    @Operation(summary = "List active runtime settings")
    @GetMapping("/settings")
    public ResponseEntity<List<RuntimeSetting>> activeSettings() {
        return ResponseEntity.ok(runtimeSettingService.activeSettings());
    }

    @Operation(summary = "Upsert runtime setting")
    @PostMapping("/settings")
    public ResponseEntity<RuntimeSetting> upsertRuntimeSetting(
            @RequestBody UpsertRuntimeSettingRequest request) {
        return ResponseEntity.ok(
                runtimeSettingService.upsert(
                        request.key(),
                        request.value(),
                        request.valueType(),
                        request.description(),
                        request.updatedBy()));
    }

    @Operation(summary = "List exchange holidays for a date range")
    @GetMapping("/calendar/holidays")
    public ResponseEntity<List<ExchangeHoliday>> holidays(
            @Parameter(description = "Range start date in yyyy-MM-dd format", example = "2026-01-01") @RequestParam LocalDate fromDate,
            @Parameter(description = "Range end date in yyyy-MM-dd format", example = "2026-12-31") @RequestParam LocalDate toDate) {
        return ResponseEntity.ok(marketCalendarService.holidays(fromDate, toDate));
    }

    @Operation(summary = "Refresh persisted exchange holidays from current static authority")
    @PostMapping("/calendar/refresh-static-authority")
    public ResponseEntity<NseHolidayCalendar.HolidayRefreshResult> refreshStaticAuthority() {
        return ResponseEntity.ok(marketCalendarService.refreshFromStaticAuthority());
    }

    @Operation(summary = "Refresh persisted exchange holidays from official NSE source")
    @PostMapping("/calendar/refresh-official-source")
    public ResponseEntity<MarketCalendarService.OfficialHolidayRefreshResult> refreshOfficialSource() {
        return ResponseEntity.ok(marketCalendarService.refreshFromOfficialNseSource());
    }

    @Operation(summary = "Get exchange holiday calendar summary")
    @GetMapping("/calendar/summary")
    public ResponseEntity<Map<String, Object>> calendarSummary() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holidayCount", marketCalendarService.holidayCount());
        body.put("lastOfficialRefreshMonth",
                runtimeSettingService.getString("calendar.last.official.refresh.month", null));
        body.put("lastOfficialRefreshAt", runtimeSettingService.getString("calendar.last.official.refresh.at", null));
        body.put("lastOfficialRefreshStatus",
                runtimeSettingService.getString("calendar.last.official.refresh.status", null));
        body.put("lastOfficialRefreshMessage",
                runtimeSettingService.getString("calendar.last.official.refresh.message", null));
        body.put("message", "Exchange holiday summary loaded");
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "List daily stock context rows for one trading date")
    @GetMapping("/daily-stock-context")
    public ResponseEntity<List<DailyStockContext>> dailyStockContext(
            @Parameter(description = "Trading date in yyyy-MM-dd format", example = "2026-06-24") @RequestParam LocalDate date) {
        return ResponseEntity.ok(dailyStockContextService.findByTradingDate(date));
    }

    @Operation(summary = "List recent live signals")
    @GetMapping("/live-signals")
    public ResponseEntity<List<LiveSimulationSignal>> recentLiveSignals(
            @Parameter(description = "Maximum number of recent signals to return", example = "50") @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(liveSignalService.recentSignals(limit));
    }

    @Operation(summary = "Clear live signals")
    @PostMapping("/live-signals/clear")
    public ResponseEntity<LiveSignalService.ClearSignalsResult> clearLiveSignals() {
        return ResponseEntity.ok(liveSignalService.clearSignals());
    }
}