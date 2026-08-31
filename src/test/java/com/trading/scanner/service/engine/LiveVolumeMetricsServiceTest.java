package com.trading.scanner.service.engine;

import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class LiveVolumeMetricsServiceTest {

    private LiveMarketSnapshotService snapshotService;
    private VolumeBaselineService volumeBaselineService;
    private LiveVolumeMetricsService service;

    @BeforeEach
    void setUp() {
        snapshotService = mock(LiveMarketSnapshotService.class);

        volumeBaselineService = mock(VolumeBaselineService.class);

        service = new LiveVolumeMetricsService(
                snapshotService,
                volumeBaselineService);
    }

    @Test
    void currentMetrics_shouldUsePersistedDailyVolumeForVolX() {
        LocalDate date = LocalDate.of(2026, 8, 27);

        LocalDateTime asOf = date.atTime(9, 45);

        VolumeBaselineService.VolumeMetrics expected = new VolumeBaselineService.VolumeMetrics(
                50_000L,
                30,
                40_000L,
                1.25);

        when(snapshotService.cumulativeVolumeAt(
                "ONGC",
                "NSE",
                date,
                asOf))
                .thenReturn(Optional.of(50_000L));

        when(volumeBaselineService
                .calculateCurrentVolumeMetrics(
                        "ONGC",
                        "NSE",
                        date,
                        asOf,
                        50_000L))
                .thenReturn(expected);

        VolumeBaselineService.VolumeMetrics result = service.currentMetrics(
                "ONGC",
                "NSE",
                date,
                asOf);

        assertEquals(expected, result);

        verify(volumeBaselineService)
                .calculateCurrentVolumeMetrics(
                        "ONGC",
                        "NSE",
                        date,
                        asOf,
                        50_000L);
    }

    @Test
    void currentMetrics_shouldPassNullWhenProviderVolumeIsUnavailable() {
        LocalDate date = LocalDate.of(2026, 8, 27);

        LocalDateTime asOf = date.atTime(9, 45);

        VolumeBaselineService.VolumeMetrics expected = new VolumeBaselineService.VolumeMetrics(
                null,
                30,
                null,
                null);

        when(snapshotService.cumulativeVolumeAt(
                "ONGC",
                "NSE",
                date,
                asOf))
                .thenReturn(Optional.empty());

        when(volumeBaselineService
                .calculateCurrentVolumeMetrics(
                        "ONGC",
                        "NSE",
                        date,
                        asOf,
                        null))
                .thenReturn(expected);

        VolumeBaselineService.VolumeMetrics result = service.currentMetrics(
                "ONGC",
                "NSE",
                date,
                asOf);

        assertEquals(expected, result);

        verify(volumeBaselineService)
                .calculateCurrentVolumeMetrics(
                        "ONGC",
                        "NSE",
                        date,
                        asOf,
                        null);
    }
}
