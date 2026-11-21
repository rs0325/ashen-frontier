package io.github.rs0325.ashenfrontier.core.catalog;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 草・標本・落ちている道具材料の小さな選択範囲。生育・自然再生は行わない。 */
final class LooseNaturalBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 8, 14);

    LooseNaturalBlock(Properties properties) {
        super(properties.noCollission().noOcclusion());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
