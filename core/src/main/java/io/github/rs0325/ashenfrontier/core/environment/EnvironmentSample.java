package io.github.rs0325.ashenfrontier.core.environment;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * climateが提供する区間入力。温度K・圧力Pa・相対湿度0〜1、降水kg/m²・日射J/m²は区間の積算量。
 * startTick/endTickは単調なgameTimeの半開区間。日長変更をまたぐ区間は提供側で分割する。
 * 瞬時照会はstartTick=endTickで、降水・日射の積算を0にする。
 * rainQualityは降水時に必須。coveredは覆い、ventilatedは換気を示し、安全/疾病の判定ではない。
 * 未実装の気象の代わりになる既定値や提供者は持たない。
 */
public record EnvironmentSample(long startTick, long endTick, long ticksPerDay, double temperatureK,
                                double pressurePa, double relativeHumidity, double precipitationKgM2,
                                double solarJoulesM2, Optional<ResourceLocation> rainQuality,
                                boolean covered, boolean ventilated) {
    public EnvironmentSample {
        if (endTick < startTick || ticksPerDay <= 0 || !Double.isFinite(temperatureK) || temperatureK <= 0
                || !Double.isFinite(pressurePa) || pressurePa <= 0 || !Double.isFinite(relativeHumidity)
                || relativeHumidity < 0 || relativeHumidity > 1 || !Double.isFinite(precipitationKgM2)
                || precipitationKgM2 < 0 || !Double.isFinite(solarJoulesM2) || solarJoulesM2 < 0
                || precipitationKgM2 > 0 && rainQuality.isEmpty()
                || startTick == endTick && (precipitationKgM2 != 0 || solarJoulesM2 != 0)) {
            throw new IllegalArgumentException("環境区間の単位・範囲・降水品質が不正です");
        }
    }
}
