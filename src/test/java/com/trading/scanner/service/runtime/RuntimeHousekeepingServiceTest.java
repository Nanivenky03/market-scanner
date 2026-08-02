package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.LiveSimulationSignalRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.VolumeDailyBaselineRepository;
import com.trading.scanner.repository.VolumeTimeWindowBaselineRepository;
import com.trading.scanner.service.provider.angelone.WebSocketFrameCaptureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeHousekeepingServiceTest {

        @Mock
        private MarketCandleRepository marketCandleRepository;

        @Mock
        private DailyStockContextRepository dailyStockContextRepository;

        @Mock
        private LiveSimulationSignalRepository liveSimulationSignalRepository;

        @Mock
        private VolumeDailyBaselineRepository volumeDailyBaselineRepository;

        @Mock
        private VolumeTimeWindowBaselineRepository volumeTimeWindowBaselineRepository;

        @Mock
        private WebSocketFrameCaptureService webSocketFrameCaptureService;

        @Mock
        private RuntimeSettingService runtimeSettingService;

        @Mock
        private RuntimeAutomationProperties runtimeAutomationProperties;

        @Mock
        private TimeProvider timeProvider;

        @InjectMocks
        private RuntimeHousekeepingService runtimeHousekeepingService;

        @Test
        void runHousekeeping_shouldPurgeConfiguredData() {
                when(timeProvider.today()).thenReturn(LocalDate.of(2026, 6, 24));
                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 6, 24, 5, 30));
                when(runtimeSettingService.retentionCandlesDays()).thenReturn(30);
                when(runtimeSettingService.retentionLiveSignalsDays()).thenReturn(30);

                when(marketCandleRepository.deleteByTimeframeAndCandleTimeBefore(
                                CandleTimeframe.ONE_MINUTE,
                                LocalDate.of(2026, 5, 25).atStartOfDay())).thenReturn(100L);
                when(marketCandleRepository.deleteByTimeframeAndCandleTimeBefore(
                                CandleTimeframe.FIVE_MINUTE,
                                LocalDate.of(2026, 5, 25).atStartOfDay())).thenReturn(20L);
                when(marketCandleRepository.deleteByTimeframeAndCandleTimeBefore(
                                CandleTimeframe.FIFTEEN_MINUTE,
                                LocalDate.of(2026, 5, 25).atStartOfDay())).thenReturn(10L);

                when(dailyStockContextRepository.deleteByTradingDateBefore(LocalDate.of(2026, 5, 25))).thenReturn(15L);
                when(liveSimulationSignalRepository.deleteBySignalDateBefore(LocalDate.of(2026, 5, 25))).thenReturn(7L);
                when(volumeDailyBaselineRepository.deleteByTradingDateBefore(LocalDate.of(2026, 5, 25))).thenReturn(9L);
                when(volumeTimeWindowBaselineRepository.deleteByTradingDateBefore(LocalDate.of(2026, 5, 25)))
                                .thenReturn(18L);

                when(webSocketFrameCaptureService.clear()).thenReturn(
                                new WebSocketFrameCaptureService.ClearResult(5, "Cleared captured websocket frames"));

                RuntimeHousekeepingService.HousekeepingResult result = runtimeHousekeepingService.runHousekeeping();

                assertEquals(30, result.candleRetentionDays());
                assertEquals(30, result.liveSignalRetentionDays());
                assertEquals(100L, result.deletedOneMinuteCandles());
                assertEquals(20L, result.deletedFiveMinuteCandles());
                assertEquals(10L, result.deletedFifteenMinuteCandles());
                assertEquals(15L, result.deletedDailyStockContexts());
                assertEquals(7L, result.deletedLiveSignals());
                assertEquals(9L, result.deletedVolumeDailyBaselines());
                assertEquals(18L, result.deletedVolumeTimeWindowBaselines());
                assertEquals(5, result.clearedFrameBufferEntries());
        }
}
