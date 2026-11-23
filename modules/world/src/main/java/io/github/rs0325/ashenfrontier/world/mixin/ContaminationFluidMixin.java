package io.github.rs0325.ashenfrontier.world.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.rs0325.ashenfrontier.world.BlockContamination;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FlowingFluid.class)
public abstract class ContaminationFluidMixin {
    @WrapMethod(method = "spreadTo")
    private void spread(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState fluid, Operation<Void> original) {
        original.call(level, pos, state, direction, fluid);
        if (level instanceof ServerLevel server && BlockContamination.isTarget(state)
                && !level.getBlockState(pos).is(state.getBlock())) BlockContamination.clear(server, pos);
    }
}
