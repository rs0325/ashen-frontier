package io.github.rs0325.ashenfrontier.climate;

/** 設定の実効境界。イベント無効化や明示的な固定気候モードを過去へ遡及させない。 */
public record WeatherSettings(long tick, int intervalTicks, int enabledEvents, boolean dynamic) {
    public WeatherSettings {
        if(tick<0 || intervalTicks<200 || intervalTicks>600 || (enabledEvents & ~511)!=0)
            throw new IllegalArgumentException("気象設定が不正です");
    }
}
