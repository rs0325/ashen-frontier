package io.github.rs0325.ashenfrontier.climate;

/** 気象・室内補正を含まない固定気候の純粋計算。 */
public final class ClimateModel {
    public static final int MODEL_VERSION = 1;
    public static final double WARMING_K = 4;
    public static final int DAYS_PER_YEAR = 24;
    private ClimateModel() {}

    /** 緯度に伴う架空の海面年平均K。温暖化をここだけで加算する。 */
    public static double annualSeaLevelK(double latitude, ClimateProfile profile) {
        double sine = Math.sin(Math.toRadians(latitude));
        return 300.15 - 35 * sine * sine + profile.annualOffsetK() + WARMING_K;
    }

    /** 日長履歴を積算したゲーム秒を用い、南半球では季節項だけを反転する。 */
    public static double temperatureK(double latitude, double elevationM, double gameSeconds, ClimateProfile profile) {
        double days = gameSeconds / 86400;
        double season = -Math.cos(2 * Math.PI * (days / DAYS_PER_YEAR - 0.04))
                * Math.sin(Math.toRadians(latitude)) * profile.seasonAmplitudeK();
        double diurnal = -Math.cos(2 * Math.PI * (days + 0.125)) * profile.dayAmplitudeK();
        return annualSeaLevelK(latitude, profile) + season + diurnal
                + StandardAtmosphere.temperatureOffsetK(elevationM);
    }

    /** gameTime=0を地方視太陽時6時とする。年位相は24ゲーム日に圧縮する。 */
    public static SolarPosition sun(double latitude, double gameSeconds) {
        double days = gameSeconds / 86400;
        return SolarPosition.at(latitude, days / DAYS_PER_YEAR, days + 0.25);
    }
}
