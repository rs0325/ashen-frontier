package io.github.rs0325.ashenfrontier.core.vanilla;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID)
public final class RemovedRecipes {
    private RemovedRecipes() {}

    @SubscribeEvent
    public static void afterServerStarts(ServerStartedEvent event) {
        removeTaggedRecipes(event.getServer());
    }

    @SubscribeEvent
    public static void afterDatapackReload(OnDatapackSyncEvent event) {
        removeTaggedRecipes(event.getPlayerList().getServer());
    }

    private static void removeTaggedRecipes(MinecraftServer server) {
        var manager = server.getRecipeManager();
        manager.replaceRecipes(manager.getRecipes().stream()
                .filter(holder -> !holder.value().getResultItem(server.registryAccess())
                        .is(RemovedItems.TAG)
                        && !holder.value().getResultItem(server.registryAccess())
                                .is(RemovedItems.RECIPE_DISABLED))
                .toList());
    }
}
