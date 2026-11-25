package io.github.rs0325.ashenfrontier.climate;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import io.github.rs0325.ashenfrontier.world.FixedMapChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.Files;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Mod(AshenFrontierClimate.MOD_ID)
public final class AshenFrontierClimate {
    public static final String MOD_ID = "ashen_frontier_climate";
    static final ModConfigSpec SPEC;
    static final ModConfigSpec.LongValue TICKS_PER_DAY;
    private static final AtomicLong PENDING_DAY_LENGTH = new AtomicLong();
    static {
        var builder = new ModConfigSpec.Builder();
        TICKS_PER_DAY = builder.comment("1ゲーム日のserver tick数。変更後の区間だけに適用する。")
                .defineInRange("ticksPerDay", 24000L, 1L, Long.MAX_VALUE);
        SPEC = builder.build();
    }

    public AshenFrontierClimate(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
        bus.addListener(AshenFrontierClimate::reload);
        NeoForge.EVENT_BUS.addListener(AshenFrontierClimate::beforeStart);
        NeoForge.EVENT_BUS.addListener(AshenFrontierClimate::started);
        NeoForge.EVENT_BUS.addListener(AshenFrontierClimate::tick);
    }

    private static void reload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) PENDING_DAY_LENGTH.set(TICKS_PER_DAY.get());
    }

    private static void beforeStart(ServerAboutToStartEvent event) {
        PENDING_DAY_LENGTH.set(0);
        var stem = event.getServer().registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
        if (!(stem.generator() instanceof FixedMapChunkGenerator)) return;
        var path = event.getServer().getWorldPath(LevelResource.ROOT).resolve("data/" + ClimateClock.ID + ".dat");
        if (!Files.exists(path)) return;
        // SavedDataが破損履歴を新規時計に置換する前に検査する。
        try {
            ClimateClock.load(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompound("data"));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("保存済み気候時計を読めません", e);
        }
    }

    private static void started(ServerStartedEvent event) {
        var level = event.getServer().overworld();
        ClimateQuery.mapVersion(level).ifPresent(version -> {
            FixedClimate.require(version);
            ClimateClock.get(level);
        });
        initializeDayLength(PENDING_DAY_LENGTH, TICKS_PER_DAY::get);
    }

    static void initializeDayLength(AtomicLong pending, LongSupplier setting) {
        // 設定を読む間に届いた監視側の通知を初期値で上書きしない。
        pending.compareAndSet(0, setting.getAsLong());
    }

    private static void tick(ServerTickEvent.Post event) {
        long value = PENDING_DAY_LENGTH.get();
        if (value == 0) return;
        var level = event.getServer().overworld();
        if (ClimateQuery.mapVersion(level).isEmpty()
                || ClimateClock.get(level).changeDayLength(level.getGameTime(), value)) PENDING_DAY_LENGTH.compareAndSet(value, 0);
    }
}
