package com.trading.scanner.controller;

import com.trading.scanner.model.MarketMinuteSnapshot;
import com.trading.scanner.service.data.EodReconciliationService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class RuntimeAdminControllerTest {

    private RuntimeAutomationService runtimeAutomationService;
    private RuntimeReadinessService runtimeReadinessService;
    private DailyStockContextService dailyStockContextService;
    private LiveSignalService liveSignalService;
    private RuntimeSettingService runtimeSettingService;
    private RuntimeHousekeepingService runtimeHousekeepingService;
    private RuntimeAlertService runtimeAlertService;
    private AngelOneSessionService angelOneSessionService;
    private MarketCalendarService marketCalendarService;
    private LiveMarketSnapshotService liveMarketSnapshotService;
    private WebSocketFrameCaptureService webSocketFrameCaptureService;
    private EodReconciliationService eodReconciliationService;
    private RuntimeAdminController controller;

    @BeforeEach
    void setUp() {
        runtimeAutomationService = mock(RuntimeAutomationService.class);
        runtimeReadinessService = mock(RuntimeReadinessService.class);
        dailyStockContextService = mock(DailyStockContextService.class);
        liveSignalService = mock(LiveSignalService.class);
        runtimeSettingService = mock(RuntimeSettingService.class);
        runtimeHousekeepingService = mock(RuntimeHousekeepingService.class);
        runtimeAlertService = mock(RuntimeAlertService.class);
        angelOneSessionService = mock(AngelOneSessionService.class);
        marketCalendarService = mock(MarketCalendarService.class);
        liveMarketSnapshotService = mock(LiveMarketSnapshotService.class);
        webSocketFrameCaptureService = mock(WebSocketFrameCaptureService.class);
        eodReconciliationService = mock(EodReconciliationService.class);

        controller = new RuntimeAdminController(
                runtimeAutomationService,
                runtimeReadinessService,
                dailyStockContextService,
                liveSignalService,
                runtimeSettingService,
                runtimeHousekeepingService,
                runtimeAlertService,
                angelOneSessionService,
                marketCalendarService,
                liveMarketSnapshotService,
                webSocketFrameCaptureService,
                eodReconciliationService);
    }

    @Test
    void latestTicks_shouldReturnSnapshotViews() {
        List<LiveMarketSnapshotService.SnapshotView> snapshots = List.of(
                new LiveMarketSnapshotService.SnapshotView(
                        "WIPRO",
                        "NSE",
                        LocalDateTime.of(2026, 7, 10, 10, 0),
                        1770.90,
                        25L,
                        "16675",
                        3,
                        1,
                        1L,
                        1000L,
                        177090L,
                        1750.0,
                        1780.0,
                        1740.0,
                        1760.0,
                        1768.4,
                        100000L,
                        50000L,
                        45000L,
                        900L,
                        1000L,
                        25.0,
                        1900.0,
                        1500.0,
                        2000.0,
                        1200.0,
                        LocalDateTime.of(2026, 7, 10, 10, 0)));

        when(liveMarketSnapshotService.latest(20)).thenReturn(snapshots);

        var response = controller.latestTicks(20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("WIPRO", response.getBody().get(0).symbol());
    }

    @Test
    void clearLatestTicks_shouldReturnClearResult() {
        when(liveMarketSnapshotService.clear())
                .thenReturn(new LiveMarketSnapshotService.ClearResult(5, "Cleared latest live market snapshots"));

        var response = controller.clearLatestTicks();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(5, response.getBody().removed());
    }

    @Test
    void recentFrames_shouldReturnFrameRecords() {
        List<WebSocketFrameCaptureService.FrameRecord> frames = List.of(
                new WebSocketFrameCaptureService.FrameRecord(
                        "BINARY",
                        379,
                        "03 01 31 36 36 37 35 ...",
                        LocalDateTime.of(2026, 7, 10, 10, 0)));

        when(webSocketFrameCaptureService.recent(20)).thenReturn(frames);

        var response = controller.recentFrames(20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("BINARY", response.getBody().get(0).frameType());
    }

    @Test
    void clearFrames_shouldReturnClearResult() {
        when(webSocketFrameCaptureService.clear())
                .thenReturn(new WebSocketFrameCaptureService.ClearResult(3, "Cleared websocket frames"));

        var response = controller.clearFrames();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(3, response.getBody().removed());
    }

    @Test
    void recentMinuteSnapshots_shouldReturnPersistedMinuteRows() {
        List<MarketMinuteSnapshot> rows = List.of(
                MarketMinuteSnapshot.builder()
                        .symbol("WIPRO")
                        .exchange("NSE")
                        .minuteTime(LocalDateTime.of(2026, 7, 14, 13, 59))
                        .latestTickTime(LocalDateTime.of(2026, 7, 14, 13, 59, 58))
                        .isFinalized(false)
                        .build());

        when(liveMarketSnapshotService.recentPersisted(20)).thenReturn(rows);

        var response = controller.recentMinuteSnapshots(20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("WIPRO", response.getBody().get(0).getSymbol());
    }

    @Test
    void triggerEodReconciliation_shouldCallServiceAndReturnSummary() {
        LocalDate date = LocalDate.of(2026, 9, 25);
        EodReconciliationService.ReconciliationBatchResult batchResult = new EodReconciliationService.ReconciliationBatchResult(
                date, 10, 8, 1, 1, "Completed reconciliation");

        when(eodReconciliationService.reconcileTradingDay(date)).thenReturn(batchResult);

        var response = controller.triggerEodReconciliation(date);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(10, response.getBody().symbolsProcessed());
        assertEquals(date, response.getBody().tradingDate());
        verify(eodReconciliationService).reconcileTradingDay(date);
    }

    @Test
    void executeSmartApiProxy_shouldCallSessionServiceAndReturnResponse() {
        var request = new com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos.SmartApiProxyRequest(
                "POST",
                "/rest/secure/angelbroking/market/v1/quote/",
                Map.of("mode", "FULL"),
                Map.of());

        var expectedResponse = new com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos.SmartApiProxyResponse(
                200,
                true,
                "POST",
                "https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/",
                Map.of("status", true),
                null);

        when(angelOneSessionService.executeSmartApiProxy(request)).thenReturn(expectedResponse);

        var response = controller.executeSmartApiProxy(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().success());
        assertEquals(200, response.getBody().statusCode());
        verify(angelOneSessionService).executeSmartApiProxy(request);
    }
}