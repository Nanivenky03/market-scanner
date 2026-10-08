package com.trading.scanner.strategy.engine;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.strategy.model.MarketState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Evaluates objective NIFTY 5-minute candle close and VWAP facts.
 *
 * Rules:
 * - Pre-09:20 AM: dataValid = false (session opening reference formation).
 * - Post-01:00 PM: dataValid = false (intraday fresh entry cutoff reached).
 * - When valid:
 *   - NIFTY 5M Close > NIFTY VWAP -> regime = BULLISH, niftyAboveVwap = true
 *   - NIFTY 5M Close < NIFTY VWAP -> regime = BEARISH, niftyAboveVwap = false
 *   - NIFTY 5M Close == NIFTY VWAP -> regime = NEUTRAL
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MasterMarketFilterService {

    public static final String NIFTY_SYMBOL = "NIFTY";
    public static final String NIFTY_EXCHANGE = "NSE";

    private static final LocalTime EVALUATION_START = LocalTime.of(9, 20);
    private static final LocalTime ENTRY_CUTOFF_TIME = LocalTime.of(13, 0);

    private final MarketCandleRepository marketCandleRepository;
    private final TimeProvider timeProvider;

    public MarketState evaluateCurrentState(LocalDate tradingDate) {
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

        LocalDateTime sessionStart = tradingDate.atTime(LocalTime.of(9, 15));
        List<MarketCandle> nifty5mCandles = marketCandleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        NIFTY_SYMBOL,
                        NIFTY_EXCHANGE,
                        CandleTimeframe.FIVE_MINUTE,
                        sessionStart,
                        now
                );

        if (nifty5mCandles.isEmpty()) {
            return new MarketState(
                    tradingDate,
                    now,
                    null,
                    null,
                    null,
                    false,
                    MarketState.MarketRegime.UNKNOWN,
                    false,
                    "No finalized 5-minute NIFTY candles found for today"
            );
        }

        MarketCandle latest5m = nifty5mCandles.get(nifty5mCandles.size() - 1);
        Double close = latest5m.getClosePrice();
        Double vwap = latest5m.getVwap();

        if (close == null || vwap == null || vwap <= 0.0) {
            return new MarketState(
                    tradingDate,
                    now,
                    close,
                    vwap,
                    null,
                    false,
                    MarketState.MarketRegime.UNKNOWN,
                    false,
                    "Invalid or uncalculated NIFTY 5M VWAP"
            );
        }

        double distancePct = ((close - vwap) / vwap) * 100.0;
        boolean aboveVwap = close > vwap;
        MarketState.MarketRegime regime = aboveVwap
                ? MarketState.MarketRegime.BULLISH
                : (close < vwap ? MarketState.MarketRegime.BEARISH : MarketState.MarketRegime.NEUTRAL);

        String reason = aboveVwap
                ? String.format("NIFTY above VWAP (+%.2f%%, BULLISH)", distancePct)
                : String.format("NIFTY below VWAP (%.2f%%, BEARISH)", distancePct);

        return new MarketState(
                tradingDate,
                now,
                close,
                vwap,
                distancePct,
                aboveVwap,
                regime,
                true,
                reason
        );
    }
}
