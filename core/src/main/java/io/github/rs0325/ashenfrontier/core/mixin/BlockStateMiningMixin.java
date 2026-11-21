package io.github.rs0325.ashenfrontier.core.mixin;

import io.github.rs0325.ashenfrontier.core.MiningRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateMiningMixin {
    @Shadow protected abstract BlockState asState();

    @Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
    private void stopZeroHardnessMining(Player player, BlockGetter level, BlockPos pos,
                                       CallbackInfoReturnable<Float> ci) {
        var state = asState();
        // 速度0÷硬さ0を計算する前に止め、破壊進行をNaNにしない。
        if (state.getDestroySpeed(level, pos) == 0 && !MiningRules.canMine(state, player.getMainHandItem())) {
            ci.setReturnValue(0.0F);
        }
    }
}
