package io.github.rs0325.ashenfrontier.core.recipe;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaterMassTest {
    @Test
    void savedValuesRoundToNearestMilligram() {
        assertEquals(25_000, WaterMass.fromKg(0.024999999984383778));
        assertEquals(0, WaterMass.fromKg(3.91e-12));
        assertEquals(1, WaterMass.fromKg(0.0000005));
    }

    @Test
    void definitionsRejectValuesOutsideTheGrid() {
        assertEquals(0.025, WaterMass.definitionKg(0.025));
        assertTrue(WaterMass.DEFINITION_KG.parse(JsonOps.INSTANCE, new JsonPrimitive(0.0250001)).error().isPresent());
        assertThrows(IllegalArgumentException.class, () -> WaterMass.definitionKg(0.0000001));
        assertThrows(IllegalArgumentException.class, () -> WaterMass.fromKg(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> WaterMass.fromKg(WaterMass.MAX_KG + 1));
    }

    @Test
    void maximumSupportedMassPreservesIndividualMilligramsThroughSaveValues() {
        long maximum = WaterMass.fromKg(WaterMass.MAX_KG);
        for (long units : new long[]{1, 25_000, 144_000_000, maximum - 1, maximum}) {
            assertEquals(units, WaterMass.fromKg(WaterMass.toKg(units)));
        }
    }
}
