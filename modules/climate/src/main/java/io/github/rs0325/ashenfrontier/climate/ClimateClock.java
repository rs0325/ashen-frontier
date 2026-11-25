package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** サーバーのgameTimeを唯一の時刻軸とする、日長履歴の保存所有者。 */
public final class ClimateClock extends SavedData {
    static final String ID = "ashen_frontier_climate_clock";
    private final DayLengthHistory history;
    private long revision;

    ClimateClock(long ticksPerDay) { this(new DayLengthHistory(0, ticksPerDay)); }
    private ClimateClock(DayLengthHistory history) { this.history = history;revision=history.changes().size()-1; }
    long revision() { return revision; }

    static ClimateClock get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(() -> {
            var data = new ClimateClock(AshenFrontierClimate.TICKS_PER_DAY.get());
            data.setDirty();
            return data;
        }, (tag, registries) -> load(tag), null), ID);
    }

    /** 履歴は変更できないスナップショットで返す。後続の環境区間列はこの境界で分割する。 */
    public DayLengthHistory history() { return new DayLengthHistory(history.changes()); }

    /** 過去の設定を遡及変更せず、開始時からのGAME秒を返す。 */
    public double gameSecondsAt(long tick) { return GameTime.gameSeconds(0, tick, history); }

    /** 同一tickの再変更は次のtickまで保留し、既に評価した履歴を書き換えない。 */
    boolean changeDayLength(long tick, long value) {
        var last = history.changes().getLast();
        if (last.ticksPerDay() == value) return true;
        if (tick <= last.tick()) return false;
        history.change(tick, value);
        revision++;
        setDirty();
        return true;
    }

    static ClimateClock load(CompoundTag tag) {
        if (tag.getInt("format") != 1 || tag.getInt("model_version") != ClimateModel.MODEL_VERSION || !tag.contains("changes", Tag.TAG_LIST)) {
            throw new IllegalStateException("気候時計の保存形式が不正です");
        }
        var rows = tag.getList("changes", Tag.TAG_COMPOUND);
        var changes = new java.util.ArrayList<DayLengthHistory.Change>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.getCompound(i);
            if (!row.contains("tick", Tag.TAG_LONG) || !row.contains("ticks_per_day", Tag.TAG_LONG)) {
                throw new IllegalStateException("日長履歴の項目が不正です");
            }
            changes.add(new DayLengthHistory.Change(row.getLong("tick"), row.getLong("ticks_per_day")));
        }
        if (changes.isEmpty() || changes.getFirst().tick() != 0) {
            throw new IllegalStateException("日長履歴の開始が不正です");
        }
        return new ClimateClock(new DayLengthHistory(changes));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", 1);
        tag.putInt("model_version", ClimateModel.MODEL_VERSION);
        var rows = new ListTag();
        for (var change : history.changes()) {
            var row = new CompoundTag();
            row.putLong("tick", change.tick());
            row.putLong("ticks_per_day", change.ticksPerDay());
            rows.add(row);
        }
        tag.put("changes", rows);
        return tag;
    }
}
