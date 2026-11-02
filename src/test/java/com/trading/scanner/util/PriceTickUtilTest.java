package com.trading.scanner.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriceTickUtilTest {

    @Test
    @DisplayName("Rounds to nearest 0.05 tick size")
    void testRoundToTick() {
        assertThat(PriceTickUtil.roundToTick(100.02, 0.05)).isEqualTo(100.00);
        assertThat(PriceTickUtil.roundToTick(100.03, 0.05)).isEqualTo(100.05);
        assertThat(PriceTickUtil.roundToTick(100.07, 0.05)).isEqualTo(100.05);
        assertThat(PriceTickUtil.roundToTick(100.08, 0.05)).isEqualTo(100.10);
    }

    @Test
    @DisplayName("Floors and ceils to tick correctly")
    void testFloorAndCeil() {
        assertThat(PriceTickUtil.floorToTick(100.04, 0.05)).isEqualTo(100.00);
        assertThat(PriceTickUtil.floorToTick(100.09, 0.05)).isEqualTo(100.05);

        assertThat(PriceTickUtil.ceilToTick(100.01, 0.05)).isEqualTo(100.05);
        assertThat(PriceTickUtil.ceilToTick(100.06, 0.05)).isEqualTo(100.10);
    }

    @Test
    @DisplayName("Directional Stop Loss: Long floors, Short ceils")
    void testRoundStopLoss() {
        // Long SL at 1477.53 -> floors to 1477.50
        assertThat(PriceTickUtil.roundStopLoss(1477.53, false, 0.05)).isEqualTo(1477.50);
        // Short SL at 1477.51 -> ceils to 1477.55
        assertThat(PriceTickUtil.roundStopLoss(1477.51, true, 0.05)).isEqualTo(1477.55);
    }

    @Test
    @DisplayName("Directional Target: Long ceils, Short floors")
    void testRoundTarget() {
        // Long Target at 1528.52 -> ceils to 1528.55
        assertThat(PriceTickUtil.roundTarget(1528.52, false, 0.05)).isEqualTo(1528.55);
        // Short Target at 1450.48 -> floors to 1450.45
        assertThat(PriceTickUtil.roundTarget(1450.48, true, 0.05)).isEqualTo(1450.45);
    }

    @Test
    @DisplayName("Fallback to default 0.05 tick size when tick size is <= 0")
    void testDefaultTickSizeFallback() {
        assertThat(PriceTickUtil.roundToTick(500.03, 0.0)).isEqualTo(500.05);
        assertThat(PriceTickUtil.roundToTick(500.03, -1.0)).isEqualTo(500.05);
    }
}
