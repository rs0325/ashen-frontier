package io.github.rs0325.ashenfrontier.world;

import static io.github.rs0325.ashenfrontier.world.LayoutJson.integer;
import static io.github.rs0325.ashenfrontier.world.LayoutJson.number;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 地域IDとは独立した地形制作契約。分類境界で地面をクランプしない。 */
public final class LandformLayout {
    public record Control(String id, int x, int z, int baseY, int controlY, double halfWidth, String role) {}
    public record Unit(int id, String kind, int baseY, int peakY, int typicalY,
                       List<HydrologyLayout.Point> boundary, List<Control> controls) {
        public Unit { boundary = List.copyOf(boundary); controls = List.copyOf(controls); }
    }
    private final Map<Integer, Unit> units;
    private final JsonObject document;

    private LandformLayout(JsonObject document) {
        this.document = document.deepCopy();
        validateDetails(document);
        var units = new HashMap<Integer, Unit>();
        var ids = new HashSet<String>();
        for (var value : document.getAsJsonArray("units")) {
            var v = value.getAsJsonObject();
            int id = integer(v, "id"), base = integer(v, "base_y"), peak = integer(v, "peak_y"), typical = integer(v, "typical_y");
            String kind = v.get("kind").getAsString();
            check(Set.of("range", "massif", "hills", "plateau", "basin", "lowland", "detail").contains(kind), "地形種別");
            check(id > 0 && base >= 64 && peak <= 520 && base <= typical && typical <= peak, "地形標高");
            var boundary = new ArrayList<HydrologyLayout.Point>();
            for (var row : v.getAsJsonArray("boundary")) {
                var a = row.getAsJsonArray();
                check(a.size() == 2, "外形座標の列数");
                int x = integer(a.get(0)), z = integer(a.get(1));
                check(MapCoordinates.contains(x, z), "外形座標の範囲");
                boundary.add(new HydrologyLayout.Point(x, z));
            }
            check(boundary.size() >= 3 || kind.equals("lowland") && boundary.isEmpty(), "外形不足");
            var controls = new ArrayList<Control>();
            for (var row : v.getAsJsonArray("pins")) {
                var c = row.getAsJsonObject(); var p = c.getAsJsonArray("xz");
                check(p.size() == 2, "制御点の列数");
                var control = new Control(c.get("id").getAsString(), integer(p.get(0)),
                        integer(p.get(1)), integer(c, "base_y"), integer(c, "control_y"),
                        number(c.get("section_half_width")), c.get("role").getAsString());
                check(ids.add(control.id) && MapCoordinates.contains(control.x, control.z)
                        && control.baseY >= 64 && control.controlY >= 64 && control.controlY <= peak
                        && Double.isFinite(control.halfWidth) && control.halfWidth >= 0 && (!control.role.equals("ridge") || control.halfWidth > 0), "制御点のID・範囲・幅");
                check(boundary.isEmpty() || HydrologyLayout.inside(boundary, control.x, control.z), "制御点が包絡外");
                check(Set.of("ridge", "surface", "valley_floor", "crater_floor", "ice_surface").contains(control.role), "制御高の役割");
                controls.add(control);
            }
            check(!controls.isEmpty() && units.putIfAbsent(id, new Unit(id, kind, base, peak, typical, boundary, controls)) == null, "地形IDの重複・制御点不足");
        }
        check(!units.isEmpty(), "地形区分不足");
        this.units = Map.copyOf(units);
    }

    /** 地形区分と制御値を検証し、不一致は版名付きIllegalStateExceptionにする。 */
    public static LandformLayout parse(JsonObject document, String mapVersion) {
        try {
            check(document.get("map_version").getAsString().equals(mapVersion) && integer(document, "format_version") == 1
                    && integer(document, "landform_schema_version") == 1, "地形の版・schema");
            return new LandformLayout(document);
        } catch (RuntimeException e) {
            throw new IllegalStateException("固定マップ版 " + mapVersion + ": 地形契約が不正です: " + e.getMessage(), e);
        }
    }

    public Map<Integer, Unit> units() { return units; }

    /** 火山・雪氷等を含む制作定義のコピー。変更してもロード済み契約には反映しない。 */
    public JsonObject document() { return document.deepCopy(); }

    private static void validateDetails(JsonObject document) {
        for (var key : List.of("volcanoes", "divides", "caldera_rim", "caldera_drain", "snowline_latitude_y"))
            check(document.has(key) && document.get(key).isJsonArray() && !document.getAsJsonArray(key).isEmpty(), "地形の必須配列: " + key);
        var volcanoIds = new HashSet<String>();
        for (var value : document.getAsJsonArray("volcanoes")) {
            var v = value.getAsJsonObject();
            check(v.get("id").isJsonPrimitive() && v.get("id").getAsJsonPrimitive().isString(), "火山IDの型");
            check(volcanoIds.add(v.get("id").getAsString()), "火山IDの重複");
            var xz = v.getAsJsonArray("xz");
            check(xz.size() == 2 && MapCoordinates.contains(integer(xz.get(0)), integer(xz.get(1))), "火山座標");
            int base = integer(v, "base_y"), rim = integer(v, "rim_y");
            check(64 <= base && base <= rim && rim <= 520 && number(v.get("width")) > 0, "火山の標高・幅");
        }
        for (var value : document.getAsJsonArray("divides")) {
            var path = value.getAsJsonArray();
            check(path.size() >= 2, "分水界の点数");
            checkPath(path, 2);
        }
        var glacier = document.getAsJsonObject("glacier");
        check(glacier.get("id").isJsonPrimitive() && glacier.get("id").getAsJsonPrimitive().isString()
                && !glacier.get("id").getAsString().isBlank(), "氷河ID");
        var points = glacier.getAsJsonArray("points");
        check(points.size() >= 2, "氷河の点数");
        checkPath(points, 5);
        for (var value : points) {
            var row = value.getAsJsonArray();
            double width = number(row.get(3)), thickness = number(row.get(4));
            check(width > 0 && thickness > 0 && thickness <= 8 && integer(row.get(2)) - thickness >= 64, "氷河の幅・厚さ");
        }
        checkPath(glacier.getAsJsonArray("moraine"), 3);
        check(glacier.getAsJsonArray("outwash").size() >= 2, "氷河排水の点数");
        checkPath(glacier.getAsJsonArray("outwash"), 3);
        check(document.getAsJsonArray("caldera_rim").size() >= 3 && document.getAsJsonArray("caldera_drain").size() >= 2, "カルデラの点数");
        checkPath(document.getAsJsonArray("caldera_rim"), 3);
        checkPath(document.getAsJsonArray("caldera_drain"), 3);
        var rules = document.getAsJsonObject("details_rules");
        for (int id = 31; id <= 40; id++) {
            var rule = rules.get(Integer.toString(id));
            check(rule != null && rule.isJsonPrimitive() && rule.getAsJsonPrimitive().isString() && !rule.getAsString().isBlank(), "細地形の規則: " + id);
        }
        double previousLatitude = 81;
        var snowline = document.getAsJsonArray("snowline_latitude_y");
        check(snowline.size() >= 2, "雪線の点数");
        for (var value : snowline) {
            var row = value.getAsJsonArray();
            check(row.size() == 2, "雪線の列数");
            double latitude = number(row.get(0)); int height = integer(row.get(1));
            check(latitude >= 0 && latitude <= 80 && latitude < previousLatitude && height >= 64 && height <= 550, "雪線の緯度・標高");
            previousLatitude = latitude;
        }
        check(integer(document, "snowline_below_40_min_y") >= 550 && number(document.get("snow_continuity_scale_blocks")) > 0, "雪線下限・連続変動幅");
        var offsets = document.getAsJsonObject("snowline_offsets_y");
        int windward = integer(offsets, "windward"), leeward = integer(offsets, "leeward");
        int north = integer(offsets, "north_facing"), south = integer(offsets, "south_facing");
        check(windward >= -20 && windward <= 0 && leeward >= 0 && leeward <= 35
                && north >= -24 && north <= 0 && south >= 0 && south <= 24, "雪線補正");
    }

    private static void checkPath(JsonArray path, int columns) {
        check(!path.isEmpty(), "地形の点列不足");
        for (var value : path) {
            var row = value.getAsJsonArray();
            check(row.size() == columns && MapCoordinates.contains(integer(row.get(0)), integer(row.get(1))), "地形の座標・列数");
            if (columns >= 3) check(integer(row.get(2)) >= 64 && integer(row.get(2)) <= 520, "地形の標高");
        }
    }

    private static void check(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
