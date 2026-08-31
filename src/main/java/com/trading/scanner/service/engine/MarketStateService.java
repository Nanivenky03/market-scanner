package com.trading.scanner.service.engine;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DayType;
import com.trading.scanner.model.ExpiryType;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MarketSession;
import com.trading.scanner.model.NiftyVwapDirection;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketStateService {

    private static final String NIFTY_SYMBOL = "NIFTY";
    private static final String NIFTY_EXCHANGE = "NSE";

    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

    private static final LocalTime ACTIVE_START = LocalTime.of(9, 30);

    private static final LocalTime ACTIVE_END = LocalTime.of(12, 30);

    private static final LocalTime FCHB_CUTOFF_START = LocalTime.of(13, 0);

    private static final LocalTime EXPIRY_WIND_DOWN_START = LocalTime.of(14, 15);

    private static final LocalTime WIND_DOWN_START = LocalTime.of(14, 45);

    private static final LocalTime HARD_CLOSE_TIME = LocalTime.of(15, 0);

    private static final LocalTime CLOSED_AFTER = LocalTime.of(15, 1);

    private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 29);

    private static final int OPENING_RANGE_MINUTES = 15;

    private static final double GAP_THRESHOLD_PERCENT = 0.75;
    private static final double HIGH_VOLATILITY_THRESHOLD_PERCENT = 0.8;
    private static final double VWAP_DIRECTION_THRESHOLD_PERCENT = 0.05;
    private static final double COMPARISON_EPSILON = 1.0e-9;

    private final MarketCandleRepository marketCandleRepository;
    private final StockPriceRepository stockPriceRepository;
    private final TimeProvider timeProvider;
    private final TradingCalendar tradingCalendar;

    private volatile MarketState lastState;

    private LocalDate latchDate;
    private DayType latchedDayType;
    private Boolean latchedHighVolatilityDay;
    private MarketSession latchedSession;
    private int lastSessionRank = -1;

    public void scheduledUpdate() {
        try {
            updateState();
        } catch (Exception ex) {
            log.warn(
                    "Market state update failed: {}",
                    ex.getMessage(),
                    ex);
        }
    }

    public MarketState currentState() {
        return lastState;
    }

    private synchronized void updateState() {
        LocalDateTime now = timeProvider.nowDateTime();

        if (now == null) {
            return;
        }

        LocalDate tradingDate = now.toLocalDate();

        LocalTime currentTime = now.toLocalTime();

        resetLatchesForNewDate(tradingDate);

        DayType dayType = resolveDayType(
                tradingDate,
                currentTime);

        ExpiryType expiryType = computeExpiryType(tradingDate);

        boolean highVolatilityDay = resolveHighVolatilityDay(
                tradingDate,
                currentTime);

        NiftyVwapSnapshot vwapSnapshot = computeNiftyVwapSnapshot(
                NIFTY_SYMBOL,
                NIFTY_EXCHANGE,
                tradingDate,
                now);

        MarketSession candidateSession = computeMarketSession(
                currentTime,
                expiryType);

        MarketSession effectiveSession = advanceMonotonicSession(
                currentTime,
                candidateSession);

        lastState = new MarketState(
                tradingDate,
                now,
                dayType,
                expiryType,
                highVolatilityDay,
                effectiveSession,
                vwapSnapshot.aboveVwap(),
                vwapSnapshot.distancePct(),
                vwapSnapshot.direction());
    }

    private void resetLatchesForNewDate(
            LocalDate tradingDate) {

        if (tradingDate.equals(latchDate)) {
            return;
        }

        latchDate = tradingDate;
        latchedDayType = null;
        latchedHighVolatilityDay = null;
        latchedSession = null;
        lastSessionRank = -1;
    }

    private DayType resolveDayType(
            LocalDate tradingDate,
            LocalTime currentTime) {

        if (latchedDayType != null) {
            return latchedDayType;
        }

        if (currentTime.isBefore(MARKET_OPEN)) {
            return DayType.NORMAL;
        }

        DayType calculated = computeDayType(
                tradingDate);

        if (calculated != null) {
            latchedDayType = calculated;
            return calculated;
        }

        return DayType.NORMAL;
    }

    private DayType computeDayType(
            LocalDate tradingDate) {

        LocalDate previousTradingDay = tradingCalendar.previousTradingDay(
                tradingDate);

        if (previousTradingDay == null) {
            return null;
        }

        Optional<StockPrice> previousPrice = stockPriceRepository.findBySymbolAndDate(
                NIFTY_SYMBOL,
                previousTradingDay);

        if (previousPrice.isEmpty()) {
            return null;
        }

        Double previousClose = previousPrice.get().getClosePrice();

        if (!isFinite(previousClose)
                || previousClose == 0.0) {
            return null;
        }

        List<MarketCandle> openingCandles = safeList(
                marketCandleRepository
                        .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                NIFTY_SYMBOL,
                                NIFTY_EXCHANGE,
                                CandleTimeframe.ONE_MINUTE,
                                tradingDate.atTime(
                                        MARKET_OPEN),
                                tradingDate.atTime(
                                        ACTIVE_START)));

        Optional<MarketCandle> firstCandle = openingCandles.stream()
                .filter(this::isUsable)
                .filter(candle -> candle.getCandleTime()
                        .equals(
                                tradingDate.atTime(
                                        MARKET_OPEN)))
                .findFirst();

        if (firstCandle.isEmpty()) {
            return null;
        }

        Double openPrice = firstCandle.get().getOpenPrice();

        if (!isFinite(openPrice)) {
            return null;
        }

        double gapPercent = (openPrice - previousClose)
                / previousClose
                * 100.0;

        if (gapPercent > GAP_THRESHOLD_PERCENT) {
            return DayType.GAP_UP;
        }

        if (gapPercent < -GAP_THRESHOLD_PERCENT) {
            return DayType.GAP_DOWN;
        }

        return DayType.NORMAL;
    }

    private ExpiryType computeExpiryType(
            LocalDate tradingDate) {

        if (tradingDate.getDayOfWeek() != DayOfWeek.THURSDAY) {
            return ExpiryType.NONE;
        }

        LocalDate lastThursday = lastThursdayOfMonth(
                tradingDate.getYear(),
                tradingDate.getMonthValue());

        return tradingDate.equals(lastThursday)
                ? ExpiryType.MONTHLY
                : ExpiryType.WEEKLY;
    }

    private LocalDate lastThursdayOfMonth(
            int year,
            int month) {

        LocalDate date = LocalDate.of(year, month, 1)
                .plusMonths(1)
                .minusDays(1);

        while (date.getDayOfWeek() != DayOfWeek.THURSDAY) {
            date = date.minusDays(1);
        }

        return date;
    }

    private boolean resolveHighVolatilityDay(
            LocalDate tradingDate,
            LocalTime currentTime) {

        if (latchedHighVolatilityDay != null) {
            return latchedHighVolatilityDay;
        }

        if (currentTime.isBefore(ACTIVE_START)) {
            return false;
        }

        Boolean calculated = computeHighVolatilityDayFlag(
                tradingDate);

        if (calculated != null) {
            latchedHighVolatilityDay = calculated;
            return calculated;
        }

        return false;
    }

    private Boolean computeHighVolatilityDayFlag(
            LocalDate tradingDate) {

        List<MarketCandle> openingRange = safeList(
                marketCandleRepository
                        .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                NIFTY_SYMBOL,
                                NIFTY_EXCHANGE,
                                CandleTimeframe.ONE_MINUTE,
                                tradingDate.atTime(
                                        MARKET_OPEN),
                                tradingDate.atTime(
                                        OPENING_RANGE_END)));

        if (!hasExactMinuteRange(
                openingRange,
                tradingDate.atTime(MARKET_OPEN),
                tradingDate.atTime(OPENING_RANGE_END))) {
            return null;
        }

        Optional<Double> high = openingRange.stream()
                .map(MarketCandle::getHighPrice)
                .filter(this::isFinite)
                .max(Double::compareTo);

        Optional<Double> low = openingRange.stream()
                .map(MarketCandle::getLowPrice)
                .filter(this::isFinite)
                .min(Double::compareTo);

        if (high.isEmpty()
                || low.isEmpty()
                || low.get() <= 0.0
                || high.get() <= low.get()) {
            return false;
        }

        double openingRangePercent = (high.get() - low.get())
                / low.get()
                * 100.0;

        return openingRangePercent > HIGH_VOLATILITY_THRESHOLD_PERCENT;
    }

    private NiftyVwapSnapshot computeNiftyVwapSnapshot(
            String symbol,
            String exchange,
            LocalDate tradingDate,
            LocalDateTime now) {

        List<MarketCandle> recent = safeList(
                marketCandleRepository
                        .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                                symbol,
                                exchange,
                                CandleTimeframe.ONE_MINUTE,
                                now))
                .stream()
                .filter(this::isUsable)
                .filter(candle -> candle.getCandleTime()
                        .toLocalDate()
                        .equals(tradingDate))
                .filter(candle -> isFinite(candle.getVwap()))
                .sorted(Comparator.comparing(
                        MarketCandle::getCandleTime))
                .toList();

        if (recent.isEmpty()) {
            return unknownVwap();
        }

        MarketCandle current = recent.get(recent.size() - 1);

        Double currentVwap = current.getVwap();

        Double currentClose = current.getClosePrice();

        if (!isFinite(currentVwap)
                || currentVwap == 0.0
                || !isFinite(currentClose)) {
            return unknownVwap();
        }

        boolean aboveVwap = currentClose > currentVwap;

        double distancePercent = (currentClose - currentVwap)
                / currentVwap
                * 100.0;

        if (recent.size() < 4) {
            return new NiftyVwapSnapshot(
                    aboveVwap,
                    distancePercent,
                    NiftyVwapDirection.UNKNOWN);
        }

        LocalDateTime threeMinutesEarlier = current.getCandleTime()
                .minusMinutes(3);

        Optional<MarketCandle> previous = recent.stream()
                .filter(candle -> candle.getCandleTime()
                        .equals(
                                threeMinutesEarlier))
                .findFirst();

        if (previous.isEmpty()
                || !isFinite(
                        previous.get().getVwap())
                || previous.get().getVwap() == 0.0) {
            return new NiftyVwapSnapshot(
                    aboveVwap,
                    distancePercent,
                    NiftyVwapDirection.UNKNOWN);
        }

        double vwapChangePercent = (currentVwap
                - previous.get().getVwap())
                / currentVwap
                * 100.0;

        NiftyVwapDirection direction;

        if (vwapChangePercent > VWAP_DIRECTION_THRESHOLD_PERCENT
                + COMPARISON_EPSILON) {
            direction = NiftyVwapDirection.RISING;
        } else if (vwapChangePercent < -VWAP_DIRECTION_THRESHOLD_PERCENT
                - COMPARISON_EPSILON) {
            direction = NiftyVwapDirection.FALLING;
        } else {
            direction = NiftyVwapDirection.FLAT;
        }

        return new NiftyVwapSnapshot(
                aboveVwap,
                distancePercent,
                direction);
    }

    private NiftyVwapSnapshot unknownVwap() {
        return new NiftyVwapSnapshot(
                false,
                null,
                NiftyVwapDirection.UNKNOWN);
    }

    private MarketSession advanceMonotonicSession(
            LocalTime currentTime,
            MarketSession candidate) {

        int candidateRank = sessionRank(
                currentTime,
                candidate);

        if (latchedSession == null
                || candidateRank > lastSessionRank) {
            latchedSession = candidate;
            lastSessionRank = candidateRank;
        }

        return latchedSession;
    }

    private int sessionRank(
            LocalTime currentTime,
            MarketSession session) {

        if (session == MarketSession.CLOSED) {
            return currentTime.isBefore(MARKET_OPEN)
                    ? 0
                    : 7;
        }

        return switch (session) {
            case OPENING_RANGE -> 1;
            case ACTIVE -> 2;
            case ORB_CUTOFF -> 3;
            case FCHB_CUTOFF -> 4;
            case WIND_DOWN, EXPIRY_WIND_DOWN -> 5;
            case HARD_CLOSE -> 6;
            case CLOSED -> 7;
        };
    }

    private MarketSession computeMarketSession(
            LocalTime currentTime,
            ExpiryType expiryType) {

        if (currentTime.isBefore(MARKET_OPEN)) {
            return MarketSession.CLOSED;
        }

        if (currentTime.isBefore(ACTIVE_START)) {
            return MarketSession.OPENING_RANGE;
        }

        if (currentTime.isBefore(ACTIVE_END)) {
            return MarketSession.ACTIVE;
        }

        if (currentTime.isBefore(FCHB_CUTOFF_START)) {
            return MarketSession.ORB_CUTOFF;
        }

        LocalTime windDownStart = expiryType == ExpiryType.NONE
                ? WIND_DOWN_START
                : EXPIRY_WIND_DOWN_START;

        if (currentTime.isBefore(windDownStart)) {
            return MarketSession.FCHB_CUTOFF;
        }

        if (currentTime.isBefore(HARD_CLOSE_TIME)) {
            return expiryType == ExpiryType.NONE
                    ? MarketSession.WIND_DOWN
                    : MarketSession.EXPIRY_WIND_DOWN;
        }

        if (currentTime.isBefore(CLOSED_AFTER)) {
            return MarketSession.HARD_CLOSE;
        }

        return MarketSession.CLOSED;
    }

    private boolean hasExactMinuteRange(
            List<MarketCandle> candles,
            LocalDateTime from,
            LocalDateTime to) {

        if (candles == null
                || candles.size() != OPENING_RANGE_MINUTES) {
            return false;
        }

        for (int i = 0; i < OPENING_RANGE_MINUTES; i++) {

            MarketCandle candle = candles.get(i);

            if (candle == null
                    || candle.getCandleTime() == null
                    || !candle.getCandleTime().equals(
                            from.plusMinutes(i))) {
                return false;
            }
        }

        return candles.get(
                candles.size() - 1)
                .getCandleTime()
                .equals(to);
    }

    private boolean isUsable(
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

    private boolean isFinite(Double value) {
        return value != null
                && !value.isNaN()
                && !value.isInfinite();
    }

    private <T> List<T> safeList(
            List<T> values) {

        return values == null
                ? List.of()
                : values;
    }

    public record MarketState(
            LocalDate tradingDate,
            LocalDateTime lastUpdatedAt,
            DayType dayType,
            ExpiryType expiryType,
            boolean highVolatilityDay,
            MarketSession marketSession,
            boolean niftyAboveVwap,
            Double niftyVwapDistancePct,
            NiftyVwapDirection niftyVwapDirection) {
    }

    private record NiftyVwapSnapshot(
            boolean aboveVwap,
            Double distancePct,
            NiftyVwapDirection direction) {
    }
}
