package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.JsonOps;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Set;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.core.QuartPos;
import com.mojang.logging.LogUtils;
import javax.imageio.ImageIO;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorldGameTests {
    private static FixedMapChunkGenerator generator(GameTestHelper helper) {
        var key = ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map"));
        var dimensions = helper.getLevel().registryAccess().registryOrThrow(Registries.WORLD_PRESET)
                .getHolderOrThrow(key).value().createWorldDimensions();
        helper.assertTrue(dimensions.dimensions().keySet().equals(Set.of(LevelStem.OVERWORLD)), "overworldだけのプリセット");
        return (FixedMapChunkGenerator) dimensions.overworld();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void generatorCodecAndSavedRecord(GameTestHelper helper) {
        var size = helper.getLevel().getStructureManager()
                .get(ResourceLocation.fromNamespaceAndPath(AshenFrontierWorld.MOD_ID, "empty"))
                .orElseThrow().getSize();
        helper.assertTrue(size.equals(new Vec3i(16, 16, 16)), "読み込んだテンプレートの寸法は16³");
        var original = generator(helper);
        helper.assertTrue(original.mapVersion().mapVersion().equals("earth_001"), "同梱配置図はearth_001");
        var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var encoded = ChunkGenerator.CODEC.encodeStart(ops, original).getOrThrow();
        var decoded = (FixedMapChunkGenerator) ChunkGenerator.CODEC.parse(ops, encoded).getOrThrow();
        helper.assertTrue(original.mapVersion().equals(decoded.mapVersion()), "生成器Codecの版を保持");
        helper.assertTrue(decoded.getBiomeSource().possibleBiomes().equals(original.getBiomeSource().possibleBiomes()), "バイオームCodecを保持");
        var record = MapSavedData.verify(helper.getLevel(), original.mapVersion());
        helper.assertTrue(MapSavedData.decode(record.save(new CompoundTag(), helper.getLevel().registryAccess()))
                .equals(original.mapVersion()), "SavedDataの保存と読戻し");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void bundledRasterLayers(GameTestHelper helper) {
        var version = generator(helper).mapVersion();
        BundledMaps.require(version);
        for (String layer : new String[]{"height", "region", "water", "soil", "geology", "coast_river"}) {
            String path = "/data/ashen_frontier/fixed_map/" + version.mapVersion() + "/" + layer + ".png";
            try (var stream = BundledMaps.class.getResourceAsStream(path)) {
                helper.assertTrue(stream != null, "配置図を同梱: " + layer);
                var image = ImageIO.read(stream);
                helper.assertTrue(image.getWidth() == 2048 && image.getHeight() == 2048, "配置図の寸法: " + layer);
                helper.assertTrue(image.getRaster().getNumBands() == 1, "整数グレースケール: " + layer);
                helper.assertTrue(image.getSampleModel().getSampleSize(0) == (layer.equals("height") ? 16 : 8),
                        "配置図のビット幅: " + layer);
                image.flush();
            } catch (IOException e) {
                throw new IllegalStateException("配置図を読めません: " + path, e);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void terrainColumnsAndBoundary(GameTestHelper helper) {
        var generator = generator(helper);
        var level = helper.getLevel();
        var random = level.getChunkSource().randomState();
        for (int x : new int[]{-16385, -16384, 16383, 16384}) {
            var chunk = new ProtoChunk(new ChunkPos(Math.floorDiv(x, 16), 0), UpgradeData.EMPTY, level,
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
            var column = generator.getBaseColumn(x, 0, level, random);
            for (int y = -64; y < 320; y++) {
                helper.assertTrue(chunk.getBlockState(new BlockPos(x, y, 0)).equals(column.getBlock(y)), "実チャンクと列サンプルの一致");
            }
            boolean inside = MapCoordinates.contains(x, 0);
            helper.assertTrue(column.getBlock(-64).is(inside ? Blocks.BEDROCK : Blocks.AIR), "端の内外の岩盤");
            int top = -64;
            for (int y = -64; y < 320; y++) if (!column.getBlock(y).isAir()) top = y + 1;
            helper.assertTrue(generator.getBaseHeight(x, 0, Heightmap.Types.WORLD_SURFACE_WG, level, random)
                    == top, "高さサンプルの一致");
        }
        helper.succeed();
    }

    private static ProtoChunk chunk(GameTestHelper helper, FixedMapChunkGenerator generator, ChunkPos pos, RandomState random) {
        var level = helper.getLevel();
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
        return chunk;
    }

    private static long hash(ProtoChunk chunk) {
        long hash = 0xcbf29ce484222325L;
        var pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = -64; y < 320; y++) {
            var state = chunk.getBlockState(pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z));
            hash = (hash ^ Block.getId(state)) * 0x100000001b3L;
        }
        return hash;
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void fixedSeedsOrdersAndAllColumns(GameTestHelper helper) {
        var generator = generator(helper);
        var level = helper.getLevel();
        var noise = level.registryAccess().lookupOrThrow(Registries.NOISE);
        var settings = level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var first = RandomState.create(settings, noise, 1L);
        var second = RandomState.create(settings, noise, -987654321L);
        List<ChunkPos> points = List.of(new ChunkPos(-532, -512), new ChunkPos(-625, -666),
                new ChunkPos(-775, -332), new ChunkPos(-250, -232), new ChunkPos(-315, -550), new ChunkPos(-1024, -1024));
        var expected = new HashMap<ChunkPos, Long>();
        for (var point : points) {
            var generated = chunk(helper, generator, point, first);
            expected.put(point, hash(generated));
            for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
                int x = point.getMinBlockX() + dx, z = point.getMinBlockZ() + dz;
                var column = generator.getBaseColumn(x, z, level, second);
                for (int y = -64; y < 320; y++) {
                    helper.assertTrue(generated.getBlockState(new BlockPos(x, y, z)).equals(column.getBlock(y)), "全Yの実生成・列一致");
                }
                for (var type : new Heightmap.Types[]{Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG}) {
                    helper.assertTrue(generated.getHeight(type, dx, dz) + 1 == generator.getBaseHeight(x, z, type, level, second), "流体を含む高さ一致");
                }
            }
        }
        var reversed = new ArrayList<>(points);
        Collections.reverse(reversed);
        for (var point : reversed) helper.assertTrue(expected.get(point) == hash(chunk(helper, generator, point, second)), "別seed・逆順のhash一致");
        var parallel = reversed.stream().map(point -> CompletableFuture.supplyAsync(() ->
                expected.get(point) == hash(chunk(helper, generator, point, second)))).toList();
        for (var result : parallel) helper.assertTrue(result.join(), "並列順のhash一致");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void northwestRegionsAndStrata(GameTestHelper helper) {
        var generator = generator(helper);
        int[][] points = {{-8500,-8192,1,100}, {-5500,-8192,2,100}, {-10000,-10650,3,170},
                {-12400,-5300,4,100}, {-6000,-5000,5,100}, {-4000,-3700,6,90}, {-4000,-14100,7,130}};
        String[] names = {"", "temperate_wasteland", "abandoned_farmland", "mining_highlands", "arid_wasteland",
                "industrial_wasteland", "industrial_coast", "cold_research_wastes"};
        for (var point : points) {
            int x = point[0], z = point[1];
            var map = generator.terrain().map();
            helper.assertTrue(map.region(x, z) == point[2], "代表地点の地域");
            helper.assertTrue(generator.terrain().surfaceY(x, z) == point[3], "保護域の地表高");
            helper.assertTrue(map.isProtected(x, z), "施設の水平保護域");
            var generated = chunk(helper, generator, new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), helper.getLevel().getChunkSource().randomState());
            helper.assertTrue(!generated.getBlockState(new BlockPos(x, point[3], z)).isAir(), "代表地点の実生成地表");
            var biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z), null);
            helper.assertTrue(biome.is(AshenFrontierCore.id(names[point[2]])), "地域に合うバイオーム");
            var base = generator.getBaseColumn(x, z, helper.getLevel(), null);
            helper.assertTrue(!base.getBlock(point[3] - 4).isAir() && base.getBlock(point[3] - 4).getFluidState().isEmpty(), "土壌直下の母岩");
            if (point[2] == 3) helper.assertTrue(base.getBlock(point[3] - 4).is(Blocks.GRANITE), "鉱業高地の花崗岩");
            if (point[2] == 1 || point[2] == 4) {
                String[] expected = point[2] == 1
                        ? new String[]{"minecraft:sandstone", "ashen_frontier:shale", "ashen_frontier:limestone", "minecraft:granite"}
                        : new String[]{"minecraft:sandstone", "ashen_frontier:gypsum", "ashen_frontier:rock_salt", "ashen_frontier:shale", "minecraft:stone"};
                int next = 0;
                for (int y = point[3] - 3; y > -64 && next < expected.length; y--) {
                    if (base.getBlock(y).is(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(expected[next])))) next++;
                }
                helper.assertTrue(next == expected.length, "平野・乾燥地域の上下地層順");
            }
        }
        helper.assertTrue(generator.getBiomeSource().possibleBiomes().size() == 12, "possibleBiomesの全列挙");
        helper.assertTrue(QuartPos.toBlock(-1) == -4 && QuartPos.fromBlock(-1) == -1, "負quart座標の丸め");
        var map = generator.terrain().map();
        int rivers = 0, coasts = 0, shores = 0;
        for (int z = -16384; z < 0 && (rivers == 0 || coasts == 0 || shores == 0); z += 16) {
            for (int x = -16384; x < 0; x += 16) {
                int mask = map.waterMask(x, z);
                if ((mask & 2) != 0 && rivers == 0) {
                    var column = generator.getBaseColumn(x, z, helper.getLevel(), null);
                    int water = map.waterSurface(x, z);
                    helper.assertTrue(!column.getBlock(water).getFluidState().isEmpty(), "大河の水面");
                    helper.assertTrue(generator.terrain().surfaceY(x, z) < water, "河床が水面より下");
                    var generated = chunk(helper, generator, new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), helper.getLevel().getChunkSource().randomState());
                    helper.assertTrue(generated.getBlockState(new BlockPos(x, water, z)).equals(column.getBlock(water)), "実生成した河川の水面");
                    rivers++;
                }
                if ((mask & 8) != 0 && coasts == 0) {
                    var column = generator.getBaseColumn(x, z, helper.getLevel(), null);
                    helper.assertTrue(column.getBlock(63).is(BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("polluted_seawater"))), "海面63の汚染海水");
                    coasts++;
                }
                if ((mask & 1) != 0 && (mask & 14) == 0 && shores == 0) {
                    helper.assertTrue(generator.terrain().surfaceY(x, z) >= 64, "陸側海岸の高さ");
                    shores++;
                }
            }
        }
        helper.assertTrue(rivers > 0 && coasts > 0 && shores > 0, "海・海岸・大河の検査地点がある");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void terrainGenerationBenchmark(GameTestHelper helper) {
        var generator = generator(helper);
        var random = helper.getLevel().getChunkSource().randomState();
        var cpu = ManagementFactory.getThreadMXBean();
        for (String area : new String[]{"temperate", "snow_ice"}) {
            boolean frozen = area.equals("snow_ice");
            int iceChunks = 0;
            long[] times = new long[1000], cpuTimes = new long[1000];
            for (int i = -100; i < times.length; i++) {
                int n = Math.max(0, i);
                var position = frozen
                        ? new ChunkPos((n < 500 ? -260 : -520) + n % 20, (n < 500 ? -895 : -750) + n % 500 / 20)
                        : new ChunkPos(-550 + n % 40, -525 + n / 40);
                long beforeCpu = cpu.getCurrentThreadCpuTime(), before = System.nanoTime();
                var generated = chunk(helper, generator, position, random);
                long elapsed = System.nanoTime() - before, elapsedCpu = cpu.getCurrentThreadCpuTime() - beforeCpu;
                if (i >= 0) { times[i] = elapsed; cpuTimes[i] = elapsedCpu; }
                helper.assertTrue(generated.getBlockState(new BlockPos(position.getMinBlockX(), -64, position.getMinBlockZ())).is(Blocks.BEDROCK), "計測チャンクを生成");
                int top = generated.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, 0, 0);
                var cap = generated.getBlockState(new BlockPos(position.getMinBlockX(), top - 1, position.getMinBlockZ()));
                if (i >= 0 && (cap.is(Blocks.PACKED_ICE) || cap.is(Blocks.BLUE_ICE))) iceChunks++;
            }
            Arrays.sort(times);
            Arrays.sort(cpuTimes);
            helper.assertTrue(!frozen || iceChunks > 0, "雪氷の計測対象に実際の氷層がある");
            LogUtils.getLogger().info("TERRAIN_BENCHMARK area={} count=1000 warmup=100 parallelism=1 ice_chunks={} wall_p50_ms={} wall_p95_ms={} cpu_p95_ms={} max_ms={} heap_max_MiB={} java={} processors={}",
                    area, iceChunks, times[499] / 1e6, times[949] / 1e6, cpuTimes[949] / 1e6, times[999] / 1e6,
                    Runtime.getRuntime().maxMemory() / 1048576, System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void iceSheetsGlaciersAndSnow(GameTestHelper helper) {
        var generator = generator(helper);
        int[][] points = {{-4000, -14100, 8}, {-8000, -11800, 3}, {-3840, -14256, 12}};
        for (var point : points) {
            int x = point[0], z = point[1];
            var column = generator.terrain().column(x, z);
            int surface = column.surfaceY();
            var generated = chunk(helper, generator, new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), helper.getLevel().getChunkSource().randomState());
            helper.assertTrue(generated.getBlockState(new BlockPos(x, surface, z)).is(Blocks.SNOW_BLOCK), "氷床・山岳氷河・寒冷な谷の積雪");
            helper.assertTrue(generated.getBlockState(new BlockPos(x, surface - 1, z)).is(Blocks.PACKED_ICE), "表層直下の氷");
            for (int depth = 1; depth <= point[2]; depth++) {
                var state = generated.getBlockState(new BlockPos(x, surface - depth, z));
                helper.assertTrue(state.is(depth >= 4 ? Blocks.BLUE_ICE : Blocks.PACKED_ICE), "緯度・標高・谷に応じた厚い氷層");
            }
            for (int y = -64; y < 320; y++) {
                helper.assertTrue(generated.getBlockState(new BlockPos(x, y, z)).equals(column.state(y)), "雪氷を含む実チャンクと列の一致");
            }
            helper.assertTrue(generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, helper.getLevel(), null)
                    == surface + 1, "雪氷を含む地表高の一致");
        }
        for (var point : new int[][]{{-16385, -14100}, {16384, -14100}, {-4000, -16385}, {-4000, 16384}}) {
            var column = generator.terrain().column(point[0], point[1]);
            for (int y = -64; y < 320; y++) helper.assertTrue(column.state(y).isAir(), "範囲外へ雪氷を生成しない");
        }
        var warm = generator.terrain().column(-8500, -8192);
        helper.assertTrue(!warm.state(warm.surfaceY()).is(Blocks.SNOW_BLOCK), "温帯の初期地点は積雪なし");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 400)
    public static void alpineBiomeMatchesRoundedSurface(GameTestHelper helper) {
        var generator = generator(helper);
        var terrain = generator.terrain();
        int below = 0, above = 0, corrected = 0;
        for (int z = -13312; z <= -10240 && (below < 16 || above < 16 || corrected == 0); z += 4) {
            for (int x = -14000; x <= 3000; x += 4) {
                if (terrain.map().region(x, z) == 0) continue;
                double raw = terrain.map().height(x, z);
                if (raw < 207 || raw > 213) continue;
                int surface = terrain.surfaceY(x, z);
                if (surface != 209 && surface != 210) continue;
                var biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z), null);
                helper.assertTrue(biome.is(AshenFrontierCore.id("alpine_wastes")) == (surface >= 210), "高山はノイズ・丸め後の地表Y=210以上");
                if (surface < 210) below++; else above++;
                if ((raw >= 210) != (surface >= 210)) corrected++;
                if (below >= 16 && above >= 16 && corrected > 0) break;
            }
        }
        helper.assertTrue(below >= 16 && above >= 16 && corrected > 0, "閾値両側と従来判定が食い違う地点を検証");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void incompatibleMapsStop(GameTestHelper helper) {
        var version = generator(helper).mapVersion();
        for (var invalid : new MapVersion[]{new MapVersion("missing_001", 1, version.manifestHash()),
                new MapVersion(version.mapVersion(), 2, version.manifestHash()),
                new MapVersion(version.mapVersion(), 1, "changed")}) {
            boolean rejected = false;
            try { BundledMaps.require(invalid); }
            catch (IllegalStateException e) { rejected = e.getMessage().contains(invalid.mapVersion()); }
            helper.assertTrue(rejected, "版名付きで不足・不一致を拒否");
        }
        boolean rejected = false;
        try { MapSavedData.requireMatch(new MapVersion("other", 1, "other"), version); }
        catch (IllegalStateException e) { rejected = true; }
        helper.assertTrue(rejected, "SavedDataと生成器の不一致を拒否");
        helper.succeed();
    }
}
