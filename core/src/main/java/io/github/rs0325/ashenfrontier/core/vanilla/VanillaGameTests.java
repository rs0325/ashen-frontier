package io.github.rs0325.ashenfrontier.core.vanilla;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public class VanillaGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 40)
    public static void removedStructuresAreNotScheduled(GameTestHelper helper) {
        var structureSets = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        for (String id : new String[] {"villages", "ancient_cities", "igloos"}) {
            var structureSet = structureSets.get(ResourceLocation.withDefaultNamespace(id));
            if (structureSet == null || !structureSet.structures().isEmpty()) {
                helper.fail("Vanilla structure set is not empty: " + id);
                return;
            }
        }
        var biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        boolean sculkPatchRemains = biomes.holders().anyMatch(biome ->
                biome.value().getGenerationSettings().features().stream()
                        .flatMap(features -> features.stream())
                        .anyMatch(feature -> feature.unwrapKey().map(key -> key.location()
                                .equals(ResourceLocation.withDefaultNamespace("sculk_patch_deep_dark")))
                                .orElse(false)));
        if (sculkPatchRemains) {
            helper.fail("Sculk patch remains in biome generation settings");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 40)
    public static void removedItemPredicatePreservesWaterBottle(GameTestHelper helper) {
        ItemStack waterBottle = new ItemStack(Items.POTION);
        waterBottle.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
        ItemStack healingPotion = new ItemStack(Items.POTION);
        healingPotion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.HEALING));
        if (RemovedItems.isRemoved(waterBottle) || !RemovedItems.isRemoved(healingPotion)
                || !RemovedItems.isRemoved(new ItemStack(Items.SPLASH_POTION))) {
            helper.fail("Potion removal must preserve only the water bottle");
            return;
        }
        helper.succeed();
    }
}
