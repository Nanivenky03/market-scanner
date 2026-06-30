package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.LiveSimulationSignalRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.service.provider.angelone.WebSocketFrameCaptureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeHousekeepingService {

    private final MarketCandleRepository marketCandleRepository;
    private final DailyStockContextRepository dailyStockContextRepository;
    private final LiveSimulationSignalRepository liveSimulationSignalRepository;
    private final WebSocketFrameCaptureService webSocketFrameCaptureService;
    private final RuntimeSettingService runtimeSettingService;
    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final TimeProvider timeProvider;

    private volatile HousekeepingResult lastResult;

    @Transactional
    public HousekeepingResult runHousekeeping() {
        LocalDate today = timeProvider.today();

        LocalDateTime candleCutoff = today.minusDays(runtimeSettingService.retentionCandlesDays()).atStartOfDay();
        LocalDate contextCutoff = today.minusDays(runtimeSettingService.retentionCandlesDays());
        LocalDate liveSignalCutoff = today.minusDays(runtimeSettingService.retentionLiveSignalsDays());

        long deletedOneMinute = marketCandleRepository.deleteByTimeframeAndCandleTimeBefore(
                CandleTimeframe.ONE_MINUTE, candleCutoff);

        long deletedFiveMinute = marketCandleRepository.deleteByTimeframeAndCandleTimeBefore(
                CandleTimeframe.FIVE_MINUTE, candleCutoff);

        long deletedFifteenMinute = marketCandleRepository.deleteByTimeframeAndCandleTimeBefore(
                CandleTimeframe.FIFTEEN_MINUTE, candleCutoff);

        long deletedDailyContext = dailyStockContextRepository.deleteByTradingDateBefore(contextCutoff);
        long deletedLiveSignals = liveSimulationSignalRepository.deleteBySignalDateBefore(liveSignalCutoff);

        WebSocketFrameCaptureService.ClearResult frameClear = webSocketFrameCaptureService.clear();

        HousekeepingResult result = new HousekeepingResult(
                timeProvider.nowDateTime(),
                runtimeSettingService.retentionCandlesDays(),
                runtimeSettingService.retentionLiveSignalsDays(),
                deletedOneMinute,
                deletedFiveMinute,
                deletedFifteenMinute,
                deletedDailyContext,
                deletedLiveSignals,
                frameClear.removed(),
                "Housekeeping completed");

        lastResult = result;
        log.info("Runtime housekeeping completed: {}", result);
        return result;
    }

    public HousekeepingResult lastResult() {
        return lastResult;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void scheduledHousekeeping() {
        if (!runtimeAutomationProperties.getHousekeeping().isAutoRun()) {
            return;
        }

        LocalTime now = timeProvider.nowDateTime().toLocalTime();
        LocalTime housekeepingTime = runtimeSettingService.housekeepingTime();

        if (now.getHour() != housekeepingTime.getHour() || now.getMinute() != housekeepingTime.getMinute()) {
            return;
        }

        try {
            runHousekeeping();
        } catch (Exception ex) {
            log.warn("Scheduled housekeeping failed: {}", ex.getMessage(), ex);
        }
    }

    public record HousekeepingResult(
            LocalDateTime executedAt,
            int candleRetentionDays,
            int liveSignalRetentionDays,
            long deletedOneMinuteCandles,
            long deletedFiveMinuteCandles,
            long deletedFifteenMinuteCandles,
            long deletedDailyStockContexts,
            long deletedLiveSignals,
            int clearedFrameBufferEntries,
            String message) {
    }
}