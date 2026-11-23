package io.github.rs0325.ashenfrontier.world.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.world.BlockContamination;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(PistonBaseBlock.class)
public abstract class ContaminationPistonMixin {
    @WrapOperation(method = "moveBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;resolve()Z"))
    private boolean resolve(PistonStructureResolver resolver, Operation<Boolean> original, @Local(argsOnly = true) Level level,
                            @Share("ashenPositions") LocalRef<List<BlockPos>> positions,
                            @Share("ashenDestroyed") LocalRef<List<BlockPos>> destroyed,
                            @Share("ashenValues") LocalRef<Map<BlockPos, SoilContamination>> values) {
        boolean resolved = original.call(resolver);
        if (resolved && level instanceof ServerLevel server) {
            positions.set(List.copyOf(resolver.getToPush()));
            destroyed.set(List.copyOf(resolver.getToDestroy()));
            var snapshot = new HashMap<BlockPos, SoilContamination>();
            for (var pos : positions.get()) BlockContamination.get(server, pos).ifPresent(value -> snapshot.put(pos, value));
            values.set(snapshot);
        }
        return resolved;
    }

    @Inject(method = "moveBlocks", at = @At("RETURN"))
    private void moved(Level level, BlockPos pos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> callback,
                       @Share("ashenPositions") LocalRef<List<BlockPos>> positions,
                       @Share("ashenDestroyed") LocalRef<List<BlockPos>> destroyed,
                       @Share("ashenValues") LocalRef<Map<BlockPos, SoilContamination>> values) {
        if (callback.getReturnValue() && level instanceof ServerLevel server) {
            for (var target : destroyed.get()) BlockContamination.clear(server, target);
            BlockContamination.moved(server, positions.get(), values.get(), extending ? direction : direction.getOpposite());
        }
    }
}
