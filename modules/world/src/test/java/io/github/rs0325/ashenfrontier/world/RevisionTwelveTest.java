package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class RevisionTwelveTest {
    private static java.util.List<int[]> islands() throws Exception {
        try(var stream=RevisionTwelveTest.class.getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/index.json")) {
            var index=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var result=new java.util.ArrayList<int[]>();
            for(var value:index.getAsJsonArray("mountain_islands")) {
                var island=value.getAsJsonObject();var xz=island.getAsJsonArray("summit_xz");
                result.add(new int[]{xz.get(0).getAsInt(),xz.get(1).getAsInt(),island.get("peak_y").getAsInt()});
            }
            return result;
        }
    }

    @Test
    void islandSummitsKeepTheirHeightWithoutANeedle() throws Exception {
        var map=MountainReliefTest.map();
        for(var value:islands()) {
            int[] point=value;
            int peak=FixedTerrain.surfaceY(map,point[0],point[1]);assertEquals(point[2],peak);
            for(int offset=-12;offset<=12;offset++) {
                int a=FixedTerrain.surfaceY(map,point[0]+offset,point[1]);
                int b=FixedTerrain.surfaceY(map,point[0]+offset+1,point[1]);
                assertTrue(Math.abs(a-b)<=3,"頂上付近を丸い肩でつなぎ、針状にしない");
            }
        }
    }

    @Test
    void riverBanksUseTheContinuousGradeAcrossWaterSteps() {
        var river=new RiverCourse(new double[][]{{0,0,100.5},{0,800,96.5}},520026);
        int drops=0;
        for(int z=1;z<800;z++) {
            var a=river.sample(0,z-1);var b=river.sample(0,z);
            if(a.surface()!=b.surface()) {
                drops++;
                assertTrue(Math.abs(a.grade()-b.grade())<.02,"周囲の谷の高さに水面の段を持ち込まない");
            }
        }
        assertTrue(drops>=3);
    }

    @Test
    void coldBiomeMarginsMixInsteadOfSwitchingAsOneStraightEdge() throws Exception {
        var map=MountainReliefTest.map();int transition=0,mixed=0;
        for(int z=-15000;z<-12500;z+=24)for(int x=-4500;x<-2000;x+=24) {
            var boundary=map.boundary(x,z);
            if(boundary.cold()>.15 && boundary.cold()<.85) {
                transition++;var ids=new HashSet<Integer>();
                for(int dx=-6;dx<=6;dx++)ids.add(map.boundary(x+dx,z).region());
                if(ids.size()>1)mixed++;
            }
        }
        assertTrue(transition>50 && mixed>30,"寒冷域の移行幅と境界の混在: "+transition+" / "+mixed);
    }

    @Test
    void alpineBiomeAlsoHasAnAltitudeTransition() throws Exception {
        var map = MountainReliefTest.map();
        var ids = new HashSet<Integer>();
        for (int x = -11400; x < -11300; x++) ids.add(FixedTerrain.biomeIndex(map, x, -12000, 210));
        assertTrue(ids.contains(11) && ids.size() > 1, "標高210で一斉に高山へ切り替えない");
    }
    @Test
    void freezingDependsOnTheSurfaceRatherThanTheDepth() {
        assertTrue(TerrainMath.freezes(76, 63, 12, 3, 0));
        assertTrue(TerrainMath.freezes(76, 63, 180, 3, 0));
        assertFalse(TerrainMath.freezes(45, 63, 180, 3, 0));
        assertTrue(TerrainMath.freezes(65, 100, 4, 2, 0));
        assertFalse(TerrainMath.freezes(45, 63, 4, 2, 0));
        assertTrue(TerrainMath.freezes(59, 63, 8, 1, .5));
    }
}
