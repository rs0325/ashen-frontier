package io.github.rs0325.ashenfrontier.core.food;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** 通常摂取のサーバー側取引。STARTで手元を固定し、FINISHだけが確定する。未接続なら消費しない。 */
public final class FoodUse extends Event {
    public enum Action { START, FINISH, CANCEL }
    private final ServerPlayer player;
    private final InteractionHand hand;
    private final Action action;
    private long operation;
    private boolean accepted;
    private FoodUse(ServerPlayer player, InteractionHand hand, long operation, Action action) {
        this.player = player; this.hand = hand; this.operation=operation; this.action = action;
    }
    public ServerPlayer player() { return player; }
    public InteractionHand hand() { return hand; }
    public Action action() { return action; }
    public long operation() { return operation; }
    public void assign(long operation) { this.operation=operation; }
    public void accept() { accepted = true; }
    public static boolean request(ServerPlayer player, InteractionHand hand, Action action) {
        return request(player,hand,0,action).accepted;
    }
    /** 新しい操作を開始し、保存された本人別の投与番号を返す。未接続/拒否ならempty。 */
    public static java.util.OptionalLong begin(ServerPlayer player,InteractionHand hand) {
        var event=request(player,hand,0,Action.START);
        return event.accepted?java.util.OptionalLong.of(event.operation):java.util.OptionalLong.empty();
    }
    /** 外部呼出し元はbeginの番号を保存して再送する。0は通常アイテムの現在使用だけを指す。 */
    public static FoodUse request(ServerPlayer player, InteractionHand hand, long operation, Action action) {
        if(operation<0)throw new IllegalArgumentException("摂取の投与番号が不正です");
        if (!player.server.isSameThread()) throw new IllegalStateException("摂取はサーバースレッド専用です");
        return NeoForge.EVENT_BUS.post(new FoodUse(player, hand, operation, action));
    }
    public boolean accepted() { return accepted; }
}
