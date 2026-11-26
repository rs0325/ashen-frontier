package io.github.rs0325.ashenfrontier.core.environment;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** 実providerを使う世界の追加処理が共有する協調的tick予算。停止/GCの壁時計上限ではない。 */
public final class EnvironmentBudget {
    public static final long TARGET_NANOS=300_000;
    private static final Map<ServerLevel,Boolean> ACTIVE=new WeakHashMap<>();
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final class State { int tick;long used; }
    private EnvironmentBudget() {}
    public static void activate(ServerLevel level) { ACTIVE.put(level,Boolean.TRUE); }
    public static void deactivate(ServerLevel level) { ACTIVE.remove(level); }
    /** サーバーPre tickで呼ぶ。試験も同じ境界で測定フレームを開始する。 */
    public static void beginTick(MinecraftServer server) {
        var state=STATES.computeIfAbsent(server,s->new State());state.tick=server.getTickCount();state.used=0;
    }
    private static State state(MinecraftServer server) {
        var value=STATES.computeIfAbsent(server,s->new State());
        if(value.tick!=server.getTickCount()) { value.tick=server.getTickCount();value.used=0; }
        return value;
    }
    public static long remaining(ServerLevel level) {
        return ACTIVE.containsKey(level)?Math.max(0,TARGET_NANOS-state(level.getServer()).used):Long.MAX_VALUE;
    }
    /** この予算が所有する処理の実行時間だけを加える。親子で二重に計上しない。 */
    public static void charge(ServerLevel level,long nanos) {
        if(ACTIVE.containsKey(level))state(level.getServer()).used+=nanos;
    }
}
