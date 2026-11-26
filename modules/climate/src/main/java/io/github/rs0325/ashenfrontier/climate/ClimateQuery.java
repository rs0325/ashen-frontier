package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import io.github.rs0325.ashenfrontier.world.FixedMapChunkGenerator;
import io.github.rs0325.ashenfrontier.world.MapCoordinates;
import io.github.rs0325.ashenfrontier.world.MapVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Optional;

/** 固定気候だけの読取りAPI。降水・屋内・換気を仮定したEnvironmentSampleは提供しない。 */
public final class ClimateQuery {
    public record Interval(long startTick, long endTick, long ticksPerDay,
                           FixedClimate.Baseline endState) {}
    private ClimateQuery() {}
    /** 登録期限等へ既存の保存時計を渡す。未対応ワールドは空。 */
    public static java.util.OptionalDouble elapsedGameSeconds(ServerLevel level,long tick) {
        if(mapVersion(level).isEmpty())return java.util.OptionalDouble.empty();
        if(tick<0||tick>level.getGameTime())throw new IllegalArgumentException("気候時計の要求時刻が不正です");
        return java.util.OptionalDouble.of(ClimateClock.get(level).gameSecondsAt(tick));
    }


    /** 固定マップoverworldだけに対応する。別次元・別生成器は空を返す。 */
    public static Optional<MapVersion> mapVersion(ServerLevel level) {
        return mapVersion(level.dimension(), level.getChunkSource().getGenerator());
    }

    static Optional<MapVersion> mapVersion(ResourceKey<Level> dimension, ChunkGenerator source) {
        return dimension == Level.OVERWORLD
                && source instanceof FixedMapChunkGenerator generator
                ? Optional.of(generator.mapVersion()) : Optional.empty();
    }

    /** 現在までの履歴のみを要求できる。範囲外は空、不正な時刻・高さは例外。 */
    public static Optional<FixedClimate.Baseline> sample(ServerLevel level, BlockPos pos, long tick) {
        return intervals(level, pos, tick, tick).map(rows -> rows.getFirst().endState());
    }

    /**
     * 日長変更境界で分割した半開区間を返す。endStateは各末尾の瞬間値であり、区間平均ではない。
     * #135が気象境界と統合してEnvironmentQueryへ接続する。全期間を一つの値で埋めない。
     */
    public static Optional<List<Interval>> intervals(ServerLevel level, BlockPos pos, long from, long to) {
        var version = mapVersion(level);
        if (version.isEmpty() || !MapCoordinates.contains(pos.getX(), pos.getZ())) return Optional.empty();
        if (from < 0 || to < from || to > level.getGameTime()) {
            throw new IllegalArgumentException("保存済み気候時計の要求時刻が不正です");
        }
        var clock = ClimateClock.get(level);
        var climate = FixedClimate.require(version.orElseThrow());
        var history = clock.history();
        if (from == to) {
            var interval = history.intervals(from, to).getFirst();
            return Optional.of(List.of(new Interval(from, to, interval.ticksPerDay(),
                    climate.sample(pos.getX(), pos.getY(), pos.getZ(), clock.gameSecondsAt(to)))));
        }
        var elapsed = new DoubleSummaryStatistics();
        var result = new ArrayList<Interval>();
        for (var interval : history.intervals(0, to)) {
            elapsed.accept(GameTime.gameSeconds(interval.endTick() - interval.startTick(), interval.ticksPerDay()));
            if (interval.endTick() > from) result.add(new Interval(
                    Math.max(from, interval.startTick()), interval.endTick(), interval.ticksPerDay(),
                    climate.sample(pos.getX(), pos.getY(), pos.getZ(), elapsed.getSum())));
        }
        return Optional.of(List.copyOf(result));
    }

    /** GAME/REAL換算と同じ日長境界を後続の提供者へ渡す。未対応次元は空。 */
    public static Optional<DayLengthHistory> dayLengthHistory(ServerLevel level) {
        return mapVersion(level).map(version -> ClimateClock.get(level).history());
    }
}
