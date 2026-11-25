package io.github.rs0325.ashenfrontier.climate;

/** US Standard Atmosphere 1976の下層。仮想標高を近似的にジオポテンシャル高度とみなす。 */
public final class StandardAtmosphere {
    private static final double G_OVER_R = 9.80665 / 287.05287;
    private StandardAtmosphere() {}

    /** 固定マップの建築範囲に対応する-4775～12825 mだけを受け付け、Paを返す。 */
    public static double pressurePa(double elevationM) {
        requireElevation(elevationM);
        double p11 = 101325 * Math.pow(216.65 / 288.15, G_OVER_R / 0.0065);
        return elevationM <= 11000
                ? 101325 * Math.pow((288.15 - 0.0065 * elevationM) / 288.15, G_OVER_R / 0.0065)
                : p11 * Math.exp(-G_OVER_R * (elevationM - 11000) / 216.65);
    }

    /** 海面からの温度差K。11 km以上では下部成層圏の等温層として扱う。 */
    public static double temperatureOffsetK(double elevationM) {
        requireElevation(elevationM);
        return -0.0065 * Math.min(elevationM, 11000);
    }

    private static void requireElevation(double value) {
        if (!Double.isFinite(value) || value < -4775 || value > 12825) {
            throw new IllegalArgumentException("固定マップの大気モデル範囲外です");
        }
    }
}
