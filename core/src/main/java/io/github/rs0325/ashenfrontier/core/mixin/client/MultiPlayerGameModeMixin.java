package io.github.rs0325.ashenfrontier.core.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Shadow private boolean isDestroying;

    @Inject(method = "sameDestroyTarget", at = @At("HEAD"), cancellable = true)
    private void restartStoppedMining(BlockPos pos, CallbackInfoReturnable<Boolean> ci) {
        // 中断前の対象・道具へ戻っても、継続ではなくSTARTを送り直す。
        if (!isDestroying) ci.setReturnValue(false);
    }
}
