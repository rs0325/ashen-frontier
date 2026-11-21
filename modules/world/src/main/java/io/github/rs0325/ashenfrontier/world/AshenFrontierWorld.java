package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.MapCodec;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.io.IOException;
import java.nio.file.Files;

@Mod(AshenFrontierWorld.MOD_ID)
public final class AshenFrontierWorld {
    public static final String MOD_ID = "ashen_frontier_world";
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, AshenFrontierCore.NAMESPACE);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, AshenFrontierCore.NAMESPACE);

    public AshenFrontierWorld(IEventBus bus) {
        GENERATORS.register("fixed_map", () -> FixedMapChunkGenerator.CODEC);
        BIOME_SOURCES.register("fixed_map", () -> FixedMapBiomeSource.CODEC);
        GENERATORS.register(bus);
        BIOME_SOURCES.register(bus);
        NeoForge.EVENT_BUS.addListener(AshenFrontierWorld::beforeStart);
        NeoForge.EVENT_BUS.addListener(AshenFrontierWorld::loadLevel);
        NeoForge.EVENT_BUS.addListener(AshenFrontierWorld::starting);
    }

    private static void beforeStart(ServerAboutToStartEvent event) {
        var server = event.getServer();
        var stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        if (!(stems.get(LevelStem.OVERWORLD).generator() instanceof FixedMapChunkGenerator generator)) return;
        BundledMaps.require(generator.mapVersion());
        var path = server.getWorldPath(LevelResource.ROOT).resolve("data/" + MapSavedData.ID + ".dat");
        if (!Files.exists(path)) return;
        // 標準SavedData読込みは破損時に新規作成へ進むため、その前に照合する。
        try {
            var tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            MapSavedData.requireMatch(MapSavedData.decode(tag.getCompound("data")), generator.mapVersion());
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("固定マップ版 " + generator.mapVersion().mapVersion() + ": 保存済み照合記録を読めません", e);
        }
    }

    private static void loadLevel(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || !(level.getChunkSource().getGenerator() instanceof FixedMapChunkGenerator generator)) return;
        MapSavedData.verify(level, generator.mapVersion());
    }

    private static void starting(ServerStartingEvent event) {
        var level = event.getServer().overworld();
        if (!(level.getChunkSource().getGenerator() instanceof FixedMapChunkGenerator)) return;
        level.getWorldBorder().setCenter(0, 0);
        level.getWorldBorder().setSize(MapCoordinates.MAX - MapCoordinates.MIN);
    }
}
