package com.trading.scanner.service.simulation;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.*;
import com.trading.scanner.repository.SimulationRunGroupRepository;
import com.trading.scanner.repository.SimulationTradeRepository;
import com.trading.scanner.repository.SimulationVariantRepository;
import com.trading.scanner.strategy.StrategyCatalogService;
import com.trading.scanner.strategy.StrategyStatus;
import com.trading.scanner.strategy.StrategyTimeframe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalSimulationManagementServiceTest {

        @Mock
        private SimulationRunGroupRepository runGroupRepository;

        @Mock
        private SimulationVariantRepository variantRepository;

        @Mock
        private SimulationTradeRepository tradeRepository;

        @Mock
        private StrategyCatalogService strategyCatalogService;

        @Mock
        private TimeProvider timeProvider;

        @InjectMocks
        private HistoricalSimulationManagementService historicalSimulationManagementService;

        @Test
        void reportForGroup_shouldAggregateRunGroupMetrics() {
                SimulationRunGroup group = SimulationRunGroup.builder()
                                .id(1)
                                .name("run-1")
                                .description("test")
                                .fromDate(LocalDate.of(2026, 5, 20))
                                .toDate(LocalDate.of(2026, 6, 10))
                                .createdAt(LocalDateTime.of(2026, 6, 18, 10, 0))
                                .status(SimulationStatus.COMPLETED)
                                .build();

                SimulationVariant v1 = SimulationVariant.builder()
                                .id(10)
                                .runGroupId(1)
                                .strategyId("breakout_v1")
                                .strategyVersion("1.0.0")
                                .strategyDisplayName("Breakout V1 15M")
                                .strategyStatus(StrategyStatus.DRAFT)
                                .timeframe(StrategyTimeframe.FIFTEEN_MINUTE)
                                .variantKey("breakout_v1__15m")
                                .variantName("Breakout V1 15M")
                                .status(SimulationStatus.COMPLETED)
                                .build();

                SimulationVariant v2 = SimulationVariant.builder()
                                .id(11)
                                .runGroupId(1)
                                .strategyId("breakout_v1_5m")
                                .strategyVersion("1.0.0")
                                .strategyDisplayName("Breakout V1 5M")
                                .strategyStatus(StrategyStatus.DRAFT)
                                .timeframe(StrategyTimeframe.FIVE_MINUTE)
                                .variantKey("breakout_v1__5m")
                                .variantName("Breakout V1 5M")
                                .status(SimulationStatus.COMPLETED)
                                .build();

                List<SimulationTrade> variant1Trades = List.of(
                                trade(10, "ONGC", LocalDate.of(2026, 5, 20), SimulationTradeResult.WIN, 100.0, 80.0),
                                trade(10, "ITC", LocalDate.of(2026, 5, 21), SimulationTradeResult.LOSS, -50.0, -70.0));

                List<SimulationTrade> variant2Trades = List.of(
                                trade(11, "WIPRO", LocalDate.of(2026, 5, 22), SimulationTradeResult.WIN, 200.0, 180.0));

                when(runGroupRepository.findById(1)).thenReturn(Optional.of(group));
                when(variantRepository.findByRunGroupIdOrderByIdAsc(1)).thenReturn(List.of(v1, v2));
                when(tradeRepository.findByVariantIdOrderBySignalDateAsc(10)).thenReturn(variant1Trades);
                when(tradeRepository.findByVariantIdOrderBySignalDateAsc(11)).thenReturn(variant2Trades);

                HistoricalSimulationManagementService.RunGroupReport report = historicalSimulationManagementService
                                .reportForGroup(1);

                assertEquals(1, report.runGroupId());
                assertEquals(2, report.variantCount());
                assertEquals(2, report.completedVariantCount());
                assertEquals(0, report.failedVariantCount());
                assertEquals(3, report.totalTrades());
                assertEquals(2, report.winningTrades());
                assertEquals(1, report.losingTrades());
                assertEquals(0, report.flatTrades());
                assertEquals(250.0, report.grossPnl());
                assertEquals(190.0, report.netPnl());
                assertEquals("Breakout V1 5M", report.bestVariant());
                assertEquals(2, report.variants().size());
        }

        @Test
        void compareVariants_shouldSortByNetPnlDescending() {
                SimulationRunGroup group = SimulationRunGroup.builder()
                                .id(1)
                                .name("run-1")
                                .description("test")
                                .fromDate(LocalDate.of(2026, 5, 20))
                                .toDate(LocalDate.of(2026, 6, 10))
                                .createdAt(LocalDateTime.of(2026, 6, 18, 10, 0))
                                .status(SimulationStatus.COMPLETED)
                                .build();

                SimulationVariant v1 = SimulationVariant.builder()
                                .id(10)
                                .runGroupId(1)
                                .strategyId("breakout_v1")
                                .strategyVersion("1.0.0")
                                .strategyDisplayName("Breakout V1 15M")
                                .strategyStatus(StrategyStatus.DRAFT)
                                .timeframe(StrategyTimeframe.FIFTEEN_MINUTE)
                                .variantKey("breakout_v1__15m")
                                .variantName("Breakout V1 15M")
                                .status(SimulationStatus.COMPLETED)
                                .build();

                SimulationVariant v2 = SimulationVariant.builder()
                                .id(11)
                                .runGroupId(1)
                                .strategyId("breakout_v1_5m")
                                .strategyVersion("1.0.0")
                                .strategyDisplayName("Breakout V1 5M")
                                .strategyStatus(StrategyStatus.DRAFT)
                                .timeframe(StrategyTimeframe.FIVE_MINUTE)
                                .variantKey("breakout_v1__5m")
                                .variantName("Breakout V1 5M")
                                .status(SimulationStatus.COMPLETED)
                                .build();

                when(runGroupRepository.findById(1)).thenReturn(Optional.of(group));
                when(variantRepository.findByRunGroupIdOrderByIdAsc(1)).thenReturn(List.of(v1, v2));
                when(tradeRepository.findByVariantIdOrderBySignalDateAsc(10)).thenReturn(List.of(
                                trade(10, "ONGC", LocalDate.of(2026, 5, 20), SimulationTradeResult.WIN, 100.0, 80.0)));
                when(tradeRepository.findByVariantIdOrderBySignalDateAsc(11)).thenReturn(List.of(
                                trade(11, "WIPRO", LocalDate.of(2026, 5, 22), SimulationTradeResult.WIN, 200.0,
                                                180.0)));

                List<HistoricalSimulationManagementService.VariantReport> compare = historicalSimulationManagementService
                                .compareVariants(1);

                assertEquals(2, compare.size());
                assertEquals("Breakout V1 5M", compare.get(0).variantName());
                assertEquals(180.0, compare.get(0).netPnl());
                assertEquals("Breakout V1 15M", compare.get(1).variantName());
                assertEquals(80.0, compare.get(1).netPnl());
        }

        private SimulationTrade trade(
                        Integer variantId,
                        String symbol,
                        LocalDate signalDate,
                        SimulationTradeResult result,
                        double grossPnl,
                        double netPnl) {
                return SimulationTrade.builder()
                                .variantId(variantId)
                                .symbol(symbol)
                                .signalDate(signalDate)
                                .entryDate(signalDate)
                                .entryPrice(100.0)
                                .quantity(10)
                                .grossPnl(grossPnl)
                                .netPnl(netPnl)
                                .tradeStatus(SimulationTradeStatus.CLOSED)
                                .tradeResult(result)
                                .createdAt(LocalDateTime.of(2026, 6, 18, 10, 0))
                                .build();
        }
}