package io.github.rs0325.ashenfrontier.climate;

/** NOAAの赤緯近似。経度・均時差・屈折を省き、全地域で同じ地方視太陽時を使う。 */
public record SolarPosition(double altitudeRad, double azimuthRad, double daylightFraction,
                            double horizontalProjection) {
    /** 年位相0は1月1日相当、日位相0は真夜中。位相は任意の有限値を周期化する。 */
    public static SolarPosition at(double latitudeDegrees, double yearPhase, double dayPhase) {
        if (!Double.isFinite(latitudeDegrees) || Math.abs(latitudeDegrees) > 90
                || !Double.isFinite(yearPhase) || !Double.isFinite(dayPhase)) {
            throw new IllegalArgumentException("緯度または太陽位相が不正です");
        }
        double gamma = 2 * Math.PI * (yearPhase - Math.floor(yearPhase));
        double declination = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma) + 0.001480 * Math.sin(3 * gamma);
        double latitude = Math.toRadians(latitudeDegrees);
        double hourAngle = 2 * Math.PI * (dayPhase - Math.floor(dayPhase) - 0.5);
        double a = Math.sin(latitude) * Math.sin(declination);
        double b = Math.cos(latitude) * Math.cos(declination);
        double sine = Math.clamp(a + b * Math.cos(hourAngle), -1, 1);
        double daylight = a >= b ? 1 : a <= -b ? 0 : Math.acos(-a / b) / Math.PI;
        double azimuth = Math.atan2(Math.sin(hourAngle),
                Math.cos(hourAngle) * Math.sin(latitude) - Math.tan(declination) * Math.cos(latitude)) + Math.PI;
        return new SolarPosition(Math.asin(sine), azimuth, daylight, Math.max(0, sine));
    }
}
