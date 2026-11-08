package com.trading.scanner.risk;

/**
 * Institutional statutory charges and broker fee calculator for Indian equity intraday trading.
 * Supports multiple broker fee structures (Angel One, Kotak Neo, Shoonya)
 * while maintaining universal statutory taxes (STT, Stamp Duty, NSE Exchange fee, SEBI fee, GST).
 */
public final class BrokerChargesCalculator {

    public enum BrokerModel {
        ANGEL_ONE,   // Standard Angel One: Rs. 20 flat / 0.1% per order leg
        KOTAK_NEO,   // Kotak Neo Trade API: Rs. 0 Brokerage on automated API orders
        SHOONYA      // Finvasia Shoonya: Rs. 0 Brokerage for life
    }

    private static volatile BrokerModel activeBroker = BrokerModel.ANGEL_ONE;

    public static void setActiveBroker(BrokerModel model) {
        activeBroker = (model != null) ? model : BrokerModel.ANGEL_ONE;
    }

    public static BrokerModel getActiveBroker() {
        return activeBroker;
    }

    private BrokerChargesCalculator() {
        // utility class
    }

    // Angel One Intraday Equity Brokerage: Lower of Rs. 20 or 0.1% per executed order (min Rs. 5.00)
    public static final double BROKERAGE_TURNOVER_PCT = 0.001; // 0.1%
    public static final double MIN_BROKERAGE_PER_ORDER = 5.0;  // Rs. 5.00
    public static final double MAX_BROKERAGE_PER_ORDER = 20.0; // Rs. 20.00

    // Statutory Taxes (Turnover-based - Universal across all Indian brokers)
    public static final double STT_SELL_RATE = 0.00025;            // 0.025% on SELL turnover only (Rs 0 on BUY)
    public static final double STAMP_DUTY_BUY_RATE = 0.00003;      // 0.003% on BUY turnover only (Rs 0 on SELL)
    public static final double EXCHANGE_TURNOVER_RATE = 0.0000307; // 0.00307% NSE Transaction Fee
    public static final double SEBI_TURNOVER_RATE = 0.000001;      // Rs 10 per Crore (0.0001%)
    public static final double GST_RATE = 0.18;                    // 18% on (Brokerage + Exchange + SEBI fees)

    public static double calculateBrokerage(double turnover) {
        return calculateBrokerage(turnover, activeBroker);
    }

    public static double calculateBrokerage(double turnover, BrokerModel broker) {
        if (turnover <= 0.0) {
            return 0.0;
        }
        if (broker == BrokerModel.KOTAK_NEO || broker == BrokerModel.SHOONYA) {
            return 0.0; // Rs. 0 brokerage on API orders
        }
        return Math.max(MIN_BROKERAGE_PER_ORDER, Math.min(MAX_BROKERAGE_PER_ORDER, turnover * BROKERAGE_TURNOVER_PCT));
    }

    public static double calculateBuyLegCharges(double turnover) {
        return calculateBuyLegCharges(turnover, activeBroker);
    }

    public static double calculateBuyLegCharges(double turnover, BrokerModel broker) {
        if (turnover <= 0.0) {
            return 0.0;
        }
        double brokerage = calculateBrokerage(turnover, broker);
        double stampDuty = turnover * STAMP_DUTY_BUY_RATE;
        double exchangeFee = turnover * EXCHANGE_TURNOVER_RATE;
        double sebiFee = turnover * SEBI_TURNOVER_RATE;
        double gst = (brokerage + exchangeFee + sebiFee) * GST_RATE;
        return brokerage + stampDuty + exchangeFee + sebiFee + gst;
    }

    public static double calculateSellLegCharges(double turnover) {
        return calculateSellLegCharges(turnover, activeBroker);
    }

    public static double calculateSellLegCharges(double turnover, BrokerModel broker) {
        if (turnover <= 0.0) {
            return 0.0;
        }
        double brokerage = calculateBrokerage(turnover, broker);
        double stt = turnover * STT_SELL_RATE;
        double exchangeFee = turnover * EXCHANGE_TURNOVER_RATE;
        double sebiFee = turnover * SEBI_TURNOVER_RATE;
        double gst = (brokerage + exchangeFee + sebiFee) * GST_RATE;
        return brokerage + stt + exchangeFee + sebiFee + gst;
    }

    public static double calculateRoundTripCharges(double buyTurnover, double sellTurnover) {
        return calculateRoundTripCharges(buyTurnover, sellTurnover, activeBroker);
    }

    public static double calculateRoundTripCharges(double buyTurnover, double sellTurnover, BrokerModel broker) {
        return calculateBuyLegCharges(buyTurnover, broker) + calculateSellLegCharges(sellTurnover, broker);
    }

    public static double estimateRoundTripCharges(int quantity, double entryPrice) {
        return estimateRoundTripCharges(quantity, entryPrice, activeBroker);
    }

    public static double estimateRoundTripCharges(int quantity, double entryPrice, BrokerModel broker) {
        double turnover = quantity * entryPrice;
        return calculateRoundTripCharges(turnover, turnover, broker);
    }

    public static double estimateExitCharges(int quantity, double estimatedExitPrice) {
        return estimateExitCharges(quantity, estimatedExitPrice, activeBroker);
    }

    public static double estimateExitCharges(int quantity, double estimatedExitPrice, BrokerModel broker) {
        return calculateSellLegCharges(quantity * estimatedExitPrice, broker);
    }

    /**
     * Calculates estimated 3-leg trade friction for a 2-tranche intraday strategy:
     * Leg 1: Entry order (Buy)
     * Leg 2: Partial Target 1 exit order (Sell)
     * Leg 3: Final Runner/Stop exit order (Sell)
     * Conservatively models worst-case broker order execution.
     */
    public static double calculate3LegEstimatedFriction(double tradeValue) {
        return calculate3LegEstimatedFriction(tradeValue, activeBroker);
    }

    public static double calculate3LegEstimatedFriction(double tradeValue, BrokerModel broker) {
        if (tradeValue <= 0.0) {
            return 0.0;
        }
        double roundTripTurnover = 2.0 * tradeValue;
        double brokerage3Legs = (broker == BrokerModel.KOTAK_NEO || broker == BrokerModel.SHOONYA)
                ? 0.0
                : (3.0 * MAX_BROKERAGE_PER_ORDER); // Rs. 20 * 3 = Rs. 60 flat on Angel One
        double stampDuty = tradeValue * STAMP_DUTY_BUY_RATE;
        double stt = tradeValue * STT_SELL_RATE;
        double exchangeFee = roundTripTurnover * EXCHANGE_TURNOVER_RATE;
        double sebiFee = roundTripTurnover * SEBI_TURNOVER_RATE;
        double gst = (brokerage3Legs + exchangeFee + sebiFee) * GST_RATE;
        return brokerage3Legs + stampDuty + stt + exchangeFee + sebiFee + gst;
    }

    public record ItemizedCharges(
            double turnover,
            double brokerage,
            double stt,
            double stampDuty,
            double exchangeFee,
            double sebiFee,
            double gst,
            double totalCharges
    ) {}

    public static ItemizedCharges calculateItemizedCharges(double turnover, boolean isBuy) {
        return calculateItemizedCharges(turnover, isBuy, activeBroker);
    }

    public static ItemizedCharges calculateItemizedCharges(double turnover, boolean isBuy, BrokerModel broker) {
        if (turnover <= 0.0) {
            return new ItemizedCharges(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        double brokerage = calculateBrokerage(turnover, broker);
        double stt = isBuy ? 0.0 : turnover * STT_SELL_RATE;
        double stampDuty = isBuy ? turnover * STAMP_DUTY_BUY_RATE : 0.0;
        double exchangeFee = turnover * EXCHANGE_TURNOVER_RATE;
        double sebiFee = turnover * SEBI_TURNOVER_RATE;
        double gst = (brokerage + exchangeFee + sebiFee) * GST_RATE;
        double total = brokerage + stt + stampDuty + exchangeFee + sebiFee + gst;
        return new ItemizedCharges(turnover, brokerage, stt, stampDuty, exchangeFee, sebiFee, gst, total);
    }
}
