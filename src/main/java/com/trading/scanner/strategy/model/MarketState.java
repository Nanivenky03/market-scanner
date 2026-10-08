package com.trading.scanner.strategy.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Captures objective macro market facts evaluated from NIFTY 5-minute candle closes and VWAP.
 *
 * Strategy-agnostic:
 * - Long strategies (e.g. FCHB) can check niftyAboveVwap == true (or regime == BULLISH).
 * - Short strategies can check niftyAboveVwap == false (or regime == BEARISH).
 * - Market-neutral strategies can inspect vwapDistancePct or trade regardless.
 */
public record MarketState(
        LocalDate tradingDate,
        LocalDateTime evaluatedAt,
        Double niftyClose,
        Double niftyVwap,
        Double vwapDistancePct,
        boolean niftyAboveVwap,
        MarketRegime regime,
        boolean dataValid,
        String reason
) {

    public enum MarketRegime {
        BULLISH,
        BEARISH,
        NEUTRAL,
        UNKNOWN
    }

    public boolean isBullish() {
        return dataValid && regime == MarketRegime.BULLISH;
    }

    public boolean isBearish() {
        return dataValid && regime == MarketRegime.BEARISH;
    }
}
