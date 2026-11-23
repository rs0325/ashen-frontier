package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RevisionSixteenTest {
    @Test
    void westernPeakDoesNotClipIntoACeilingPlateau() throws Exception {
        var map=MountainReliefTest.map();int maximum=0;
        for(int z=-11872;z<=-11552;z+=4)for(int x=-13660;x<=-13340;x+=4) {
            int height=FixedTerrain.surfaceY(map,x,z);maximum=Math.max(maximum,height);
            assertTrue(height<520,"西端の峰を高度上限へ押し付けて平坦面にしない");
        }
        // r15の同じ格子での実測最大Yは457。
        assertTrue(maximum>=457,"西端の固定峰の比高を保つ: "+maximum);
    }

    @Test
    void undergroundReferenceSurfacesStayAtTheirFixedHeights() throws Exception {
        var map=MountainReliefTest.map();int count=0;
        try(var stream=getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/index.json")) {
            var index=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var value:index.getAsJsonArray("underground_surface_controls")) {
                var point=value.getAsJsonArray();int x=point.get(0).getAsInt(),z=point.get(1).getAsInt();
                assertEquals(point.get(2).getAsInt(),FixedTerrain.surfaceY(map,x,z),"地下中心・入口の固定地表高: "+x+","+z);
                count++;
            }
        }
        assertEquals(77,count);
    }

    @Test
    void rockReliefIsContinuousAndBreaksConstantSlopeAtSeveralScales() {
        double min=100,max=-100,curvature=0,previous=TerrainMath.rockRelief(520026,-4900,-14800);
        double before=previous;
        for(int x=-4899;x<-4650;x++) {
            double y=TerrainMath.rockRelief(520026,x,-14800);
            assertTrue(Math.abs(y-previous)<8,"岩の起伏はセル境界でも連続する");
            curvature+=Math.abs(y-2*previous+before);before=previous;previous=y;
            min=Math.min(min,y);max=Math.max(max,y);
        }
        assertTrue(max-min>8 && curvature/249>.08,"岩棚・割れ目の数ブロックの起伏を持つ");
    }
}
