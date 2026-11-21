package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** 版ごとに一度だけ読み込む配置図。配列と索引は構築後に変更・公開しない。 */
public final class FixedMapData {
    private static final Map<MapVersion, FixedMapData> LOADED = new HashMap<>();
    private static final int SIZE = 2048;
    private final short[] heights = new short[SIZE * SIZE];
    private final byte[][] categories = new byte[5][SIZE * SIZE];
    private final short[] waterSurface = new short[SIZE * SIZE];
    private final boolean[] protectedArea = new boolean[SIZE * SIZE];
    private final JsonObject geology;
    private final long seed;

    /** ハッシュ照合後にロードする。不正な画像・索引は版名を含む例外で停止する。 */
    public static synchronized FixedMapData require(MapVersion version) {
        return LOADED.computeIfAbsent(version, FixedMapData::new);
    }

    private FixedMapData(MapVersion version) {
        BundledMaps.require(version);
        String root = "/data/ashen_frontier/fixed_map/" + version.mapVersion() + "/";
        try {
            seed = json(root + "manifest.json").get("fixed_seed").getAsLong();
            String[] names = {"height", "region", "water", "soil", "geology", "coast_river"};
            for (int layer = 0; layer < names.length; layer++) {
                try (var stream = FixedMapData.class.getResourceAsStream(root + names[layer] + ".png")) {
                    var image = ImageIO.read(stream);
                    if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE
                            || image.getRaster().getNumBands() != 1
                            || image.getSampleModel().getSampleSize(0) != (layer == 0 ? 16 : 8)) {
                        throw new IllegalStateException("不正な配置図: " + names[layer]);
                    }
                    var raster = image.getRaster();
                    for (int z = 0; z < SIZE; z++) {
                        for (int x = 0; x < SIZE; x++) {
                            int value = raster.getSample(x, z, 0), at = z * SIZE + x;
                            if (layer == 0) {
                                if (value > 383) throw new IllegalStateException("標高が範囲外です");
                                heights[at] = (short) (value - 64);
                            } else categories[layer - 1][at] = (byte) value;
                        }
                    }
                    image.flush();
                }
            }
            var index = json(root + "index.json");
            geology = json(root + "geology_profiles.json");
            if (!geology.get("map_version").getAsString().equals(version.mapVersion())
                    || geology.get("format_version").getAsInt() != version.formatVersion()
                    || !geology.get("profile_version").equals(index.get("geology_profile_version"))) {
                throw new IllegalStateException("地質定義の版が一致しません");
            }
            Arrays.fill(waterSurface, Short.MIN_VALUE);
            for (var entry : index.getAsJsonArray("water_bodies")) {
                var body = entry.getAsJsonObject();
                int bit = body.get("membership_bit").getAsInt();
                if (body.has("surface_y")) {
                    for (int i = 0; i < waterSurface.length; i++) {
                        if ((Byte.toUnsignedInt(categories[4][i]) & bit) != 0) waterSurface[i] = body.get("surface_y").getAsShort();
                    }
                } else {
                    for (var value : body.getAsJsonArray("surface_spans_y_x0_x1ex_y")) {
                        var span = value.getAsJsonArray();
                        for (int x = span.get(1).getAsInt(); x < span.get(2).getAsInt(); x++) {
                            waterSurface[span.get(0).getAsInt() * SIZE + x] = span.get(3).getAsShort();
                        }
                    }
                }
            }
            for (int i = 0; i < heights.length; i++) {
                int region = Byte.toUnsignedInt(categories[0][i]);
                int water = Byte.toUnsignedInt(categories[1][i]), soil = Byte.toUnsignedInt(categories[2][i]);
                if ((region > 7 && region != 255) || water > 8 || soil > 5
                        || !geology.getAsJsonObject("profiles").has(Integer.toString(Byte.toUnsignedInt(categories[3][i])))) {
                    throw new IllegalStateException("未定義の配置図カテゴリです");
                }
                if ((Byte.toUnsignedInt(categories[4][i]) & 14) != 0
                        && (water == 0 || waterSurface[i] == Short.MIN_VALUE)) {
                    throw new IllegalStateException("水域の品質・水面が不足しています");
                }
            }
            for (var value : index.getAsJsonArray("facilities")) {
                var facility = value.getAsJsonObject();
                var xz = facility.getAsJsonArray("xz");
                markProtection(xz.get(0).getAsDouble(), xz.get(1).getAsDouble(),
                        xz.get(0).getAsDouble(), xz.get(1).getAsDouble(), facility.get("protection_radius").getAsDouble());
            }
            for (var value : index.getAsJsonArray("routes")) {
                var route = value.getAsJsonObject();
                var points = route.getAsJsonArray("points");
                for (int i = 1; i < points.size(); i++) {
                    var a = points.get(i - 1).getAsJsonArray();
                    var b = points.get(i).getAsJsonArray();
                    markProtection(a.get(0).getAsDouble(), a.get(1).getAsDouble(), b.get(0).getAsDouble(),
                            b.get(1).getAsDouble(), route.get("protection_radius").getAsDouble());
                }
            }
            smoothRegionHeights();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("固定マップ版 " + version.mapVersion() + ": 配置図を読めません", e);
        }
    }

    private void smoothRegionHeights() {
        byte[] distance = new byte[heights.length];
        Arrays.fill(distance, (byte) 6);
        for (int z = 1; z < SIZE - 1; z++) for (int x = 1; x < SIZE - 1; x++) {
            int i = z * SIZE + x, region = Byte.toUnsignedInt(categories[0][i]);
            if (region == 0) continue;
            for (int offset : new int[]{-1, 1, -SIZE, SIZE}) {
                int neighbor = Byte.toUnsignedInt(categories[0][i + offset]);
                if (neighbor != 0 && neighbor != region) distance[i] = 0;
            }
        }
        for (int z = 1; z < SIZE; z++) for (int x = 1; x < SIZE; x++) {
            int i = z * SIZE + x;
            distance[i] = (byte) Math.min(distance[i], 1 + Math.min(distance[i - 1], distance[i - SIZE]));
        }
        for (int z = SIZE - 2; z >= 0; z--) for (int x = SIZE - 2; x >= 0; x--) {
            int i = z * SIZE + x;
            distance[i] = (byte) Math.min(distance[i], 1 + Math.min(distance[i + 1], distance[i + SIZE]));
        }
        short[] original = heights.clone();
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            int i = z * SIZE + x;
            if (distance[i] >= 6 || protectedArea[i] || categories[4][i] != 0 || categories[0][i] == 0) continue;
            double sum = 0;
            int count = 0;
            for (int dz = -6; dz <= 6; dz++) for (int dx = -6; dx <= 6; dx++) {
                int nx = x + dx, nz = z + dz;
                if (nx < 0 || nx >= SIZE || nz < 0 || nz >= SIZE) continue;
                int at = nz * SIZE + nx;
                if (categories[4][at] == 0 && categories[0][at] != 0) { sum += original[at]; count++; }
            }
            double weight = 1 - distance[i] / 6.0;
            heights[i] = (short) Math.round(original[i] + (sum / count - original[i]) * weight);
        }
    }

    private void markProtection(double ax, double az, double bx, double bz, double radius) {
        // 最近傍セルの全域が保護円柱・経路幅を覆うよう半対角長を加える。
        double r = radius + 12, dx = bx - ax, dz = bz - az, length = dx * dx + dz * dz;
        int x0 = Math.max(0, TerrainMath.pixel((int) Math.floor(Math.min(ax, bx) - r)));
        int x1 = Math.min(SIZE - 1, TerrainMath.pixel((int) Math.ceil(Math.max(ax, bx) + r)));
        int z0 = Math.max(0, TerrainMath.pixel((int) Math.floor(Math.min(az, bz) - r)));
        int z1 = Math.min(SIZE - 1, TerrainMath.pixel((int) Math.ceil(Math.max(az, bz) + r)));
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                double px = MapCoordinates.MIN + 16 * x - ax, pz = MapCoordinates.MIN + 16 * z - az;
                double t = length == 0 ? 0 : Math.clamp((px * dx + pz * dz) / length, 0, 1);
                if (Math.pow(px - t * dx, 2) + Math.pow(pz - t * dz, 2) <= r * r) protectedArea[z * SIZE + x] = true;
            }
        }
    }

    private static JsonObject json(String path) throws IOException {
        try (var stream = FixedMapData.class.getResourceAsStream(path)) {
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private int at(int x, int z) {
        return TerrainMath.nearestPixel(z) * SIZE + TerrainMath.nearestPixel(x);
    }

    long seed() { return seed; }
    JsonObject geology() { return geology.deepCopy(); }

    /** 範囲外は0。範囲内は最近傍の地域ID（0〜7、255）を返す。 */
    public int region(int x, int z) { return category(0, x, z); }
    int category(int layer, int x, int z) {
        return MapCoordinates.contains(x, z) ? Byte.toUnsignedInt(categories[layer][at(x, z)]) : 0;
    }

    /** 海・河道・湖のbit。範囲外は0で、海域として扱わない。 */
    public int waterMask(int x, int z) { return category(4, x, z); }

    /** 水質対応表のID。乾燥・範囲外は0。 */
    public int waterQuality(int x, int z) { return category(1, x, z); }

    /** 土壌5段階のID。0は露岩・未割当て・範囲外で、境界の材料混合前の値。 */
    public int soilQuality(int x, int z) { return category(2, x, z); }

    /** geology_profiles.jsonを参照するID。範囲外は0なのでcontainsと併用する。 */
    public int geologyProfile(int x, int z) { return category(3, x, z); }

    /** 指定水域の水面ブロックY。水域以外はInteger.MIN_VALUE。 */
    public int waterSurface(int x, int z) {
        return (waterMask(x, z) & 14) == 0 ? Integer.MIN_VALUE : waterSurface[at(x, z)];
    }

    /** 施設・経路の水平保護域。16ブロック格子の被覆により最大24ブロック広く保護する。 */
    public boolean isProtected(int x, int z) {
        return MapCoordinates.contains(x, z) && protectedArea[at(x, z)];
    }

    /** 範囲内の標高Yを双線形補間する。最終画素の外挿はせず、端の値を延長する。 */
    public double height(int x, int z) {
        if (!MapCoordinates.contains(x, z)) throw new IllegalArgumentException("配置図の範囲外です");
        int px = TerrainMath.pixel(x), pz = TerrainMath.pixel(z);
        int nx = Math.min(SIZE - 1, px + 1), nz = Math.min(SIZE - 1, pz + 1);
        return TerrainMath.bilinear(heights[pz * SIZE + px], heights[pz * SIZE + nx],
                heights[nz * SIZE + px], heights[nz * SIZE + nx], TerrainMath.fraction(x), TerrainMath.fraction(z));
    }
}
