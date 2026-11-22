package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class FixedReliefTest {
    private static final String ROOT = "/data/ashen_frontier/fixed_map/earth_001/";

    private static FixedMapData map() throws Exception {
        try (var stream = FixedReliefTest.class.getResourceAsStream(ROOT + "manifest.json")) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            return FixedMapData.require(new MapVersion("earth_001", 1, hash));
        }
    }

    @Test
    void slopesHaveSmallStepsWithoutLongFlatBands() throws Exception {
        var map = map();
        for (var point : new int[][]{{-9300, -9000}, {-10000, -11000}, {-12200, -5600}}) {
            int small = 0, total = 0, changes = 0, longest = 0;
            for (int dz = 0; dz < 128; dz += 4) {
                int previous = FixedTerrain.surfaceY(map, point[0], point[1] + dz), run = 0;
                for (int dx = 1; dx <= 256; dx++) {
                    int height = FixedTerrain.surfaceY(map, point[0] + dx, point[1] + dz);
                    if (map.detailWeight(point[0] + dx, point[1] + dz) < 0.99) {
                        previous = height; run = 0; continue;
                    }
                    int step = Math.abs(height - previous);
                    if (step <= 1) small++;
                    if (step != 0) { changes++; run = 0; } else run++;
                    longest = Math.max(longest, run);
                    total++;
                    previous = height;
                }
            }
            assertTrue(small / (double) total >= 0.99,"1ブロック以下の段差: " + small + "/" + total);
            assertTrue(changes > total / 40,"斜面の変化量");
            assertTrue(longest < 128,"平坦な帯の最大長: " + longest + " 地点=" + point[0] + "," + point[1]);
        }
    }

    @Test
    void coastJoinsShelfWithoutUnderwaterWalls() throws Exception {
        var map = map();
        int coastPairs = 0;
        for (int z = -15000; z < -3000; z += 193) {
            for (int x = -15000; x < 3000; x += 16) {
                double distance = map.coastDistance(x, z);
                if (Math.abs(distance) > 128) continue;
                if (map.mountainIsland(x,z)!=null || map.mountainIsland(x+1,z)!=null || map.mountainIsland(x,z+1)!=null) continue;
                int height = FixedTerrain.surfaceY(map, x, z);
                var river = map.river(x, z);
                boolean cliff = distance > 0 && OceanRelief.shore(map,x,z).cliff() > .4;
                int limit = river.distance() < river.width() + 4 ? 3 : cliff || distance > 16 && map.mountainWeight(x,z) > .1 ? 5 : 2;
                assertTrue(Math.abs(height - FixedTerrain.surfaceY(map, x + 1, z)) <= limit,"海岸・河口の東西段差: " + x + "," + z
                        + " height="+height+" next="+FixedTerrain.surfaceY(map,x+1,z)+" coast="+distance+" river="+river.distance()+" width="+river.width()+" water="+river.surface()+" grade="+river.grade()+" limit="+limit);
                assertTrue(Math.abs(height - FixedTerrain.surfaceY(map, x, z + 1)) <= limit,"海岸・河口の南北段差: " + x + "," + z
                        + " coast=" + distance + " height=" + height + " next=" + FixedTerrain.surfaceY(map,x,z+1) + " limit=" + limit);
                coastPairs++;
            }
        }
        assertTrue(coastPairs > 1000,"海岸の検査数");
    }

    @Test
    void actualRiverSurfaceDescendsAndMatchesMembership() throws Exception {
        var map = map();
        try (var stream = getClass().getResourceAsStream(ROOT + "index.json")) {
            var index = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var points = index.getAsJsonArray("water_bodies").get(1).getAsJsonObject().getAsJsonArray("centerline_pixel_x_y_surface");
            int previous = Integer.MAX_VALUE;
            for (var value : points) {
                var point = value.getAsJsonArray();
                int x = MapCoordinates.MIN + point.get(0).getAsInt() * 16;
                int z = MapCoordinates.MIN + point.get(1).getAsInt() * 16;
                int water = map.waterSurface(x, z);
                assertTrue(water <= previous,"下流の水面: " + x + "," + z);
                assertTrue(water >= 63,"河道からの脱落");
                assertTrue(FixedTerrain.surfaceY(map, x, z) < water,"水面下の河床");
                assertNotEquals(0, map.waterQuality(x, z),"水質の欠落");
                previous = water;
            }
            assertEquals(63, previous,"河口は海面に接続");
        }
    }

    @Test
    void beachHasSingleStepsAndDeepSeaHasRelief() throws Exception {
        var map = map();
        int beaches = 0, deep = 0, shallow = 0, minimum = 320, maximum = -64;
        for (int x = -13264; x <= -10704; x++) {
            double distance = map.coastDistance(x, -6880);
            int height = FixedTerrain.surfaceY(map, x, -6880);
            if (Math.abs(distance) < 32) {
                assertTrue(Math.abs(height - FixedTerrain.surfaceY(map, x + 1, -6880)) <= 1,"砂浜の段差: " + x);
                beaches++;
            }
            if (distance < -768) {
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
                deep++;
            }
            if (distance < -16 && distance > -96) shallow++;
        }
        assertTrue(beaches > 20 && shallow > 40,"湾内の砂浜・大陸棚を通る断面");
        for (int x = -16384; x < -12000; x++) {
            if (map.coastDistance(x, -8192) < -768) {
                int height = FixedTerrain.surfaceY(map, x, -8192);
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
                deep++;
            }
        }
        assertTrue(deep > 100,"外洋の深海を通る断面");
        assertTrue(maximum - minimum >= 3,"深海にも起伏がある");
        assertTrue(minimum < -30,"深海の深さ");
    }

    @Test
    void shorelineDoesNotFollowLongPixelSteps() throws Exception {
        var map = map();
        double previous = Double.NaN;
        int aligned = 0, count = 0;
        for (int z = -7110; z <= -6630; z++) {
            for (int x = -11472; x < -10960; x++) {
                double a = map.coastDistance(x,z), b = map.coastDistance(x + 1,z);
                if (a < 0 && b >= 0) {
                    double crossing = x - a / (b - a);
                    if (!Double.isNaN(previous) && Math.abs(crossing - previous) < 0.01) aligned++;
                    previous = crossing;
                    count++;
                    break;
                }
            }
        }
        assertTrue(count > 200,"海岸線の測定行数");
        assertTrue(aligned < count / 10,"格子軸に固定された海岸: " + aligned + "/" + count);
    }
}
