package io.github.rs0325.ashenfrontier.world.mixin.client;

import io.github.rs0325.ashenfrontier.world.MapClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMapMixin {
    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void markMapChunk(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        MapClient.blockChanged((ClientLevel) (Object) this, pos);
    }
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void dirtyMapChunk(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        MapClient.blockChanged((ClientLevel) (Object) this, pos);
    }
}
