package io.github.rs0325.ashenfrontier.survival;

/** 係数が変わったtickで区間を分け、同時活動を加算しない。 */
public enum Activity {
    REST(1, -8, 10), LIGHT(1.2, 2, 2), WALK(2, 4, 2),
    HEAVY(3, 8, -3), RUN(6, 12, -8), SLEEP(.7, -12, 15);
    final double met, fatiguePerHour, staminaPerSecond;
    Activity(double met, double fatigue, double stamina) {
        this.met=met;fatiguePerHour=fatigue;staminaPerSecond=stamina;
    }
}
