package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverCourseTest {
    @Test
    void headwatersGrowFromShallowBrooksAndReceiveNarrowTributaries() {
        double[][] points=new double[1201][3];
        for(int i=0;i<points.length;i++)points[i]=new double[]{i*4,0,110-i/256.0};
        var river=new RiverCourse(points,520026);
        double previousWidth=river.sample(0,0).width(),previousDepth=1,previousWater=110;
        for(int x=0;x<=2400;x++) {
            var sample=river.sample(x,0);
            assertFalse(sample.tributary());
            assertTrue(sample.width()>=previousWidth && sample.width()-previousWidth<.03,"合流域でも断面を連続して広げる");
            assertTrue(sample.depth()>=previousDepth && sample.depth()-previousDepth<.01);
            assertTrue(sample.surface()<=previousWater && previousWater-sample.surface()<=1);
            if(x<96){assertTrue(sample.width()<=1);assertEquals(1,sample.depth());}
            previousWidth=sample.width();previousDepth=sample.depth();previousWater=sample.surface();
        }
        assertTrue(river.sample(2400,0).width()>8);
        assertTrue(river.sample(4200,0).depth()>=5);
        int branches=0;
        for(int x=200;x<2100;x+=4)for(int z=-160;z<=160;z++) {
            var sample=river.sample(x,z);
            if(sample.tributary() && sample.distance()<sample.width()) {
                assertEquals(.8,sample.width());assertEquals(1,sample.depth());branches++;
            }
        }
        assertTrue(branches>100,"両岸から細い支沢が接続する");
    }

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
