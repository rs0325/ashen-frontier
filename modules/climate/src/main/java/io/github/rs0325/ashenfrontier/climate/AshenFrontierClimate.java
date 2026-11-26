package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget;
import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
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
    static final ModConfigSpec.IntValue WEATHER_INTERVAL,WEATHER_CELL_BUDGET,WEATHER_MICROS;
    static final ModConfigSpec.BooleanValue DYNAMIC_WEATHER;
    static final java.util.Map<WeatherEvent,ModConfigSpec.BooleanValue> EVENT_ENABLED=new java.util.EnumMap<>(WeatherEvent.class);
    private static final AtomicLong PENDING_DAY_LENGTH = new AtomicLong();
    static {
        var builder = new ModConfigSpec.Builder();
        TICKS_PER_DAY = builder.comment("1ゲーム日のserver tick数。変更後の区間だけに適用する。")
                .defineInRange("ticksPerDay", 24000L, 1L, Long.MAX_VALUE);
        WEATHER_INTERVAL=builder.comment("気象の論理更新間隔（server tick、10～30実秒）。").defineInRange("weatherIntervalTicks",600,200,600);
        WEATHER_CELL_BUDGET=builder.comment("1tickの気象計算セル上限。").defineInRange("weatherCellBudget",2048,16,8192);
        WEATHER_MICROS=builder.comment("1tickの気象処理の目標時間予算（マイクロ秒）。").defineInRange("weatherBudgetMicros",600,100,900);
        DYNAMIC_WEATHER=builder.comment("falseは明示的な固定気候モード。履歴へ記録する。").define("dynamicWeather",true);
        builder.push("weatherEvents");
        for(var type:WeatherEvent.values())EVENT_ENABLED.put(type,builder.define(type.name().toLowerCase(java.util.Locale.ROOT),true));
        builder.pop();
        SPEC = builder.build();
    }

    public AshenFrontierClimate(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
        RoomAir.install(container);
        EnvironmentProvider.install();
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,(ServerTickEvent.Pre event)->
                EnvironmentBudget.beginTick(event.getServer()));
        bus.addListener(WeatherCellsPayload::register);
        bus.addListener(AshenFrontierClimate::reload);
        NeoForge.EVENT_BUS.addListener(AshenFrontierClimate::beforeStart);
        NeoForge.EVENT_BUS.addListener(AshenFrontierClimate::started);
        NeoForge.EVENT_BUS.addListener(AshenFrontierClimate::tick);
        NeoForge.EVENT_BUS.addListener(WeatherService::started);
        NeoForge.EVENT_BUS.addListener(WeatherService::tick);
    }

    private static void reload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) { PENDING_DAY_LENGTH.set(TICKS_PER_DAY.get());WeatherService.reload(); }
    }

    private static void beforeStart(ServerAboutToStartEvent event) {
        PENDING_DAY_LENGTH.set(0);
        WeatherService.reset();
        var root=event.getServer().getWorldPath(LevelResource.ROOT);
        var roomPaths=new java.util.ArrayList<java.nio.file.Path>();
        roomPaths.add(root.resolve("data/ashen_frontier_room_air.dat"));
        roomPaths.add(root.resolve("DIM-1/data/ashen_frontier_room_air.dat"));
        roomPaths.add(root.resolve("DIM1/data/ashen_frontier_room_air.dat"));
        var dimensions=root.resolve("dimensions");
        try {
            if(Files.isDirectory(dimensions))try(var namespaces=Files.list(dimensions)) {
                for(var namespace:namespaces.filter(Files::isDirectory).toList())try(var names=Files.list(namespace)) {
                    names.filter(Files::isDirectory).forEach(p->roomPaths.add(p.resolve("data/ashen_frontier_room_air.dat")));
                }
            }
            for(var roomPath:roomPaths)if(Files.exists(roomPath))
                RoomAir.verify(NbtIo.readCompressed(roomPath,NbtAccounter.unlimitedHeap()).getCompound("data"));
        } catch(IOException|RuntimeException e) { throw new IllegalStateException("保存済み室内履歴を読めません",e); }
        var stem = event.getServer().registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
        if (!(stem.generator() instanceof FixedMapChunkGenerator)) return;
        var path = event.getServer().getWorldPath(LevelResource.ROOT).resolve("data/" + ClimateClock.ID + ".dat");
        var weatherPath=event.getServer().getWorldPath(LevelResource.ROOT).resolve("data/"+WeatherStore.ID+".dat");
        if(Files.exists(weatherPath)) {
            try {
                WeatherStore.verify(NbtIo.readCompressed(weatherPath,NbtAccounter.unlimitedHeap()).getCompound("data"),
                        ((FixedMapChunkGenerator)stem.generator()).mapVersion(),
                        event.getServer().getWorldData().worldGenOptions().seed()^((FixedMapChunkGenerator)stem.generator()).mapVersion().manifestHash().hashCode());
            } catch(IOException|RuntimeException e) { throw new IllegalStateException("保存済み気象を読めません",e); }
        }
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
