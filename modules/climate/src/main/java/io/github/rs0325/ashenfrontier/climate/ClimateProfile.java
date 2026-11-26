package io.github.rs0325.ashenfrontier.climate;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** 架空地域の海面基準値。温度は産業革命前相当で、温暖化・標高補正を含めない。 */
public record ClimateProfile(double annualOffsetK, double seasonAmplitudeK, double dayAmplitudeK,
                             double relativeHumidity, double annualPrecipitationKgM2,
                             double summerRainFraction) {
    private static final Map<Integer, ClimateProfile> PROFILES = load();

    public static ClimateProfile forRegion(int region) {
        var value = PROFILES.get(region);
        if (value == null) throw new IllegalArgumentException("未定義の気候地域です: " + region);
        return value;
    }

    private static Map<Integer, ClimateProfile> load() {
        String path = "/data/ashen_frontier_climate/climate/regions.json";
        try (var stream = ClimateProfile.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("地域気候表がありません");
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var result = new HashMap<Integer, ClimateProfile>();
            for (var entry : root.entrySet()) {
                var row = entry.getValue().getAsJsonObject();
                var value = new ClimateProfile(row.get("annual_offset_k").getAsDouble(),
                        row.get("season_amplitude_k").getAsDouble(), row.get("day_amplitude_k").getAsDouble(),
                        row.get("relative_humidity").getAsDouble(), row.get("annual_precipitation_kg_m2").getAsDouble(),
                        row.get("summer_rain_fraction").getAsDouble());
                if (value.seasonAmplitudeK < 0 || value.dayAmplitudeK < 0
                        || !(value.relativeHumidity >= 0 && value.relativeHumidity <= 1)
                        || !(value.annualPrecipitationKgM2 >= 0)
                        || !(value.summerRainFraction >= 0 && value.summerRainFraction <= 1)) {
                    throw new IllegalStateException("地域気候表の数値が不正です");
                }
                result.put(Integer.parseInt(entry.getKey()), value);
            }
            if (!result.keySet().equals(java.util.Set.of(0, 1, 2, 3, 4, 5, 6, 7, 255))) {
                throw new IllegalStateException("全地域の気候表が必要です");
            }
            // 初期地域を通年の凍結・乾燥にしない。降雨そのものは#134が決める。
            var initial = result.get(1);
            double minimum = ClimateModel.annualSeaLevelK(40, initial) - initial.seasonAmplitudeK - initial.dayAmplitudeK;
            if (minimum < 278.15 || initial.annualPrecipitationKgM2 < 400) {
                throw new IllegalStateException("初期地域の栽培・水確保を恒常的に妨げる気候表です");
            }
            return Map.copyOf(result);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("地域気候表を読めません", e);
        }
    }
}
