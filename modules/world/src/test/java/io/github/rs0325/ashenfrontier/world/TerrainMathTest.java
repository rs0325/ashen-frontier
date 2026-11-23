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
        assertEquals(575, 639 - 64);
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

    @Test
    void cubicSharesValuesAndTangentsAcrossCells() {
        double epsilon = 1e-5;
        for (double t = 0; t <= 1; t += 0.05) {
            assertEquals(16 + 16 * t, TerrainMath.cubic(0, 16, 32, 48, t), 1e-12);
        }
        double left = (TerrainMath.cubic(0, 7, 19, 23, 1) - TerrainMath.cubic(0, 7, 19, 23, 1 - epsilon)) / epsilon;
        double right = (TerrainMath.cubic(7, 19, 23, 40, epsilon) - TerrainMath.cubic(7, 19, 23, 40, 0)) / epsilon;
        assertEquals(left, right, 0.001);
    }

    @Test
    void rotatedOctavesAreContinuousAtPositiveAndNegativeGridLines() {
        for (int x = -1024; x <= 1024; x += 16) {
            double left = TerrainMath.fbm(520026,"surface_detail", x - 1e-5, -256, 128);
            double right = TerrainMath.fbm(520026,"surface_detail", x + 1e-5, -256, 128);
            assertEquals(left, right, 1e-5);
            assertTrue(Math.abs(left) <= 1);
        }
    }

    @Test
    void snowlineVariesWithPositionAspectAndSlope() {
        double low = 320, high = -64;
        for (int x = -10500; x <= -9500; x += 4) {
            double line = TerrainMath.snowline(520026, x, -11000, 54, 0, 0, false);
            low = Math.min(low, line);
            high = Math.max(high, line);
        }
        assertTrue(high - low > 8, "積雪下限は固定の等高線にしない");
        assertTrue(TerrainMath.snowline(520026, -10000, -11000, 54, 0, 0.3, false)
                < TerrainMath.snowline(520026, -10000, -11000, 54, 0, -0.3, false));
        assertEquals(0, TerrainMath.snowLayers(220, 200, 1.3));
        assertEquals(0, TerrainMath.snowLayers(180, 200, 0));
        assertTrue(TerrainMath.snowLayers(200, 200, 0) > 0);
        assertTrue(TerrainMath.snowLayers(200, 200, 0) < 8);
        assertEquals(8, TerrainMath.snowLayers(230, 200, 0));
    }

    @Test
    void valleyOnlyCutsAndJoinsTheSurroundings() {
        for (double ground : new double[]{80, 100, 140}) {
            for (int distance = 32; distance <= 160; distance++) {
                double height = TerrainMath.valleyHeight(ground, 90, distance, 40, 0.18);
                assertTrue(height <= ground, "川岸を周囲より持ち上げない");
                double next = TerrainMath.valleyHeight(ground, 90, distance + 1, 40, 0.18);
                assertTrue(Math.abs(next - height) < 1.5, "谷の斜面を連続させる");
            }
            assertEquals(ground, TerrainMath.valleyHeight(ground, 90, 160, 40, 0.18));
        }
    }

    @Test
    void thickSnowCoversSteppedGround() {
        double low = 10, high = 0, previous = 0;
        for (int i = 0; i <= 1000; i++) {
            double depth = TerrainMath.snowPack(100,100,.1,i/500.0,0);
            assertTrue(depth >= 2 && depth <= 4);
            if (i>0) assertTrue(Math.abs(depth-previous) < .01,"吹きだまりの連続した厚さ");
            low = Math.min(low, depth); high = Math.max(high, depth); previous=depth;
        }
        assertTrue(high > low, "厚い積雪にもまとまりのある厚さの変化");
        assertEquals(0,TerrainMath.snowPack(100,0,.1,2,0));
        assertEquals(0,TerrainMath.snowPack(100,100,.8,2,0));
    }

    @Test
    void snowCapsSmoothTheRoundedHeightSteps() {
        double previous=Double.NaN;
        for(int i=0;i<1024;i++) {
            double continuous=100+i/64.0;int ground=(int)Math.round(continuous);
            double top=ground+TerrainMath.snowCover(continuous,ground,8)/8.0;
            if(!Double.isNaN(previous))assertTrue(Math.abs(top-previous)<=.125,"雪層が地面の丸め境界を滑らかにつなぐ");
            previous=top;
        }
        assertEquals(3,TerrainMath.snowCover(100.4,100,3));
    }

    @Test
    void localLayersVaryWithinTheProfileThicknessRange() {
        int low = 320, high = -64, thin = 384, thick = 0;
        for (int x = -8548; x <= -8452; x++) {
            double cap = TerrainMath.layerThickness(520026, "geology_thickness:2:clay_cap", x, -8192, 2, 5, 0);
            double sand = TerrainMath.layerThickness(520026, "geology_thickness:2:sandstone", x, -8192, 8, 16, 0);
            assertTrue(cap >= 2 && cap <= 5 && sand >= 8 && sand <= 16);
            int top = (int) Math.floor(100 - cap), bottom = (int) Math.floor(100 - cap - sand);
            low = Math.min(low, bottom); high = Math.max(high, bottom);
            thin = Math.min(thin, top - bottom); thick = Math.max(thick, top - bottom);
        }
        assertTrue(high - low >= 4, "局所境界の起伏: " + low + ".." + high);
        assertTrue(thick - thin >= 3, "局所の厚さ: " + thin + ".." + thick);
    }
}
