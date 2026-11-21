package io.github.rs0325.ashenfrontier.core.catalog;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 中空の外装のみ。流体や電力を蓄積・接続しない。 */
final class RelicCasingBlock extends Block {
    private static final VoxelShape SHAPE = Shapes.or(box(0, 0, 0, 3, 16, 16), box(13, 0, 0, 16, 16, 16),
            box(3, 0, 0, 13, 3, 16), box(3, 13, 0, 13, 16, 16));

    RelicCasingBlock(Properties properties) { super(properties); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
}
