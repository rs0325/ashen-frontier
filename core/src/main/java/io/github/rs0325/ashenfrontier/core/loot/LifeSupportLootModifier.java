package io.github.rs0325.ashenfrontier.core.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rs0325.ashenfrontier.core.config.CoreConfig;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.LootModifier;

public final class LifeSupportLootModifier extends LootModifier {
    public static final MapCodec<LifeSupportLootModifier> CODEC = RecordCodecBuilder.mapCodec(instance ->
            codecStart(instance).apply(instance, LifeSupportLootModifier::new));

    public LifeSupportLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        var table = context.getQueriedLootTableId();
        if (!appliesTo(table)) return loot;
        double multiplier = CoreConfig.lifeSupportLootMultiplier();
        for (int i = loot.size() - 1; i >= 0; i--) {
            ItemStack stack = loot.get(i);
            if (stack.isEmpty() || !stack.is(LifeSupportLoot.ITEMS)) continue;
            LifeSupportLoot.scale(stack, multiplier);
            if (stack.isEmpty()) loot.remove(i);
            else if (stack.getCount() > stack.getMaxStackSize()) {
                int remaining = stack.getCount() - stack.getMaxStackSize();
                stack.setCount(stack.getMaxStackSize());
                while (remaining > 0) {
                    ItemStack extra = stack.copy();
                    int amount = Math.min(remaining, extra.getMaxStackSize());
                    extra.setCount(amount);
                    loot.add(extra);
                    remaining -= amount;
                }
            }
        }
        return loot;
    }

    public static boolean appliesTo(net.minecraft.resources.ResourceLocation table) {
        return table.getNamespace().equals("ashen_frontier") && table.getPath().startsWith("buildings/")
                && !table.getPath().startsWith("buildings/guaranteed/");
    }

    @Override
    public MapCodec<? extends LootModifier> codec() {
        return CODEC;
    }
}
