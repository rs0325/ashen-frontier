package io.github.rs0325.ashenfrontier.agriculture;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.core.recipe.RecipeTypeRegistry;
import io.github.rs0325.ashenfrontier.core.recipe.TimeBasis;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierAgriculture.MOD_ID)
@PrefixGameTestTemplate(false)
public class AgricultureGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierAgriculture.MOD_ID)
    public static void modulesLoadWithSharedFoodAndProcessingTypes(GameTestHelper helper) {
        if (!ModList.get().isLoaded("ashen_frontier_core") || !ModList.get().isLoaded("ashen_frontier_primitive")
                || !FoodProperties.CROP_SPECIES.isBound() || !FoodProperties.NUTRITION.isBound()
                || RecipeTypeRegistry.timeBasis(AshenFrontierCore.id("food_drying")) != TimeBasis.GAME
                || RecipeTypeRegistry.timeBasis(AshenFrontierCore.id("knapping")) != TimeBasis.REAL
                || RecipeTypeRegistry.registeredTypes().size() < 8) {
            helper.fail("機能Modと共通型の配線が不一致です");
            return;
        }
        helper.succeed();
    }
}
