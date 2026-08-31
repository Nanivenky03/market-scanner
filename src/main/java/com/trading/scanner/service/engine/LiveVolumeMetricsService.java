package com.trading.scanner.service.engine;

import com.trading.scanner.service.provider.angelone.LiveMarketSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LiveVolumeMetricsService {

    private final LiveMarketSnapshotService liveMarketSnapshotService;

    private final VolumeBaselineService volumeBaselineService;

    @Transactional(readOnly = true)
    public VolumeBaselineService.VolumeMetrics currentMetrics(
            String symbol,
            String exchange,
            LocalDate tradingDate,
            LocalDateTime asOf) {

        Long cumulativeVolumeToday = liveMarketSnapshotService
                .cumulativeVolumeAt(
                        symbol,
                        exchange,
                        tradingDate,
                        asOf)
                .orElse(null);

        return volumeBaselineService
                .calculateCurrentVolumeMetrics(
                        symbol,
                        exchange,
                        tradingDate,
                        asOf,
                        cumulativeVolumeToday);
    }
}
