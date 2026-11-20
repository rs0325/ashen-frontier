package io.github.rs0325.ashenfrontier.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitsTest {
    @Test
    void formatsSiPrefixesAtBoundariesAndForNegativeValues() {
        assertEquals("1.00 kJ", Units.format(1_000, "J"));
        assertEquals("1.00 MJ", Units.format(1_000_000, "J"));
        assertEquals("1.00 GJ", Units.format(1_000_000_000, "J"));
        assertEquals("1.00 mJ", Units.format(0.001, "J"));
        assertEquals("1.00 µJ", Units.format(0.000001, "J"));
        assertEquals("-1.00 kJ", Units.format(-1_000, "J"));
    }

    @Test
    void convertsCelsiusFromKelvin() {
        assertEquals("0.00 °C", Units.temperature(273.15, false));
        assertEquals("273.15 K", Units.temperature(273.15, true));
    }

    @Test
    void formatsPressureInSelectedUnit() {
        assertEquals("1.00 bar", Units.pressure(100_000, false));
        assertEquals("100.00 kPa", Units.pressure(100_000, true));
    }
}
