package io.github.rs0325.ashenfrontier.world.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.rs0325.ashenfrontier.world.BlockContamination;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FallingBlockEntity.class)
public abstract class ContaminationFallingMixin {
    @WrapMethod(method = "fall")
    private static FallingBlockEntity start(Level level, BlockPos pos, BlockState state, Operation<FallingBlockEntity> original) {
        if (!(level instanceof ServerLevel server) || !BlockContamination.isTarget(state)) return original.call(level, pos, state);
        var value = BlockContamination.get(server, pos).orElseThrow();
        var entity = original.call(level, pos, state);
        BlockContamination.falling(server, pos, entity, value);
        return entity;
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean land(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        boolean placed = original.call(level, pos, state, flags);
        if (placed && level instanceof ServerLevel server)
            BlockContamination.landed(server, pos, (FallingBlockEntity) (Object) this);
        return placed;
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;spawnAtLocation(Lnet/minecraft/world/level/ItemLike;)Lnet/minecraft/world/entity/item/ItemEntity;"))
    private ItemEntity drop(FallingBlockEntity entity, ItemLike item, Operation<ItemEntity> original) {
        var dropped = original.call(entity, item);
        BlockContamination.fallingDrop(entity, dropped);
        return dropped;
    }
}
