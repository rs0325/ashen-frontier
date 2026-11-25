package io.github.rs0325.ashenfrontier.core.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Optional;

/**
 * 設備がclimateへ要求する区間入力。応答がなければ未接続であり、設備はその区間を進めない。
 * 提供側は気温・気圧・湿度・覆い・換気等をEnvironmentSampleの単位で設定する。
 * サーバースレッドで同期発火し、サンプルは要求区間を覆う必要がある。
 */
public final class EnvironmentQuery extends Event {
    private final ServerLevel level;
    private final BlockPos position;
    private final long startTick;
    private final long endTick;
    private Optional<EnvironmentSample> sample = Optional.empty();

    private EnvironmentQuery(ServerLevel level, BlockPos position, long startTick, long endTick) {
        this.level = level;
        this.position = position.immutable();
        this.startTick = startTick;
        this.endTick = endTick;
    }

    public ServerLevel level() { return level; }
    public BlockPos position() { return position; }
    public long startTick() { return startTick; }
    public long endTick() { return endTick; }

    public void provide(EnvironmentSample value) {
        if (value.startTick() > startTick || value.endTick() < endTick) {
            throw new IllegalArgumentException("環境入力が要求区間を覆っていません");
        }
        sample = Optional.of(value);
    }

    public static Optional<EnvironmentSample> sample(ServerLevel level, BlockPos position, long from, long to) {
        return NeoForge.EVENT_BUS.post(new EnvironmentQuery(level, position, from, to)).sample;
    }
}
