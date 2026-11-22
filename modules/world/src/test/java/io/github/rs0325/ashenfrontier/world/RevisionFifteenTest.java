package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RevisionFifteenTest {
    @Test
    void fixedIslandSummitsKeepTheirHeightOnTheActualCoast() throws Exception {
        var map=MountainReliefTest.map();var diagnostics=new StringBuilder();boolean valid=true;
        try(var stream=getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/index.json")) {
            var index=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var value:index.getAsJsonArray("mountain_islands"))for(var definition:value.getAsJsonObject().getAsJsonArray("summits")) {
                var peak=definition.getAsJsonObject();var xz=peak.getAsJsonArray("xz");int x=xz.get(0).getAsInt(),z=xz.get(1).getAsInt();
                int h=FixedTerrain.surfaceY(map,x,z),expected=peak.get("peak_y").getAsInt();
                diagnostics.append(x).append(',').append(z).append(" target=").append(expected).append(" actual=").append(h)
                        .append(" coast=").append(map.coastDistance(x,z)).append('\n');
                valid &= h>=expected-1;
            }
        }
        assertTrue(valid,diagnostics.toString());
    }
    @Test
    void islandSpursAndGulliesBreakRadialSymmetryWithoutNeedles() {
        double low=Double.MAX_VALUE,high=0;
        for(int i=0;i<360;i++) {
            double angle=Math.toRadians(i);
            double y=TerrainMath.islandPeak(Math.cos(angle)*65,Math.sin(angle)*65,205,145,1,0,.15);
            low=Math.min(low,y);high=Math.max(high,y);
        }
        assertTrue(high-low>25,"同じ半径でも尾根・沢と斜面の向きで高さが変わる");
        assertEquals(205,TerrainMath.islandPeak(0,0,205,145,1,0,.15));
        for(int x=-8;x<8;x++)
            assertTrue(Math.abs(TerrainMath.islandPeak(x,0,205,145,1,0,.15)
                    -TerrainMath.islandPeak(x+1,0,205,145,1,0,.15))<2,"頂上付近を針状にしない");
    }
}
