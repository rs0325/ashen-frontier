package io.github.rs0325.ashenfrontier.core.vanilla;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;

public final class RemovedItems {
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM,
            AshenFrontierCore.id("removed"));
    public static final TagKey<Item> RECIPE_DISABLED = TagKey.create(Registries.ITEM,
            AshenFrontierCore.id("recipe_disabled"));

    private RemovedItems() {}

    public static boolean isRemoved(ItemStack stack) {
        return stack.is(TAG) && !(stack.is(Items.POTION)
                && stack.get(DataComponents.POTION_CONTENTS).is(Potions.WATER));
    }
}
