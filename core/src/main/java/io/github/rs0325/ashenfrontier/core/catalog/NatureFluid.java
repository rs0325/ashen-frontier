package io.github.rs0325.ashenfrontier.core.catalog;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

/** 異品質の流体による上書きを止める。混合・精製は後続工程で明示的に扱う。 */
final class NatureFluid {
    private NatureFluid() {}

    static final class Source extends BaseFlowingFluid.Source {
        Source(Properties properties) {
            super(properties);
        }

        @Override
        protected boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos, Fluid fluid, Direction direction) {
            return false;
        }
    }

    static final class Flowing extends BaseFlowingFluid.Flowing {
        Flowing(Properties properties) {
            super(properties);
        }

        @Override
        protected boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos, Fluid fluid, Direction direction) {
            return false;
        }
    }
}
