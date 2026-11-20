package io.github.rs0325.ashenfrontier.core;

import io.github.rs0325.ashenfrontier.core.time.GameTime;

public final class ItemPropertyMath {
    private ItemPropertyMath() {}

    public static double cool(double initialK, double ambientK, double elapsedSeconds, double timeConstantSeconds) {
        return ambientK + (initialK - ambientK) * Math.exp(-elapsedSeconds / timeConstantSeconds);
    }

    public static double activity(double initialBq, double elapsedSeconds, double halfLifeSeconds) {
        return initialBq * Math.pow(0.5, elapsedSeconds / halfLifeSeconds);
    }

    public static double rotProgress(double referenceSeconds, double elapsedGameSeconds, double temperatureK, double referenceK, double q10) {
        return referenceSeconds + elapsedGameSeconds
                * Math.pow(q10, (temperatureK - referenceK) / 10.0);
    }

    public static double roundDown(double value, double step) { return Math.floor(value / step) * step; }

    public static double roundUp(double value, double step) { return Math.ceil(value / step) * step; }

    public static long floorTime(long gameTime, long step) { return Math.floorDiv(gameTime, step) * step; }
}
