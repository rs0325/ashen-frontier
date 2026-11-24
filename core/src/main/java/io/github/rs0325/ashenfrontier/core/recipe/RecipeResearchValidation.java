package io.github.rs0325.ashenfrontier.core.recipe;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.research.ResearchTree;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** 両方の定義のロード完了後、同期前に研究参照を検証する。 */
@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID)
public final class RecipeResearchValidation {
    private RecipeResearchValidation() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void afterServerStarts(ServerStartedEvent event) {
        validate(event.getServer().getRecipeManager(), ResearchManager.tree());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void afterDatapackReload(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            validate(event.getPlayerList().getServer().getRecipeManager(), ResearchManager.tree());
        }
    }

    static void validate(RecipeManager manager, ResearchTree.Resolution research) {
        manager.replaceRecipes(manager.getRecipes().stream().filter(holder -> {
            if (!(holder.value() instanceof CommonRecipe recipe)) return true;
            var missing = recipe.conditions().research()
                    .filter(id -> !research.definitions().containsKey(id.toString()));
            missing.ifPresent(id -> AshenFrontierCore.LOGGER.error(
                    "レシピ {} の研究参照 {} が不正なため無効化しました", holder.id(), id));
            return missing.isEmpty();
        }).toList());
    }
}
