package com.trading.scanner.service.engine;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.ContextStatus;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.DayType;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.VolumeTimeWindowBaseline;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.VolumeTimeWindowBaselineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DailyStockContextService {

        private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

        private static final LocalTime FIRST_CANDLE_END = LocalTime.of(9, 19);

        private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 29);

        private static final int FIRST_CANDLE_MINUTES = 5;
        private static final int OPENING_RANGE_MINUTES = 15;
        private static final int OPENING_RANGE_SESSION_MINUTE = 14;
        private static final int RESISTANCE_LOOKBACK_DAYS = 15;

        private static final double FIRST_CANDLE_MIN_RANGE_PCT = 0.15;
        private static final double FIRST_CANDLE_MAX_RANGE_PCT = 3.0;
        private static final long FIRST_CANDLE_MIN_VOLUME = 50_000L;

        private static final double OPENING_RANGE_MIN_SIZE_PCT = 0.15;
        private static final double OPENING_RANGE_MAX_SIZE_PCT = 2.5;
        private static final double OPENING_RANGE_MIN_PARTICIPATION = 0.50;

        /*
         * openingRangeSkew stores the strategist-defined
         * skewPosition value:
         *
         * (averageTypicalPrice - openingRangeLow)
         * / (openingRangeHigh - openingRangeLow)
         */
        private static final double OPENING_RANGE_MIN_SKEW = 0.25;
        private static final double OPENING_RANGE_MAX_SKEW = 0.75;

        private static final double PREVIOUS_DAY_MAX_RANGE_PCT = 4.0;

        private final DailyStockContextRepository dailyStockContextRepository;

        private final MarketCandleRepository marketCandleRepository;

        private final StockPriceRepository stockPriceRepository;

        private final VolumeTimeWindowBaselineRepository volumeTimeWindowBaselineRepository;

        private final MarketStateService marketStateService;
        private final TradingCalendar tradingCalendar;
        private final TimeProvider timeProvider;

        @Transactional
        public void processFinalizedOneMinuteCandle(
                        MarketCandle candle) {

                if (!isUsableOneMinuteCandle(candle)) {
                        return;
                }

                LocalDateTime candleTime = candle.getCandleTime();

                LocalTime minute = candleTime.toLocalTime();

                if (minute.isBefore(MARKET_OPEN)
                                || minute.isAfter(OPENING_RANGE_END)) {
                        return;
                }

                LocalDate tradingDate = candleTime.toLocalDate();

                List<MarketCandle> sessionCandles = Optional.ofNullable(
                                marketCandleRepository
                                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                                candle.getSymbol(),
                                                                candle.getExchange(),
                                                                CandleTimeframe.ONE_MINUTE,
                                                                tradingDate.atTime(
                                                                                MARKET_OPEN),
                                                                candleTime))
                                .orElseGet(List::of)
                                .stream()
                                .filter(this::isUsableOneMinuteCandle)
                                .sorted(Comparator.comparing(
                                                MarketCandle::getCandleTime))
                                .toList();

                List<MarketCandle> firstCandleWindow = sessionCandles.stream()
                                .filter(item -> !item.getCandleTime()
                                                .isBefore(
                                                                tradingDate.atTime(
                                                                                MARKET_OPEN)))
                                .filter(item -> !item.getCandleTime()
                                                .isAfter(
                                                                tradingDate.atTime(
                                                                                FIRST_CANDLE_END)))
                                .toList();

                List<MarketCandle> openingRangeWindow = sessionCandles.stream()
                                .filter(item -> !item.getCandleTime()
                                                .isBefore(
                                                                tradingDate.atTime(
                                                                                MARKET_OPEN)))
                                .filter(item -> !item.getCandleTime()
                                                .isAfter(
                                                                tradingDate.atTime(
                                                                                OPENING_RANGE_END)))
                                .toList();

                DailyStockContext context = dailyStockContextRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                candle.getSymbol(),
                                                candle.getExchange(),
                                                tradingDate)
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
                                                .contextStatus(
                                                                ContextStatus.PARTIAL)
                                                .createdAt(
                                                                timeProvider.nowDateTime())
                                                .build());

                MarketCandle firstTodayCandle = sessionCandles.isEmpty()
                                ? candle
                                : sessionCandles.get(0);

                ensurePreviousDayFactsAndGap(
                                context,
                                firstTodayCandle);

                applyFirstCandle(
                                context,
                                firstCandleWindow);

                applyOpeningRange(
                                context,
                                openingRangeWindow);

                resolveBreakoutReferencePrice(context);
                refreshSkipToday(context);
                refreshContextStatus(context);

                context.setUpdatedAt(
                                timeProvider.nowDateTime());

                dailyStockContextRepository.save(context);
        }

        @Transactional(readOnly = true)
        public List<DailyStockContext> findByTradingDate(
                        LocalDate tradingDate) {

                return dailyStockContextRepository
                                .findByTradingDateOrderBySymbolAsc(
                                                tradingDate);
        }

        private void ensurePreviousDayFactsAndGap(
                        DailyStockContext context,
                        MarketCandle firstTodayCandle) {

                LocalDate tradingDate = context.getTradingDate();

                LocalDate previousTradingDate = tradingCalendar.previousTradingDay(
                                tradingDate);

                List<StockPrice> history = Optional.ofNullable(
                                stockPriceRepository
                                                .findBySymbolAndDateLessThanEqualOrderByDateAsc(
                                                                context.getSymbol(),
                                                                tradingDate))
                                .orElseGet(List::of)
                                .stream()
                                .filter(price -> price != null
                                                && price.getDate() != null)
                                .filter(price -> price.getDate()
                                                .isBefore(tradingDate))
                                .sorted(Comparator.comparing(
                                                StockPrice::getDate))
                                .toList();

                if (history.isEmpty()) {
                        refreshSkipToday(context);
                        return;
                }

                StockPrice previousDay = history.stream()
                                .filter(price -> previousTradingDate.equals(
                                                price.getDate()))
                                .findFirst()
                                .orElseGet(() -> history.get(
                                                history.size() - 1));

                context.setPrevDayOpen(
                                previousDay.getOpenPrice());

                context.setPrevDayHigh(
                                previousDay.getHighPrice());

                context.setPrevDayLow(
                                previousDay.getLowPrice());

                context.setPrevDayClose(
                                previousDay.getClosePrice());

                if (previousDay.getLowPrice() != null
                                && previousDay.getLowPrice() > 0.0
                                && previousDay.getHighPrice() != null) {

                        context.setPrevDayRangePct(
                                        (previousDay.getHighPrice()
                                                        - previousDay.getLowPrice())
                                                        / previousDay.getLowPrice()
                                                        * 100.0);
                } else {
                        context.setPrevDayRangePct(null);
                }

                int previousDayIndex = history.size() - 1;

                context.setConsecutiveRedDays(
                                consecutiveDownCloses(
                                                history,
                                                previousDayIndex));

                context.setConsecutiveGreenDays(
                                consecutiveUpCloses(
                                                history,
                                                previousDayIndex));

                int fromIndex = Math.max(
                                0,
                                history.size()
                                                - RESISTANCE_LOOKBACK_DAYS);

                Double highestClose = history.subList(
                                fromIndex,
                                history.size())
                                .stream()
                                .map(StockPrice::getClosePrice)
                                .filter(Objects::nonNull)
                                .max(Double::compareTo)
                                .orElse(null);

                context.setHighestClose15d(highestClose);

                if (highestClose != null
                                && highestClose > 0.0
                                && previousDay.getClosePrice() != null) {

                        context.setDistFromResistancePct(
                                        (highestClose
                                                        - previousDay.getClosePrice())
                                                        / highestClose
                                                        * 100.0);
                } else {
                        context.setDistFromResistancePct(null);
                }

                if (firstTodayCandle != null
                                && firstTodayCandle.getOpenPrice() != null
                                && previousDay.getClosePrice() != null
                                && previousDay.getClosePrice() != 0.0) {

                        context.setGapPct(
                                        (firstTodayCandle.getOpenPrice()
                                                        - previousDay.getClosePrice())
                                                        / previousDay.getClosePrice()
                                                        * 100.0);
                } else {
                        context.setGapPct(null);
                }

                refreshSkipToday(context);
        }

        private void applyFirstCandle(
                        DailyStockContext context,
                        List<MarketCandle> candles) {

                if (!hasExactMinuteRange(
                                candles,
                                context.getTradingDate()
                                                .atTime(MARKET_OPEN),
                                context.getTradingDate()
                                                .atTime(FIRST_CANDLE_END),
                                FIRST_CANDLE_MINUTES)) {

                        context.setFirstCandleReady(false);
                        context.setFirstCandleValid(false);
                        return;
                }

                MarketCandle first = candles.get(0);

                MarketCandle last = candles.get(candles.size() - 1);

                double high = highestHigh(candles);

                double low = lowestLow(candles);

                long volume = totalVolume(candles);

                context.setFirstCandleOpen(
                                first.getOpenPrice());

                context.setFirstCandleHigh(high);
                context.setFirstCandleLow(low);

                context.setFirstCandleClose(
                                last.getClosePrice());

                context.setFirstCandleVolume(volume);
                context.setFirstCandleRange(high - low);

                context.setFirstCandleRangePct(
                                low > 0.0
                                                ? (high - low)
                                                                / low
                                                                * 100.0
                                                : null);

                context.setFirstCandleReady(true);

                boolean bullish = first.getOpenPrice() != null
                                && last.getClosePrice() != null
                                && last.getClosePrice() > first.getOpenPrice();

                context.setFirstCandleBullish(bullish);

                Double rangePct = context.getFirstCandleRangePct();

                context.setFirstCandleValid(
                                bullish
                                                && volume >= FIRST_CANDLE_MIN_VOLUME
                                                && rangePct != null
                                                && rangePct >= FIRST_CANDLE_MIN_RANGE_PCT
                                                && rangePct <= FIRST_CANDLE_MAX_RANGE_PCT);
        }

        private void applyOpeningRange(
                        DailyStockContext context,
                        List<MarketCandle> candles) {

                if (!hasExactMinuteRange(
                                candles,
                                context.getTradingDate()
                                                .atTime(MARKET_OPEN),
                                context.getTradingDate()
                                                .atTime(OPENING_RANGE_END),
                                OPENING_RANGE_MINUTES)) {

                        context.setOpeningRangeReady(false);
                        context.setOpeningRangeValid(false);
                        return;
                }

                double high = highestHigh(candles);

                double low = lowestLow(candles);

                long volume = totalVolume(candles);

                context.setOpeningRangeHigh(high);
                context.setOpeningRangeLow(low);
                context.setOpeningRangeSize(high - low);

                context.setOpeningRangeSizePct(
                                low > 0.0
                                                ? (high - low)
                                                                / low
                                                                * 100.0
                                                : null);

                context.setOpeningRangeVolume(volume);
                context.setOpeningRangeReady(true);

                if (low <= 0.0 || high <= low) {
                        context.setOpeningRangeSkew(null);
                        context.setOpeningRangeValid(false);
                        return;
                }

                double range = high - low;

                Double averageTypicalPrice = averageTypicalPrice(candles);

                Double skewPosition = averageTypicalPrice != null
                                && range > 0.0
                                                ? (averageTypicalPrice - low)
                                                                / range
                                                : null;

                context.setOpeningRangeSkew(
                                skewPosition);

                VolumeTimeWindowBaseline baseline = volumeTimeWindowBaselineRepository
                                .findBySymbolAndExchangeAndTradingDateAndSessionMinute(
                                                context.getSymbol(),
                                                context.getExchange(),
                                                context.getTradingDate(),
                                                OPENING_RANGE_SESSION_MINUTE)
                                .orElse(null);

                Long baselineVolume = baseline == null
                                ? null
                                : baseline.getAvgCumulativeVolume20();

                Double participationRatio = baselineVolume != null
                                && baselineVolume > 0L
                                                ? volume / (double) baselineVolume
                                                : null;

                Double sizePct = context.getOpeningRangeSizePct();

                context.setOpeningRangeValid(
                                sizePct != null
                                                && sizePct >= OPENING_RANGE_MIN_SIZE_PCT
                                                && sizePct <= OPENING_RANGE_MAX_SIZE_PCT
                                                && skewPosition != null
                                                && skewPosition >= OPENING_RANGE_MIN_SKEW
                                                && skewPosition <= OPENING_RANGE_MAX_SKEW
                                                && participationRatio != null
                                                && participationRatio >= OPENING_RANGE_MIN_PARTICIPATION);
        }

        private Double averageTypicalPrice(
                        List<MarketCandle> candles) {

                if (candles == null
                                || candles.isEmpty()) {
                        return null;
                }

                double total = 0.0;

                for (MarketCandle candle : candles) {
                        if (candle == null
                                        || !isFinite(candle.getHighPrice())
                                        || !isFinite(candle.getLowPrice())
                                        || !isFinite(candle.getClosePrice())
                                        || candle.getHighPrice() < candle.getLowPrice()) {
                                return null;
                        }

                        total += typicalPrice(candle);
                }

                return total / candles.size();
        }

        private double typicalPrice(
                        MarketCandle candle) {

                return (candle.getHighPrice()
                                + candle.getLowPrice()
                                + candle.getClosePrice())
                                / 3.0;
        }

        private boolean isFinite(
                        Double value) {

                return value != null
                                && !value.isNaN()
                                && !value.isInfinite();
        }

        private void resolveBreakoutReferencePrice(
                        DailyStockContext context) {

                MarketStateService.MarketState state = marketStateService.currentState();

                if (state == null
                                || state.tradingDate() == null
                                || !state.tradingDate().equals(
                                                context.getTradingDate())
                                || state.dayType() == null) {
                        return;
                }

                if (state.dayType() == DayType.NORMAL) {
                        context.setBreakoutReferencePrice(
                                        context.getPrevDayHigh());
                } else if (state.dayType() == DayType.GAP_UP) {
                        context.setBreakoutReferencePrice(
                                        context.getOpeningRangeHigh());
                } else if (state.dayType() == DayType.GAP_DOWN) {
                        context.setBreakoutReferencePrice(null);
                }
        }

        private void refreshSkipToday(
                        DailyStockContext context) {

                boolean previousDayTooVolatile = context.getPrevDayRangePct() != null
                                && context.getPrevDayRangePct() > PREVIOUS_DAY_MAX_RANGE_PCT;

                context.setSkipToday(
                                Boolean.TRUE.equals(
                                                context.getCorporateActionFlag())
                                                || Boolean.TRUE.equals(
                                                                context.getFoBanFlag())
                                                || Boolean.TRUE.equals(
                                                                context.getResultsLast3dFlag())
                                                || previousDayTooVolatile);
        }

        private void refreshContextStatus(
                        DailyStockContext context) {

                boolean previousDayComplete = context.getPrevDayOpen() != null
                                && context.getPrevDayHigh() != null
                                && context.getPrevDayLow() != null
                                && context.getPrevDayClose() != null
                                && context.getGapPct() != null;

                boolean firstCandleComplete = Boolean.TRUE.equals(
                                context.getFirstCandleReady());

                boolean openingRangeComplete = Boolean.TRUE.equals(
                                context.getOpeningRangeReady());

                MarketStateService.MarketState state = marketStateService.currentState();

                boolean dayTypeAvailable = state != null
                                && context.getTradingDate().equals(
                                                state.tradingDate())
                                && state.dayType() != null;

                boolean breakoutReferenceResolved = dayTypeAvailable
                                && (state.dayType() == DayType.GAP_DOWN
                                                || context.getBreakoutReferencePrice() != null);

                if (previousDayComplete
                                && firstCandleComplete
                                && openingRangeComplete
                                && breakoutReferenceResolved) {

                        context.setContextStatus(
                                        ContextStatus.COMPLETE);
                } else {
                        context.setContextStatus(
                                        ContextStatus.PARTIAL);
                }
        }

        private int consecutiveDownCloses(
                        List<StockPrice> history,
                        int lastIndex) {

                int count = 0;

                for (int index = lastIndex; index > 0; index--) {

                        Double currentClose = history.get(index)
                                        .getClosePrice();

                        Double previousClose = history.get(index - 1)
                                        .getClosePrice();

                        if (currentClose == null
                                        || previousClose == null
                                        || currentClose >= previousClose) {
                                break;
                        }

                        count++;
                }

                return count;
        }

        private int consecutiveUpCloses(
                        List<StockPrice> history,
                        int lastIndex) {

                int count = 0;

                for (int index = lastIndex; index > 0; index--) {

                        Double currentClose = history.get(index)
                                        .getClosePrice();

                        Double previousClose = history.get(index - 1)
                                        .getClosePrice();

                        if (currentClose == null
                                        || previousClose == null
                                        || currentClose <= previousClose) {
                                break;
                        }

                        count++;
                }

                return count;
        }

        private boolean hasExactMinuteRange(
                        List<MarketCandle> candles,
                        LocalDateTime from,
                        LocalDateTime to,
                        int expectedCount) {

                if (candles == null
                                || candles.size() != expectedCount) {
                        return false;
                }

                for (int i = 0; i < expectedCount; i++) {

                        LocalDateTime expected = from.plusMinutes(i);

                        if (!expected.isBefore(
                                        to.plusMinutes(1))
                                        || candles.get(i)
                                                        .getCandleTime()
                                                        .equals(expected) == false) {
                                return false;
                        }
                }

                return candles.get(
                                candles.size() - 1)
                                .getCandleTime()
                                .equals(to);
        }

        private boolean isUsableOneMinuteCandle(
                        MarketCandle candle) {

                if (candle == null
                                || candle.getCandleTime() == null
                                || !Boolean.TRUE.equals(
                                                candle.getIsFinalized())) {
                        return false;
                }

                if (candle.getQualityStatus() == CandleQualityStatus.SUSPECT) {
                        return false;
                }

                return candle.getProcessingStatus() == null
                                || candle.getProcessingStatus() == CandleProcessingStatus.RELEASED;
        }

        private double highestHigh(
                        List<MarketCandle> candles) {

                return candles.stream()
                                .map(MarketCandle::getHighPrice)
                                .filter(Objects::nonNull)
                                .max(Double::compareTo)
                                .orElse(0.0);
        }

        private double lowestLow(
                        List<MarketCandle> candles) {

                return candles.stream()
                                .map(MarketCandle::getLowPrice)
                                .filter(Objects::nonNull)
                                .min(Double::compareTo)
                                .orElse(0.0);
        }

        private long totalVolume(
                        List<MarketCandle> candles) {

                return candles.stream()
                                .map(MarketCandle::getVolume)
                                .filter(Objects::nonNull)
                                .mapToLong(Long::longValue)
                                .sum();
        }
}
