package io.github.rs0325.ashenfrontier.core.vanilla;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

import java.util.Collection;
import java.util.List;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class RemovedItemsClientEvents {
    private RemovedItemsClientEvents() {}

    @SubscribeEvent
    public static void removeFromCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        removeTaggedEntries(event, event.getParentEntries(), CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
        removeTaggedEntries(event, event.getSearchEntries(), CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
    }

    private static void removeTaggedEntries(BuildCreativeModeTabContentsEvent event,
                                             Collection<ItemStack> entries,
                                             CreativeModeTab.TabVisibility visibility) {
        for (ItemStack stack : List.copyOf(entries)) {
            if (RemovedItems.isRemoved(stack)) {
                event.remove(stack, visibility);
            }
        }
    }
}
