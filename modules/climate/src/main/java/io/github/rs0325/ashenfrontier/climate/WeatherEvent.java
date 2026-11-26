package io.github.rs0325.ashenfrontier.climate;

public enum WeatherEvent {
    SANDSTORM, HEATWAVE, DROUGHT, ACID_RAIN, BLIZZARD, COLD_WAVE, THUNDERSTORM, WINDSTORM, FOG;
    public int bit() { return 1 << ordinal(); }
    public boolean destructive() { return this != FOG; }
}
