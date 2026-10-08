package com.trading.scanner.strategy.engine;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.strategy.model.MarketState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterMarketFilterServiceTest {

    @Mock
    private MarketCandleRepository marketCandleRepository;

    @Mock
    private TimeProvider timeProvider;

    private MasterMarketFilterService filterService;

    private final LocalDate today = LocalDate.of(2026, 10, 6);

    @BeforeEach
    void setUp() {
        filterService = new MasterMarketFilterService(marketCandleRepository, timeProvider);
    }

    @Test
    void evaluateCurrentState_before0920_shouldBeInvalid() {
        LocalDateTime time = today.atTime(9, 18);
        when(timeProvider.nowDateTime()).thenReturn(time);

        MarketState state = filterService.evaluateCurrentState(today);

        assertFalse(state.dataValid());
        assertEquals(MarketState.MarketRegime.UNKNOWN, state.regime());
        assertTrue(state.reason().contains("Pre-evaluation window"));
    }

    @Test
    void evaluateCurrentState_after1300_shouldBeInvalid() {
        LocalDateTime time = today.atTime(13, 5);
        when(timeProvider.nowDateTime()).thenReturn(time);

        MarketState state = filterService.evaluateCurrentState(today);

        assertFalse(state.dataValid());
        assertEquals(MarketState.MarketRegime.UNKNOWN, state.regime());
        assertTrue(state.reason().contains("cutoff reached"));
    }

    @Test
    void evaluateCurrentState_noNiftyCandles_shouldBeInvalid() {
        LocalDateTime time = today.atTime(9, 45);
        when(timeProvider.nowDateTime()).thenReturn(time);
        when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("NIFTY"), eq("NSE"), eq(CandleTimeframe.FIVE_MINUTE), any(), any()))
                .thenReturn(Collections.emptyList());

        MarketState state = filterService.evaluateCurrentState(today);

        assertFalse(state.dataValid());
        assertEquals(MarketState.MarketRegime.UNKNOWN, state.regime());
        assertTrue(state.reason().contains("No finalized 5-minute NIFTY candles"));
    }

    @Test
    void evaluateCurrentState_niftyAboveVwap_shouldBeBullish() {
        LocalDateTime time = today.atTime(9, 45);
        when(timeProvider.nowDateTime()).thenReturn(time);

        MarketCandle candle = MarketCandle.builder()
                .symbol("NIFTY")
                .exchange("NSE")
                .timeframe(CandleTimeframe.FIVE_MINUTE)
                .candleTime(today.atTime(9, 40))
                .closePrice(25050.0)
                .vwap(25000.0)
                .build();

        when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("NIFTY"), eq("NSE"), eq(CandleTimeframe.FIVE_MINUTE), any(), any()))
                .thenReturn(List.of(candle));

        MarketState state = filterService.evaluateCurrentState(today);

        assertTrue(state.dataValid());
        assertTrue(state.isBullish());
        assertTrue(state.niftyAboveVwap());
        assertEquals(MarketState.MarketRegime.BULLISH, state.regime());
        assertEquals(25050.0, state.niftyClose());
        assertEquals(25000.0, state.niftyVwap());
        assertEquals(0.2, state.vwapDistancePct(), 0.001);
        assertTrue(state.reason().contains("BULLISH"));
    }

    @Test
    void evaluateCurrentState_niftyBelowVwap_shouldBeBearish() {
        LocalDateTime time = today.atTime(10, 15);
        when(timeProvider.nowDateTime()).thenReturn(time);

        MarketCandle candle = MarketCandle.builder()
                .symbol("NIFTY")
                .exchange("NSE")
                .timeframe(CandleTimeframe.FIVE_MINUTE)
                .candleTime(today.atTime(10, 10))
                .closePrice(24900.0)
                .vwap(25000.0)
                .build();

        when(marketCandleRepository.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("NIFTY"), eq("NSE"), eq(CandleTimeframe.FIVE_MINUTE), any(), any()))
                .thenReturn(List.of(candle));

        MarketState state = filterService.evaluateCurrentState(today);

        assertTrue(state.dataValid());
        assertTrue(state.isBearish());
        assertFalse(state.niftyAboveVwap());
        assertEquals(MarketState.MarketRegime.BEARISH, state.regime());
        assertEquals(24900.0, state.niftyClose());
        assertEquals(25000.0, state.niftyVwap());
        assertEquals(-0.4, state.vwapDistancePct(), 0.001);
        assertTrue(state.reason().contains("BEARISH"));
    }
}
