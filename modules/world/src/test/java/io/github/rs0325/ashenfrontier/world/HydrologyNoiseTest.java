package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HydrologyNoiseTest {
    @Test void cachedCellsKeepTheOriginalBitsAfterEvictionAndReverseTraversal() {
        int checked=0;int[] scales={12,36,96,112};
        for(long seed:new long[]{520026,Long.MAX_VALUE-1}) {
            var noise=new HydrologyNoise(seed);
            var salts=HydrologyNoise.Salt.values();
            for(int pass=0;pass<2;pass++)for(int n=0;n<salts.length;n++) {
                var salt=salts[pass==0?n:salts.length-1-n];
                for(int i=0;i<1024;i++) {
                    int at=pass==0?i:1023-i,scale=scales[at%4];
                    double x=-12000+(at*2137%25000)+.125,z=-16000+(at*3167%18000)+.875;
                    assertEquals(Double.doubleToLongBits(TerrainMath.noise(seed,salt.id,x,z,scale)),
                            Double.doubleToLongBits(noise.noise(salt,x,z,scale)),"雑音セル: "+salt);
                    assertEquals(Double.doubleToLongBits(TerrainMath.fbm(seed,salt.id,x,z,scale)),
                            Double.doubleToLongBits(noise.fbm(salt,x,z,scale)),"複数尺度: "+salt);
                    checked++;
                }
            }
        }
        System.out.println("HYDROLOGY_NOISE_EXACT checked="+checked);
    }
}
