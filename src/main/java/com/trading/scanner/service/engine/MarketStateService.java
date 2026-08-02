package com.trading.scanner.service.engine;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleDirection;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketStateService {

    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime OPENING_RANGE_END = LocalTime.of(9, 29);
    private static final LocalTime ACTIVE_START = LocalTime.of(9, 30);
    private static final LocalTime ACTIVE_END = LocalTime.of(12, 30);
    private static final LocalTime ORB_CUTOFF_START = LocalTime.of(12, 30);
    private static final LocalTime FCHB_CUTOFF_START = LocalTime.of(13, 0);
    private static final LocalTime EXPIRY_WIND_DOWN_START = LocalTime.of(14, 15);
    private static final LocalTime WIND_DOWN_START = LocalTime.of(14, 45);
    private static final LocalTime HARD_CLOSE_TIME = LocalTime.of(15, 0);

    private static final int OPENING_RANGE_MINUTES = 15; // 9:15–9:29 inclusive

    private final MarketCandleRepository marketCandleRepository;
    private final StockPriceRepository stockPriceRepository;
    private final TimeProvider timeProvider;

    private volatile MarketState lastState;

    public void scheduledUpdate() {
        try {
            updateState();
        } catch (Exception ex) {
            log.warn("Market state update failed: {}", ex.getMessage(), ex);
        }
    }

    public MarketState currentState() {
        return lastState;
    }

    private void updateState() {
        LocalDateTime now = timeProvider.nowDateTime();
        LocalDate tradingDate = now.toLocalDate();
        LocalTime currentTime = now.toLocalTime();

        String niftySymbol = "NIFTY"; // can be made configurable later
        String niftyExchange = "NSE";

        DayType dayType = computeDayType(niftySymbol, tradingDate);
        ExpiryType expiryType = computeExpiryType(tradingDate);
        boolean highVolatilityDay = computeHighVolatilityDayFlag(niftySymbol, niftyExchange, tradingDate, currentTime);

        NiftyVwapSnapshot vwapSnapshot = computeNiftyVwapSnapshot(niftySymbol, niftyExchange, tradingDate, now);
        NiftyVwapDirection vwapDirection = vwapSnapshot.direction();
        boolean niftyAboveVwap = vwapSnapshot.aboveVwap();
        Double niftyVwapDistancePct = vwapSnapshot.distancePct();

        MarketSession marketSession = computeMarketSession(currentTime, expiryType);

        MarketState state = new MarketState(
                tradingDate,
                now,
                dayType,
                expiryType,
                highVolatilityDay,
                marketSession,
                niftyAboveVwap,
                niftyVwapDistancePct,
                vwapDirection);

        lastState = state;
    }

    private DayType computeDayType(String symbol, LocalDate tradingDate) {
        LocalDate prevDate = tradingDate.minusDays(1);

        Optional<StockPrice> prevOpt = stockPriceRepository.findBySymbolAndDate(symbol, prevDate);
        if (prevOpt.isEmpty()) {
            return DayType.NORMAL;
        }

        Double prevClose = prevOpt.get().getClosePrice();
        if (prevClose == null || prevClose == 0.0) {
            return DayType.NORMAL;
        }

        List<MarketCandle> firstCandles = marketCandleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        symbol,
                        "NSE",
                        CandleTimeframe.ONE_MINUTE,
                        tradingDate.atTime(MARKET_OPEN),
                        tradingDate.atTime(MARKET_OPEN.plusMinutes(1)));

        if (firstCandles.isEmpty()) {
            return DayType.NORMAL;
        }

        Double openPrice = firstCandles.get(0).getOpenPrice();
        if (openPrice == null) {
            return DayType.NORMAL;
        }

        double gapPct = (openPrice - prevClose) / prevClose * 100.0;

        if (gapPct > 0.75) {
            return DayType.GAP_UP;
        }

        if (gapPct < -0.75) {
            return DayType.GAP_DOWN;
        }

        return DayType.NORMAL;
    }

    private ExpiryType computeExpiryType(LocalDate tradingDate) {
        if (tradingDate.getDayOfWeek() != DayOfWeek.THURSDAY) {
            return ExpiryType.NONE;
        }

        LocalDate lastThursday = lastThursdayOfMonth(tradingDate.getYear(), tradingDate.getMonthValue());
        if (tradingDate.equals(lastThursday)) {
            return ExpiryType.MONTHLY;
        }

        return ExpiryType.WEEKLY;
    }

    private LocalDate lastThursdayOfMonth(int year, int month) {
        LocalDate date = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        while (date.getDayOfWeek() != DayOfWeek.THURSDAY) {
            date = date.minusDays(1);
        }
        return date;
    }

    private boolean computeHighVolatilityDayFlag(String symbol, String exchange, LocalDate tradingDate,
            LocalTime currentTime) {
        if (currentTime.isBefore(OPENING_RANGE_END.plusMinutes(1))) {
            return false;
        }

        List<MarketCandle> openingRangeCandles = marketCandleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        symbol,
                        exchange,
                        CandleTimeframe.ONE_MINUTE,
                        tradingDate.atTime(MARKET_OPEN),
                        tradingDate.atTime(OPENING_RANGE_END));

        if (openingRangeCandles.size() < OPENING_RANGE_MINUTES) {
            return false;
        }

        double high = openingRangeCandles.stream()
                .map(MarketCandle::getHighPrice)
                .filter(h -> h != null)
                .max(Double::compareTo)
                .orElse(0.0);

        double low = openingRangeCandles.stream()
                .map(MarketCandle::getLowPrice)
                .filter(l -> l != null)
                .min(Double::compareTo)
                .orElse(0.0);

        if (low <= 0.0 || high <= low) {
            return false;
        }

        double orSizePct = (high - low) / low * 100.0;

        return orSizePct > 0.8;
    }

    private NiftyVwapSnapshot computeNiftyVwapSnapshot(String symbol, String exchange, LocalDate tradingDate,
            LocalDateTime now) {
        List<MarketCandle> recentDesc = marketCandleRepository
                .findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                        symbol,
                        exchange,
                        CandleTimeframe.ONE_MINUTE,
                        now);

        List<MarketCandle> recent = recentDesc.stream()
                .filter(c -> c.getVwap() != null)
                .sorted(Comparator.comparing(MarketCandle::getCandleTime))
                .toList();

        if (recent.isEmpty()) {
            return new NiftyVwapSnapshot(false, null, NiftyVwapDirection.UNKNOWN);
        }

        MarketCandle current = recent.get(recent.size() - 1);
        Double currentVwap = current.getVwap();
        Double currentClose = current.getClosePrice();

        if (currentVwap == null || currentClose == null || currentVwap == 0.0) {
            return new NiftyVwapSnapshot(false, null, NiftyVwapDirection.UNKNOWN);
        }

        boolean aboveVwap = currentClose > currentVwap;
        double distancePct = (currentClose - currentVwap) / currentVwap * 100.0;

        if (recent.size() < 4) {
            return new NiftyVwapSnapshot(aboveVwap, distancePct, NiftyVwapDirection.UNKNOWN);
        }

        MarketCandle threeAgo = recent.get(recent.size() - 4);
        Double vwapThreeAgo = threeAgo.getVwap();
        if (vwapThreeAgo == null) {
            return new NiftyVwapSnapshot(aboveVwap, distancePct, NiftyVwapDirection.UNKNOWN);
        }

        double diff = currentVwap - vwapThreeAgo;
        double pct = diff / currentVwap * 100.0;

        NiftyVwapDirection direction;
        if (pct > 0.05) {
            direction = NiftyVwapDirection.RISING;
        } else if (pct < -0.05) {
            direction = NiftyVwapDirection.FALLING;
        } else {
            direction = NiftyVwapDirection.FLAT;
        }

        return new NiftyVwapSnapshot(aboveVwap, distancePct, direction);
    }

    private MarketSession computeMarketSession(LocalTime now, ExpiryType expiryType) {
        if (now.isBefore(MARKET_OPEN) || now.isAfter(HARD_CLOSE_TIME)) {
            return MarketSession.CLOSED;
        }

        if (!now.isBefore(MARKET_OPEN) && now.isBefore(OPENING_RANGE_END.plusMinutes(1))) {
            return MarketSession.OPENING_RANGE;
        }

        if (!now.isBefore(ACTIVE_START) && now.isBefore(ACTIVE_END)) {
            return MarketSession.ACTIVE;
        }

        if (!now.isBefore(ORB_CUTOFF_START) && now.isBefore(FCHB_CUTOFF_START)) {
            return MarketSession.ORB_CUTOFF;
        }

        if (!now.isBefore(FCHB_CUTOFF_START) && now.isBefore(expiryWindDownStart(expiryType))) {
            return MarketSession.FCHB_CUTOFF;
        }

        LocalTime expiryWindDownStart = expiryWindDownStart(expiryType);
        LocalTime windDownStart = windDownStart(expiryType);

        if (!now.isBefore(expiryWindDownStart) && now.isBefore(HARD_CLOSE_TIME)) {
            return expiryType == ExpiryType.NONE ? MarketSession.WIND_DOWN : MarketSession.EXPIRY_WIND_DOWN;
        }

        if (now.equals(HARD_CLOSE_TIME)) {
            return MarketSession.HARD_CLOSE;
        }

        return MarketSession.CLOSED;
    }

    private LocalTime expiryWindDownStart(ExpiryType expiryType) {
        return expiryType == ExpiryType.NONE ? WIND_DOWN_START : EXPIRY_WIND_DOWN_START;
    }

    private LocalTime windDownStart(ExpiryType expiryType) {
        return expiryType == ExpiryType.NONE ? WIND_DOWN_START : EXPIRY_WIND_DOWN_START;
    }

    private record NiftyVwapSnapshot(
            boolean aboveVwap,
            Double distancePct,
            NiftyVwapDirection direction) {
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

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
