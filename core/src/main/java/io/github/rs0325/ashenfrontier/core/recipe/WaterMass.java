package io.github.rs0325.ashenfrontier.core.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 水量の唯一の変換境界。内部は1 mg単位のlong、保存・表示はkgのdouble。 */
public final class WaterMass {
    private static final long UNITS_PER_KG = 1_000_000;
    // kgのdoubleで保存しても1 mgを区別して往復できる範囲。
    public static final double MAX_KG = 1_000_000_000;
    static final Codec<Double> DEFINITION_KG = Codec.DOUBLE.validate(value -> {
        try {
            definitionKg(value);
            return DataResult.success(value);
        } catch (IllegalArgumentException e) {
            return DataResult.error(e::getMessage);
        }
    });

    private WaterMass() {}

    /** 保存値・APIのkg値を最寄りのmgへ丸める。ちょうど半分は切上げ。不正な範囲は例外。 */
    public static long fromKg(double kg) {
        requireRange(kg);
        return BigDecimal.valueOf(kg).movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 非負のmgをkgの保存・表示値へ変換する。演算は変換前のlongで行う。 */
    public static double toKg(long units) {
        if (units < 0 || units > (long) MAX_KG * UNITS_PER_KG) throw new IllegalArgumentException("水量が範囲外です");
        return (double) units / UNITS_PER_KG;
    }

    /** 制作データは1 mg格子上の値だけを受け、格子外は制作エラーにする。 */
    public static double definitionKg(double kg) {
        requireRange(kg);
        try {
            return toKg(BigDecimal.valueOf(kg).movePointRight(6).longValueExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("水量は1 mg（0.000001 kg）単位で指定してください", e);
        }
    }

    static boolean inRange(double kg) {
        return Double.isFinite(kg) && kg >= 0 && kg <= MAX_KG;
    }

    private static void requireRange(double kg) {
        if (!inRange(kg)) throw new IllegalArgumentException("水量は0以上10億kg以下が必要です");
    }
}
