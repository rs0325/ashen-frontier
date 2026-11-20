package io.github.rs0325.ashenfrontier.core;

import io.github.rs0325.ashenfrontier.core.time.GameTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemPropertyMathTest {
    @Test void coolingAndDecayAreLazyCalculations() {
        assertEquals(300.0 + 100.0 / Math.E, ItemPropertyMath.cool(400, 300, 20, 20), 1e-9);
        assertEquals(50.0, ItemPropertyMath.activity(100, 20, 20), 1e-9);
        assertEquals(11.0, ItemPropertyMath.rotProgress(1, 10.0, 293.15, 293.15, 2), 1e-9);
    }

    @Test void elapsedTicksConvertToRealAndGameSeconds() {
        assertEquals(1.0, GameTime.realSeconds(20), 1e-9);
        assertEquals(86_400.0, GameTime.gameSeconds(24_000), 1e-9);
        assertEquals(43_200.0, GameTime.gameSeconds(24_000, 48_000), 1e-9);
    }

    @Test void componentValuesQuantizeTowardTheWorseSide() {
        assertEquals(500.0, ItemPropertyMath.roundDown(504.9, 5));
        assertEquals(0.2, ItemPropertyMath.roundUp(0.191, 0.01), 1e-9);
        assertEquals(0.19, ItemPropertyMath.roundDown(0.199, 0.01), 1e-9);
        assertEquals(600.0, ItemPropertyMath.roundUp(1, ItemProperties.ROT_STEP_SECONDS));
    }

    @Test void elapsedTimeBucketsRoundTowardTheOlderValue() {
        assertEquals(100, ItemPropertyMath.floorTime(119, 20));
        assertEquals(120, ItemPropertyMath.floorTime(120, 20));
        assertEquals(40, ItemPropertyMath.roundUp(21, 20), 1e-9);
    }
}
