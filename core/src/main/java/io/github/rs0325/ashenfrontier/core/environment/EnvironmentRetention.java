package io.github.rs0325.ashenfrontier.core.environment;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** C06への保持所有権の通知。未接続はempty、幾何の準備完了とは別の応答。 */
public final class EnvironmentRetention extends Event {
    public enum Action { RETAIN, COMMIT, END, RELEASE }
    private final ServerLevel level;
    private final UUID owner;
    private final long generation,from,tick,sequence;
    private final BlockPos position;
    private final Action action;
    private Optional<Boolean> result=Optional.empty();
    private EnvironmentRetention(ServerLevel level,UUID owner,long generation,BlockPos position,long from,long tick,long sequence,Action action) {
        this.level=level;this.owner=owner;this.generation=generation;this.position=position.immutable();
        this.from=from;this.tick=tick;this.sequence=sequence;this.action=action;
    }
    public ServerLevel level() { return level; }
    public UUID owner() { return owner; }
    public long generation() { return generation; }
    public BlockPos position() { return position; }
    public long from() { return from; }
    public long tick() { return tick; }
    public long sequence() { return sequence; }
    public Action action() { return action; }
    public void provide(boolean complete) { result=Optional.of(complete); }
    /**
     * サーバースレッドから保持所有権を通知する。幾何の探索・環境応答の完成は別途照会する。
     * ownerは設備の発生源UUID、generationは1から連続発行する登録世代。
     * RETAINのpositionは空気位置、fromは必要期間の開始。未観測の過去へ遡って登録できない。
     * COMMITは評価と熱通知を確定したtick/sequence、ENDは以後追加しない末尾を宣言する。
     * RELEASEはENDまでCOMMITした後に行い、未精算/解放整理中はfalse。成功・終了済み再送はtrue、未接続はempty。
     * 受理された解放整理はC06に保存され、呼出し元のロードに依存せず進む。
     * 同じ世代のRETAIN再送は同じ位置/開始で行う。解放整理中/解放済み世代のRETAINはfalseで、再作成しない。
     * 世代の欠落、逆転する時刻/通知番号、不整合な再送はIllegalArgumentException。
     */
    public static Optional<Boolean> request(ServerLevel level,UUID owner,long generation,BlockPos position,long from,long tick,long sequence,Action action) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("保持操作はサーバースレッド専用です");
        return NeoForge.EVENT_BUS.post(new EnvironmentRetention(level,owner,generation,position,from,tick,sequence,action)).result;
    }
}
