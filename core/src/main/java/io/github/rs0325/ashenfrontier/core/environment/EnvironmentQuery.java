package io.github.rs0325.ashenfrontier.core.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.Optional;

/**
 * 設備がclimateへ要求する環境区間。サーバースレッドで同期発火し、未応答は未接続を表す。
 * 全区間要求には要求範囲に一致する連続・重複なしの列、先頭要求には同じ先頭1区間を返す。積算量は各小区間だけの値。
 * 日長・天候・覆い・換気の変化は境界で分け、平均化しない。
 */
public final class EnvironmentQuery extends Event {
    private final ServerLevel level;
    private final BlockPos position;
    private final long startTick;
    private final long endTick;
    private final boolean firstOnly;
    private Optional<List<EnvironmentSample>> samples = Optional.empty();
    private Optional<String> reason=Optional.empty();
    public Optional<List<EnvironmentSample>> values() { return samples; }
    public Optional<String> reason() { return reason; }
    /** 未計算等の停止理由を翻訳キーで通知する。成功区間は変更しない。 */
    public void pending(String key) { reason=Optional.of(key); }


    private EnvironmentQuery(ServerLevel level, BlockPos position, long startTick, long endTick, boolean firstOnly) {
        if (endTick < startTick) throw new IllegalArgumentException("環境要求の時刻が逆転しています");
        this.level = level;
        this.position = position.immutable();
        this.startTick = startTick;
        this.endTick = endTick;
        this.firstOnly = firstOnly;
    }

    public ServerLevel level() { return level; }
    public BlockPos position() { return position; }
    public long startTick() { return startTick; }
    public long endTick() { return endTick; }
    /** trueなら同じ全区間応答の先頭1区間だけを要求する。末尾より先を平均化しない。 */
    public boolean firstOnly() { return firstOnly; }

    /** 単一区間を提供する。要求より広い区間の積算を流用せず、境界を正確に一致させる。 */
    public void provide(EnvironmentSample value) {
        provide(List.of(value));
    }

    /**
     * 順序付き区間列を提供する。空列・欠落・重複・逆順はIllegalArgumentException。
     * 正の要求区間にゼロ長区間は含めない。瞬時照会には積算0の区間を1件だけ返す。
     * 先頭要求では1区間の確定接頭部、または従来の完全列を受け付け、先頭1件だけを保持する。
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
        if (next != endTick && !(firstOnly && copy.size() == 1) || startTick == endTick && copy.size() != 1) {
            throw new IllegalArgumentException("環境区間が要求範囲に一致しません");
        }
        samples = Optional.of(firstOnly ? List.of(copy.getFirst()) : copy);
    }

    /** 要求範囲全体を一度に照会する。未提供の場合はemptyで、部分応答や既定環境は返さない。 */
    public static Optional<List<EnvironmentSample>> samples(ServerLevel level, BlockPos position, long from, long to) {
        return query(level,position,from,to).samples;
    }

    /** 理由付きの同じ同期照会。未計算時は区間が空のままで、呼出し側が再試行する。 */
    public static EnvironmentQuery query(ServerLevel level,BlockPos position,long from,long to) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("環境照会はサーバースレッドで行います");
        return NeoForge.EVENT_BUS.post(new EnvironmentQuery(level,position,from,to,false));
    }

    /**
     * 全区間応答の先頭と同じ値・同じ末尾の1区間を要求する。正長要求の応答はfromから始まりto以下で終わる。
     * 未計算・未観測ではvaluesが空で、提供側の停止理由をreasonで返す。未接続は両方空。
     * 消費側は返された末尾だけを確定し、残りを次回照会する。サーバースレッド専用。
     */
    public static EnvironmentQuery queryFirst(ServerLevel level,BlockPos position,long from,long to) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("環境照会はサーバースレッドで行います");
        return NeoForge.EVENT_BUS.post(new EnvironmentQuery(level,position,from,to,true));
    }

    /**
     * 瞬時または単一区間用。複数区間の応答はemptyとなり、平均化しない。
     * 経過時間を評価する設備はsamplesを使う。
     */
    public static Optional<EnvironmentSample> sample(ServerLevel level, BlockPos position, long from, long to) {
        return samples(level, position, from, to).filter(values -> values.size() == 1).map(List::getFirst);
    }
}
