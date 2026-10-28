package com.trading.scanner.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BrokerChargesCalculatorTest {

    @Test
    @DisplayName("Brokerage clamps between minimum Rs. 5.0 and maximum Rs. 20.0 per executed order")
    void testCalculateBrokerage_Clamping() {
        // Zero or negative turnover -> 0.0
        assertThat(BrokerChargesCalculator.calculateBrokerage(0.0)).isEqualTo(0.0);
        assertThat(BrokerChargesCalculator.calculateBrokerage(-500.0)).isEqualTo(0.0);

        // Small turnover (e.g. 1,000 * 0.1% = Rs. 1.0) -> Clamped to min Rs. 5.0
        assertThat(BrokerChargesCalculator.calculateBrokerage(1000.0)).isEqualTo(5.0);

        // Middle turnover (e.g. 15,000 * 0.1% = Rs. 15.0) -> Rs. 15.0
        assertThat(BrokerChargesCalculator.calculateBrokerage(15000.0)).isEqualTo(15.0);

        // Large turnover (e.g. 50,000 * 0.1% = Rs. 50.0) -> Clamped to max Rs. 20.0
        assertThat(BrokerChargesCalculator.calculateBrokerage(50000.0)).isEqualTo(20.0);
    }

    @Test
    @DisplayName("Buy leg charges include brokerage, stamp duty, exchange fee, SEBI fee, GST, and zero STT")
    void testCalculateBuyLegCharges() {
        double buyTurnover = 20000.0;
        double charges = BrokerChargesCalculator.calculateBuyLegCharges(buyTurnover);

        // Expected breakdown:
        // Brokerage = 20000 * 0.001 = 20.0
        // Stamp duty = 20000 * 0.00003 = 0.60
        // Exchange fee = 20000 * 0.0000307 = 0.614
        // SEBI fee = 20000 * 0.000001 = 0.020
        // GST = (20.0 + 0.614 + 0.020) * 0.18 = 3.71412
        // Total = 20.0 + 0.60 + 0.614 + 0.020 + 3.71412 = 24.94812
        assertThat(charges).isCloseTo(24.94812, within(0.001));
    }

    @Test
    @DisplayName("Sell leg charges include brokerage, STT, exchange fee, SEBI fee, GST, and zero stamp duty")
    void testCalculateSellLegCharges() {
        double sellTurnover = 20000.0;
        double charges = BrokerChargesCalculator.calculateSellLegCharges(sellTurnover);

        // Expected breakdown:
        // Brokerage = 20.0
        // STT = 20000 * 0.00025 = 5.00
        // Exchange fee = 20000 * 0.0000307 = 0.614
        // SEBI fee = 20000 * 0.000001 = 0.020
        // GST = (20.0 + 0.614 + 0.020) * 0.18 = 3.71412
        // Total = 20.0 + 5.00 + 0.614 + 0.020 + 3.71412 = 29.34812
        assertThat(charges).isCloseTo(29.34812, within(0.001));
    }

    @Test
    @DisplayName("3-Leg estimated friction models full intraday entry and 2-tranche exit cycle")
    void testCalculate3LegEstimatedFriction() {
        double tradeValue = 22200.0;
        double friction = BrokerChargesCalculator.calculate3LegEstimatedFriction(tradeValue);

        // 3 legs * Rs. 20 = Rs. 60.0 brokerage
        // Round trip turnover = 44,400
        // Stamp duty (buy) = 22200 * 0.00003 = 0.666
        // STT (sell) = 22200 * 0.00025 = 5.55
        // NSE fee = 44400 * 0.0000307 = 1.36308
        // SEBI fee = 44400 * 0.000001 = 0.0444
        // GST = (60.0 + 1.36308 + 0.0444) * 0.18 = 11.05335
        // Expected total = 60 + 0.666 + 5.55 + 1.36308 + 0.0444 + 11.05335 = 78.67683
        assertThat(friction).isCloseTo(78.67683, within(0.01));
    }

    @Test
    @DisplayName("Exit charges estimator correctly estimates sell leg costs")
    void testEstimateExitCharges() {
        int qty = 10;
        double exitPrice = 1000.0;
        double exitCharges = BrokerChargesCalculator.estimateExitCharges(qty, exitPrice);

        assertThat(exitCharges).isGreaterThan(0.0);
        assertThat(exitCharges).isEqualTo(BrokerChargesCalculator.calculateSellLegCharges(10000.0));
    }
}
