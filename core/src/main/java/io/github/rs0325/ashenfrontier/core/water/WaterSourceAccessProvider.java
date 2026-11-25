package io.github.rs0325.ashenfrontier.core.water;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * worldが実装するロード済み入口の解決。強制生成・残量変更を行わない。
 * aquiferの地質/取水可否、海域マスク内の指定source判定はworldの責務。
 * surfaceの現在のFluidStateと品質はサービスでも再検証する。
 */
@FunctionalInterface
public interface WaterSourceAccessProvider {
    Optional<Access> resolve(ServerLevel level, BlockPos intake, WaterSourceKey.Kind kind);

    record Access(WaterSourceKey key, boolean designatedOceanSource) {}
}
