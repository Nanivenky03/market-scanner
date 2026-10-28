package com.trading.scanner.risk;

/**
 * Institutional statutory charges and broker fee calculator for Indian equity intraday trading (Angel One / NSE).
 * Centralizes all statutory constants, tax schedules, and brokerage calculations.
 */
public final class BrokerChargesCalculator {

    private BrokerChargesCalculator() {
        // utility class
    }

    // Angel One Intraday Equity Brokerage: Lower of Rs. 20 or 0.1% per executed order (min Rs. 5.00)
    public static final double BROKERAGE_TURNOVER_PCT = 0.001; // 0.1%
    public static final double MIN_BROKERAGE_PER_ORDER = 5.0;  // Rs. 5.00
    public static final double MAX_BROKERAGE_PER_ORDER = 20.0; // Rs. 20.00

    // Statutory Taxes (Turnover-based)
    public static final double STT_SELL_RATE = 0.00025;            // 0.025% on SELL turnover only (Rs 0 on BUY)
    public static final double STAMP_DUTY_BUY_RATE = 0.00003;      // 0.003% on BUY turnover only (Rs 0 on SELL)
    public static final double EXCHANGE_TURNOVER_RATE = 0.0000307; // 0.00307% NSE Transaction Fee
    public static final double SEBI_TURNOVER_RATE = 0.000001;      // Rs 10 per Crore (0.0001%)
    public static final double GST_RATE = 0.18;                    // 18% on (Brokerage + Exchange + SEBI fees)

    public static double calculateBrokerage(double turnover) {
        if (turnover <= 0.0) {
            return 0.0;
        }
        return Math.max(MIN_BROKERAGE_PER_ORDER, Math.min(MAX_BROKERAGE_PER_ORDER, turnover * BROKERAGE_TURNOVER_PCT));
    }

    public static double calculateBuyLegCharges(double turnover) {
        if (turnover <= 0.0) {
            return 0.0;
        }
        double brokerage = calculateBrokerage(turnover);
        double stampDuty = turnover * STAMP_DUTY_BUY_RATE;
        double exchangeFee = turnover * EXCHANGE_TURNOVER_RATE;
        double sebiFee = turnover * SEBI_TURNOVER_RATE;
        double gst = (brokerage + exchangeFee + sebiFee) * GST_RATE;
        return brokerage + stampDuty + exchangeFee + sebiFee + gst;
    }

    public static double calculateSellLegCharges(double turnover) {
        if (turnover <= 0.0) {
            return 0.0;
        }
        double brokerage = calculateBrokerage(turnover);
        double stt = turnover * STT_SELL_RATE;
        double exchangeFee = turnover * EXCHANGE_TURNOVER_RATE;
        double sebiFee = turnover * SEBI_TURNOVER_RATE;
        double gst = (brokerage + exchangeFee + sebiFee) * GST_RATE;
        return brokerage + stt + exchangeFee + sebiFee + gst;
    }

    public static double calculateRoundTripCharges(double buyTurnover, double sellTurnover) {
        return calculateBuyLegCharges(buyTurnover) + calculateSellLegCharges(sellTurnover);
    }

    public static double estimateRoundTripCharges(int quantity, double entryPrice) {
        double turnover = quantity * entryPrice;
        return calculateRoundTripCharges(turnover, turnover);
    }

    public static double estimateExitCharges(int quantity, double estimatedExitPrice) {
        return calculateSellLegCharges(quantity * estimatedExitPrice);
    }

    /**
     * Calculates estimated 3-leg trade friction for a 2-tranche intraday strategy:
     * Leg 1: Entry order (Buy)
     * Leg 2: Partial Target 1 exit order (Sell)
     * Leg 3: Final Runner/Stop exit order (Sell)
     * Conservatively models worst-case broker order execution (Rs. 20 flat per order leg).
     */
    public static double calculate3LegEstimatedFriction(double tradeValue) {
        if (tradeValue <= 0.0) {
            return 0.0;
        }
        double roundTripTurnover = 2.0 * tradeValue;
        double brokerage3Legs = 3.0 * MAX_BROKERAGE_PER_ORDER; // Rs. 20 * 3 = Rs. 60 flat
        double stampDuty = tradeValue * STAMP_DUTY_BUY_RATE;
        double stt = tradeValue * STT_SELL_RATE;
        double exchangeFee = roundTripTurnover * EXCHANGE_TURNOVER_RATE;
        double sebiFee = roundTripTurnover * SEBI_TURNOVER_RATE;
        double gst = (brokerage3Legs + exchangeFee + sebiFee) * GST_RATE;
        return brokerage3Legs + stampDuty + stt + exchangeFee + sebiFee + gst;
    }
}
