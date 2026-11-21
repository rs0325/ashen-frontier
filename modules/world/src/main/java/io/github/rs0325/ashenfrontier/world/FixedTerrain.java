package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;

/** 固定地形の列サンプラー。ワールドseed・SavedData・共有Randomを参照しない。 */
public final class FixedTerrain {
    private record Layer(BlockState rock, int min, int max, String salt) {}
    private record Fault(double x, double z, double nx, double nz, double displacement) {}
    private record Profile(List<Layer> layers, BlockState basement, double ax, double az,
                           double gx, double gz, List<Fault> faults) {}
    private final FixedMapData map;
    private final Profile[] profiles = new Profile[6];
    private final BlockState[] soils = new BlockState[6];
    private final BlockState[] waters = new BlockState[9];
    private final FixedUnderground underground;

    public FixedTerrain(MapVersion version) {
        map = FixedMapData.require(version);
        var geology = map.geology();
        String salt = geology.get("thickness_salt").getAsString();
        for (var entry : geology.getAsJsonObject("profiles").entrySet()) {
            var profile = entry.getValue().getAsJsonObject();
            List<Layer> layers = new ArrayList<>();
            for (var value : profile.getAsJsonArray("layers_top_to_bottom")) {
                var layer = value.getAsJsonObject();
                var range = layer.getAsJsonArray("thickness_blocks");
                layers.add(new Layer(block(layer.get("rock").getAsString()), range.get(0).getAsInt(),
                        range.get(1).getAsInt(), salt + ":" + entry.getKey() + ":" + layer.get("id").getAsString()));
            }
            List<Fault> faults = new ArrayList<>();
            for (var value : profile.getAsJsonArray("faults")) {
                var fault = value.getAsJsonObject();
                faults.add(new Fault(component(fault, "point_xz", 0), component(fault, "point_xz", 1),
                        component(fault, "normal_xz", 0), component(fault, "normal_xz", 1), fault.get("throw_blocks").getAsDouble()));
            }
            var dip = profile.getAsJsonObject("dip");
            profiles[Integer.parseInt(entry.getKey())] = new Profile(List.copyOf(layers),
                    block(profile.get("basement").getAsString()), component(dip, "anchor_xz", 0), component(dip, "anchor_xz", 1),
                    component(dip, "gradient_xz", 0), component(dip, "gradient_xz", 1), List.copyOf(faults));
        }
        String[] soilIds = {"fertile_soil", "poor_soil", "degraded_soil", "contaminated_soil", "toxic_soil"};
        String[] waterIds = {"ultrapure_water", "pure_water", "clean_water", "raw_water", "turbid_water",
                "contaminated_water", "toxic_water", "polluted_seawater"};
        for (int i = 0; i < soilIds.length; i++) soils[i + 1] = block("ashen_frontier:" + soilIds[i]);
        for (int i = 0; i < waterIds.length; i++) waters[i + 1] = block("ashen_frontier:" + waterIds[i]);
        underground = new FixedUnderground(version, this);
    }

    private static double component(JsonObject object, String name, int i) {
        return object.getAsJsonArray(name).get(i).getAsDouble();
    }

    private static BlockState block(String id) {
        var key = ResourceLocation.parse(id);
        if (!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalStateException("地形のブロックが未登録です: " + id);
        return BuiltInRegistries.BLOCK.get(key).defaultBlockState();
    }

    public FixedMapData map() { return map; }
    FixedUnderground underground() { return underground; }

    /** 地表の固体最上部Y。範囲外は−65。水面・建築余地を含む上限は地形に含めない。 */
    public int surfaceY(int x, int z) {
        return surfaceY(map, x, z);
    }

    static int surfaceY(FixedMapData map, int x, int z) {
        if (!MapCoordinates.contains(x, z)) return -65;
        double base = map.height(x, z);
        int water = map.waterSurface(x, z);
        double detail = map.isProtected(x, z) || (map.waterMask(x, z) & 15) != 0 ? 0
                : (TerrainMath.noise(map.seed(), "surface_detail", x, z, 192) - 0.5) * 4;
        int height = (int) Math.round(base + detail);
        if (water != Integer.MIN_VALUE) height = Math.min(height, water - 1);
        else if (map.region(x, z) != 0) height = Math.max(64, height);
        return Math.clamp(height, -63, 287);
    }

    static int biomeIndex(FixedMapData map, int x, int z, int surface) {
        if (!MapCoordinates.contains(x, z)) return 0;
        int region = map.region(x, z);
        double latitude = Math.abs(MapCoordinates.latitude(z));
        int biome = region;
        if (region == 255) biome = latitude >= 60 ? 9 : latitude < 25 ? 10 : 8;
        if (region != 0 && surface >= 210) biome = 11;
        return biome;
    }

    private int iceDepth(int x, int z, int surface) {
        if ((map.waterMask(x, z) & 14) != 0) return 0;
        double latitude = MapCoordinates.latitude(z);
        int region = map.region(x, z);
        if (latitude < 50 || (region != 3 && region != 7)) return 0;
        boolean valley = false;
        if (latitude >= 60 && MapCoordinates.contains(x - 96, z - 96) && MapCoordinates.contains(x + 96, z + 96)) {
            double surroundings = (map.height(x - 96, z) + map.height(x + 96, z)
                    + map.height(x, z - 96) + map.height(x, z + 96)) / 4;
            valley = surroundings - map.height(x, z) >= 2;
        }
        double detail = TerrainMath.noise(map.seed(), "ice_depth", x, z, 192);
        if (region == 7 && latitude >= 65) {
            return 8 + (int) ((latitude - 65) * 0.6) + Math.min(4, surface / 80) + (int) (detail * 4) + (valley ? 4 : 0);
        }
        if ((latitude < 65 && surface >= 180) || valley) return 3 + (int) (detail * 5) + Math.max(0, (surface - 180) / 24);
        return 0;
    }

    private int soil(int x, int z) {
        int local = map.category(2, x, z);
        if (local == 0 || map.isProtected(x, z) || (map.waterMask(x, z) & 14) != 0) return local;
        // 幅192の畳み込みでカテゴリの比率をつなぎ、固定ハッシュで地表材を選ぶ。
        double choice = TerrainMath.unit(map.seed(), "surface_material", x, z) * 192 * 192;
        int px = TerrainMath.nearestPixel(x), pz = TerrainMath.nearestPixel(z);
        for (int dz = -6; dz <= 6; dz++) {
            int sz = MapCoordinates.MIN + (pz + dz) * 16;
            int wz = Math.max(0, Math.min(z + 96, sz + 8) - Math.max(z - 96, sz - 8));
            for (int dx = -6; dx <= 6; dx++) {
                int sx = MapCoordinates.MIN + (px + dx) * 16;
                int wx = Math.max(0, Math.min(x + 96, sx + 8) - Math.max(x - 96, sx - 8));
                choice -= wx * wz;
                if (choice < 0) {
                    if (!MapCoordinates.contains(sx, sz) || map.region(sx, sz) == 0) return local;
                    return map.category(2, sx, sz);
                }
            }
        }
        return local;
    }

    /** 列ごとに地層境界を一度計算する。列は呼出し元専有で、全Yの照会に再利用できる。 */
    public Column column(int x, int z) {
        if (!MapCoordinates.contains(x, z)) return new Column(-65, Integer.MIN_VALUE, null, null, new int[0], null, 0, false, x, z, null);
        int surface = surfaceY(x, z);
        var profile = profiles[map.category(3, x, z)];
        double displacement = (x - profile.ax) * profile.gx + (z - profile.az) * profile.gz;
        for (var fault : profile.faults) {
            if ((x - fault.x) * fault.nx + (z - fault.z) * fault.nz >= 0) displacement += fault.displacement;
        }
        int[] boundaries = new int[profile.layers.size()];
        int previous = surface, thickness = 0;
        for (int i = 0; i < boundaries.length; i++) {
            var layer = profile.layers.get(i);
            thickness += layer.min + (int) Math.floor(TerrainMath.noise(map.seed(), layer.salt, x, z, 256) * (layer.max - layer.min + 1));
            boundaries[i] = Math.clamp((int) Math.floor(surface - thickness + displacement), -63, previous);
            previous = boundaries[i];
        }
        int soil = soil(x, z), quality = map.waterQuality(x, z);
        int ice = iceDepth(x, z, surface);
        int biome = biomeIndex(map, x, z, surface);
        boolean snow = (map.waterMask(x, z) & 14) == 0 && (ice > 0 || biome == 7 || biome == 9 || biome == 11);
        var cell = underground != null && !map.isProtected(x, z) && map.region(x, z) >= 1 && map.region(x, z) <= 7
                ? underground.cell(x, z) : null;
        return new Column(surface, map.waterSurface(x, z), soils[soil], waters[quality], boundaries, profile, ice, snow, x, z, cell);
    }

    /** 固体地表・水面と母岩を持つ不変の列。stateは生成範囲外で空気を返す。 */
    public static final class Column {
        private final int surface, waterY;
        private final BlockState soil, water;
        private final int[] boundaries;
        private final Profile profile;
        private final int ice;
        private final boolean snow;
        private final int x, z;
        private final FixedUnderground.Cell underground;

        private Column(int surface, int waterY, BlockState soil, BlockState water, int[] boundaries, Profile profile,
                       int ice, boolean snow, int x, int z, FixedUnderground.Cell underground) {
            this.surface = surface;
            this.waterY = waterY;
            this.soil = soil;
            this.water = water;
            this.boundaries = boundaries;
            this.profile = profile;
            this.ice = ice;
            this.snow = snow;
            this.x = x;
            this.z = z;
            this.underground = underground;
        }

        public int surfaceY() { return surface; }
        public int topY() { return Math.max(surface, waterY); }

        public BlockState state(int y) {
            if (profile == null || y < -64 || y > topY()) return Blocks.AIR.defaultBlockState();
            if (y == -64) return Blocks.BEDROCK.defaultBlockState();
            if (y > surface) return water;
            if (underground != null && FixedUnderground.carved(underground, x, y, z)) return Blocks.AIR.defaultBlockState();
            if (snow && y == surface) return Blocks.SNOW_BLOCK.defaultBlockState();
            if (y >= surface - ice && y < surface) return (surface - y >= 4 ? Blocks.BLUE_ICE : Blocks.PACKED_ICE).defaultBlockState();
            if (soil != null && y > surface - 3) return soil;
            var rock = rockState(y);
            return underground == null ? rock : FixedUnderground.state(underground, x, y, z, rock);
        }

        /** 土壌・水・雪氷を重ねる前の母岩。Eの母岩条件に使い、範囲外と地表より上は空気、底は岩盤を返す。 */
        public BlockState rockState(int y) {
            if (profile == null || y < -64 || y > surface) return Blocks.AIR.defaultBlockState();
            if (y == -64) return Blocks.BEDROCK.defaultBlockState();
            for (int i = 0; i < boundaries.length; i++) {
                if (y > boundaries[i]) return underground == null ? profile.layers.get(i).rock
                        : FixedUnderground.rock(underground, x, y, z, profile.layers.get(i).rock);
            }
            return underground == null ? profile.basement : FixedUnderground.rock(underground, x, y, z, profile.basement);
        }
    }
}
