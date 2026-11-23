package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import javax.imageio.ImageIO;

/** 配布jarに含めない、実サンプラーの比較画像出力。 */
@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerrainPreviewGameTests {
    static FixedMapChunkGenerator generator(GameTestHelper helper) {
        var key = ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map"));
        return (FixedMapChunkGenerator) helper.getLevel().registryAccess().registryOrThrow(Registries.WORLD_PRESET)
                .getHolderOrThrow(key).value().createWorldDimensions().overworld();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void terrainWithoutUndergroundBenchmark(GameTestHelper helper) {
        var generator = generator(helper);
        var positions = new ArrayList<ChunkPos>();
        for (int z = -600; z < -400 && positions.size() < 1000; z++) {
            for (int x = -600; x < -400 && positions.size() < 1000; x++) {
                var cell = generator.terrain().underground().cell(x * 16, z * 16);
                if (cell.bodies().isEmpty() && cell.passages().isEmpty()) positions.add(new ChunkPos(x, z));
            }
        }
        helper.assertTrue(positions.size() == 1000,"地下候補のない1000チャンクを計測");
        long[] times = new long[1000];
        var level = helper.getLevel();
        for (int i = -100; i < 1000; i++) {
            var position = positions.get(Math.max(0, i));
            long before = System.nanoTime();
            var chunk = new ProtoChunk(position, UpgradeData.EMPTY, LevelHeightAccessor.create(-128,704), level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.fillFromNoise(Blender.empty(), level.getChunkSource().randomState(), level.structureManager(), chunk).join();
            if (i >= 0) times[i] = System.nanoTime() - before;
            helper.assertTrue(!chunk.getBlockState(new BlockPos(position.getMinBlockX(), -128, position.getMinBlockZ())).isAir(),"実チャンクを生成");
        }
        Arrays.sort(times);
        LogUtils.getLogger().info("TERRAIN_ONLY_BENCHMARK count=1000 warmup=100 parallelism=1 underground_candidates=0 p50_ms={} p95_ms={} max_ms={}",
                times[499] / 1e6, times[949] / 1e6, times[999] / 1e6);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void entrancesStillReachGround(GameTestHelper helper) {
        var terrain = generator(helper).terrain();
        for (var passage : terrain.underground().passages()) {
            boolean entrance = passage.id().endsWith("_entry") || terrain.underground().bodies().stream()
                    .anyMatch(body -> body.data().id().equals(passage.id()));
            if (entrance) helper.assertTrue(passage.ay() == terrain.surfaceY(passage.ax(), passage.az()),
                    "入口は地表に接続: " + passage.id()+" "+passage.ax()+","+passage.az()+" "+passage.ay()+" != "+terrain.surfaceY(passage.ax(),passage.az()));
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void exportTerrain(GameTestHelper helper) throws Exception {
        String phase = System.getenv("ASHEN_TERRAIN_PREVIEW");
        if (phase == null) { helper.succeed(); return; }
        var terrain = generator(helper).terrain();
        var out = Path.of("../../../../../tools/fixed-map/preview", phase).normalize();
        Files.createDirectories(out);
        int[][] points = {{-8500, -8192}, {-10000, -10650}, {-8000, -11800}, {-12400, -5300},
                {-6000, -5000}, {-4000, -3700}, {-4000, -14100}, {-5000, -8200}, {-3500, -5000},
                {-5024, -8272}, {-11216, -6880}};
        for (var point : points) {
            int cx = point[0], cz = point[1], size = 513;
            var heights = new StringBuilder("x,z,height,water,soil,region,coast,detail_weight\n");
            for (int dz = -256; dz <= 256; dz += 2) for (int dx = -256; dx <= 256; dx += 2) {
                int x = cx + dx, z = cz + dz;
                var column = terrain.column(x, z);
                heights.append(x).append(',').append(z).append(',').append(column.surfaceY()).append(',')
                        .append(terrain.map().waterSurface(x, z)).append(',')
                        .append(BuiltInRegistries.BLOCK.getKey(column.state(column.surfaceY()).getBlock())).append(',')
                        .append(terrain.map().region(x, z)).append(',')
                        .append(terrain.map().coastDistance(x, z)).append(',')
                        .append(terrain.map().detailWeight(x, z)).append('\n');
            }
            String name = cx + "_" + cz;
            Files.writeString(out.resolve(name + ".csv"), heights);
            var section = new BufferedImage(size, 704, BufferedImage.TYPE_INT_RGB);
            for (int dx = -256; dx <= 256; dx++) {
                var column = terrain.column(cx + dx, cz);
                for (int y = -128; y < 448; y++) {
                    var state = column.state(y);
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    int color = state.isAir() ? 0xc3e0fa : !state.getFluidState().isEmpty() ? 0x397cae
                            : 0x404040 | (id.hashCode() & 0xbfbfbf);
                    section.setRGB(dx + 256, 575 - y, color);
                }
            }
            ImageIO.write(section, "png", out.resolve(name + "-section.png").toFile());
            if (cx == -11216) {
                var profile = new StringBuilder("x,z,height,coast\n");
                for (int dx = -2048; dx <= 512; dx++) {
                    int x = cx + dx;
                    profile.append(x).append(',').append(cz).append(',').append(terrain.surfaceY(x, cz)).append(',')
                            .append(terrain.map().coastDistance(x, cz)).append('\n');
                }
                Files.writeString(out.resolve(name + "-wide-profile.csv"), profile);
            }
        }
        var ocean = new StringBuilder("x,z,height,coast\n");
        for (int x = -16384; x <= -12000; x++) {
            ocean.append(x).append(',').append(-8192).append(',').append(terrain.surfaceY(x, -8192)).append(',')
                    .append(terrain.map().coastDistance(x, -8192)).append('\n');
        }
        Files.writeString(out.resolve("west-ocean-profile.csv"), ocean);
        helper.succeed();
    }
}
