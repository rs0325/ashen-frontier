package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainMathTest {
    @Test
    void quartPositionsResolveToExpectedPixels() {
        assertEquals(0, TerrainMath.pixel(-4096 * 4));
        assertEquals(1023, TerrainMath.pixel(-1 * 4));
        assertEquals(1024, TerrainMath.pixel(0 * 4));
        assertEquals(2047, TerrainMath.pixel(4095 * 4));
        assertFalse(MapCoordinates.contains(4096 * 4, 0));
    }

    @Test
    void northwestSamplingAndNearestCategories() {
        assertEquals(0, TerrainMath.pixel(-16384));
        assertEquals(0, TerrainMath.pixel(-16369));
        assertEquals(1, TerrainMath.pixel(-16368));
        assertEquals(2047, TerrainMath.pixel(16383));
        assertEquals(2047, TerrainMath.nearestPixel(16383));
        assertEquals(0, TerrainMath.nearestPixel(-16377));
        assertEquals(1, TerrainMath.nearestPixel(-16376));
        assertEquals(15 / 16.0, TerrainMath.fraction(-1));
        assertEquals(0, TerrainMath.fraction(0));
        assertEquals(-1, TerrainMath.pixel(-16385));
        assertFalse(MapCoordinates.contains(-16385, 0));
        assertFalse(MapCoordinates.contains(0, 16384));
    }

    @Test
    void sharedBilinearEdgesAndElevation() {
        assertEquals(0, TerrainMath.bilinear(0, 16, 32, 48, 0, 0));
        assertEquals(24, TerrainMath.bilinear(0, 16, 32, 48, 0.5, 0.5));
        assertEquals(TerrainMath.bilinear(0, 16, 32, 48, 1, 0.3),
                TerrainMath.bilinear(16, 64, 48, 96, 0, 0.3));
        assertEquals(925, MapCoordinates.elevation(100));
        assertEquals(5600, MapCoordinates.elevation(287));
        assertEquals(-64, 0 - 64);
        assertEquals(319, 383 - 64);
    }

    @Test
    void detailNoiseIsContinuousAndIndependentOfCallOrder() {
        double expected = TerrainMath.noise(520026, "surface_detail", -8500, -8192, 192);
        TerrainMath.noise(520026, "surface_detail", 0, 0, 192);
        assertEquals(expected, TerrainMath.noise(520026, "surface_detail", -8500, -8192, 192));
        double left = TerrainMath.noise(520026, "surface_detail", -1, 0, 192);
        double right = TerrainMath.noise(520026, "surface_detail", 0, 0, 192);
        assertTrue(Math.abs(left - right) < 0.001);
        assertNotEquals(expected, TerrainMath.noise(520026, "other_salt", -8500, -8192, 192));
    }
}
