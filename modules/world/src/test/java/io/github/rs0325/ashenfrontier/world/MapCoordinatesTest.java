package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapCoordinatesTest {
    @Test
    void latitudeAndInverseIncludeBoundaryLimits() {
        for (double z : new double[]{-16384, -8192, -0.5, 0, 0.5, 16383, Math.nextDown(16384.0), 16384}) {
            assertEquals(-80.0 * z / 16384, MapCoordinates.latitude(z));
            assertEquals(z, MapCoordinates.zFromLatitude(MapCoordinates.latitude(z)), 1e-10);
        }
        assertEquals(80, MapCoordinates.latitude(-16384));
        assertEquals(-80, MapCoordinates.latitude(16384));
        assertTrue(MapCoordinates.contains(-16384, 16383));
        assertFalse(MapCoordinates.contains(16384, 0));
        assertFalse(MapCoordinates.contains(0, -16385));
        assertEquals(0, MapCoordinates.elevation(63));
        assertEquals(-3175, MapCoordinates.elevation(-64));
        assertEquals(6400, MapCoordinates.elevation(319));
    }
}
