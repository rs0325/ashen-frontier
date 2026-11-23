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
import net.minecraft.world.level.LevelHeightAccessor;
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
            var chunk = new ProtoChunk(new ChunkPos(Math.floorDiv(x, 16), 0), UpgradeData.EMPTY, LevelHeightAccessor.create(-128,704),
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
            var column = generator.getBaseColumn(x, 0, LevelHeightAccessor.create(-128,704), random);
            for (int y = -128; y < 576; y++) {
                helper.assertTrue(chunk.getBlockState(new BlockPos(x, y, 0)).equals(column.getBlock(y)), "実チャンクと列サンプルの一致");
            }
            boolean inside = MapCoordinates.contains(x, 0);
            helper.assertTrue(column.getBlock(-128).is(inside ? Blocks.BEDROCK : Blocks.AIR), "端の内外の岩盤");
            int top = -128;
            for (int y = -128; y < 576; y++) if (!column.getBlock(y).isAir()) top = y + 1;
            helper.assertTrue(generator.getBaseHeight(x, 0, Heightmap.Types.WORLD_SURFACE_WG, LevelHeightAccessor.create(-128,704), random)
                    == top, "高さサンプルの一致");
        }
        helper.succeed();
    }

    private static ProtoChunk chunk(GameTestHelper helper, FixedMapChunkGenerator generator, ChunkPos pos, RandomState random) {
        var level = helper.getLevel();
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(-128,704),
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
        return chunk;
    }

    private static long hash(ProtoChunk chunk) {
        long hash = 0xcbf29ce484222325L;
        var pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = -128; y < 576; y++) {
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
                var column = generator.getBaseColumn(x, z, LevelHeightAccessor.create(-128,704), second);
                for (int y = -128; y < 576; y++) {
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
        int[][] points = {{-8500,-8192,1,100}, {-5500,-8192,2,100}, {-10000,-10650,3,110},
                {-12400,-5300,4,100}, {-6000,-5000,5,100}, {-4000,-3700,6,90}, {-4000,-14100,7,100}};
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
            if (point[2] == 3) {
                var rock = base.getBlock(point[3] - 4);
                helper.assertTrue(rock.is(Blocks.GRANITE) || rock.is(BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("gneiss")))
                        || rock.is(BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("quartzite"))), "侵食で露出する山脈の母岩");
            }
            if (point[2] == 1 || point[2] == 4) {
                String[] expected = point[2] == 1
                        ? new String[]{"minecraft:sandstone", "ashen_frontier:shale", "ashen_frontier:limestone", "minecraft:granite"}
                        : new String[]{"minecraft:sandstone", "ashen_frontier:gypsum", "ashen_frontier:rock_salt", "ashen_frontier:shale", "minecraft:stone"};
                int previous = -1, layers = 0;
                for (int y = point[3] - 4; y > -64; y--) {
                    for (int index = 0; index < expected.length; index++) {
                        if (!base.getBlock(y).is(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(expected[index])))) continue;
                        helper.assertTrue(index >= previous, "侵食・尖滅後も上下の地層順を逆転しない");
                        if (index > previous) layers++;
                        previous = index;
                    }
                }
                helper.assertTrue(layers >= 2, "地表で切られた平野・乾燥地域にも複数の地層が残る");
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
                    var surface = column.getBlock(63);
                    helper.assertTrue(surface.is(BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("polluted_seawater")))
                            || surface.is(BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("sea_ice")))
                            && column.getBlock(62).is(BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("polluted_seawater"))),
                            "海面63の汚染海水、または同じ海水の上の海氷");
                    coasts++;
                }
                if ((mask & 1) != 0 && (mask & 14) == 0 && shores == 0) {
                    helper.assertTrue(generator.terrain().surfaceY(x, z) >= 63, "海面と同高の乾いた砂浜を含む陸側海岸の高さ");
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
                helper.assertTrue(generated.getBlockState(new BlockPos(position.getMinBlockX(), -128, position.getMinBlockZ())).is(Blocks.BEDROCK), "計測チャンクを生成");
                int top = generated.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, 0, 0);
                var cap = generated.getBlockState(new BlockPos(position.getMinBlockX(), top - 6, position.getMinBlockZ()));
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
        int[] glacier = null;
        for (int z = -11920; z <= -11680 && glacier == null; z += 8) {
            for (int x = -8160; x <= -7840; x += 8) {
                var column = generator.terrain().column(x, z);
                int floor = column.surfaceY();
                while (column.state(floor).is(Blocks.SNOW_BLOCK)) floor--;
                if (column.state(floor).is(Blocks.PACKED_ICE) && column.state(floor - 2).is(Blocks.PACKED_ICE)) {
                    glacier = new int[]{x, z, 3, 0};
                    break;
                }
            }
        }
        helper.assertTrue(glacier != null, "急な山腹の間の谷に山岳氷河を残す");
        int[][] points = {{-4000, -14100, 8, 2}, glacier, {-3840, -14256, 12, 2}};
        for (var point : points) {
            int x = point[0], z = point[1];
            var column = generator.terrain().column(x, z);
            int surface = column.surfaceY();
            var generated = chunk(helper, generator, new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), helper.getLevel().getChunkSource().randomState());
            var top = generated.getBlockState(new BlockPos(x, surface, z));
            helper.assertTrue(top.is(Blocks.SNOW_BLOCK) || point[3] == 0 && top.is(Blocks.PACKED_ICE),
                    "氷床と谷の厚雪、急になった山岳氷河の露氷: "+x+","+surface+","+z+" "+top);
            int snow = 0;
            while (column.state(surface - snow).is(Blocks.SNOW_BLOCK)) snow++;
            helper.assertTrue(snow >= point[3] && snow <= 4, "傾斜に応じた積雪量");
            for (int depth = 1; depth <= point[2]; depth++) {
                var state = generated.getBlockState(new BlockPos(x, surface - snow + 1 - depth, z));
                helper.assertTrue(state.is(depth >= 4 ? Blocks.BLUE_ICE : Blocks.PACKED_ICE), "緯度・標高・谷に応じた厚い氷層: "+x+","+z+" depth="+depth+" surface="+surface+" snow="+snow+" "+state);
            }
            for (int y = -128; y < 576; y++) {
                helper.assertTrue(generated.getBlockState(new BlockPos(x, y, z)).equals(column.state(y)), "雪氷を含む実チャンクと列の一致");
            }
            helper.assertTrue(generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, helper.getLevel(), null)
                    == column.topY() + 1, "雪層・雪氷を含む地表高の一致");
        }
        for (var point : new int[][]{{-16385, -14100}, {16384, -14100}, {-4000, -16385}, {-4000, 16384}}) {
            var column = generator.terrain().column(point[0], point[1]);
            for (int y = -128; y < 576; y++) helper.assertTrue(column.state(y).isAir(), "範囲外へ雪氷を生成しない");
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
                boolean alpine = biome.is(AshenFrontierCore.id("alpine_wastes"));
                helper.assertTrue(alpine == (FixedTerrain.biomeIndex(terrain.map(), x, z, surface) == 11), "実バイオームが地表の移行帯と一致");
                if (alpine) above++; else below++;
                if (alpine != (surface >= 210)) corrected++;
                if (below >= 16 && above >= 16 && corrected > 0) break;
            }
        }
        helper.assertTrue(below >= 16 && above >= 16 && corrected > 0, "標高209〜210にも両側のバイオームが混在");
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

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void worldBorderSpawnAndAtmosphere(GameTestHelper helper) {
        var level = helper.getLevel();
        AshenFrontierWorld.configureFixedMap(level, generator(helper));
        level.setDefaultSpawnPos(new BlockPos(MapCoordinates.MAX, 100, 0), 0);
        helper.assertTrue(level.getLevelData().getSpawnPos().getX() == MapCoordinates.MAX,
                "ボーダー設定後に範囲外の保存スポーンを検査対象へ設定");
        AshenFrontierWorld.configureFixedMap(level, generator(helper));
        var border = helper.getLevel().getWorldBorder();
        helper.assertTrue(border.getCenterX() == 0 && border.getCenterZ() == 0, "ボーダー中心が原点");
        helper.assertTrue(border.getSize() == MapCoordinates.MAX - MapCoordinates.MIN, "ボーダー幅が32768");
        var spawn = helper.getLevel().getSharedSpawnPos();
        helper.assertTrue(MapCoordinates.contains(spawn.getX(), spawn.getZ()), "共有スポーンが固定マップ内");
        helper.assertTrue(border.isWithinBounds(spawn), "共有スポーンがワールドボーダー内");
        var data = level.getLevelData();
        helper.assertTrue(data.getSpawnPos().equals(new BlockPos(-8500, 100, -8192)),
                "保存スポーンそのものを初期地点へ戻す");
        level.setDefaultSpawnPos(new BlockPos(-8000, 110, -8000), 30);
        AshenFrontierWorld.configureFixedMap(level, generator(helper));
        helper.assertTrue(data.getSpawnPos().equals(new BlockPos(-8000, 110, -8000)) && data.getSpawnAngle() == 30,
                "範囲内の保存スポーンは変更しない");
        helper.assertTrue(BiomeAtmosphere.values().size() == 12, "12バイオーム分の大気データを読込");
        helper.assertTrue(BiomeAtmosphere.values().get(AshenFrontierCore.id("polluted_ocean")).density() > 0.7,
                "汚染された海に濃い霧を設定");
        helper.assertTrue(BiomeAtmosphere.values().get(AshenFrontierCore.id("alpine_wastes")).density() < 0.2,
                "高山を清浄な例外に設定");
        helper.assertTrue(BiomeAtmosphere.values().get(AshenFrontierCore.id("arid_wasteland")).dustParticle()
                        == BiomeAtmosphere.DustParticle.WHITE_ASH
                        && BiomeAtmosphere.values().get(AshenFrontierCore.id("industrial_wasteland")).dustParticle()
                        == BiomeAtmosphere.DustParticle.ASH
                        && BiomeAtmosphere.values().get(AshenFrontierCore.id("barren_land")).dustParticle()
                        == BiomeAtmosphere.DustParticle.ASH
                        && BiomeAtmosphere.values().get(AshenFrontierCore.id("warm_barren_land")).dustParticle()
                        == BiomeAtmosphere.DustParticle.ASH
                        && BiomeAtmosphere.values().values().stream()
                        .filter(value -> value.dustParticle() != BiomeAtmosphere.DustParticle.NONE).count() == 4,
                "砂塵の対象4地域と粒子種別を設定");
        helper.assertTrue(BiomeAtmosphere.values().get(AshenFrontierCore.id("temperate_wasteland")).dustParticle()
                        == BiomeAtmosphere.DustParticle.NONE
                        && BiomeAtmosphere.values().get(AshenFrontierCore.id("cold_research_wastes")).dustParticle()
                        == BiomeAtmosphere.DustParticle.NONE,
                "通常地域と寒冷地域は砂塵なし");
        helper.succeed();
    }
}
