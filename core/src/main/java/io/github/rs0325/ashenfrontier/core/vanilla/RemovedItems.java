package io.github.rs0325.ashenfrontier.core.vanilla;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

import java.util.List;
import java.util.Collection;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class RemovedItems {
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM,
            AshenFrontierCore.id("removed"));
    public static final TagKey<Item> RECIPE_DISABLED = TagKey.create(Registries.ITEM,
            AshenFrontierCore.id("recipe_disabled"));

    private RemovedItems() {}

    public static List<ItemStack> stacks() {
        return BuiltInRegistries.ITEM.getTag(TAG).stream()
                .flatMap(holders -> holders.stream())
                .map(holder -> new ItemStack(holder.value()))
                .toList();
    }

    @SubscribeEvent
    public static void removeFromCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        removeTaggedEntries(event, event.getParentEntries(), CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
        removeTaggedEntries(event, event.getSearchEntries(), CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
    }

    private static void removeTaggedEntries(BuildCreativeModeTabContentsEvent event,
                                             Collection<ItemStack> entries,
                                             CreativeModeTab.TabVisibility visibility) {
        for (ItemStack stack : List.copyOf(entries)) {
            if (stack.is(TAG)) {
                event.remove(stack, visibility);
            }
        }
    }
}
