package io.github.rs0325.ashenfrontier.core.loot;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 建物の任意lootに含める生命維持消耗品の数量計算。 */
public final class LifeSupportLoot {
    public static final TagKey<Item> ITEMS = TagKey.create(Registries.ITEM, AshenFrontierCore.id("life_support_supplies"));

    private LifeSupportLoot() {}

    /** 消耗品stackを倍率適用し、スタック上限を超える場合は複数stackへ分ける。 */
    public static void scale(ItemStack stack, double multiplier) {
        if (stack.isEmpty() || !stack.is(ITEMS)) return;
        stack.setCount(LifeSupportQuantity.scaledCount(stack.getCount(), multiplier));
    }
}
