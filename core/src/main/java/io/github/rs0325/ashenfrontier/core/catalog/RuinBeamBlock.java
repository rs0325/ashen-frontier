package io.github.rs0325.ashenfrontier.core.catalog;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class RuinBeamBlock extends RotatedPillarBlock {
    final RuinGeometry geometry;
    private final VoxelShape[] shapes = new VoxelShape[3];

    RuinBeamBlock(RuinGeometry geometry, Properties properties) {
        super(properties);
        this.geometry = geometry;
        for (var axis : Direction.Axis.values()) shapes[axis.ordinal()] = geometry.shape(geometry.along(axis));
    }

    @Override public MapCodec<? extends RotatedPillarBlock> codec() {
        return simpleCodec(properties -> new RuinBeamBlock(geometry, properties));
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes[state.getValue(AXIS).ordinal()];
    }
}
