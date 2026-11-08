package com.trading.scanner.execution;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.execution.adapter.SimulatedOrderAdapter;
import com.trading.scanner.execution.adapter.model.OrderResponse;
import com.trading.scanner.execution.lock.StripedSymbolLockManager;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.strategy.cache.StockContextCache;
import com.trading.scanner.strategy.engine.MasterMarketFilterService;
import com.trading.scanner.strategy.fchb.FchbStrategy;
import com.trading.scanner.strategy.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LiveStrategyExecutionConductorTest {

    @Mock
    private StockContextCache stockContextCache;

    @Mock
    private FchbStrategy fchbStrategy;

    @Mock
    private MasterMarketFilterService masterMarketFilterService;

    @Mock
    private OrderExecutionService orderExecutionService;

    @Mock
    private PositionManagerService positionManagerService;

    @Mock
    private SimulatedOrderAdapter simulatedOrderAdapter;

    @Mock
    private TimeProvider timeProvider;

    @Mock
    private TradingCalendar tradingCalendar;

    private StripedSymbolLockManager lockManager;
    private LiveStrategyExecutionConductor conductor;

    private final LocalDate today = LocalDate.of(2026, 10, 11);

    @BeforeEach
    void setUp() {
        lockManager = new StripedSymbolLockManager();
        conductor = new LiveStrategyExecutionConductor(
                stockContextCache,
                fchbStrategy,
                masterMarketFilterService,
                orderExecutionService,
                positionManagerService,
                simulatedOrderAdapter,
                lockManager,
                timeProvider,
                tradingCalendar
        );
    }

    @Test
    @DisplayName("Candle finalized event on index candle is ignored for stock breakout evaluation")
    void onCandleFinalized_whenIndexCandle_shouldIgnore() {
        MarketCandle indexCandle = MarketCandle.builder()
                .symbol("NIFTY 50")
                .exchange("NSE")
                .timeframe(CandleTimeframe.FIVE_MINUTE)
                .candleTime(today.atTime(9, 35))
                .build();

        CandleFinalizedEvent event = new CandleFinalizedEvent(
                "NIFTY 50", "NSE", CandleTimeframe.FIVE_MINUTE, today.atTime(9, 35), indexCandle, true);

        conductor.onCandleFinalized(event);

        verifyNoInteractions(fchbStrategy);
        verifyNoInteractions(orderExecutionService);
    }

    @Test
    @DisplayName("5M candle close at 09:35 evaluates FCHB setup and dispatches proposal to OrderExecutionService")
    void onCandleFinalized_when5mCandleQualifies_shouldDispatchProposal() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);
        when(orderExecutionService.resolveExecutionMode(FchbStrategy.STRATEGY_ID)).thenReturn(PortfolioMode.PAPER);

        MarketState marketState = new MarketState(
                today, today.atTime(9, 35), 25000.0, 24950.0, 25010.0, true,
                MarketState.MarketRegime.BULLISH, true, null);
        when(masterMarketFilterService.evaluateCurrentState(today)).thenReturn(marketState);

        StockContext mockContext = mock(StockContext.class);
        when(stockContextCache.getStockContext("INFY", "NSE", today)).thenReturn(Optional.of(mockContext));

        StrategyProposal proposal = new StrategyProposal(
                "INFY", "NSE", 2, "FCHB", "BUY", 1500.0, 1485.0, 1511.0, 1495.0, 100.0, Collections.emptyMap());
        StrategyEvaluation evaluation = StrategyEvaluation.qualified(proposal, 100.0, List.of("ALL_PASSED"));
        when(fchbStrategy.evaluate(eq(mockContext), eq(marketState), any())).thenReturn(evaluation);

        when(orderExecutionService.processCandidateProposal(proposal, PortfolioMode.PAPER))
                .thenReturn(OrderExecutionService.ExecutionResult.filled("INFY", "LONG", 50, 1500.05, 1485.0, 1511.0, "ORD1", "SL1"));

        MarketCandle candle5m = MarketCandle.builder()
                .symbol("INFY")
                .exchange("NSE")
                .timeframe(CandleTimeframe.FIVE_MINUTE)
                .candleTime(today.atTime(9, 35))
                .closePrice(1500.0)
                .build();

        CandleFinalizedEvent event = new CandleFinalizedEvent(
                "INFY", "NSE", CandleTimeframe.FIVE_MINUTE, today.atTime(9, 35), candle5m, false);

        conductor.onCandleFinalized(event);

        verify(orderExecutionService).processCandidateProposal(proposal, PortfolioMode.PAPER);
    }

    @Test
    @DisplayName("5M candle outside entry window (e.g. 11:30) skips strategy evaluation")
    void onCandleFinalized_when5mCandleOutsideWindow_shouldSkip() {
        when(tradingCalendar.isTradingDay(today)).thenReturn(true);

        MarketCandle candle5m = MarketCandle.builder()
                .symbol("INFY")
                .exchange("NSE")
                .timeframe(CandleTimeframe.FIVE_MINUTE)
                .candleTime(today.atTime(11, 30))
                .closePrice(1520.0)
                .build();

        CandleFinalizedEvent event = new CandleFinalizedEvent(
                "INFY", "NSE", CandleTimeframe.FIVE_MINUTE, today.atTime(11, 30), candle5m, false);

        conductor.onCandleFinalized(event);

        verifyNoInteractions(fchbStrategy);
        verifyNoInteractions(orderExecutionService);
    }

    @Test
    @DisplayName("1M candle close at 14:45 triggers wind-down tactical exit on open position")
    void onCandleFinalized_when1mCandleAt1445_shouldTriggerWindDownExit() {
        PositionSnapshot pos = new PositionSnapshot(
                101, 2, "TCS", "NSE", "LONG", 20, 20, 3000.0, 2970.0, 3022.0, 2990.0, false, 3010.0, today.atTime(9, 35));
        when(positionManagerService.getPosition(PortfolioMode.PAPER, "TCS")).thenReturn(pos);

        MarketCandle candle1m = MarketCandle.builder()
                .symbol("TCS")
                .exchange("NSE")
                .timeframe(CandleTimeframe.ONE_MINUTE)
                .candleTime(today.atTime(14, 45))
                .closePrice(3015.0)
                .build();

        CandleFinalizedEvent event = new CandleFinalizedEvent(
                "TCS", "NSE", CandleTimeframe.ONE_MINUTE, today.atTime(14, 45), candle1m, false);

        conductor.onCandleFinalized(event);

        verify(orderExecutionService).executeTacticalExit(
                PortfolioMode.PAPER, "TCS", 3015.0, "INTRADAY_WIND_DOWN_1445");
    }

    @Test
    @DisplayName("processIncomingTick fast bypasses when no active position exists")
    void processIncomingTick_whenNoPosition_shouldFastBypass() {
        when(positionManagerService.getPosition(PortfolioMode.PAPER, "SBIN")).thenReturn(null);
        when(positionManagerService.getPosition(PortfolioMode.LIVE, "SBIN")).thenReturn(null);

        conductor.processIncomingTick("SBIN", "NSE", 800.0, today.atTime(10, 15));

        verifyNoInteractions(simulatedOrderAdapter);
        verify(positionManagerService, never()).processTick(any(), any(), anyDouble(), any());
    }

    @Test
    @DisplayName("processIncomingTick executes tactical exit when resting SL order is breached")
    void processIncomingTick_whenRestingSlBreached_shouldTriggerExit() {
        PositionSnapshot pos = new PositionSnapshot(
                102, 2, "RELIANCE", "NSE", "LONG", 30, 30, 2500.0, 2480.0, 2515.0, 2490.0, false, 2500.0, today.atTime(9, 35));
        when(positionManagerService.getPosition(PortfolioMode.PAPER, "RELIANCE")).thenReturn(pos);

        OrderResponse triggeredOrder = OrderResponse.filled("SL_ORD_1", 202, 30, 2479.95, 0.05, 30.0);
        when(simulatedOrderAdapter.checkRestingTriggers("RELIANCE", 2479.50)).thenReturn(List.of(triggeredOrder));

        conductor.processIncomingTick("RELIANCE", "NSE", 2479.50, today.atTime(10, 5));

        verify(orderExecutionService).executeTacticalExit(
                PortfolioMode.PAPER, "RELIANCE", 2479.50, "STOP_LOSS_TRIGGERED");
    }

    @Test
    @DisplayName("processIncomingTick triggers Target 1 partial exit when LTP reaches Target 1 price")
    void processIncomingTick_whenTarget1Hit_shouldTriggerPartialExit() {
        PositionSnapshot pos = new PositionSnapshot(
                103, 2, "HDFCBANK", "NSE", "LONG", 40, 40, 1600.0, 1580.0, 1614.7, 1590.0, false, 1605.0, today.atTime(9, 35));
        when(positionManagerService.getPosition(PortfolioMode.PAPER, "HDFCBANK")).thenReturn(pos);
        when(simulatedOrderAdapter.checkRestingTriggers("HDFCBANK", 1615.0)).thenReturn(Collections.emptyList());

        conductor.processIncomingTick("HDFCBANK", "NSE", 1615.0, today.atTime(10, 12));

        verify(orderExecutionService).executeTarget1PartialExit(PortfolioMode.PAPER, "HDFCBANK", 1615.0);
        verify(positionManagerService).processTick(PortfolioMode.PAPER, "HDFCBANK", 1615.0, LocalTime.of(10, 12));
    }

    @Test
    @DisplayName("onMinuteRollover flushes trailing stop updates for both PAPER and LIVE portfolios")
    void onMinuteRollover_shouldFlushTrailingStops() {
        LocalTime time = LocalTime.of(10, 30);
        conductor.onMinuteRollover(today, time);

        verify(orderExecutionService).flushMinuteRolloverTrailingStops(PortfolioMode.PAPER, today, time);
        verify(orderExecutionService).flushMinuteRolloverTrailingStops(PortfolioMode.LIVE, today, time);
    }
}
