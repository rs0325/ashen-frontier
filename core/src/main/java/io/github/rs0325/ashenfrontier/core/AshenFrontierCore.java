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

@Mod(AshenFrontierCore.MOD_ID)
public final class AshenFrontierCore {
    public static final String MOD_ID = "ashen_frontier_core";
    public static final String NAMESPACE = "ashen_frontier";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AshenFrontierCore(IEventBus modEventBus, ModContainer modContainer) {
        UnitsConfig.register(modContainer);
        MaterialData.register(modEventBus);
        modEventBus.addListener(MaterialDataProvider::gatherData);
        modEventBus.addListener(AshenFrontierCore::addGameTestDataPack);
    }

    /** Ashen Frontier全Mod共通の名前空間でリソースIDを作る。 */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

    private static void addGameTestDataPack(AddPackFindersEvent event) {
        boolean gameTestEnabled = Arrays.stream(System.getProperty("neoforge.enabledGameTestNamespaces", "").split(","))
                .map(String::trim)
                .anyMatch(MOD_ID::equals);
        if (event.getPackType() == PackType.SERVER_DATA && gameTestEnabled) {
            event.addPackFinders(ResourceLocation.fromNamespaceAndPath(MOD_ID, "data/ashen_frontier_core/datapacks/gametest"),
                    PackType.SERVER_DATA, Component.literal("Ashen Frontier GameTest"),
                    PackSource.BUILT_IN, true, Pack.Position.TOP);
        }
    }
}
