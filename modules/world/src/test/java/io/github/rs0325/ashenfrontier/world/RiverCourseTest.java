package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverCourseTest {
    @Test
    void poolsStayLevelAndDropsHaveShortRiffles() {
        double[][] points = new double[97][3];
        for (int i = 0; i < points.length; i++) points[i] = new double[]{i * 4, 0, 110 - i / 16.0};
        var river = new RiverCourse(points, 520026);
        for (int x = 1; x < 64; x++) assertEquals(110, river.sample(x, 0).surface());
        for (int x = 65; x < 128; x++) assertEquals(109, river.sample(x, 0).surface());
        assertEquals(109, river.sample(64, 0).surface());
        assertTrue(river.sample(64, 0).riffle() > 0.9);
        assertEquals(0, river.sample(32, 0).riffle());
        double previous = 110;
        for (int x = 0; x <= 384; x++) {
            var sample = river.sample(x, 0);
            assertTrue(sample.surface() <= previous);
            assertTrue(previous - sample.surface() <= 1);
            assertEquals(sample.surface(), river.sample(x, 25).surface());
            previous = sample.surface();
        }
    }
}
