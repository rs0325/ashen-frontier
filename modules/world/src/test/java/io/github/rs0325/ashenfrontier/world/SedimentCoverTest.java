package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SedimentCoverTest {
    @Test
    void scatteredEdgesOnlyHaveOneOrTwoLayersAndThinOutwards() {
        int previous = 0;
        for (double coverage : new double[]{0, .1, .3, .6, .9}) {
            int count = 0, thick = 0;
            for (int i = 0; i < 1000; i++) {
                int top = TerrainMath.sedimentCover(80.49, 80, coverage, i / 1000.0, .1, 0);
                assertTrue(top == 0 || top == 9 || top == 10, "縁は地面の上に1〜2層のみ");
                if (top > 0) count++;
                if (top == 10) thick++;
            }
            assertTrue(count >= previous, "外側ほどまばら");
            assertTrue(thick <= count, "厚い散布も2層まで");
            previous = count;
        }
    }

    @Test
    void fractionalSlopeHasNoThickStepAtGroundRoundingBoundaries() {
        for (double slope : new double[]{.02, .08, .15, .25}) {
            int previous = 0;
            for (int x = 0; x < 512; x++) {
                double height = 80 + x * slope;
                int ground = (int) Math.round(height);
                int top = ground * 8 + TerrainMath.sedimentCover(height, ground, 1, .5, slope, 0);
                if (x > 0) assertTrue(Math.abs(top - previous) <= 3, "隣の天面へ1〜3層刻みで接続");
                previous = top;
            }
        }
    }

    @Test
    void retentionIsThinOnSlopesAndThickerInsideHollows() {
        int flat = TerrainMath.sedimentCover(80, 80, 1, .5, 0, .8);
        int steep = TerrainMath.sedimentCover(80, 80, 1, .5, .24, .8);
        int convex = TerrainMath.sedimentCover(80, 80, 1, .5, 0, -.4);
        assertEquals(12, flat, "くぼみの内側は最大1/2ブロック");
        assertEquals(9, steep, "斜面は1/8ブロックの薄い保持量");
        assertEquals(9, convex, "凸部も薄く");
        assertEquals(TerrainMath.sedimentCover(80, 80, .98, .5, 0, .8),
                TerrainMath.sedimentCover(80, 80, .98, .99, 0, .8), "塊の内側に散布乱数で穴をあけない");
        int previous = 0;
        for (int i = 0; i <= 100; i++) {
            int top = TerrainMath.sedimentCover(80, 80, i / 100.0, .01, 0, .8);
            assertTrue(top >= previous, "平坦な塊は外から内へ厚く");
            previous = top;
        }
    }
}
