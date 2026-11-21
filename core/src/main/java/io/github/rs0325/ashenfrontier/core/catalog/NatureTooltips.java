package io.github.rs0325.ashenfrontier.core.catalog;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, value = Dist.CLIENT)
public final class NatureTooltips {
    private NatureTooltips() {}

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        var id = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (!id.getNamespace().equals(AshenFrontierCore.NAMESPACE)) return;
        NatureCatalog.entries().stream().filter(entry -> entry.id().equals(id.getPath()) && entry.blockItem()
                && !entry.recovery().equals(entry.id())).findFirst().ifPresent(entry -> {
            var recovery = ResourceLocation.parse(entry.recovery().contains(":")
                    ? entry.recovery() : "ashen_frontier:" + entry.recovery());
            event.getToolTip().add(Component.translatable("tooltip.ashen_frontier.recovery",
                    Component.translatable(BuiltInRegistries.ITEM.get(recovery).getDescriptionId())));
        });
    }
}
