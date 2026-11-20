package io.github.rs0325.ashenfrontier.core;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.slf4j.Logger;

import java.util.Arrays;
import io.github.rs0325.ashenfrontier.core.team.TeamCommands;
import io.github.rs0325.ashenfrontier.core.team.TeamData;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.neoforged.neoforge.common.NeoForge;
import io.github.rs0325.ashenfrontier.core.recipe.RecipeTypeRegistry;

@Mod(AshenFrontierCore.MOD_ID)
public final class AshenFrontierCore {
    public static final String MOD_ID = "ashen_frontier_core";
    public static final String NAMESPACE = "ashen_frontier";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AshenFrontierCore(IEventBus modEventBus, ModContainer modContainer) {
        UnitsConfig.register(modContainer);
        MaterialData.register(modEventBus);
        ItemProperties.register(modEventBus);
        modEventBus.addListener(MaterialDataProvider::gatherData);
        modEventBus.addListener(AshenFrontierCore::addGameTestDataPack);
        TeamData.register();
        ResearchManager.register(modEventBus);
        if (gameTestEnabled() || Boolean.getBoolean("ashen_frontier.testRecipes")) {
            RecipeTypeRegistry.register(modEventBus, id("game_test"));
        }
        NeoForge.EVENT_BUS.addListener(TeamCommands::register);
    }

    /** Ashen Frontier全Mod共通の名前空間でリソースIDを作る。 */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

    private static void addGameTestDataPack(AddPackFindersEvent event) {
        boolean testDataEnabled = gameTestEnabled() || Boolean.getBoolean("ashen_frontier.testRecipes");
        if (event.getPackType() == PackType.SERVER_DATA && testDataEnabled) {
            event.addPackFinders(ResourceLocation.fromNamespaceAndPath(MOD_ID, "data/ashen_frontier_core/datapacks/gametest"),
                    PackType.SERVER_DATA, Component.literal("Ashen Frontier GameTest"),
                    PackSource.BUILT_IN, true, Pack.Position.TOP);
        }
    }

    private static boolean gameTestEnabled() {
        return Arrays.stream(System.getProperty("neoforge.enabledGameTestNamespaces", "").split(","))
                .map(String::trim)
                .anyMatch(MOD_ID::equals);
    }
}
