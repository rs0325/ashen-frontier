package io.github.rs0325.ashenfrontier.core.catalog;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 登録・データ生成・配置側が共有する自然物カタログ。IDは共有名前空間からの相対パス。 */
public final class NatureCatalog {
    private static final Document DOCUMENT = load();

    private NatureCatalog() {}

    public static List<Entry> entries() {
        return DOCUMENT.entries();
    }

    public static Map<String, String> sources() {
        return DOCUMENT.sources();
    }

    public static Map<String, FuelProfile> fuelProfiles() {
        return DOCUMENT.fuelProfiles();
    }

    /** 燃料値は品質IDごとに一度だけ定義する。未知の参照は制作エラーとして停止する。 */
    public static FuelProfile requireFuel(String id) {
        var profile = fuelProfiles().get(id);
        if (profile == null) throw new IllegalArgumentException("未登録の燃料プロファイル: " + id);
        return profile;
    }

    /** 存在しないIDは制作データの誤りとして停止する。 */
    public static Entry require(String id) {
        return entries().stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未登録の自然物: " + id));
    }

    private static Document load() {
        try (var reader = new InputStreamReader(NatureCatalog.class.getResourceAsStream("/catalog/nature.json"),
                StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, Document.class);
        } catch (IOException exception) {
            throw new IllegalStateException("自然物カタログを読めません", exception);
        }
    }

    private record Document(int version, Map<String, String> sources,
                            @SerializedName("fuel_profiles") Map<String, FuelProfile> fuelProfiles, List<Entry> entries) {}

    public record FuelProfile(Map<String, Double> values, String source) {}

    /** hardnessは採掘時間係数。hostのない行はnull。shapeは新規廃墟材の固定形状名で、既存行はnull。 */
    public record Entry(String id, String en, String ja, String kind, String material, float hardness,
                        String recovery, String behavior, String domain, @SerializedName("mass_kg") double massKg,
                        String host, String source, String color, Integer sprite, boolean surface, String quality,
                        String fuel, Map<String, Double> physical, String shape) {
        public boolean fluid() {
            return kind.equals("water") || kind.equals("oil");
        }

        public boolean block() {
            return !kind.equals("item");
        }

        public boolean blockItem() {
            return block() && !fluid();
        }

        public boolean loose() {
            return kind.equals("natural") && !id.equals("dead_tree") && !id.equals("fallen_log");
        }

        public boolean sediment() {
            return kind.equals("sediment") || kind.equals("falling_sediment") || kind.equals("mud") || layer();
        }

        public boolean layer() {
            return kind.equals("sediment_layer");
        }
    }
}
