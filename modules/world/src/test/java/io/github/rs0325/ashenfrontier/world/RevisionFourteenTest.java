package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RevisionFourteenTest {
    @Test
    void scatteredMarginsStayThinAndCoherentDepositsTaper() {
        for(double coverage=.01;coverage<.92;coverage+=.01)
            for(double scatter=0;scatter<1;scatter+=.01)
                assertTrue(TerrainMath.depositLayers(coverage,scatter)<=2,"散らす移行帯は1〜2層");
        assertEquals(0,TerrainMath.depositLayers(0,0));
        assertEquals(8,TerrainMath.depositLayers(1,.5));
        int previous=2;
        for(double coverage=.92;coverage<=1.00001;coverage+=.001) {
            int layers=TerrainMath.depositLayers(coverage,.1);
            assertTrue(layers>=previous && layers-previous<=1,"塊の縁から内側へ1層ずつ厚くする");
            previous=layers;
        }
    }
}
