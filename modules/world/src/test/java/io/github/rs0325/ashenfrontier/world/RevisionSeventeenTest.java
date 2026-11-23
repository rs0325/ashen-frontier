package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RevisionSeventeenTest {
    @Test
    void westernRidgesDescendSeparatelyToTheExistingCoast() throws Exception {
        var map=MountainReliefTest.map();
        int peak=FixedTerrain.surfaceY(map,-13500,-11712);
        int shoulder=FixedTerrain.surfaceY(map,-13740,-11760);
        int cape=FixedTerrain.surfaceY(map,-13920,-11770);
        int valley=FixedTerrain.surfaceY(map,-13600,-11970);
        assertTrue(peak>shoulder+100 && shoulder>cape+70,"支尾根は海へ段階的に低くなる");
        assertTrue(valley<shoulder-100,"支尾根の間に、稜線の細部より大きい谷を刻む");
        assertTrue(FixedTerrain.surfaceY(map,-13240,-12202)>250,"北への別の支尾根を残す");
    }

    @Test
    void massifReliefHasBroadShouldersAndContinuousGullies() {
        double min=100,max=-100,curvature=0;
        double a=TerrainMath.massifRelief(520026,-13801,-11700);
        double b=TerrainMath.massifRelief(520026,-13800,-11700);
        for(int x=-13799;x<-13200;x++) {
            double c=TerrainMath.massifRelief(520026,x,-11700);
            assertTrue(Math.abs(c-b)<6,"溝と岩棚は1ブロックで針状に飛び出さない");
            curvature+=Math.abs(c-2*b+a);a=b;b=c;
            min=Math.min(min,c);max=Math.max(max,c);
        }
        assertTrue(max-min>20 && curvature/599>.04,"中腹にも削り込みと岩の肩を持つ");
    }
}
