package io.github.rs0325.ashenfrontier.core.recipe.jei;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe;
import io.github.rs0325.ashenfrontier.core.recipe.RecipeTypeRegistry;
import io.github.rs0325.ashenfrontier.core.vanilla.RemovedItems;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

@JeiPlugin
public final class AshenFrontierJeiPlugin implements IModPlugin {
    private final Map<ResourceLocation, RecipeType<CommonRecipe>> types = new LinkedHashMap<>();

    @Override
    public ResourceLocation getPluginUid() {
        return AshenFrontierCore.id("jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        runtime.getIngredientManager().removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK,
                runtime.getIngredientManager().getAllIngredients(VanillaTypes.ITEM_STACK).stream()
                        .filter(RemovedItems::isRemoved)
                        .toList());
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var gui = registration.getJeiHelpers().getGuiHelper();
        RecipeTypeRegistry.registeredTypes().keySet().forEach(id -> {
            JeiRecipeCategory category = new JeiRecipeCategory(id, gui);
            types.put(id, category.getRecipeType());
            registration.addRecipeCategories(category);
        });
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        types.forEach((id, jeiType) -> registration.addRecipes(jeiType,
                level.getRecipeManager().getAllRecipesFor(RecipeTypeRegistry.type(id))
                        .stream().map(holder -> holder.value()).toList()));
    }
}
