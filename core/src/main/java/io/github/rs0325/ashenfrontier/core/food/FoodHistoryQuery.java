package io.github.rs0325.ashenfrontier.core.food;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import java.util.Optional;

/**
 * 保管所有者への食品履歴の要求。所有者が記録した保管位置・保持区間で評価したコピーだけを返す。
 * 現在位置で未観測の過去を補わない。未応答・履歴欠落は未確認のままで消費しない。
 * 提供側は共有予算100µs以下・最大16区間の継続仕事を使い、未完了なら次の要求まで保持する。
 */
public final class FoodHistoryQuery extends Event {
    private final ServerPlayer player;
    private final ItemStack source;
    private final long through;
    private Optional<ItemStack> value = Optional.empty();
    private Optional<String> reason=Optional.empty();
    private FoodHistoryQuery(ServerPlayer player, ItemStack source, long through) {
        this.player = player; this.source = source.copy(); this.through = through;
    }
    public ServerPlayer player() { return player; }
    public ItemStack source() { return source.copy(); }
    public long through() { return through; }
    public Optional<ItemStack> value() { return value; }
    public Optional<String> reason() { return reason; }
    public void reject(String reason) { this.reason=Optional.of(reason); }
    public void provide(ItemStack evaluated) {
        if (evaluated.getItem() != source.getItem() || evaluated.getCount() != source.getCount())
            throw new IllegalArgumentException("履歴応答が元の可食部と一致しません");
        var before=source.copy();var after=evaluated.copy();
        before.remove(io.github.rs0325.ashenfrontier.core.ItemProperties.ROT);
        after.remove(io.github.rs0325.ashenfrontier.core.ItemProperties.ROT);
        if(!ItemStack.isSameItemSameComponents(before,after))throw new IllegalArgumentException("腐敗の履歴応答が栄養・由来・汚染を変更しています");
        var old=source.get(io.github.rs0325.ashenfrontier.core.ItemProperties.ROT);
        var rot=evaluated.get(io.github.rs0325.ashenfrontier.core.ItemProperties.ROT);
        if(old==null||rot==null||rot.createdAt()!=old.createdAt()||rot.lastCalculatedAt()!=through||rot.referenceSeconds()<old.referenceSeconds())
            throw new IllegalArgumentException("腐敗の履歴応答が若返り・時刻不一致を含みます");
        value = Optional.of(evaluated.copy());
    }
    public static Optional<ItemStack> query(ServerPlayer player, ItemStack source, long through) {
        return request(player,source,through).value;
    }
    public static FoodHistoryQuery request(ServerPlayer player,ItemStack source,long through) {
        return NeoForge.EVENT_BUS.post(new FoodHistoryQuery(player,source,through));
    }
}
