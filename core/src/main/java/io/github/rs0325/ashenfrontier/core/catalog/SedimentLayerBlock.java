package io.github.rs0325.ashenfrontier.core.catalog;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/** 雪と同じ積層形状を使う、融けない薄い堆積物。 */
public final class SedimentLayerBlock extends SnowLayerBlock {
    private static final MapCodec<SnowLayerBlock> CODEC = simpleCodec(SedimentLayerBlock::new);

    public SedimentLayerBlock(Properties properties) { super(properties); }

    @Override
    public MapCodec<SnowLayerBlock> codec() { return CODEC; }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        var below = level.getBlockState(pos.below());
        return Block.isFaceFull(below.getBlockSupportShape(level, pos.below()), Direction.UP);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {}
}
