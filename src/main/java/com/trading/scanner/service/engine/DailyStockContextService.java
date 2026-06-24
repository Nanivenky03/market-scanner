package com.trading.scanner.service.engine;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DailyStockContextService {

    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime FIRST_CANDLE_END = LocalTime.of(9, 19);
    private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 29);

    private final DailyStockContextRepository dailyStockContextRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final TimeProvider timeProvider;

    @Transactional
    public void processFinalizedOneMinuteCandle(MarketCandle candle) {
        if (candle == null || candle.getTimeframe() != CandleTimeframe.ONE_MINUTE
                || !Boolean.TRUE.equals(candle.getIsFinalized())) {
            return;
        }

        LocalDateTime candleTime = candle.getCandleTime();
        LocalTime minute = candleTime.toLocalTime();

        if (minute.isBefore(MARKET_OPEN) || minute.isAfter(OPENING_RANGE_END)) {
            return;
        }

        LocalDate tradingDate = candleTime.toLocalDate();
        LocalDateTime dayStart = tradingDate.atTime(MARKET_OPEN);
        LocalDateTime dayCurrent = tradingDate.atTime(minute);

        List<MarketCandle> sessionCandles = marketCandleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        candle.getSymbol(),
                        candle.getExchange(),
                        CandleTimeframe.ONE_MINUTE,
                        dayStart,
                        dayCurrent);

        List<MarketCandle> firstCandleWindow = sessionCandles.stream()
                .filter(c -> !c.getCandleTime().toLocalTime().isAfter(FIRST_CANDLE_END))
                .toList();

        List<MarketCandle> openingRangeWindow = sessionCandles.stream()
                .filter(c -> !c.getCandleTime().toLocalTime().isAfter(OPENING_RANGE_END))
                .toList();

        DailyStockContext context = dailyStockContextRepository
                .findBySymbolAndExchangeAndTradingDate(candle.getSymbol(), candle.getExchange(), tradingDate)
                .orElseGet(() -> DailyStockContext.builder()
                        .symbol(candle.getSymbol())
                        .exchange(candle.getExchange())
                        .tradingDate(tradingDate)
                        .firstCandleReady(false)
                        .openingRangeReady(false)
                        .createdAt(timeProvider.nowDateTime())
                        .build());

        applyFirstCandle(context, firstCandleWindow);
        applyOpeningRange(context, openingRangeWindow);
        context.setUpdatedAt(timeProvider.nowDateTime());

        dailyStockContextRepository.save(context);
    }

    @Transactional(readOnly = true)
    public List<DailyStockContext> findByTradingDate(LocalDate tradingDate) {
        return dailyStockContextRepository.findByTradingDateOrderBySymbolAsc(tradingDate);
    }

    private void applyFirstCandle(DailyStockContext context, List<MarketCandle> candles) {
        if (candles.isEmpty()) {
            return;
        }

        MarketCandle first = candles.get(0);
        MarketCandle last = candles.get(candles.size() - 1);

        double high = candles.stream().mapToDouble(MarketCandle::getHighPrice).max().orElse(first.getHighPrice());
        double low = candles.stream().mapToDouble(MarketCandle::getLowPrice).min().orElse(first.getLowPrice());
        long volume = candles.stream()
                .map(MarketCandle::getVolume)
                .filter(v -> v != null)
                .mapToLong(Long::longValue)
                .sum();

        context.setFirstCandleOpen(first.getOpenPrice());
        context.setFirstCandleHigh(high);
        context.setFirstCandleLow(low);
        context.setFirstCandleClose(last.getClosePrice());
        context.setFirstCandleVolume(volume);
        context.setFirstCandleRange(high - low);
        context.setFirstCandleRangePct(low > 0 ? ((high - low) / low) * 100.0 : null);
        context.setFirstCandleReady(candles.size() >= 5);
    }

    private void applyOpeningRange(DailyStockContext context, List<MarketCandle> candles) {
        if (candles.isEmpty()) {
            return;
        }

        MarketCandle first = candles.stream()
                .min(Comparator.comparing(MarketCandle::getCandleTime))
                .orElse(candles.get(0));

        double high = candles.stream().mapToDouble(MarketCandle::getHighPrice).max().orElse(first.getHighPrice());
        double low = candles.stream().mapToDouble(MarketCandle::getLowPrice).min().orElse(first.getLowPrice());

        context.setOpeningRangeHigh(high);
        context.setOpeningRangeLow(low);
        context.setOpeningRangeSize(high - low);
        context.setOpeningRangeSizePct(low > 0 ? ((high - low) / low) * 100.0 : null);
        context.setOpeningRangeReady(candles.size() >= 15);
    }
}