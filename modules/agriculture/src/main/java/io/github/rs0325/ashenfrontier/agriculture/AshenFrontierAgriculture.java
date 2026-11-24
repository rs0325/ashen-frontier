package io.github.rs0325.ashenfrontier.agriculture;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.recipe.RecipeTypeRegistry;
import io.github.rs0325.ashenfrontier.core.recipe.TimeBasis;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** 食品加工型の登録。気象・生育・食品本体の代替実装は持たない。 */
@Mod(AshenFrontierAgriculture.MOD_ID)
public final class AshenFrontierAgriculture {
    public static final String MOD_ID = "ashen_frontier_agriculture";

    public AshenFrontierAgriculture(IEventBus bus) {
        RecipeTypeRegistry.register(bus, AshenFrontierCore.id("food_preparation"), TimeBasis.REAL);
        RecipeTypeRegistry.register(bus, AshenFrontierCore.id("food_drying"), TimeBasis.GAME);
    }
}
