package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import java.util.List;
import java.util.DoubleSummaryStatistics;

/** coreの履歴の読取り用索引。保存の所有者はClimateClockのまま。 */
final class WeatherTime {
    private final List<DayLengthHistory.Change> changes;
    private final double[] seconds;
    WeatherTime(DayLengthHistory history) {
        changes = history.changes();
        if(changes.getFirst().tick()!=0)throw new IllegalArgumentException("気象時計はtick 0から必要です");
        seconds = new double[changes.size()];
        var sum = new DoubleSummaryStatistics();
        for (int i = 1; i < seconds.length; i++) {
            var previous = changes.get(i-1);
            sum.accept(GameTime.gameSeconds(changes.get(i).tick()-previous.tick(), previous.ticksPerDay()));
            seconds[i] = sum.getSum();
        }
    }
    List<DayLengthHistory.Change> changes() { return changes; }
    private int index(long tick) {
        int lo=0, hi=changes.size();
        while (lo+1<hi) { int mid=(lo+hi)>>>1; if(changes.get(mid).tick()<=tick)lo=mid;else hi=mid; }
        return lo;
    }
    double seconds(long tick) {
        if(tick<0)throw new IllegalArgumentException("負の気象時刻です");
        int i=index(tick);var change=changes.get(i);
        return seconds[i]+GameTime.gameSeconds(tick-change.tick(),change.ticksPerDay());
    }
    long dayLength(long tick) { return changes.get(index(tick)).ticksPerDay(); }
    long nextChange(long tick) {
        int i=index(tick)+1;return i<changes.size()?changes.get(i).tick():Long.MAX_VALUE;
    }
}
