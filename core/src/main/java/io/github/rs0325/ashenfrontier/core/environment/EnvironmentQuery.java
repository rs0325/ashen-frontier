package io.github.rs0325.ashenfrontier.core.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.Optional;

/**
 * 設備がclimateへ要求する環境区間。サーバースレッドで同期発火し、未応答は未接続を表す。
 * 提供側は要求範囲に一致する連続・重複なしの区間列を返す。積算量は各小区間だけの値。
 * 日長・天候・覆い・換気の変化は境界で分け、平均化しない。
 */
public final class EnvironmentQuery extends Event {
    private final ServerLevel level;
    private final BlockPos position;
    private final long startTick;
    private final long endTick;
    private Optional<List<EnvironmentSample>> samples = Optional.empty();

    private EnvironmentQuery(ServerLevel level, BlockPos position, long startTick, long endTick) {
        if (endTick < startTick) throw new IllegalArgumentException("環境要求の時刻が逆転しています");
        this.level = level;
        this.position = position.immutable();
        this.startTick = startTick;
        this.endTick = endTick;
    }

    public ServerLevel level() { return level; }
    public BlockPos position() { return position; }
    public long startTick() { return startTick; }
    public long endTick() { return endTick; }

    /** 単一区間を提供する。要求より広い区間の積算を流用せず、境界を正確に一致させる。 */
    public void provide(EnvironmentSample value) {
        provide(List.of(value));
    }

    /**
     * 順序付き区間列を提供する。空列・欠落・重複・逆順はIllegalArgumentException。
     * 正の要求区間にゼロ長区間は含めない。瞬時照会には積算0の区間を1件だけ返す。
     * 呼出し後のリスト変更は応答へ影響しない。
     */
    public void provide(List<EnvironmentSample> values) {
        var copy = List.copyOf(values);
        if (copy.isEmpty()) throw new IllegalArgumentException("環境区間が空です");
        long next = startTick;
        for (var value : copy) {
            if (value.startTick() != next || value.endTick() > endTick
                    || startTick < endTick && value.endTick() == value.startTick()) {
                throw new IllegalArgumentException("環境区間に欠落・重複・範囲外があります");
            }
            next = value.endTick();
        }
        if (next != endTick || startTick == endTick && copy.size() != 1) {
            throw new IllegalArgumentException("環境区間が要求範囲に一致しません");
        }
        samples = Optional.of(copy);
    }

    /** 要求範囲全体を一度に照会する。未提供の場合はemptyで、部分応答や既定環境は返さない。 */
    public static Optional<List<EnvironmentSample>> samples(ServerLevel level, BlockPos position, long from, long to) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("環境照会はサーバースレッドで行います");
        return NeoForge.EVENT_BUS.post(new EnvironmentQuery(level, position, from, to)).samples;
    }

    /**
     * 瞬時または単一区間用。複数区間の応答はemptyとなり、平均化しない。
     * 経過時間を評価する設備はsamplesを使う。
     */
    public static Optional<EnvironmentSample> sample(ServerLevel level, BlockPos position, long from, long to) {
        return samples(level, position, from, to).filter(values -> values.size() == 1).map(List::getFirst);
    }
}
