package io.github.rs0325.ashenfrontier.core.time;

public final class GameTime {
    public static final long TICKS_PER_DAY = 24_000;
    private static final double SECONDS_PER_DAY = 86_400.0;
    private static final double REAL_SECONDS_PER_TICK = 0.05;

    private GameTime() {}

    public static double realSeconds(long elapsedTicks) {
        return elapsedTicks * REAL_SECONDS_PER_TICK;
    }

    public static double gameSeconds(long elapsedTicks) {
        return gameSeconds(elapsedTicks, TICKS_PER_DAY);
    }

    public static double gameSeconds(long elapsedTicks, long ticksPerDay) {
        return elapsedTicks * (SECONDS_PER_DAY / ticksPerDay);
    }

}
