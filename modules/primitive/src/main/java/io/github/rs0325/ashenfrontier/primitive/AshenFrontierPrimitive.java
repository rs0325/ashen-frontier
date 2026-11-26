package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.recipe.RecipeTypeRegistry;
import io.github.rs0325.ashenfrontier.core.recipe.TimeBasis;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** Tier 0の加工型を共通基盤に登録する。設備・道具は各実装Issueで追加する。 */
@Mod(AshenFrontierPrimitive.MOD_ID)
public final class AshenFrontierPrimitive {
    public static final String MOD_ID = "ashen_frontier_primitive";

    public AshenFrontierPrimitive(IEventBus bus) {
        PotteryProperties.register(bus);
        PrimitiveContent.register(bus);
        PotteryActions.register();
        AssemblyRecipe.register(bus);
        PrimitiveActions.register();
        PendingThermal.install();
        for (String type : new String[]{"knapping", "fiberworking", "firing", "water_treatment", "cooking"}) {
            RecipeTypeRegistry.register(bus, AshenFrontierCore.id(type), TimeBasis.REAL);
        }
        RecipeTypeRegistry.register(bus, AshenFrontierCore.id("drying"), TimeBasis.GAME);
    }
}
