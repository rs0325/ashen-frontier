package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class ReliefTenTest {
    @Test
    void trenchEndHasNoSideSignSeam() throws Exception {
        var map=MountainReliefTest.map();
        for(int x=-8910;x<-8830;x++) {
            double a=map.ocean().height(map,x,-15000,-map.coastDistance(x,-15000),OceanRelief.shore(map,x,-15000));
            double b=map.ocean().height(map,x,-15001,-map.coastDistance(x,-15001),OceanRelief.shore(map,x,-15001));
            assertTrue(Math.abs(a-b)<4,"海溝端点で軸の左右に不連続な壁を作らない");
        }
    }
    @Test
    void snowThinsBeforeTheRockWallInsteadOfOpeningAnAbruptHole() {
        int previous=8;var thickness=new HashSet<Integer>();
        for(int i=0;i<=100;i++) {
            int layers=TerrainMath.snowLayers(300,160,i/100.0);
            assertTrue(layers<=previous && previous-layers<=1,"傾斜に沿った雪の薄まり");
            thickness.add(layers);previous=layers;
        }
        assertEquals(9,thickness.size());
    }

    @Test
    void seamountKeepsTheAbyssOutsideItsCompactFoot() throws Exception {
        var map=MountainReliefTest.map();var none=new OceanRelief(new JsonArray());
        for(int x:new int[]{200,350,1650,1800}) {
            double distance=-map.coastDistance(x,0);var shore=OceanRelief.shore(map,x,0);
            double height=map.ocean().height(map,x,0,distance,shore);
            assertEquals(none.height(map,x,0,distance,shore),height,1e-8,"海山の外で深海平原を持ち上げない");
            assertTrue(height>-45 && height<-30,"深海平原の深さ");
        }
        assertTrue(FixedTerrain.surfaceY(map,1000,0)>35 && FixedTerrain.surfaceY(map,1000,0)<55,"海山頂部の深さを維持");
    }

    @Test
    void bendsDistinguishInnerAndOuterBanksAndVaryTheWidth() {
        double[][] points=new double[97][3];
        for(int i=0;i<points.length;i++){double angle=i*Math.PI/192;points[i]=new double[]{Math.sin(angle)*400,Math.cos(angle)*400,100};}
        var river=new RiverCourse(points,520026);var widths=new HashSet<Integer>();
        boolean opposed=false;
        for(int i=16;i<80;i+=4) {
            double angle=i*Math.PI/192;
            var inner=river.sample((int)(Math.sin(angle)*370),(int)(Math.cos(angle)*370));
            var outer=river.sample((int)(Math.sin(angle)*430),(int)(Math.cos(angle)*430));
            if(inner.bank()*outer.bank()<0)opposed=true;
            widths.add((int)inner.width());
        }
        assertTrue(opposed,"蛇行の内外で河岸の条件を分ける");
        assertTrue(widths.size()>=3,"区間ごとに異なる河幅");
    }
}
