package io.github.rs0325.ashenfrontier.core.food;

import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.ItemPropertyMath;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;

/** 保持所有者が保存・継続する腐敗積算。未確定の元アイテムは変更せず、丸めは完成時に1回だけ行う。 */
public final class FoodAging {
    private final long created, from;
    private long through;
    private double reference;
    public FoodAging(ItemProperties.Rot rot) {
        created = rot.createdAt(); from = rot.lastCalculatedAt(); through = from; reference = rot.referenceSeconds();
        if (created < 0 || from < created || !Double.isFinite(reference) || reference < 0)
            throw new IllegalArgumentException("腐敗履歴が不正です");
    }
    public long through() { return through; }
    /**
     * C06で保持済みの固定保管位置を進める。移送前にこの仕事を確定し、移送後の現在位置で過去を補わない。
     * 1回100µs以下の協調期限・最大16区間。emptyは完了、非emptyは提供側の未完了/欠落理由。
     * 所有者は同じtargetまで再開し、HISTORY_MISSINGなら未確認として終了する。
     */
    public java.util.Optional<String> work(ServerLevel level, BlockPos position, long target) {
        if(target<through)throw new IllegalArgumentException("腐敗評価の末尾が逆転しています");
        long start=System.nanoTime();long deadline=start+Math.min(100_000,EnvironmentBudget.remaining(level));long ownedNanos=0;
        try {
            for(int n=0;n<16&&through<target&&System.nanoTime()<deadline;n++) {
                var request=EnvironmentQuery.query(level,position,through,Math.min(target,through+600));
                if(request.values().isEmpty())return request.reason().or(()->java.util.Optional.of("intake.ashen_frontier.rot_history"));
                // 要求末尾までの小区間が多い場合も、先頭1区間だけを確定し次回の始点を進める。
                long calculationStart=System.nanoTime();add(request.values().orElseThrow().getFirst());
                ownedNanos+=System.nanoTime()-calculationStart;
            }
            return through==target?java.util.Optional.empty():java.util.Optional.of("intake.ashen_frontier.history_pending");
        } finally { EnvironmentBudget.charge(level,ownedNanos); }
    }
    /** 連続した小区間を1つ加える。C06の近似応答も呼出し側で拒否せず、この温度で積算する。 */
    public void add(EnvironmentSample sample) {
        if (sample.startTick() != through || sample.endTick() <= through) throw new IllegalArgumentException("腐敗履歴に欠落・重複があります");
        reference = ItemPropertyMath.rotProgress(reference, GameTime.gameSeconds(sample.endTick()-through, sample.ticksPerDay()),
                sample.temperatureK(), ItemProperties.ROT_REFERENCE_K, ItemProperties.ROT_Q10);
        through = sample.endTick();
    }
    public ItemProperties.Rot finish(long expectedThrough) {
        if (through != expectedThrough) throw new IllegalStateException("腐敗の区間が未完了です");
        return new ItemProperties.Rot(created, through, Math.ceil(reference / 600) * 600);
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("format", 1); tag.putLong("created", created);
        tag.putLong("from", from); tag.putLong("through", through); tag.putDouble("reference", reference); return tag;
    }
    public static FoodAging load(CompoundTag tag) {
        if (!tag.contains("format", Tag.TAG_INT) || tag.getInt("format") != 1 || !tag.contains("created", Tag.TAG_LONG)
                || !tag.contains("from", Tag.TAG_LONG) || !tag.contains("through", Tag.TAG_LONG) || !tag.contains("reference", Tag.TAG_DOUBLE))
            throw new IllegalArgumentException("腐敗の継続保存が不正です");
        var value = new FoodAging(new ItemProperties.Rot(tag.getLong("created"), tag.getLong("from"), tag.getDouble("reference")));
        value.through = tag.getLong("through");
        if (value.through < value.from) throw new IllegalArgumentException("腐敗の継続時刻が逆転しています");
        return value;
    }
    /** 物理減衰だけを日長履歴で評価する。未知核種をCsとして計算しない。丸めは行わない。 */
    public static double activity(ItemProperties.Radioactivity value, long through, DayLengthHistory history) {
        if (!value.nuclide().equals("ashen_frontier:cs137") || value.gameTime() > through || !Double.isFinite(value.becquerels()) || value.becquerels() < 0)
            throw new IllegalArgumentException("核種・記録時刻・放射能が未確認です");
        return ItemPropertyMath.activity(value.becquerels(), GameTime.gameSeconds(value.gameTime(), through, history), IntakeRules.CS137_HALF_LIFE);
    }
}
