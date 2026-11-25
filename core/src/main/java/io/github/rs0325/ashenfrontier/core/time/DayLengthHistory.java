package io.github.rs0325.ashenfrontier.core.time;

import java.util.ArrayList;
import java.util.List;

/**
 * 単調なgameTime上の日長変更履歴。変更時刻以降だけに新しい日長を適用する。
 * climateが保存したchangesから復元する。壁時計・dayTime・既定の気候提供者は使わない。
 */
public final class DayLengthHistory {
    public record Change(long tick, long ticksPerDay) {
        public Change {
            if (ticksPerDay <= 0) throw new IllegalArgumentException("日長は正値が必要です");
        }
    }

    public record Interval(long startTick, long endTick, long ticksPerDay) {}
    private final List<Change> changes;

    public DayLengthHistory(long startTick, long ticksPerDay) {
        this(List.of(new Change(startTick, ticksPerDay)));
    }

    /** 保存済みの昇順履歴を復元する。空・同時刻・逆順はIllegalArgumentException。 */
    public DayLengthHistory(List<Change> changes) {
        if (changes.isEmpty()) throw new IllegalArgumentException("日長履歴が空です");
        this.changes = new ArrayList<>();
        for (var change : changes) {
            if (!this.changes.isEmpty() && change.tick() <= this.changes.getLast().tick()) {
                throw new IllegalArgumentException("日長変更は時刻の昇順で記録します");
            }
            this.changes.add(change);
        }
    }

    /** 設定変更をその実効時刻に追記する。既存時刻を上書きしない。 */
    public void change(long tick, long ticksPerDay) {
        var value = new Change(tick, ticksPerDay);
        if (tick <= changes.getLast().tick()) throw new IllegalArgumentException("過去の日長履歴は変更できません");
        changes.add(value);
    }

    /** 保存用の不変スナップショット。環境の履歴と同じ所有者が永続化する。 */
    public List<Change> changes() { return List.copyOf(changes); }

    /** 履歴の開始より前や逆転した要求は拒否する。ゼロ長はその時刻の状態を返す。 */
    public List<Interval> intervals(long from, long to) {
        if (from < changes.getFirst().tick() || to < from) throw new IllegalArgumentException("日長履歴の要求範囲が不正です");
        int index = 0;
        while (index + 1 < changes.size() && changes.get(index + 1).tick() <= from) index++;
        var result = new ArrayList<Interval>();
        long start = from;
        while (index + 1 < changes.size() && changes.get(index + 1).tick() < to) {
            long end = changes.get(index + 1).tick();
            result.add(new Interval(start, end, changes.get(index).ticksPerDay()));
            start = end;
            index++;
        }
        result.add(new Interval(start, to, changes.get(index).ticksPerDay()));
        return List.copyOf(result);
    }
}
