package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.ItemPropertyMath;

/** 水4 kgの集中定数モデル。比熱・潜熱は代表値、沸点は一定潜熱のClausius–Clapeyron近似。 */
final class BoilingHeat {
    static final double CAPACITY_J_K = 4 * 4180;
    static final double LATENT_J_KG = 2_257_000;
    static final double EVAPORATION_J = 0.2 * LATENT_J_KG;
    private double temperatureK = Double.NaN;
    private double reservedJoules;
    private double sensibleJoules;

    record Step(double hotSeconds, double vaporJoules, double absorbedJoules, double temperatureK,
                boolean interrupted) {}

    static double boilingK(double pressurePa) {
        if (!Double.isFinite(pressurePa) || pressurePa <= 0) throw new IllegalArgumentException("気圧は有限の正のPaが必要です");
        return 1 / (1 / 373.15 - 461.526 / LATENT_J_KG * Math.log(pressurePa / 101325));
    }

    static double holdSeconds(double virtualElevationM) { return virtualElevationM > 2000 ? 180 : 60; }

    double temperatureK() { return temperatureK; }
    double reservedJoules() { return reservedJoules; }
    double sensibleJoules() { return sensibleJoules; }

    /** 未確定の潜熱を負の留保熱として返す。槽の顕熱と水量は変えない。 */
    double releaseVapor() {
        double released = -reservedJoules;
        reservedJoules = 0;
        return released;
    }

    /** 移送で無効になった温度モデルの留保熱を精算する。 */
    double reset() {
        double released = -sensibleJoules + releaseVapor();
        complete();
        return released;
    }

    /** 完成品と確定した蒸発へ引き渡した熱は、再放出しない。 */
    void complete() {
        temperatureK = Double.NaN;
        reservedJoules = 0;
        sensibleJoules = 0;
    }

    double cool(double gameSeconds, double ambientK) {
        if (!Double.isFinite(temperatureK)) return 0;
        double before = temperatureK;
        temperatureK = ItemPropertyMath.cool(temperatureK, ambientK, gameSeconds, CAPACITY_J_K);
        double retainedJ = CAPACITY_J_K * (temperatureK - before);
        sensibleJoules += retainedJ;
        return retainedJ;
    }

    /** 実供給Jの予熱分を引いてから連続保持秒と未留保分の潜熱を返す。余剰熱は留保しない。 */
    Step advance(double seconds, double gameSeconds, double ambientK, double pressurePa, double suppliedJ) {
        if (Double.isNaN(temperatureK)) temperatureK = ambientK;
        double before = temperatureK;
        double boiling = boilingK(pressurePa);
        boolean interrupted = temperatureK < boiling;
        double releasedJ = interrupted ? releaseVapor() : 0;
        if (boiling <= 273.16 || boiling >= 647.096 || temperatureK <= 273.15) {
            double retainedJ = cool(gameSeconds, ambientK);
            return new Step(0, 0, retainedJ + releasedJ, temperatureK, true);
        }
        double fraction = 1;
        double decay = gameSeconds / CAPACITY_J_K;
        if (decay == 0) {
            double needed = Math.max(0, (boiling - temperatureK) * CAPACITY_J_K);
            temperatureK = Math.min(boiling, temperatureK + suppliedJ / CAPACITY_J_K);
            if (suppliedJ > 0) fraction = Math.min(1, needed / suppliedJ);
        } else {
            double equilibrium = ambientK + suppliedJ / gameSeconds;
            double end = equilibrium + (temperatureK - equilibrium) * Math.exp(-decay);
            if (suppliedJ > 0 && equilibrium > boiling && end >= boiling) {
                fraction = temperatureK >= boiling ? 0
                        : -Math.log((boiling - equilibrium) / (temperatureK - equilibrium)) / decay;
            }
            temperatureK = Math.min(boiling, end);
        }
        fraction = Math.clamp(fraction, 0, 1);
        double generatedJ = Math.max(0, (suppliedJ - (boiling - ambientK) * gameSeconds) * (1 - fraction));
        double vaporJ = Math.min(EVAPORATION_J - reservedJoules, generatedJ);
        reservedJoules += vaporJ;
        double sensibleJ = CAPACITY_J_K * (temperatureK - before);
        sensibleJoules += sensibleJ;
        return new Step(seconds * (1 - fraction), vaporJ, sensibleJ + vaporJ + releasedJ, temperatureK, interrupted);
    }

    void restore(double kelvin, double reservedJ, double sensibleJ) {
        if (!Double.isNaN(kelvin) && (!Double.isFinite(kelvin) || kelvin <= 0)
                || !Double.isFinite(reservedJ) || reservedJ < 0 || reservedJ > EVAPORATION_J
                || !Double.isFinite(sensibleJ)) throw new IllegalArgumentException("保存した水温・留保熱が不正です");
        temperatureK = kelvin;
        reservedJoules = reservedJ;
        sensibleJoules = sensibleJ;
    }
}
