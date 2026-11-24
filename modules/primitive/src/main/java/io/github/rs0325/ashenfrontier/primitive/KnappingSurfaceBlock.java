package io.github.rs0325.ashenfrontier.primitive;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 入出力は右クリック、稼働中だけ20tick間隔で更新する台石。 */
public final class KnappingSurfaceBlock extends Block implements EntityBlock {
    private static final VoxelShape SHAPE = box(0, 0, 0, 16, 4, 16);
    public KnappingSurfaceBlock(Properties properties) { super(properties); }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new KnappingSurfaceEntity(pos, state); }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            ((KnappingSurfaceEntity) level.getBlockEntity(pos)).interact(serverPlayer, hand, hit.getDirection());
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            ((KnappingSurfaceEntity) level.getBlockEntity(pos)).interact(serverPlayer, InteractionHand.MAIN_HAND, hit.getDirection());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof KnappingSurfaceEntity entity) entity.work();
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level.getBlockEntity(pos) instanceof KnappingSurfaceEntity entity) entity.dropContents();
        super.onRemove(state, level, pos, next, moving);
    }
}
