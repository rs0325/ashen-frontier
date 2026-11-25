package io.github.rs0325.ashenfrontier.core.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/**
 * M4が実際に燃焼・余熱評価した区間をM3へ通知する。質量kg、化学発熱量J、炉へ留保した熱J。
 * M3が燃料profileと燃焼質量から排煙を評価し、放出熱（化学発熱量－留保熱）を環境へ接続する。
 * 余熱の冷却では燃焼質量と化学発熱量は0、留保熱は負となり、既存の蓄熱を放出する。
 * 病原性・CO濃度・室内安全をM4で仮定しない。サーバースレッド上の通知で、取消不可。
 */
public final class CombustionEvent extends Event {
    private final ServerLevel level;
    private final BlockPos position;
    private final long startTick;
    private final long endTick;
    private final ResourceLocation fuelProfile;
    private final double burnedKg;
    private final double energyJoules;
    private final double retainedHeatJoules;

    public CombustionEvent(ServerLevel level, BlockPos position, long startTick, long endTick,
                           ResourceLocation fuelProfile, double burnedKg, double energyJoules, double retainedHeatJoules) {
        this.level = level;
        this.position = position.immutable();
        this.startTick = startTick;
        this.endTick = endTick;
        this.fuelProfile = fuelProfile;
        this.burnedKg = burnedKg;
        this.energyJoules = energyJoules;
        this.retainedHeatJoules = retainedHeatJoules;
    }

    public ServerLevel level() { return level; }
    public BlockPos position() { return position; }
    public long startTick() { return startTick; }
    public long endTick() { return endTick; }
    public ResourceLocation fuelProfile() { return fuelProfile; }
    public double burnedKg() { return burnedKg; }
    public double energyJoules() { return energyJoules; }
    public double retainedHeatJoules() { return retainedHeatJoules; }
}
