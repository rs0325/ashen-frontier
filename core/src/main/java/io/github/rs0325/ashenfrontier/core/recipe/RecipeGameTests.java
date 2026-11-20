package io.github.rs0325.ashenfrontier.core.recipe;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public class RecipeGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 40)
    public static void matchesIngredientsRegardlessOfSlotOrder(GameTestHelper helper) {
        AshenFrontierCore.LOGGER.info("GameTest実行: ashen_frontier_core:matchesIngredientsRegardlessOfSlotOrder");
        CommonRecipe recipe = new CommonRecipe(ResourceLocation.parse("ashen_frontier:game_test"),
                List.of(Ingredient.of(Items.OAK_LOG, Items.BIRCH_LOG), Ingredient.of(Items.OAK_LOG)),
                List.of(ItemStack.EMPTY), new RecipeConditions(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));

        if (!recipe.matches(CraftingInput.of(2, 1,
                        List.of(new ItemStack(Items.OAK_LOG), new ItemStack(Items.BIRCH_LOG))), helper.getLevel())
                || !recipe.matches(CraftingInput.of(2, 1,
                        List.of(new ItemStack(Items.BIRCH_LOG), new ItemStack(Items.OAK_LOG))), helper.getLevel())
                || !recipe.matches(CraftingInput.of(2, 1,
                        List.of(new ItemStack(Items.OAK_LOG), new ItemStack(Items.OAK_LOG))), helper.getLevel())
                || recipe.matches(CraftingInput.of(2, 1,
                        List.of(new ItemStack(Items.BIRCH_LOG), new ItemStack(Items.BIRCH_LOG))), helper.getLevel())) {
            helper.fail("Recipe ingredient matching depends on slot order or accepts an invalid input");
            return;
        }
        helper.succeed();
    }
}
