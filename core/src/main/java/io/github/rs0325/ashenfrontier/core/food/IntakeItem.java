package io.github.rs0325.ashenfrontier.core.food;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/** バニラの満腹度を加えず、coreの摂取受付を使う既存食品・密封水容器。 */
public final class IntakeItem extends Item {
    private final boolean water;
    public IntakeItem(Properties properties, boolean water) { super(properties); this.water = water; }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server && !FoodUse.request(server, hand, FoodUse.Action.START))
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }
    @Override public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof ServerPlayer player) {
            var hand = player.getUsedItemHand();
            FoodUse.request(player, hand, FoodUse.Action.FINISH);
            return player.getItemInHand(hand);
        }
        return stack;
    }
    @Override public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        if (entity instanceof ServerPlayer player) FoodUse.request(player, player.getUsedItemHand(), FoodUse.Action.CANCEL);
    }
    @Override public int getUseDuration(ItemStack stack, LivingEntity entity) { return 32; }
    @Override public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        if(!water&&entity instanceof ServerPlayer player)
            FoodStorage.observe(player.serverLevel(),player.getUUID(),player.blockPosition(),stack);
    }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return water ? UseAnim.DRINK : UseAnim.EAT; }
    @Override public boolean onEntityItemUpdate(ItemStack stack, net.minecraft.world.entity.item.ItemEntity entity) {
        if(!water&&entity.level() instanceof net.minecraft.server.level.ServerLevel level)
            FoodStorage.observe(level,entity.getUUID(),entity.blockPosition(),stack);
        return false;
    }
}
