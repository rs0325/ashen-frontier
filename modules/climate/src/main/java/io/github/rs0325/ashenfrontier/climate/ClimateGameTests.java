package io.github.rs0325.ashenfrontier.climate;

import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.levelgen.WorldOptions;
import com.mojang.serialization.Lifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.Util;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import java.util.List;
import com.mojang.logging.LogUtils;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.world.FixedMapBiomeSource;
import io.github.rs0325.ashenfrontier.world.BundledMaps;
import io.github.rs0325.ashenfrontier.world.FixedMapChunkGenerator;
import io.github.rs0325.ashenfrontier.world.MapVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.security.MessageDigest;
import java.util.HexFormat;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ClimateGameTests {
    private static FixedMapChunkGenerator generator(GameTestHelper helper) {
        var key = ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map"));
        return (FixedMapChunkGenerator) helper.getLevel().registryAccess().registryOrThrow(Registries.WORLD_PRESET)
                .getHolderOrThrow(key).value().createWorldDimensions().overworld();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void mapSelectionAndAllCellsWithoutChunks(GameTestHelper helper) {
        var generator = generator(helper);
        helper.assertTrue(ClimateQuery.mapVersion(Level.OVERWORLD, generator).orElseThrow().equals(generator.mapVersion()),
                "固定マップの版を生成器から選ぶ");
        helper.assertTrue(ClimateQuery.mapVersion(Level.NETHER, generator).isEmpty(), "別次元にフォールバックしない");
        helper.assertTrue(ClimateQuery.mapVersion(helper.getLevel()).isEmpty(), "GameTestの平坦生成器には提供しない");
        helper.assertTrue(ClimateQuery.sample(helper.getLevel(), BlockPos.ZERO, helper.getLevel().getGameTime()).isEmpty(),
                "未対応ワールドに固定気候を提供しない");
        int before = helper.getLevel().getChunkSource().getLoadedChunksCount();
        long started = System.nanoTime();
        var climate = FixedClimate.require(generator.mapVersion());
        long elapsed = System.nanoTime() - started;
        helper.assertTrue(climate.cells().size() == 4096, "64×64セル");
        for (var cell : climate.cells()) if (cell.region() == 1) {
            boolean growingSeason = false;
            for (int day = 0; day < 24; day++) {
                if (climate.sample(cell.x(), cell.surfaceElevationM() / 25 + 63, cell.z(), day * 86400 + 32400).temperatureK() >= 278.15) growingSeason = true;
            }
            helper.assertTrue(growingSeason, "初期地域の地表に栽培可能な季節がある");
        }
        for (var cell : climate.cells()) {
            var sample = climate.sample(cell.x(), 63, cell.z(), 0);
            helper.assertTrue(sample.pressurePa() == 101325 && sample.temperatureK() > 0
                    && Double.isFinite(sample.sun().altitudeRad()), "全セルのSI基準値");
        }
        helper.assertTrue(helper.getLevel().getChunkSource().getLoadedChunksCount() == before, "固定層読取りでチャンクを増やさない");
        boolean rejected = false;
        try { climate.sample(16384, 63, 0, 0); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "半開範囲の外側を拒否する");
        long[] batches = new long[40];
        double checksum = 0;
        for (int run = -5; run < batches.length; run++) {
            long start = System.nanoTime();
            for (var cell : climate.cells()) {
                var sample = climate.sample(cell.x(), 100, cell.z(), run + 5);
                checksum += sample.temperatureK() + sample.pressurePa() + sample.sun().altitudeRad()
                        + sample.sun().azimuthRad() + sample.sun().daylightFraction() + sample.sun().horizontalProjection();
            }
            if (run >= 0) batches[run] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(batches);
        LogUtils.getLogger().info("M3-133 fixed layer cache lookup={} ms; 4096 samples p95={} ms; checksum={}",
                elapsed / 1e6, batches[37] / 1e6, checksum);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void clockSaveReloadAndHistoricalBoundaries(GameTestHelper helper) {
        var clock = new ClimateClock(24000);
        clock.changeDayLength(12000, 48000);
        var restored = ClimateClock.load(clock.save(new CompoundTag(), helper.getLevel().registryAccess()));
        helper.assertTrue(restored.gameSecondsAt(36000) == 86400, "変更前後のGAME秒を保存する");
        helper.assertTrue(restored.history().intervals(0, 36000).size() == 2, "#132と同じ区間境界");
        var climate = FixedClimate.require(generator(helper).mapVersion());
        helper.assertTrue(climate.sample(-8500, 100, -8192, clock.gameSecondsAt(36000))
                .equals(climate.sample(-8500, 100, -8192, restored.gameSecondsAt(36000))), "保存読戻しで同じ位置の基準値が一致");
        long dayTime = helper.getLevel().getDayTime();
        helper.getLevel().setDayTime(dayTime + 12000);
        helper.assertTrue(restored.gameSecondsAt(36000) == 86400, "dayTime変更でGAME秒は進まない");
        helper.getLevel().setDayTime(dayTime);
        restored.history().change(40000, 100);
        helper.assertTrue(restored.history().changes().size() == 2, "公開スナップショットを変更しても時計を書き換えない");
        helper.assertTrue(!restored.changeDayLength(12000, 100), "同時刻の日長変更は履歴を上書きしない");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void previewRemainsUnassignedAndVersionSeparated(GameTestHelper helper) throws Exception {
        String path = "/data/ashen_frontier/fixed_map/earth_002/manifest.json";
        String hash;
        try (var stream = BundledMaps.class.getResourceAsStream(path)) {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
        var version = new MapVersion("earth_002", 1, hash);
        var original = generator(helper);
        var preview = new FixedMapChunkGenerator(((FixedMapBiomeSource) original.getBiomeSource()).withMapVersion(version), version);
        helper.assertTrue(ClimateQuery.mapVersion(Level.OVERWORLD, preview).orElseThrow().equals(version), "版はearth_001に固定しない");
        var climate = FixedClimate.require(version);
        helper.assertTrue(climate != FixedClimate.require(original.mapVersion()), "版ごとに固定層を分離する");
        var cell = climate.cells().stream().filter(row -> row.region() == 255).findFirst().orElseThrow();
        var sample = climate.sample(cell.x(), 63, cell.z(), 0);
        helper.assertTrue(sample.region() == 255 && !sample.regionAssigned(), "D2以前の未割当地域を用途地域にしない");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void fixedLevelDiskReloadPreservesQueryAndHistory(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var generator = generator(helper);
        var type = helper.getLevel().registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, AshenFrontierCore.id("fixed_map")));
        var stem = new LevelStem(type, generator);
        var data = new PrimaryLevelData(server.getWorldData().getLevelSettings(),
                new WorldOptions(0, false, false),
                PrimaryLevelData.SpecialWorldProperty.NONE,
                Lifecycle.stable());
        data.setGameTime(36000);
        var root = Files.createTempDirectory(Path.of("."), "climate-clock-");
        var source = LevelStorageSource.createDefault(root);
        var pos = new BlockPos(-8500, 100, -8192);
        FixedClimate.Baseline before;
        try (var access = source.createAccess("fixture");
             var level = new ServerLevel(server, Util.backgroundExecutor(),
                     access, data, Level.OVERWORLD, stem,
                     LoggerChunkProgressListener.createCompleted(),
                     false, 0, List.of(), false, null)) {
            var clock = ClimateClock.get(level);
            clock.changeDayLength(12000, 48000);
            before = ClimateQuery.sample(level, pos, 36000).orElseThrow();
            var intervals = ClimateQuery.intervals(level, pos, 0, 36000).orElseThrow();
            helper.assertTrue(intervals.size() == 2 && intervals.getFirst().endTick() == 12000
                    && intervals.getFirst().ticksPerDay() == 24000 && intervals.getLast().ticksPerDay() == 48000,
                    "実ServerLevelでも日長境界で分割");
            helper.assertTrue(intervals.getFirst().endState().equals(ClimateQuery.sample(level, pos, 12000).orElseThrow()),
                    "途中の小区間に現在の状態を付けない");
            helper.assertTrue(ClimateQuery.sample(level, new BlockPos(16384, 63, 0), 0).isEmpty(), "実APIの範囲外");
            helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == 0, "照会でチャンクをロードしない");
            level.getDataStorage().save();
            IOUtilities.waitUntilIOWorkerComplete();
        }
        try (var access = source.createAccess("fixture");
             var level = new ServerLevel(server, Util.backgroundExecutor(),
                     access, data, Level.OVERWORLD, stem,
                     LoggerChunkProgressListener.createCompleted(),
                     false, 0, List.of(), false, null)) {
            helper.assertTrue(before.equals(ClimateQuery.sample(level, pos, 36000).orElseThrow()),
                    "別のServerLevelがディスクから復元しても同位置・時刻の状態が一致");
            helper.assertTrue(ClimateQuery.dayLengthHistory(level).orElseThrow().changes().size() == 2,
                    "実SavedDataの履歴を読み戻す");
            helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == 0, "再読込み後もチャンク不要");
            data.setGameTime(100000);
            historicalQueriesMatchAndBenchmark(helper, level, pos);
        }
        boolean corruptRejected = false;
        try { ClimateClock.load(new CompoundTag()); } catch (IllegalStateException expected) { corruptRejected = true; }
        helper.assertTrue(corruptRejected, "破損時計を新規履歴として扱わない");
        var wrongModel = new ClimateClock(24000).save(new CompoundTag(), helper.getLevel().registryAccess());
        wrongModel.putInt("model_version", ClimateModel.MODEL_VERSION + 1);
        boolean modelRejected = false;
        try { ClimateClock.load(wrongModel); } catch (IllegalStateException expected) { modelRejected = true; }
        helper.assertTrue(modelRejected, "別モデルで過去を再計算しない");
        helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = AshenFrontierClimate.MOD_ID)
    public static void startupDoesNotOverwriteNewerReload(GameTestHelper helper) {
        var pending = new java.util.concurrent.atomic.AtomicLong();
        AshenFrontierClimate.initializeDayLength(pending, () -> {
            long oldValue = 24000;
            pending.set(12000);
            return oldValue;
        });
        helper.assertTrue(pending.get() == 12000, "初期値読取り後に届いた新しい日長通知を保持");
        pending.set(0);
        AshenFrontierClimate.initializeDayLength(pending, () -> 24000);
        helper.assertTrue(pending.get() == 24000, "通知なしなら初期日長を提供");
        helper.succeed();
    }

    private static List<ClimateQuery.Interval> previousIntervals(ServerLevel level, BlockPos pos, long from, long to) {
        var clock = ClimateClock.get(level);
        var climate = FixedClimate.require(ClimateQuery.mapVersion(level).orElseThrow());
        return clock.history().intervals(from, to).stream().map(interval -> new ClimateQuery.Interval(
                interval.startTick(), interval.endTick(), interval.ticksPerDay(),
                climate.sample(pos.getX(), pos.getY(), pos.getZ(), clock.gameSecondsAt(interval.endTick())))).toList();
    }

    private static void historicalQueriesMatchAndBenchmark(GameTestHelper helper, ServerLevel level, BlockPos pos) {
        var clock = ClimateClock.get(level);
        int count = 2;
        double checksum = 0;
        for (int target : new int[]{256, 1024, 4096}) {
            while (count < target) {
                clock.changeDayLength(12000 + (count - 1) * 7L, count % 2 == 0 ? 12347 : 23457);
                count++;
            }
            long end = 12000 + (count - 2) * 7L + 3;
            helper.assertTrue(previousIntervals(level, pos, 0, end)
                    .equals(ClimateQuery.intervals(level, pos, 0, end).orElseThrow()),
                    "全期間の末尾状態が従来計算と一致: " + count);
            long[] previous = new long[5], current = new long[5];
            for (int run = -2; run < current.length; run++) {
                long start = System.nanoTime();
                var before = previousIntervals(level, pos, 0, end);
                long oldNanos = System.nanoTime() - start;
                start = System.nanoTime();
                var after = ClimateQuery.intervals(level, pos, 0, end).orElseThrow();
                long newNanos = System.nanoTime() - start;
                for (var rows : List.of(before, after)) for (var row : rows) {
                    var state = row.endState();
                    checksum += state.temperatureK() + state.pressurePa() + state.sun().altitudeRad()
                            + state.sun().azimuthRad() + state.sun().daylightFraction() + state.sun().horizontalProjection();
                }
                if (run >= 0) { previous[run] = oldNanos; current[run] = newNanos; }
            }
            java.util.Arrays.sort(previous);
            java.util.Arrays.sort(current);
            LogUtils.getLogger().info("M3-133 history benchmark changes={} previous_median_ms={} current_median_ms={} current_max_ms={} checksum={}",
                    count, previous[2] / 1e6, current[2] / 1e6, current[4] / 1e6, checksum);
        }
        long end = 12000 + (count - 2) * 7L + 3;
        for (long[] range : new long[][]{{11, end - 1}, {12000, end}, {end, end}, {12000, 12000}, {12001, 12002}, {0, 0}}) {
            helper.assertTrue(previousIntervals(level, pos, range[0], range[1])
                    .equals(ClimateQuery.intervals(level, pos, range[0], range[1]).orElseThrow()),
                    "部分区間・変更境界・ゼロ長の状態が一致: " + range[0] + ".." + range[1]);
        }
    }
}
