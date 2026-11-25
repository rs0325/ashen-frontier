package io.github.rs0325.ashenfrontier.primitive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HearthHeatTest {
    @Test
    void fuelEnergyAndMassReachExactZeroAndNeverProduceFreeHeat() {
        var heat = new HearthHeat();
        heat.addFuel(1, 16_000_000);
        var step = heat.advance(200, (200) * 72, 293.15, true, true);
        assertEquals(16_000_000, step.chemicalJoules());
        assertEquals(1, step.burnedKg());
        assertEquals(0, heat.fuelJoules());
        assertEquals(0, heat.remainingKg());
        assertTrue(step.storedJoules() + step.processJoules() <= step.chemicalJoules());
        var cooling = heat.advance(30, (30) * 72, 293.15, true, true);
        assertEquals(0, cooling.chemicalJoules());
        assertEquals(0, cooling.processJoules());
        assertTrue(cooling.storedJoules() < 0);
    }

    @Test
    void intervalPartitionAndStoredFuelPreserveTheSamePhysicalState() {
        var whole = new HearthHeat();
        var split = new HearthHeat();
        whole.addFuel(2, 16_000_000);
        split.addFuel(2, 16_000_000);
        var all = whole.advance(400, (400) * 72, 293.15, true, false);
        double burned = 0;
        double retained = 0;
        for (int i = 0; i < 400; i++) {
            var part = split.advance(1, (1) * 72, 293.15, true, false);
            burned += part.burnedKg();
            retained += part.storedJoules();
        }
        assertEquals(all.burnedKg(), burned, 1e-10);
        assertEquals(all.storedJoules(), retained, 1e-7);
        assertEquals(whole.temperatureK(), split.temperatureK(), 1e-8);
        assertEquals(0, split.fuelJoules());
        var restored = new HearthHeat();
        restored.restore(whole.fuelJoules(), whole.heatingValueJKg(), whole.temperatureK());
        assertEquals(whole.advance(10, (10) * 72, 280, false, false), restored.advance(10, (10) * 72, 280, false, false));
    }

    @Test
    void passiveCoolingUsesGameSecondsWithoutBurningOrProcessing() {
        var heat = new HearthHeat();
        heat.restore(16000000, 16000000, 1000);
        var cooled = heat.advance(0, 7200, 293.15, false, false);
        double expected = 293.15 + (1000 - 293.15) * Math.exp(-1);
        assertEquals(expected, heat.temperatureK(), 1e-10);
        assertEquals(16000000, heat.fuelJoules());
        assertEquals(0, cooled.chemicalJoules());
        assertEquals(0, cooled.processSeconds());
        assertEquals(500 * (expected - 1000), cooled.storedJoules(), 1e-7);
        assertEquals(0, heat.advance(0, 0, 293.15, false, false).storedJoules());
    }

    @Test
    void invalidPhysicalInputsAreRejected() {
        var heat = new HearthHeat();
        assertThrows(IllegalArgumentException.class, () -> heat.addFuel(0, 16_000_000));
        assertThrows(IllegalArgumentException.class, () -> heat.addFuel(1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> heat.advance(-1, (-1) * 72, 293, true, false));
        assertThrows(IllegalArgumentException.class, () -> heat.advance(1, (1) * 72, 0, true, false));
        assertThrows(IllegalArgumentException.class, () -> heat.advance(1, -1, 293, true, false));
        assertThrows(IllegalArgumentException.class, () -> heat.restore(-1, 16_000_000, 293));
    }
}
