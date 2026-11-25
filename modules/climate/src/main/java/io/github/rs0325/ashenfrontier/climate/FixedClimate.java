package io.github.rs0325.ashenfrontier.climate;

import com.mojang.logging.LogUtils;
import io.github.rs0325.ashenfrontier.world.FixedMapData;
import io.github.rs0325.ashenfrontier.world.MapCoordinates;
import io.github.rs0325.ashenfrontier.world.MapVersion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** チャンクを参照しない版別の64×64固定層。気象状態は所有しない。 */
public final class FixedClimate {
    public static final int SIDE = 64;
    public static final int CELL_SIZE = 512;
    private static final Map<MapVersion, FixedClimate> CACHE = new HashMap<>();
    public record Cell(int x, int z, int region, double latitudeDegrees, double surfaceElevationM,
                       double coastDistanceBlocks, double westReliefM, ClimateProfile profile) {}
    public record Baseline(MapVersion mapVersion, int region, boolean regionAssigned,
                           double temperatureK, double pressurePa, SolarPosition sun) {}
    private final MapVersion version;
    private final FixedMapData map;
    private final List<Cell> cells;

    private FixedClimate(MapVersion version) {
        long started = System.nanoTime();
        this.version = version;
        map = FixedMapData.require(version);
        var rows = new java.util.ArrayList<Cell>(SIDE * SIDE);
        int[] coast = new int[SIDE * SIDE];
        var queue = new java.util.ArrayDeque<Integer>();
        java.util.Arrays.fill(coast, Integer.MAX_VALUE);
        for (int z = 0; z < SIDE; z++) for (int x = 0; x < SIDE; x++) {
            int wx = MapCoordinates.MIN + x * CELL_SIZE + CELL_SIZE / 2;
            int wz = MapCoordinates.MIN + z * CELL_SIZE + CELL_SIZE / 2;
            int region = map.region(wx, wz);
            rows.add(new Cell(wx, wz, region, MapCoordinates.latitude(wz), MapCoordinates.elevation(map.height(wx, wz)),
                    0, 0, ClimateProfile.forRegion(region)));
            if (region == 0) { coast[z * SIDE + x] = 0; queue.add(z * SIDE + x); }
        }
        while (!queue.isEmpty()) {
            int at = queue.removeFirst(), x = at % SIDE, z = at / SIDE;
            for (int next : new int[]{x > 0 ? at - 1 : -1, x + 1 < SIDE ? at + 1 : -1,
                    z > 0 ? at - SIDE : -1, z + 1 < SIDE ? at + SIDE : -1}) {
                if (next >= 0 && coast[next] > coast[at] + 1) {
                    coast[next] = coast[at] + 1;
                    queue.add(next);
                }
            }
        }
        for (int at = 0; at < rows.size(); at++) {
            var cell = rows.get(at);
            double west = at % SIDE == 0 ? cell.surfaceElevationM() : rows.get(at - 1).surfaceElevationM();
            rows.set(at, new Cell(cell.x(), cell.z(), cell.region(), cell.latitudeDegrees(), cell.surfaceElevationM(),
                    coast[at] == Integer.MAX_VALUE ? Double.POSITIVE_INFINITY : coast[at] * (double) CELL_SIZE,
                    Math.max(0, west - cell.surfaceElevationM()), cell.profile()));
        }
        cells = List.copyOf(rows);
        LogUtils.getLogger().info("M3-133 fixed layer initialization {}: {} ms",
                version.mapVersion(), (System.nanoTime() - started) / 1e6);
    }

    /** 完全なMapVersionをキーに一度だけ固定層を構築する。未知版はworldの照合で拒否する。 */
    public static synchronized FixedClimate require(MapVersion version) {
        return CACHE.computeIfAbsent(version, FixedClimate::new);
    }

    public List<Cell> cells() { return cells; }

    /** 半開の固定マップ範囲を要求する。セル中心以外でも地域・緯度・標高は位置から直接読む。 */
    public Baseline sample(int x, double y, int z, double gameSeconds) {
        if (!MapCoordinates.contains(x, z) || !Double.isFinite(gameSeconds) || gameSeconds < 0) {
            throw new IllegalArgumentException("基準気候の要求範囲が不正です");
        }
        int region = map.region(x, z);
        var profile = ClimateProfile.forRegion(region);
        double latitude = MapCoordinates.latitude(z), elevation = MapCoordinates.elevation(y);
        return new Baseline(version, region, region != 255,
                ClimateModel.temperatureK(latitude, elevation, gameSeconds, profile),
                StandardAtmosphere.pressurePa(elevation), ClimateModel.sun(latitude, gameSeconds));
    }
}
