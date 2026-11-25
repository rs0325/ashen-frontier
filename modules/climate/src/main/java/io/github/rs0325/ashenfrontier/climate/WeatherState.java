package io.github.rs0325.ashenfrontier.climate;

/**
 * セル平均の気象。気圧/温度は基準からのPa/K偏差、水蒸気はkg/m²、風はm/s、
 * 雨雪は水当量kg/(m² GAME秒)、粒子はkg/m³。covered/ventilatedや健康判定を含まない。
 */
public record WeatherState(double pressureAnomalyPa, double temperatureAnomalyK, double vaporKgM2,
                           double windXMps, double windZMps, double cloudFraction,
                           double rainKgM2GameSecond, double snowKgM2GameSecond,
                           double dustKgM3, double pollutionKgM3, int events, double severity) {
    public boolean contaminatedRain() { return (events & WeatherEvent.ACID_RAIN.bit()) != 0 || pollutionKgM3 > 60e-9; }
}
