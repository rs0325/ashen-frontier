package io.github.rs0325.ashenfrontier.primitive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoilingHeatTest {
    @Test
    void pressureChangesBoilingPointAndVirtualElevationChangesHoldingTime() {
        assertEquals(373.15, BoilingHeat.boilingK(101325), 1e-9);
        assertTrue(BoilingHeat.boilingK(79500) < 367);
        assertTrue(BoilingHeat.boilingK(79500) > 365);
        assertEquals(60, BoilingHeat.holdSeconds(2000));
        assertEquals(180, BoilingHeat.holdSeconds(2000.1));
    }

    @Test
    void warmingConsumesHeatBeforeTheBoilingClockCanAdvance() {
        var water = new BoilingHeat();
        double warming = BoilingHeat.CAPACITY_J_K * 80;
        var cold = water.advance(10, 0, 293.15, 101325, warming / 2);
        assertEquals(333.15, cold.temperatureK(), 1e-9);
        assertEquals(0, cold.hotSeconds());
        assertEquals(0, cold.vaporJoules());
        var hot = water.advance(20, 0, 293.15, 101325, warming);
        assertEquals(373.15, hot.temperatureK(), 1e-9);
        assertEquals(10, hot.hotSeconds(), 1e-9);
        assertEquals(BoilingHeat.EVAPORATION_J, hot.vaporJoules(), 1e-6);
        assertEquals(warming / 2 + BoilingHeat.EVAPORATION_J, hot.absorbedJoules());
        assertEquals(451400, BoilingHeat.EVAPORATION_J);
    }

    @Test
    void noHeatOrFrozenWaterCannotProduceBoilingTimeAndSavedWaterCools() {
        var water = new BoilingHeat();
        assertEquals(0, water.advance(60, 4320, 293.15, 101325, 0).hotSeconds());
        water.restore(373.15, 0, 0);
        water.cool(BoilingHeat.CAPACITY_J_K, 293.15);
        assertEquals(293.15 + 80 / Math.E, water.temperatureK(), 1e-9);
        var loaded = new BoilingHeat();
        loaded.restore(water.temperatureK(), 0, 0);
        assertEquals(0, loaded.advance(60, 4320, 293.15, 101325, 0).hotSeconds());
        var frozen = new BoilingHeat();
        assertEquals(0, frozen.advance(60, 4320, 260, 101325, 2_000_000).hotSeconds());
    }
    @Test
    void simultaneousHeatingAndCoolingDoNotChangeTheHoldTimeWithTheEvaluationInterval() {
        var whole = new BoilingHeat();
        var expected = whole.advance(200, 14400, 293.15, 101325, 3_600_000);
        var split = new BoilingHeat();
        double seconds = 0;
        double vaporJ = 0;
        double retainedJ = 0;
        for (int i = 0; i < 200; i++) {
            var part = split.advance(1, 72, 293.15, 101325, 18000);
            seconds += part.hotSeconds(); vaporJ += part.vaporJoules(); retainedJ += part.absorbedJoules();
        }
        assertEquals(expected.hotSeconds(), seconds, 1e-8);
        assertEquals(expected.vaporJoules(), vaporJ, 1e-5);
        assertEquals(expected.absorbedJoules(), retainedJ, 1e-5);
        assertTrue(retainedJ <= 3_600_000);
    }

    @Test
    void latentRetentionStopsAtTheFixedEvaporationBudget() {
        var water = new BoilingHeat();
        water.restore(373.15, 0, 0);
        var step = water.advance(60, 4320, 293.15, 101325, 1_080_000);
        assertEquals(60, step.hotSeconds(), 1e-9);
        assertEquals(451400, step.vaporJoules(), 1e-6);
        assertEquals(451400, step.absorbedJoules(), 1e-6);
        assertEquals(628600, 1_080_000 - step.absorbedJoules(), 1e-6);
    }
}
