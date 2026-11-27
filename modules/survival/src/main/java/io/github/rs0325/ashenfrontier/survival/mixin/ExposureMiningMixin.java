package io.github.rs0325.ashenfrontier.survival.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.rs0325.ashenfrontier.world.BlockContamination;
import io.github.rs0325.ashenfrontier.survival.AshenFrontierSurvival;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayerGameMode.class)
public abstract class ExposureMiningMixin {
    @Shadow protected ServerLevel level;
    @Shadow @Final protected ServerPlayer player;

    @WrapMethod(method = "removeBlock")
    private boolean mined(BlockPos pos, BlockState state, boolean canHarvest, Operation<Boolean> original) {
        boolean mineral = AshenFrontierSurvival.miningTarget(level, pos, state);
        var soil = mineral ? BlockContamination.get(level, pos).orElse(null) : null;
        boolean removed = original.call(pos, state, canHarvest);
        if (removed && mineral) {
            AshenFrontierSurvival.mined(player, soil);
        }
        return removed;
    }
}
