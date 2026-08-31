package com.trading.scanner.service.runtime;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BaseDataResetServiceTest {

        private JdbcTemplate jdbcTemplate;
        private RuntimeAutomationService runtimeAutomationService;
        private RuntimeSettingService runtimeSettingService;
        private LiveMarketSnapshotService liveMarketSnapshotService;
        private TimeProvider timeProvider;
        private BaseDataResetService service;

        @BeforeEach
        void setUp() {
                jdbcTemplate = mock(JdbcTemplate.class);
                runtimeAutomationService = mock(RuntimeAutomationService.class);
                runtimeSettingService = mock(RuntimeSettingService.class);
                liveMarketSnapshotService = mock(LiveMarketSnapshotService.class);
                timeProvider = mock(TimeProvider.class);

                service = new BaseDataResetService(
                                jdbcTemplate,
                                runtimeAutomationService,
                                runtimeSettingService,
                                liveMarketSnapshotService,
                                timeProvider);
        }

        @Test
        void resetBaseData_shouldDisconnectClearAndRequireBootstrap() {
                LocalDateTime now = LocalDateTime.of(2026, 8, 27, 18, 0);

                when(timeProvider.nowDateTime())
                                .thenReturn(now);

                doReturn(Boolean.TRUE)
                                .when(jdbcTemplate)
                                .execute(
                                                ArgumentMatchers
                                                                .<ConnectionCallback<Boolean>>any());

                BaseDataResetService.ResetResult result = service.resetBaseData();

                assertEquals(
                                now,
                                result.resetAt());

                assertEquals(
                                BaseDataResetService.BOOTSTRAP_REQUIRED,
                                result.bootstrapStatus());

                verify(runtimeAutomationService)
                                .flushAndDisconnect();

                verify(runtimeAutomationService)
                                .clearBrokerSession();

                verify(liveMarketSnapshotService)
                                .clear();

                verify(runtimeSettingService)
                                .upsert(
                                                eq(BaseDataResetService.BOOTSTRAP_STATUS_KEY),
                                                eq(BaseDataResetService.BOOTSTRAP_REQUIRED),
                                                eq("STRING"),
                                                anyString(),
                                                eq("system"));

                verify(jdbcTemplate)
                                .update(
                                                "DELETE FROM \"volume_daily_baseline\"");

                verify(jdbcTemplate, never())
                                .update(
                                                "DELETE FROM \"backfill_jobs\"");

                verify(jdbcTemplate, never())
                                .update(
                                                "DELETE FROM \"market_candle\"");
        }
}
