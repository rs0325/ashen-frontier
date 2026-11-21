package io.github.rs0325.ashenfrontier.core.mixin;

import io.github.rs0325.ashenfrontier.core.MiningRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {
    @Shadow @Final protected ServerPlayer player;
    @Shadow protected ServerLevel level;
    @Shadow private boolean isDestroyingBlock;
    @Shadow private boolean hasDelayedDestroy;
    @Shadow private BlockPos destroyPos;
    @Shadow private BlockPos delayedDestroyPos;
    @Shadow private int lastSentState;

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void ignoreStoppedMining(BlockPos pos, Action action, Direction face, int maxBuildHeight,
                                     int sequence, CallbackInfo ci) {
        if (action == Action.STOP_DESTROY_BLOCK && !isDestroyingBlock && !hasDelayedDestroy) ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void stopUnsuitableMining(CallbackInfo ci) {
        if (player.isCreative()) return;
        // 遅延破壊も中断し、不適正工具で経過したtickを持ち替え後に利用させない。
        if (isDestroyingBlock && !MiningRules.canMine(level.getBlockState(destroyPos), player.getMainHandItem())) {
            isDestroyingBlock = false;
            level.destroyBlockProgress(player.getId(), destroyPos, -1);
            lastSentState = -1;
        }
        if (hasDelayedDestroy && !MiningRules.canMine(level.getBlockState(delayedDestroyPos), player.getMainHandItem())) {
            hasDelayedDestroy = false;
            level.destroyBlockProgress(player.getId(), delayedDestroyPos, -1);
            lastSentState = -1;
        }
    }
}
