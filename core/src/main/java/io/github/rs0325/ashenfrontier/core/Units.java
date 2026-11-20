package io.github.rs0325.ashenfrontier.core;

import java.util.Locale;

/** SI値を設定中の表示単位と接頭辞で書式化する。 */
public final class Units {
    private Units() {}

    /** 値を3桁ごとのSI接頭辞で表示する。単位文字列は接頭辞の後ろに付ける。 */
    public static String format(double value, String unit) {
        double magnitude = Math.abs(value);
        if (magnitude == 0 || !Double.isFinite(magnitude)) {
            return String.format(Locale.ROOT, "%.2f %s", value, unit);
        }
        String prefix = "";
        double scaled = value;
        if (magnitude >= 1_000_000_000) { scaled /= 1_000_000_000; prefix = "G"; }
        else if (magnitude >= 1_000_000) { scaled /= 1_000_000; prefix = "M"; }
        else if (magnitude >= 1_000) { scaled /= 1_000; prefix = "k"; }
        else if (magnitude < 0.001) { scaled *= 1_000_000; prefix = "µ"; }
        else if (magnitude < 1) { scaled *= 1_000; prefix = "m"; }
        return String.format(Locale.ROOT, "%.2f %s%s", scaled, prefix, unit);
    }

    public static String temperature(double kelvin, boolean useKelvin) {
        return temperature(kelvin, useKelvin, useKelvin ? "K" : "°C");
    }

    static String temperature(double kelvin, boolean useKelvin, String unit) {
        return String.format(Locale.ROOT, "%.2f %s", useKelvin ? kelvin : kelvin - 273.15,
                unit);
    }

    public static String pressure(double pascals, boolean usePascal) {
        return pressure(pascals, usePascal, usePascal ? "Pa" : "bar");
    }

    static String pressure(double pascals, boolean usePascal, String unit) {
        return usePascal ? format(pascals, unit) : String.format(Locale.ROOT, "%.2f %s", pascals / 100_000, unit);
    }
}
