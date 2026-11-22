package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapViewMathTest {
    @Test void northUpAndZoomAnchorRoundTrip() {
        for (double scale : new double[]{0.002, 0.125, 1, 4}) {
            for (double value : new double[]{-16384, -8500.5, 0, 16383.99}) {
                double screen = MapViewMath.screen(value, -8192.5, 128, scale);
                assertEquals(value, MapViewMath.world(screen, -8192.5, 128, scale), 1e-8);
            }
            assertTrue(MapViewMath.screen(-100, 0, 128, scale) < 128);
        }
        double oldScale = 0.01, nextScale = 0.02, mouse = 290, center = -8500;
        double anchor = MapViewMath.world(mouse, center, 160, oldScale);
        double shifted = center + anchor - MapViewMath.world(mouse, center, 160, nextScale);
        assertEquals(anchor, MapViewMath.world(mouse, shifted, 160, nextScale), 1e-8);
    }

    @Test void reliefDepthShadeAndPixelChannels() {
        int shallow = MapViewMath.color(63, 3, 0);
        int deep = MapViewMath.color(63, 127, 0);
        assertTrue((shallow & 255) > (deep & 255));
        int low = MapViewMath.color(70, 0, 0);
        int high = MapViewMath.color(550, 0, 0);
        assertTrue(((low >> 8) & 255) > ((low >> 16) & 255));
        assertTrue((high & 255) > (low & 255));
        assertTrue(MapViewMath.color(200, 0, 100) > MapViewMath.color(200, 0, -100));
        assertEquals(0xFF563412, MapViewMath.abgr(0x123456));
        assertEquals(MapViewMath.color(63, 12, 0), MapViewMath.color(200, 12, 0));
        assertTrue(MapViewMath.blockColor(0x6699DD, 3, 0) > MapViewMath.blockColor(0x6699DD, 100, 0));
        assertTrue(MapViewMath.blockColor(0x808080, 0, 1) > MapViewMath.blockColor(0x808080, 0, -1));
        assertEquals(MapViewMath.color(63, 127, 0), MapViewMath.color(63, 200, 0));
    }
}
