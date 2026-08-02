package com.trading.scanner.service.engine;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.model.DayType;
import com.trading.scanner.model.VolumeTimeWindowBaseline;
import com.trading.scanner.repository.VolumeTimeWindowBaselineRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DailyStockContextService {

    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime FIRST_CANDLE_END = LocalTime.of(9, 19);
    private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 29);

    private final DailyStockContextRepository dailyStockContextRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final StockPriceRepository stockPriceRepository;
    private final MarketStateService marketStateService;
    private final VolumeTimeWindowBaselineRepository volumeTimeWindowBaselineRepository;
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
                        .corporateActionFlag(false)
                        .foBanFlag(false)
                        .resultsLast3dFlag(false)
                        .skipToday(false)
                        .createdAt(timeProvider.nowDateTime())
                        .build());

        ensurePreviousDayFactsAndGap(context, candle);
        applyFirstCandle(context, firstCandleWindow);
        applyOpeningRange(context, openingRangeWindow);
        resolveBreakoutReferencePrice(context);

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

        boolean ready = candles.size() >= 5;
        context.setFirstCandleReady(ready);

        if (!ready || low <= 0.0) {
            context.setFirstCandleBullish(false);
            context.setFirstCandleValid(false);
            return;
        }

        Double open = context.getFirstCandleOpen();
        Double close = context.getFirstCandleClose();
        Double rangePct = context.getFirstCandleRangePct();

        boolean bullish = close != null && open != null && close > open;
        context.setFirstCandleBullish(bullish);

        boolean valid = Boolean.TRUE.equals(bullish)
                && rangePct != null
                && rangePct >= 0.15
                && rangePct <= 3.0
                && volume >= 50_000L;

        context.setFirstCandleValid(valid);
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
        long volume = candles.stream()
                .map(MarketCandle::getVolume)
                .filter(v -> v != null)
                .mapToLong(Long::longValue)
                .sum();

        context.setOpeningRangeHigh(high);
        context.setOpeningRangeLow(low);
        context.setOpeningRangeSize(high - low);
        context.setOpeningRangeSizePct(low > 0 ? ((high - low) / low) * 100.0 : null);
        context.setOpeningRangeVolume(volume);

        boolean ready = candles.size() >= 15;
        context.setOpeningRangeReady(ready);

        if (!ready || low <= 0.0 || high <= low) {
            context.setOpeningRangeSkew(null);
            context.setOpeningRangeValid(false);
            return;
        }

        double midpoint = (high + low) / 2.0;
        double upperHalf = high - midpoint;
        double lowerHalf = midpoint - low;

        Double skew = (lowerHalf > 0.0) ? (upperHalf / lowerHalf) : null;
        context.setOpeningRangeSkew(skew);

        Double sizePct = context.getOpeningRangeSizePct();

        // --- NEW: baseline participation check using V1.3.3 baselines ---
        VolumeTimeWindowBaseline baseline = volumeTimeWindowBaselineRepository
                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                        context.getSymbol(),
                        context.getExchange(),
                        context.getTradingDate(),
                        14 // session minute 14 = 9:29 AM
                )
                .orElse(null);

        Long baselineVolume = baseline != null ? baseline.getAvgCumulativeVolume20() : null;
        Double participationRatio = (baselineVolume != null && baselineVolume > 0L)
                ? volume / (double) baselineVolume
                : null;
        // ---------------------------------------------------------------

        boolean valid = sizePct != null
                && sizePct >= 0.15
                && sizePct <= 2.5
                && skew != null
                && skew >= 0.5
                && skew <= 2.0
                && participationRatio != null
                && participationRatio >= 0.5;

        context.setOpeningRangeValid(valid);
    }

    private void ensurePreviousDayFactsAndGap(DailyStockContext context, MarketCandle todayFirstCandle) {
        if (context.getPrevDayClose() != null) {
            return;
        }

        LocalDate tradingDate = context.getTradingDate();
        LocalDate prevDate = tradingDate.minusDays(1);

        List<StockPrice> history = stockPriceRepository
                .findBySymbolAndDateLessThanEqualOrderByDateAsc(context.getSymbol(), tradingDate);

        if (history.isEmpty()) {
            return;
        }

        // Previous day row
        StockPrice prev = history.stream()
                .filter(p -> tradingDate.minusDays(1).equals(p.getDate()))
                .reduce((first, second) -> second)
                .orElse(null);

        if (prev == null) {
            return;
        }

        Double prevOpen = prev.getOpenPrice();
        Double prevHigh = prev.getHighPrice();
        Double prevLow = prev.getLowPrice();
        Double prevClose = prev.getClosePrice();

        context.setPrevDayOpen(prevOpen);
        context.setPrevDayHigh(prevHigh);
        context.setPrevDayLow(prevLow);
        context.setPrevDayClose(prevClose);

        if (prevOpen != null && prevClose != null && prevOpen != 0.0) {
            double prevRangePct = Math.abs(prevClose - prevOpen) / prevOpen * 100.0;
            context.setPrevDayRangePct(prevRangePct);
        }

        // consecutive red/green days ending yesterday
        int consecutiveRed = 0;
        int consecutiveGreen = 0;

        StockPrice prevIter = prev;
        for (int i = history.size() - 2; i >= 0; i--) {
            StockPrice current = history.get(i);
            if (current.getClosePrice() == null || prevIter.getClosePrice() == null) {
                break;
            }

            if (current.getClosePrice() < prevIter.getClosePrice()) {
                if (consecutiveGreen > 0)
                    break;
                consecutiveRed++;
            } else if (current.getClosePrice() > prevIter.getClosePrice()) {
                if (consecutiveRed > 0)
                    break;
                consecutiveGreen++;
            } else {
                break;
            }

            prevIter = current;
        }

        context.setConsecutiveRedDays(consecutiveRed);
        context.setConsecutiveGreenDays(consecutiveGreen);

        // highestClose15d and distFromResistancePct
        int fromIndex = Math.max(0, history.size() - 15);
        List<StockPrice> last15 = history.subList(fromIndex, history.size());
        double highestClose = last15.stream()
                .map(StockPrice::getClosePrice)
                .filter(c -> c != null)
                .max(Double::compareTo)
                .orElse(prevClose != null ? prevClose : 0.0);

        context.setHighestClose15d(highestClose);

        if (prevClose != null && prevClose != 0.0) {
            double distFromResistancePct = (highestClose - prevClose) / prevClose * 100.0;
            context.setDistFromResistancePct(distFromResistancePct);
        }

        // gapPct using today's first candle open
        Double todayOpen = todayFirstCandle.getOpenPrice();
        if (todayOpen != null && prevClose != null && prevClose != 0.0) {
            double gapPct = (todayOpen - prevClose) / prevClose * 100.0;
            context.setGapPct(gapPct);
        }

        // exclusion flags already defaulted to false in builder; skipToday is OR of
        // them
        boolean skipToday = Boolean.TRUE.equals(context.getCorporateActionFlag())
                || Boolean.TRUE.equals(context.getFoBanFlag())
                || Boolean.TRUE.equals(context.getResultsLast3dFlag());
        context.setSkipToday(skipToday);
    }

    private void resolveBreakoutReferencePrice(DailyStockContext context) {
        if (context.getBreakoutReferencePrice() != null) {
            return;
        }

        var state = marketStateService.currentState();
        if (state == null || state.dayType() == null) {
            return;
        }

        DayType dayType = state.dayType();

        if (dayType == DayType.NORMAL) {
            context.setBreakoutReferencePrice(context.getPrevDayHigh());
        } else if (dayType == DayType.GAP_UP) {
            context.setBreakoutReferencePrice(context.getOpeningRangeHigh());
        } else if (dayType == DayType.GAP_DOWN) {
            context.setBreakoutReferencePrice(null);
        }
    }

}
