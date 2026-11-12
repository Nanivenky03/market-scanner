package com.trading.scanner.strategy.engine;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.strategy.model.MarketState;
import com.trading.scanner.strategy.cache.StockContextCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Evaluates objective NIFTY candle close, VWAP, and TWAP facts across multiple timeframes.
 *
 * Rules:
 * - Pre-09:20 AM: dataValid = false (session opening reference formation).
 * - Post-01:00 PM: dataValid = false (intraday fresh entry cutoff reached).
 * - When valid:
 *   - Volume-Weighted / Time-Weighted Baseline:
 *     If VWAP is present (> 0), uses VWAP. For spot indices where volume is 0, falls back to
 *     Session TWAP: sum((High + Low + Close)/3) / N.
 *   - Regime Alignment:
 *     - Close > Baseline && Close >= Day Open -> regime = BULLISH, niftyAboveVwap = true
 *     - Close < Baseline && Close < Day Open  -> regime = BEARISH, niftyAboveVwap = false
 *     - Otherwise                             -> regime = NEUTRAL, niftyAboveVwap = (Close > Baseline)
 * - Multi-Timeframe Support:
 *   Evaluates 1M, 5M, and 15M baselines in memory, allowing strategies to subscribe to their
 *   preferred timeframe via marketState.forTimeframe(timeframe).
 */
@Service
@Slf4j
public class MasterMarketFilterService {

    public static final String NIFTY_SYMBOL = "NIFTY";
    public static final String NIFTY_EXCHANGE = "NSE";

    private static final LocalTime EVALUATION_START = LocalTime.of(9, 20);
    private static final LocalTime ENTRY_CUTOFF_TIME = LocalTime.of(13, 0);

    private final MarketCandleRepository marketCandleRepository;
    private final TimeProvider timeProvider;
    private final StockContextCache stockContextCache;

    @Autowired
    public MasterMarketFilterService(
            MarketCandleRepository marketCandleRepository,
            TimeProvider timeProvider,
            @Autowired(required = false) StockContextCache stockContextCache) {
        this.marketCandleRepository = marketCandleRepository;
        this.timeProvider = timeProvider;
        this.stockContextCache = stockContextCache;
    }

    public MasterMarketFilterService(
            MarketCandleRepository marketCandleRepository,
            TimeProvider timeProvider) {
        this(marketCandleRepository, timeProvider, null);
    }

    public MarketState evaluateCurrentState(LocalDate tradingDate) {
        return evaluateCurrentState(tradingDate, CandleTimeframe.FIVE_MINUTE);
    }

    public MarketState evaluateCurrentState(LocalDate tradingDate, CandleTimeframe primaryTimeframe) {
        LocalDateTime now = timeProvider.nowDateTime();
        LocalTime currentTime = now.toLocalTime();

        if (currentTime.isBefore(EVALUATION_START)) {
            return new MarketState(
                    tradingDate,
                    now,
                    null,
                    null,
                    null,
                    false,
                    MarketState.MarketRegime.UNKNOWN,
                    false,
                    "Pre-evaluation window before 09:20 AM"
            );
        }

        if (currentTime.isAfter(ENTRY_CUTOFF_TIME)) {
            return new MarketState(
                    tradingDate,
                    now,
                    null,
                    null,
                    null,
                    false,
                    MarketState.MarketRegime.UNKNOWN,
                    false,
                    "Session entry cutoff reached at 01:00 PM"
            );
        }

        CandleTimeframe targetPrimary = primaryTimeframe != null ? primaryTimeframe : CandleTimeframe.FIVE_MINUTE;

        MarketState primaryState = evaluateTimeframeState(tradingDate, now, targetPrimary);
        if (primaryState == null) {
            return new MarketState(
                    tradingDate,
                    now,
                    null,
                    null,
                    null,
                    false,
                    MarketState.MarketRegime.UNKNOWN,
                    false,
                    "No finalized NIFTY candles found for today"
            );
        }

        Map<CandleTimeframe, MarketState> timeframeStates = new EnumMap<>(CandleTimeframe.class);
        timeframeStates.put(targetPrimary, primaryState);

        if (stockContextCache != null) {
            for (CandleTimeframe tf : List.of(CandleTimeframe.ONE_MINUTE, CandleTimeframe.FIVE_MINUTE, CandleTimeframe.FIFTEEN_MINUTE)) {
                if (tf != targetPrimary) {
                    try {
                        MarketState subState = evaluateTimeframeState(tradingDate, now, tf);
                        if (subState != null) {
                            timeframeStates.put(tf, subState);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return new MarketState(
                primaryState.tradingDate(),
                primaryState.evaluatedAt(),
                primaryState.niftyClose(),
                primaryState.niftyVwap(),
                primaryState.vwapDistancePct(),
                primaryState.niftyAboveVwap(),
                primaryState.regime(),
                primaryState.dataValid(),
                primaryState.reason(),
                primaryState.dayOpen(),
                timeframeStates
        );
    }

    private MarketState evaluateTimeframeState(LocalDate tradingDate, LocalDateTime now, CandleTimeframe timeframe) {
        List<MarketCandle> candles = loadCandles(tradingDate, now, timeframe);
        String tfLabel = timeframe == CandleTimeframe.FIVE_MINUTE ? "5-minute"
                : (timeframe == CandleTimeframe.ONE_MINUTE ? "1-minute"
                : (timeframe == CandleTimeframe.FIFTEEN_MINUTE ? "15-minute" : timeframe.name()));

        if (candles == null || candles.isEmpty()) {
            return new MarketState(
                    tradingDate,
                    now,
                    null,
                    null,
                    null,
                    false,
                    MarketState.MarketRegime.UNKNOWN,
                    false,
                    String.format("No finalized %s NIFTY candles found for today", tfLabel)
            );
        }

        MarketCandle latest = candles.get(candles.size() - 1);
        int maxAllowedMinutes = switch (timeframe) {
            case ONE_MINUTE -> 5;
            case FIVE_MINUTE -> 10;
            case FIFTEEN_MINUTE -> 25;
            default -> 15;
        };

        if (latest.getCandleTime() == null || java.time.Duration.between(latest.getCandleTime(), now).toMinutes() > maxAllowedMinutes) {
            long minutesOld = latest.getCandleTime() != null
                    ? java.time.Duration.between(latest.getCandleTime(), now).toMinutes()
                    : -1;
            log.warn("[MACRO_DATA_STALE] Latest NIFTY {} candle is {} minutes old. Market state invalidated.", timeframe, minutesOld);
            return new MarketState(
                    tradingDate,
                    now,
                    latest.getClosePrice(),
                    latest.getVwap(),
                    null,
                    false,
                    MarketState.MarketRegime.UNKNOWN,
                    false,
                    String.format("NIFTY macro %s candle data is STALE (%d min old, max allowed %d min)",
                            timeframe, minutesOld, maxAllowedMinutes)
            );
        }

        Double close = latest.getClosePrice();
        Double vwap = latest.getVwap();
        Double dayOpen = candles.get(0).getOpenPrice();

        // Baseline: use VWAP if valid (>0), otherwise fall back to session TWAP
        Double baseline = (vwap != null && vwap > 0.0) ? vwap : calculateTwap(candles);

        if (close == null || baseline == null || baseline <= 0.0) {
            return new MarketState(
                    tradingDate,
                    now,
                    close,
                    baseline,
                    null,
                    false,
                    MarketState.MarketRegime.UNKNOWN,
                    false,
                    String.format("Invalid or uncalculated NIFTY %s baseline", timeframe)
            );
        }

        double distancePct = ((close - baseline) / baseline) * 100.0;
        boolean aboveBaseline = close > baseline;
        boolean aboveDayOpen = (dayOpen != null) ? close >= dayOpen : true;

        boolean bullish = aboveBaseline && aboveDayOpen;
        boolean bearish = !aboveBaseline && (dayOpen != null ? close < dayOpen : true);

        MarketState.MarketRegime regime = bullish
                ? MarketState.MarketRegime.BULLISH
                : (bearish ? MarketState.MarketRegime.BEARISH : MarketState.MarketRegime.NEUTRAL);

        String baselineType = (vwap != null && vwap > 0.0) ? "VWAP" : "TWAP";
        String reason = bullish
                ? String.format("NIFTY above %s (+%.2f%%, BULLISH, DayOpen: %.1f)", baselineType, distancePct, dayOpen != null ? dayOpen : 0.0)
                : (bearish
                    ? String.format("NIFTY below %s (%.2f%%, BEARISH, DayOpen: %.1f)", baselineType, distancePct, dayOpen != null ? dayOpen : 0.0)
                    : String.format("NIFTY Neutral (%.2f%% vs %s, DayOpen: %.1f)", distancePct, baselineType, dayOpen != null ? dayOpen : 0.0));

        return new MarketState(
                tradingDate,
                now,
                close,
                baseline,
                distancePct,
                aboveBaseline,
                regime,
                true,
                reason,
                dayOpen,
                Collections.emptyMap()
        );
    }

    private List<MarketCandle> loadCandles(LocalDate tradingDate, LocalDateTime now, CandleTimeframe timeframe) {
        if (stockContextCache != null) {
            List<MarketCandle> cached = stockContextCache.getCandles(NIFTY_SYMBOL, NIFTY_EXCHANGE, timeframe);
            if (cached != null && cached.isEmpty()) {
                cached = stockContextCache.getCandles("NIFTY 50", NIFTY_EXCHANGE, timeframe);
            }
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
            return Collections.emptyList();
        }

        LocalDateTime sessionStart = tradingDate.atTime(LocalTime.of(9, 15));
        return marketCandleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        NIFTY_SYMBOL,
                        NIFTY_EXCHANGE,
                        timeframe,
                        sessionStart,
                        now
                );
    }

    public static Double calculateTwap(List<MarketCandle> candles) {
        if (candles == null || candles.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        int count = 0;
        for (MarketCandle c : candles) {
            if (c != null && c.getHighPrice() != null && c.getLowPrice() != null && c.getClosePrice() != null) {
                sum += (c.getHighPrice() + c.getLowPrice() + c.getClosePrice()) / 3.0;
                count++;
            }
        }
        return count > 0 ? (sum / count) : null;
    }
}
