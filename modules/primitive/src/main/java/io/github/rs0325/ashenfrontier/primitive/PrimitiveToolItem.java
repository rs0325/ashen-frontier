package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.ItemProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** 木工・土用の工具。摩耗は既存成分で持ち、金属や硬岩用の等級は与えない。 */
public final class PrimitiveToolItem extends Item {
    public PrimitiveToolItem(Properties properties) { super(properties); }

    public static boolean usable(ItemStack stack) {
        var wear = stack.get(ItemProperties.WEAR);
        return wear == null || wear.value() < wear.maximum();
    }

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) { return usable(stack) ? 2 : 0; }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return usable(player.getMainHandItem());
    }

    @Override
    public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner) {
        if (!level.isClientSide && state.getDestroySpeed(level, pos) > 0) {
            var wear = stack.get(ItemProperties.WEAR);
            stack.set(ItemProperties.WEAR, ItemProperties.Wear.of(wear.value() + io.github.rs0325.ashenfrontier.core.ToolWearEvent.committed(miner,1), wear.maximum()));
        }
        return true;
    }
}
