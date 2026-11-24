package io.github.rs0325.ashenfrontier.world;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyMapContractTest {
    @Test
    void originalSurfaceAndWaterFingerprint() throws Exception {
        var map = MountainReliefTest.map();
        var digest = MessageDigest.getInstance("SHA-256");
        // 全域に加え、旧主脈・源頭・支沢・島を高密度で標本化する。
        sample(map, digest, -16384, 16383, -16384, 16383, 256);
        sample(map, digest, -13000, 3000, -14500, -10000, 64);
        sample(map, digest, -4300, -3650, -12400, -11300, 8);
        String actual = HexFormat.of().formatHex(digest.digest());
        System.out.println("EARTH_001_FINGERPRINT=" + actual);
        assertEquals("3503b4798d7483c66667d3de883b9afa80d163f0c2dc855c37ec0cbd1223f120", actual);
    }

    private static void sample(FixedMapData map, MessageDigest digest, int x0, int x1, int z0, int z1, int step) {
        var bytes = ByteBuffer.allocate(24);
        for (int z = z0; z <= z1; z += step) for (int x = x0; x <= x1; x += step) {
            bytes.clear();
            bytes.putInt(x).putInt(z).putInt(FixedTerrain.surfaceY(map, x, z))
                    .putInt(map.waterSurface(x, z)).putInt(map.waterQuality(x, z)).putInt(map.waterMask(x, z));
            digest.update(bytes.array());
        }
    }
}
