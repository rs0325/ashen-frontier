package io.github.rs0325.ashenfrontier.world;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeadwaterContinuityTest {
    @Test
    void valleyCutDoesNotIntroduceHighWallsInTheHead() throws Exception {
        String hash;
        try(var stream=getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/manifest.json")) {
            hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
        var map=FixedMapData.require(new MapVersion("earth_001",1,hash));
        int maximum=0,count=0,transition=0;
        for(int z=-12520;z<-11820;z++)for(int x=-4300;x<-3740;x++) {
            int a=FixedTerrain.surfaceY(map,x,z);if(a>180)continue;
            for(var offset:new int[][]{{1,0},{0,1}}) {
                int b=FixedTerrain.surfaceY(map,x+offset[0],z+offset[1]);if(b>180)continue;
                double naturalA=FixedTerrain.landHeight(map,x,z),naturalB=FixedTerrain.landHeight(map,x+offset[0],z+offset[1]);
                int difference=Math.abs(a-b);
                if(Math.abs(map.height(x,z)-130)<4 && Math.abs(map.height(x+offset[0],z+offset[1])-130)<4)
                    transition=Math.max(transition,difference);
                if(difference>maximum) {
                    maximum=difference;
                    var ra=map.river(x,z);var rb=map.river(x+offset[0],z+offset[1]);
                    System.out.println("HEAD_WALL xyz="+x+","+z+" d="+offset[0]+","+offset[1]+" h="+a+","+b+" natural="+naturalA+","+naturalB
                            +" base="+map.height(x,z)+","+map.height(x+offset[0],z+offset[1])+" mountain="+map.mountainWeight(x,z)
                            +" branch="+ra.tributary()+","+rb.tributary()+" distance="+ra.distance()+","+rb.distance()+" nearest="+ra.x()+","+ra.z()+" / "+rb.x()+","+rb.z());
                }
                if(difference>4)count++;
            }
        }
        System.out.println("HEAD_WALL_SUMMARY max="+maximum+" transition_max="+transition+" above_four="+count);
        assertTrue(transition<=4,"低地と山地の切替で雪斜面に直壁を作らない: "+transition);
        assertTrue(maximum<=12,"元からある岩斜面の起伏を超える直壁を作らない: "+maximum);
    }
}
