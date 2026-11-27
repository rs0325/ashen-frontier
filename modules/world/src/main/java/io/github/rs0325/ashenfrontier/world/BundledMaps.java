package io.github.rs0325.ashenfrontier.world;

import static io.github.rs0325.ashenfrontier.world.LayoutJson.integer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** クラスパスに同梱した版だけを解決する。ワールドやデータパックから資産を上書きしない。 */
public final class BundledMaps {
    record Definitions(LandformLayout landform, HydrologyLayout hydrology) {}
    private static final Map<MapVersion, Definitions> VERIFIED = new HashMap<>();
    private static final Set<String> REQUIRED = Set.of("height.png", "region.png", "water.png", "soil.png",
            "geology.png", "coast_river.png", "index.json", "geology_profiles.json", "underground.json");
    private BundledMaps() {}

    /** 指定版のmanifestと資産を照合し、不足・不一致は版名を含む例外にする。 */
    public static synchronized void require(MapVersion version) {
        if (VERIFIED.containsKey(version)) return;
        String root = "/data/ashen_frontier/fixed_map/" + version.mapVersion() + "/";
        VERIFIED.put(version, verify(version, name -> read(root + name, version.mapVersion())));
    }

    /** earth_002の地形制作定義。未同梱・旧版には旧地形からの代替を作らない。 */
    public static synchronized LandformLayout landform(MapVersion version) {
        require(version);
        var layout = VERIFIED.get(version).landform;
        if (layout == null) throw new IllegalStateException("固定マップ版 " + version.mapVersion() + ": 地形契約を持たない旧版です");
        return layout;
    }

    /** earth_002の初期水系。採取後の現況と残水量は返さない。 */
    public static synchronized HydrologyLayout hydrology(MapVersion version) {
        require(version);
        var layout = VERIFIED.get(version).hydrology;
        if (layout == null) throw new IllegalStateException("固定マップ版 " + version.mapVersion() + ": 水系契約を持たない旧版です");
        return layout;
    }

    // 同じ検証経路へ小fixtureと破損資産を渡し、クラスパスやキャッシュで失敗例を隠さない。
    static Definitions verify(MapVersion version, Function<String, byte[]> resource) {
        try {
            if (!Set.of("earth_001", "earth_002").contains(version.mapVersion()))
                throw new IllegalStateException("未知の固定マップ版です");
            byte[] bytes = resource.apply("manifest.json");
            var manifest = json(bytes);
            if (!manifest.get("map_version").getAsString().equals(version.mapVersion())
                    || integer(manifest.get("format_version")) != version.formatVersion()
                    || version.formatVersion() != 1 || !hash(bytes).equals(version.manifestHash()))
                throw new IllegalStateException("manifestの版・形式・ハッシュが一致しません");
            var files = manifest.getAsJsonObject("files");
            if (!files.keySet().containsAll(REQUIRED))
                throw new IllegalStateException("必須レイヤー・索引が不足しています");
            boolean preview = version.isPreview();
            if (preview && (!files.has("landform.json") || !files.has("hydrology.json")
                    || integer(manifest.get("landform_schema_version")) != 1
                    || integer(manifest.get("hydrology_schema_version")) != 1))
                throw new IllegalStateException("地形・水系の必須資産またはschemaが不足・不正です");
            for (var entry : files.entrySet()) {
                if (!entry.getKey().matches("[a-z0-9_]+\\.(png|json)"))
                    throw new IllegalStateException("不正な資産名 " + entry.getKey());
                if (!hash(resource.apply(entry.getKey())).equals(entry.getValue().getAsString()))
                    throw new IllegalStateException("資産ハッシュ不一致 " + entry.getKey());
            }
            if (preview && manifest.has("stage") && manifest.get("stage").getAsString().equals("D4")) {
                var underground=json(resource.apply("underground.json"));
                if (!underground.has("d4") || integer(underground.getAsJsonObject("d4").get("schema_version"))!=1
                        || underground.getAsJsonArray("deposits").size()!=40
                        || underground.getAsJsonObject("d4").getAsJsonArray("handoffs").size()!=40)
                    throw new IllegalStateException("D4の地質・残鉱・引継ぎ契約が不足しています");
            }
            return preview ? new Definitions(
                    LandformLayout.parse(json(resource.apply("landform.json")), version.mapVersion()),
                    HydrologyLayout.parse(json(resource.apply("hydrology.json")), version.mapVersion()))
                    : new Definitions(null, null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("固定マップ版 " + version.mapVersion() + ": " + e.getMessage(), e);
        }
    }

    private static JsonObject json(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] read(String path, String version) {
        try (var stream = BundledMaps.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("固定マップ版 " + version + ": 同梱資産が不足しています " + path);
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("固定マップ版 " + version + ": 資産を読めません " + path, e);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
