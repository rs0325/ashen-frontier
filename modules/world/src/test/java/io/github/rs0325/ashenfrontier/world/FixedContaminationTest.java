package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.SoilContamination;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixedContaminationTest {
    private final FixedMapData map;
    private final FixedContamination distribution;

    FixedContaminationTest() throws Exception {
        map = MountainReliefTest.map();
        distribution = new FixedContamination(map);
    }

    private SoilContamination sample(int x, int z) {
        return distribution.surface(x, z, map.coastDistance(x, z), map.river(x, z));
    }

    @Test
    void sourcesAreDeterministicAcrossInstancesAndVisitOrder() {
        var other = new FixedContamination(map);
        int[][] points = {{-10050,-10800},{-6100,-5200},{-4100,-3900},{-12400,-5400},{-7000,-6200},{-7800,-7900}};
        for (int i = points.length - 1; i >= 0; i--) {
            int x = points[i][0], z = points[i][1];
            assertEquals(sample(x, z), other.surface(x, z, map.coastDistance(x, z), map.river(x, z)));
            assertTrue(sample(x, z).severe(), "局所発生源: " + x + "," + z);
        }
        assertEquals(4, sample(-10050,-10800).stage(SoilContamination.Kind.METALS));
        assertEquals(4, sample(-6100,-5200).stage(SoilContamination.Kind.ORGANICS));
        assertEquals(4, sample(-12400,-5400).stage(SoilContamination.Kind.SALTS));
        assertEquals(4, sample(-7000,-6200).stage(SoilContamination.Kind.RADIOACTIVE));
        assertEquals(4, sample(-7800,-7900).stage(SoilContamination.Kind.BIOLOGICAL));
    }

    @Test
    void reserveHasLowBackgroundAndSparseLightTraces() {
        int light = 0, total = 0;
        var traces = new java.util.HashSet<SoilContamination.Kind>();
        for (int x = -5728; x < -5472; x++) for (int z = -8320; z < -8064; z++) {
            var value = sample(x, z);
            boolean marked = false;
            for (var kind : SoilContamination.Kind.values()) {
                assertTrue(value.value(kind) > 0, "背景をゼロにしない: " + kind);
                assertTrue(value.stage(kind) <= 1, "予備地を中度以上にしない: " + kind);
                if (value.stage(kind) == 1) { marked = true; traces.add(kind); }
            }
            if (marked) light++;
            total++;
        }
        assertTrue(light > total / 100 && light < total / 3, "軽度の斑はまばら: " + light + "/" + total);
        assertEquals(java.util.Set.of(SoilContamination.Kind.ORGANICS, SoilContamination.Kind.SALTS,
                SoilContamination.Kind.BIOLOGICAL), traces);
        assertNotEquals(sample(-5600,-8192), sample(-5616,-8192), "背景値にも空間変化");
        assertTrue(FixedContamination.reserve(-5728,-8320));
        assertTrue(FixedContamination.reserve(-5473,-8065));
        assertFalse(FixedContamination.reserve(-5472,-8065));
        assertFalse(FixedContamination.reserve(-5473,-8064));
        assertTrue(sample(-8140,-7900).fecalColiform() > 0, "初期拠点の半径512でもB1の外縁が残る");
    }

    @Test
    void falloutIsOnlyLightOutsideItsLocalCoreAndNotGlobal() {
        var downwind = sample(-6400,-6800);
        assertEquals(1, downwind.stage(SoilContamination.Kind.RADIOACTIVE));
        assertEquals(0, sample(-7600,-6800).stage(SoilContamination.Kind.RADIOACTIVE));
        assertEquals(SoilContamination.CLEAN, sample(0,0));
    }

    @Test
    void riverPollutionStartsAtTheProjectedConfluence() {
        var confluence = map.river(-4500, -6400);
        assertTrue(Double.isFinite(confluence.station()), "合流候補を既存河道へ投影できる");
        var here = sample(confluence.x(), confluence.z());
        assertTrue(here.pcb() > 1e-6, "合流点は地域背景以上の有機化学物質");
        assertFalse(here.severe(), "河川帯を高度・有毒にはしない");
        assertEquals(2, here.stage(SoilContamination.Kind.ORGANICS));
        assertEquals(1, here.stage(SoilContamination.Kind.METALS));
        assertEquals(1, here.stage(SoilContamination.Kind.BIOLOGICAL));
    }

    @Test
    void coastlineNeverCreatesHighSalinityAwayFromTheSaltSource() {
        for (double coast : new double[]{0, 1, 32, 64})
            assertEquals(2, distribution.surface(-4200, -3800, coast, map.river(-4200,-3800)).stage(SoilContamination.Kind.SALTS));
        for (double coast : new double[]{Math.nextUp(64.0), 128, 192})
            assertEquals(1, distribution.surface(-4200, -3800, coast, map.river(-4200,-3800)).stage(SoilContamination.Kind.SALTS));
    }

    @Test
    void cleanEdgeOfAPlumeKeepsConcentrationsContinuous() {
        assertEquals(1, sample(-9050,-10800).stage(SoilContamination.Kind.METALS));
        assertEquals(0, sample(-9049,-10800).stage(SoilContamination.Kind.METALS));
        assertTrue(Math.abs(sample(-9050,-10800).lead() - sample(-9049,-10800).lead()) < 1e-6);
    }

    @Test
    void requiredAccessAndWalkwaysStayOutsideHighCores() throws Exception {
        try (var stream = getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/index.json")) {
            var index = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (var route : index.getAsJsonArray("walkways")) {
                var points = route.getAsJsonObject().getAsJsonArray("points_x_z_y");
                for (int i = 1; i < points.size(); i++) {
                    var a = points.get(i - 1).getAsJsonArray(); var b = points.get(i).getAsJsonArray();
                    double ax = a.get(0).getAsDouble(), az = a.get(1).getAsDouble();
                    double dx = b.get(0).getAsDouble() - ax, dz = b.get(1).getAsDouble() - az;
                    int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / 2));
                    for (int step = 0; step <= steps; step++) {
                        int x = (int) Math.round(ax + dx * step / steps), z = (int) Math.round(az + dz * step / steps);
                        assertFalse(sample(x,z).severe(), "徒歩路が高度のコアに重ならない: " + x + "," + z);
                    }
                }
            }
        }
        try (var stream = getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/underground.json")) {
            var layout = new com.google.gson.Gson().fromJson(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8), UndergroundLayout.class);
            for (var deposit : layout.deposits()) if (deposit.required()) {
                int entrance = deposit.x() - Math.max(96, deposit.depth() + 24);
                assertFalse(sample(entrance, deposit.z()).severe(), "必須資源の入口: " + deposit.id());
            }
        }
    }
}
