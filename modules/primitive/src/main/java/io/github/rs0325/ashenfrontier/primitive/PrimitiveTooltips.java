package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.MaterialData;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = AshenFrontierPrimitive.MOD_ID, value = Dist.CLIENT)
public final class PrimitiveTooltips {
    private PrimitiveTooltips() {}

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        if (!PrimitiveContent.owns(stack.getItem()) && !stack.is(RelicContent.item("stone_hammer"))) return;
        MaterialData.get(stack.getItem()).ifPresent(material -> event.getToolTip().add(
                Component.translatable("tooltip.ashen_frontier.primitive_mass", material.massKg())));
        var wear = stack.get(ItemProperties.WEAR);
        if (wear != null) event.getToolTip().add(Component.translatable("tooltip.ashen_frontier.primitive_wear", wear.value(), wear.maximum()));
    }
}
