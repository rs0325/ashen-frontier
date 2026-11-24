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
import java.util.Set;
import io.github.rs0325.ashenfrontier.core.team.TeamCommands;
import io.github.rs0325.ashenfrontier.core.team.TeamData;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.neoforged.neoforge.common.NeoForge;
import io.github.rs0325.ashenfrontier.core.recipe.RecipeTypeRegistry;
import io.github.rs0325.ashenfrontier.core.compat.CompatibilityChecker;
import io.github.rs0325.ashenfrontier.core.rescue.RescueCommands;
import io.github.rs0325.ashenfrontier.core.config.CoreConfig;
import io.github.rs0325.ashenfrontier.core.terminal.TerminalAttachments;
import io.github.rs0325.ashenfrontier.core.terminal.TerminalModules;
import io.github.rs0325.ashenfrontier.core.terminal.TerminalModuleAction;
import net.minecraft.world.item.Items;
import io.github.rs0325.ashenfrontier.core.vanilla.RemovedLootModifiers;
import io.github.rs0325.ashenfrontier.core.catalog.FuelData;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.CoreCreativeTab;
import io.github.rs0325.ashenfrontier.core.catalog.NatureDataProvider;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.catalog.RelicModels;
import io.github.rs0325.ashenfrontier.core.loot.LifeSupportLootModifiers;

@Mod(AshenFrontierCore.MOD_ID)
public final class AshenFrontierCore {
    public static final String MOD_ID = "ashen_frontier_core";
    public static final String NAMESPACE = "ashen_frontier";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AshenFrontierCore(IEventBus modEventBus, ModContainer modContainer) {
        CoreConfig.register(modContainer);
        RemovedLootModifiers.register(modEventBus);
        LifeSupportLootModifiers.register(modEventBus);
        MaterialData.register(modEventBus);
        FoodProperties.register(modEventBus);
        ItemProperties.register(modEventBus);
        ContaminationTools.register(modEventBus);
        NatureProperties.register(modEventBus);
        NatureContent.register(modEventBus);
        RelicProperties.register(modEventBus);
        RelicContent.register(modEventBus);
        CoreCreativeTab.register(modEventBus);
        MiningRules.register();
        FuelData.register(modEventBus);
        modEventBus.addListener(NatureDataProvider::gatherData);
        modEventBus.addListener(RelicModels::gatherData);
        modEventBus.addListener(MaterialDataProvider::gatherData);
        modEventBus.addListener(AshenFrontierCore::addGameTestDataPack);
        TeamData.register();
        ResearchManager.register(modEventBus);
        if (gameTestEnabled() || Boolean.getBoolean("ashen_frontier.testRecipes")) {
            RecipeTypeRegistry.register(modEventBus, id("game_test"));
            RecipeTypeRegistry.register(modEventBus, id("game_test_processing"));
        }
        NeoForge.EVENT_BUS.addListener(TeamCommands::register);
        NeoForge.EVENT_BUS.addListener(RescueCommands::register);
        CompatibilityChecker.register();
        TerminalAttachments.register(modEventBus);
        modEventBus.addListener(TerminalModuleAction::register);
        if (gameTestEnabled()) {
            TerminalModules.register(id("game_test_module"), Items.COMPASS, Set.of(id("test")));
        }
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
