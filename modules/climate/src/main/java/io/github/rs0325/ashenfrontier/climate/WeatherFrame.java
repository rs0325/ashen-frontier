package io.github.rs0325.ashenfrontier.climate;

/** 一つの論理stepの開始状態。配列は気象エンジンの所有で、公開後は変更しない。 */
public final class WeatherFrame {
    static final int P = 0, T = 1, Q = 2, U = 3, V = 4, CLOUD = 5, RAIN = 6, SNOW = 7,
            DUST = 8, POLLUTION = 9, EVENTS = 10, SEVERITY = 11, FIELDS = 12, CELLS = 4096;
    final double[] values;
    private final long tick, step;
    WeatherFrame(long tick, long step, double[] values) {
        if (values.length != CELLS * FIELDS || tick < 0 || step < 0) throw new IllegalArgumentException("気象状態の寸法・時刻が不正です");
        this.tick = tick; this.step = step; this.values = values;
    }
    public long tick() { return tick; }
    public long step() { return step; }
    double get(int cell, int field) { return values[cell * FIELDS + field]; }
    public WeatherState cell(int cell) {
        if (cell < 0 || cell >= CELLS) throw new IllegalArgumentException("気象セルが範囲外です");
        int at = cell * FIELDS;
        return new WeatherState(values[at], values[at+1], values[at+2], values[at+3], values[at+4],
                values[at+5], values[at+6], values[at+7], values[at+8], values[at+9], (int)values[at+10], values[at+11]);
    }
}
