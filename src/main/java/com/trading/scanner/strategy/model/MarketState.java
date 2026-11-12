package com.trading.scanner.strategy.model;

import com.trading.scanner.model.CandleTimeframe;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

/**
 * Captures objective macro market facts evaluated from NIFTY candle closes and VWAP/TWAP.
 *
 * Strategy-agnostic:
 * - Long strategies (e.g. FCHB) can check niftyAboveVwap == true (or regime == BULLISH).
 * - Short strategies can check niftyAboveVwap == false (or regime == BEARISH).
 * - Multi-timeframe strategies can inspect state for 1M, 5M, 15M via forTimeframe(tf).
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
        String reason,
        Double dayOpen,
        Map<CandleTimeframe, MarketState> timeframeStates
) {

    public enum MarketRegime {
        BULLISH,
        BEARISH,
        NEUTRAL,
        UNKNOWN
    }

    // 100% backward-compatible constructor for existing 9-parameter call sites
    public MarketState(
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
        this(tradingDate, evaluatedAt, niftyClose, niftyVwap, vwapDistancePct, niftyAboveVwap, regime, dataValid, reason, null, Collections.emptyMap());
    }

    public MarketState forTimeframe(CandleTimeframe timeframe) {
        if (timeframeStates != null && timeframeStates.containsKey(timeframe)) {
            return timeframeStates.get(timeframe);
        }
        return this;
    }

    public boolean isBullish() {
        return dataValid && regime == MarketRegime.BULLISH;
    }

    public boolean isBearish() {
        return dataValid && regime == MarketRegime.BEARISH;
    }
}
