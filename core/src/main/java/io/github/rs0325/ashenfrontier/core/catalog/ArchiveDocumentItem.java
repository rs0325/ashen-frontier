package io.github.rs0325.ashenfrontier.core.catalog;

import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class ArchiveDocumentItem extends Item {
    ArchiveDocumentItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        var document = stack.get(RelicProperties.DOCUMENT);
        if (document == null) return InteractionResultHolder.fail(stack);
        if (player instanceof ServerPlayer serverPlayer) {
            ResearchManager.reportKnowledge(serverPlayer, document);
            player.displayClientMessage(Component.translatable("message.ashen_frontier.document_recorded", document.toString()), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
