package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.ItemPropertyMath;

/** 炉の集中定数モデル。発熱量と余熱はJ、温度はK、燃焼は実秒、冷却はゲーム秒。係数は短い焼成用のゲーム設定。 */
public final class HearthHeat {
    static final double POWER_W = 120_000;
    static final double CAPACITY_J_K = 500;
    static final double COOLING_TIME_CONSTANT_GAME_SECONDS = 7200;
    static final double MAXIMUM_K = 1123;
    private double fuelJoules;
    private double heatingValueJKg;
    private double temperatureK = Double.NaN;

    public record Step(double burnedKg, double chemicalJoules, double storedJoules,
                       double processJoules, double processSeconds, double temperatureK) {}

    public double fuelJoules() { return fuelJoules; }
    public double remainingKg() { return fuelJoules == 0 ? 0 : fuelJoules / heatingValueJKg; }
    public double temperatureK() { return temperatureK; }
    public double heatingValueJKg() { return heatingValueJKg; }

    public void addFuel(double massKg, double heatingValue) {
        if (!Double.isFinite(massKg) || massKg <= 0 || !Double.isFinite(heatingValue) || heatingValue <= 0) {
            throw new IllegalArgumentException("燃料の質量・発熱量は有限の正値が必要です");
        }
        if (fuelJoules > 0 && heatingValueJKg != heatingValue) throw new IllegalArgumentException("燃焼中の異種燃料は混合しません");
        heatingValueJKg = heatingValue;
        fuelJoules += massKg * heatingValue;
    }

    /**
     * secondsは評価対象の燃焼実秒、gameSecondsは同じ区間の冷却ゲーム秒。無燃焼の遅延評価はsecondsを0にする。
     * 入力は有限の非負秒と正の気温K。戻り値は実際の燃焼・蓄熱・加工熱と適合した実秒を示す。
     */
    public Step advance(double seconds, double gameSeconds, double ambientK, boolean lit, boolean process) {
        if (!Double.isFinite(seconds) || seconds < 0 || !Double.isFinite(gameSeconds) || gameSeconds < 0 || !Double.isFinite(ambientK) || ambientK <= 0) {
            throw new IllegalArgumentException("炉の区間秒数と気温が不正です");
        }
        if (Double.isNaN(temperatureK)) temperatureK = ambientK;
        double before = temperatureK;
        double burned = lit ? Math.min(fuelJoules, POWER_W * seconds) : 0;
        double burningSeconds = burned / POWER_W;
        fuelJoules -= burned;
        double burningGameSeconds = seconds == 0 ? 0 : gameSeconds * (burningSeconds / seconds);
        if (burningGameSeconds > 0) {
            double equilibrium = ambientK + burned * 0.05 / CAPACITY_J_K
                    * (COOLING_TIME_CONSTANT_GAME_SECONDS / burningGameSeconds);
            temperatureK = ItemPropertyMath.cool(before, equilibrium, burningGameSeconds, COOLING_TIME_CONSTANT_GAME_SECONDS);
        } else {
            temperatureK += burned * 0.05 / CAPACITY_J_K;
        }
        if (burned > 0) temperatureK = Math.min(MAXIMUM_K, temperatureK);
        temperatureK = ItemPropertyMath.cool(temperatureK, ambientK, gameSeconds - burningGameSeconds, COOLING_TIME_CONSTANT_GAME_SECONDS);
        double delivered = process && before >= 973 && temperatureK >= 973 ? burned * 0.15 : 0;
        return new Step(burned == 0 ? 0 : burned / heatingValueJKg, burned,
                CAPACITY_J_K * (temperatureK - before), delivered, delivered == 0 ? 0 : burningSeconds, temperatureK);
    }

    void restore(double fuelJ, double heatingValue, double kelvin) {
        if (!Double.isFinite(fuelJ) || fuelJ < 0 || fuelJ > 0 && (!Double.isFinite(heatingValue) || heatingValue <= 0)
                || !Double.isNaN(kelvin) && (!Double.isFinite(kelvin) || kelvin <= 0)) {
            throw new IllegalArgumentException("保存した炉の熱量が不正です");
        }
        fuelJoules = fuelJ;
        heatingValueJKg = heatingValue;
        temperatureK = kelvin;
    }
}
