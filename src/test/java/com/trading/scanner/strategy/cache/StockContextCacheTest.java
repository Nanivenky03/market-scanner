package com.trading.scanner.strategy.cache;

import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.strategy.model.StockContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class StockContextCacheTest {

    @Mock
    private MarketCandleRepository marketCandleRepository;

    @Mock
    private DailyStockContextRepository dailyStockContextRepository;

    private StockContextCache cache;

    private final LocalDate today = LocalDate.of(2026, 10, 6);

    @BeforeEach
    void setUp() {
        cache = new StockContextCache(marketCandleRepository, dailyStockContextRepository);
    }

    @Test
    void getStockContext_whenEmpty_shouldReturnEmpty() {
        Optional<StockContext> result = cache.getStockContext("INFY", "NSE", today);
        assertTrue(result.isEmpty());
    }

    @Test
    void onCandleFinalized_and_updateDailyContext_shouldAccumulateInCache() {
        DailyStockContext dailyCtx = DailyStockContext.builder()
                .symbol("INFY")
                .exchange("NSE")
                .tradingDate(today)
                .firstCandleHigh(1500.0)
                .firstCandleLow(1485.0)
                .build();

        cache.updateDailyContext(dailyCtx);

        MarketCandle c1 = MarketCandle.builder()
                .symbol("INFY")
                .exchange("NSE")
                .timeframe(CandleTimeframe.FIVE_MINUTE)
                .candleTime(today.atTime(9, 20))
                .openPrice(1490.0)
                .highPrice(1500.0)
                .lowPrice(1485.0)
                .closePrice(1495.0)
                .volume(60000L)
                .build();

        cache.onCandleFinalized(c1);

        Optional<StockContext> optContext = cache.getStockContext("INFY", "NSE", today);
        assertTrue(optContext.isPresent());

        StockContext ctx = optContext.get();
        assertEquals("INFY", ctx.symbol());
        assertEquals("NSE", ctx.exchange());
        assertFalse(ctx.backfillActive());
        assertNotNull(ctx.dailyContext());
        assertEquals(1500.0, ctx.dailyContext().getFirstCandleHigh());

        assertEquals(1, ctx.candles5m().size());
        assertEquals(1495.0, ctx.latest5mCandle().getClosePrice());
    }

    @Test
    void getStockContext_whenBackfillActive_shouldReflectFlag() {
        MarketCandle c1 = MarketCandle.builder()
                .symbol("TCS")
                .exchange("NSE")
                .timeframe(CandleTimeframe.ONE_MINUTE)
                .candleTime(today.atTime(9, 16))
                .openPrice(3500.0)
                .highPrice(3510.0)
                .lowPrice(3495.0)
                .closePrice(3505.0)
                .volume(12000L)
                .build();

        cache.onCandleFinalized(c1);

        cache.markBackfillActive("TCS", "NSE");

        Optional<StockContext> optContext = cache.getStockContext("TCS", "NSE", today);
        assertTrue(optContext.isPresent());
        assertTrue(optContext.get().backfillActive());

        cache.markBackfillCompleted("TCS", "NSE");

        Optional<StockContext> recoveredContext = cache.getStockContext("TCS", "NSE", today);
        assertTrue(recoveredContext.isPresent());
        assertFalse(recoveredContext.get().backfillActive());
    }
}
