package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.JsonOps;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Set;
import java.util.Arrays;
import com.mojang.logging.LogUtils;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContaminationGameTests {
    private static final SoilContamination SAMPLE = new SoilContamination(1.5e-4, 2.5e-5, .8, 4.5, 8000, 1e8);

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void breakPlaceAndChunkPersistence(GameTestHelper helper) {
        var level = helper.getLevel();
        var source = helper.absolutePos(new BlockPos(2, 3, 2));
        level.setBlockAndUpdate(source, Blocks.DIRT.defaultBlockState());
        BlockContamination.set(level, source, SAMPLE);
        level.destroyBlock(source, true);
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(source).inflate(1));
        helper.assertTrue(drops.size() == 1, "実際の破壊で土が1個落ちる");
        var stack = drops.getFirst().getItem().copy();
        drops.forEach(ItemEntity::discard);
        helper.assertTrue(stack.get(ItemProperties.CONTAMINATION) != null, "採取イベントが成分を設定");
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        stack = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, stack).getOrThrow()).getOrThrow();
        var destination = helper.absolutePos(new BlockPos(6, 3, 6));
        level.setBlockAndUpdate(destination.below(), Blocks.STONE.defaultBlockState());
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.OFF_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(destination.below()), Direction.UP, destination.below(), false);
        var result = CommonHooks.onPlaceItemIntoWorld(new UseOnContext(player, InteractionHand.OFF_HAND, hit));
        helper.assertTrue(result.consumesAction(), "オフハンドの最後の1個を実際に設置できる");
        helper.assertTrue(BlockContamination.get(level, destination).orElseThrow().equals(SAMPLE), "設置後も6指標が一致");
        var chunk = level.getChunkAt(destination);
        var saved = ChunkSerializer.write(level, chunk);
        var loaded = ChunkSerializer.read(level, level.getPoiManager(),
                new RegionStorageInfo("contamination_test", level.dimension(), "chunk"), chunk.getPos(), saved);
        helper.assertTrue(loaded.getData(BlockContamination.CHANGES).get(destination.asLong()).equals(SAMPLE), "チャンク保存・読込みで保持");
        BlockContamination.set(level, destination, SoilContamination.CLEAN);
        helper.assertTrue(level.getChunkAt(destination).getData(BlockContamination.CHANGES).containsKey(destination.asLong()), "清浄化も明示保存");
        var cleanStack = new ItemStack(Items.DIRT);
        player.setItemInHand(InteractionHand.MAIN_HAND, cleanStack);
        level.removeBlock(destination, false);
        CommonHooks.onPlaceItemIntoWorld(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.assertTrue(BlockContamination.get(level, destination).orElseThrow().equals(SoilContamination.CLEAN), "成分のない持込み土も清浄");
        helper.assertTrue(BlockContamination.get(level, destination.below()).isEmpty(), "岩は対象外");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 200)
    public static void actualGeneratedSoilAndOldIds(GameTestHelper helper) {
        var preset = helper.getLevel().registryAccess().registryOrThrow(Registries.WORLD_PRESET)
                .get(ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map")));
        var generator = (FixedMapChunkGenerator) preset.createWorldDimensions().overworld();
        for (var point : new int[][]{{-6100,-5200},{-12400,-5400},{-7800,-7900},{-5600,-8192}}) {
            int x = point[0], z = point[1];
            var column = generator.terrain().column(x, z);
            var position = new net.minecraft.world.level.ChunkPos(x >> 4, z >> 4);
            var chunk = new net.minecraft.world.level.chunk.ProtoChunk(position, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    helper.getLevel(), helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.fillFromNoise(net.minecraft.world.level.levelgen.blending.Blender.empty(),
                    helper.getLevel().getChunkSource().randomState(), helper.getLevel().structureManager(), chunk).join();
            var pos = new BlockPos(x, column.surfaceY(), z);
            helper.assertTrue(BlockContamination.isTarget(chunk.getBlockState(pos)), "生成された土・堆積物が汚染対象: " + pos);
            helper.assertTrue(!chunk.hasData(BlockContamination.CHANGES), "未改変チャンクには汚染保存なし");
            if (x == -5600) helper.assertTrue(column.contamination(pos.getY()).equals(SoilContamination.CLEAN), "予備地の実生成列は清浄");
            else helper.assertTrue(column.contamination(pos.getY()).severe(), "発生源の実生成列は高段階");
        }
        for (String old : Set.of("contaminated_soil", "toxic_soil")) {
            helper.assertTrue(!BuiltInRegistries.BLOCK.containsKey(AshenFrontierCore.id(old)), "旧ブロックIDなし");
            helper.assertTrue(!BuiltInRegistries.ITEM.containsKey(AshenFrontierCore.id(old)), "旧Item IDなし");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void itemBoundariesAndThinLayers(GameTestHelper helper) {
        var layer = BuiltInRegistries.ITEM.get(AshenFrontierCore.id("silt_layer"));
        for (var kind : SoilContamination.Kind.values()) for (int stage = 1; stage <= 4; stage++) {
            double edge = kind.boundary(stage);
            for (double value : new double[]{Math.nextDown(edge), edge, Math.nextUp(edge)}) {
                var sample = new SoilContamination(kind == SoilContamination.Kind.METALS ? value : SAMPLE.lead(),
                        kind == SoilContamination.Kind.ORGANICS ? value : SAMPLE.pcb(),
                        kind == SoilContamination.Kind.SALTS ? value : SAMPLE.conductivity(),
                        kind == SoilContamination.Kind.ACIDITY ? value : SAMPLE.ph(),
                        kind == SoilContamination.Kind.RADIOACTIVE ? value : SAMPLE.caesium137(),
                        kind == SoilContamination.Kind.BIOLOGICAL ? value : SAMPLE.fecalColiform());
                var stack = new ItemStack(layer);
                BlockContamination.writeItem(stack, sample);
                helper.assertTrue(stack.get(ItemProperties.RADIOACTIVITY).becquerels() == sample.caesium137() * .125,
                        "薄層1アイテムの総Bqは0.125 kg分");
                helper.assertTrue(BlockContamination.readItem(stack).equals(sample), "薄層0.125 kgのBq換算・境界精度を保持");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void fallingSandSurvivesEntitySaveAndLanding(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(9, 7, 9));
        var destination = helper.absolutePos(new BlockPos(9, 2, 9));
        level.setBlockAndUpdate(destination.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(origin, Blocks.SAND.defaultBlockState());
        BlockContamination.set(level, origin, SAMPLE);
        var falling = net.minecraft.world.entity.item.FallingBlockEntity.fall(level, origin, level.getBlockState(origin));
        var saved = new net.minecraft.nbt.CompoundTag();
        falling.saveWithoutId(saved);
        falling.discard();
        var loaded = net.minecraft.world.entity.EntityType.FALLING_BLOCK.create(level);
        loaded.load(saved);
        level.addFreshEntity(loaded);
        for (int i = 0; i < 60 && loaded.isAlive(); i++) loaded.tick();
        helper.assertTrue(level.getBlockState(destination).is(Blocks.SAND), "保存した落下砂が着地");
        helper.assertTrue(BlockContamination.get(level, destination).orElseThrow().equals(SAMPLE), "落下中の保存・着地後も汚染値を保持");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void differentLayerConcentrationsCannotBeMixed(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(10, 3, 4));
        var layer = BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("silt_layer"));
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, layer.defaultBlockState());
        BlockContamination.set(level, pos, SAMPLE);
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        var stack = new ItemStack(layer.asItem(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(level.getBlockState(pos).getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS) == 1,
                "清浄土と汚染土の混合を拒否");
        helper.assertTrue(stack.getCount() == 2 && BlockContamination.get(level, pos).orElseThrow().equals(SAMPLE), "拒否で消費・値変更なし");
        BlockContamination.writeItem(stack, SAMPLE);
        player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(level.getBlockState(pos).getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS) == 2,
                "同じ濃度なら積層できる");
        helper.assertTrue(BlockContamination.get(level, pos).orElseThrow().equals(SAMPLE), "積層で濃度は不変");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void highContaminationHasDeadVegetation(GameTestHelper helper) {
        var preset = helper.getLevel().registryAccess().registryOrThrow(Registries.WORLD_PRESET)
                .get(ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map")));
        var terrain = ((FixedMapChunkGenerator) preset.createWorldDimensions().overworld()).terrain();
        var grass = BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("dead_grass"));
        int high = 0, clean = 0;
        for (int dx = -16; dx < 16; dx++) for (int dz = -16; dz < 16; dz++) {
            var polluted = terrain.column(-6100 + dx, -5200 + dz);
            var reserve = terrain.column(-5600 + dx, -8192 + dz);
            if (polluted.state(polluted.surfaceY() + 1).is(grass)) high++;
            if (reserve.state(reserve.surfaceY() + 1).is(grass)) clean++;
        }
        helper.assertTrue(high > 0 && clean == 0, "高段階だけに既存の枯草を生成");
        var pos = helper.absolutePos(new BlockPos(3, 3, 3));
        var level = helper.getLevel();
        level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(pos.above(), Blocks.SHORT_GRASS.defaultBlockState());
        var stack = new ItemStack(Items.DIRT);
        BlockContamination.writeItem(stack, SAMPLE);
        BlockContamination.placed(level, pos, stack);
        helper.assertTrue(level.getBlockState(pos.above()).is(grass), "高段階の持込みで直上の草が枯れる");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 400)
    public static void contaminationSamplingBenchmark(GameTestHelper helper) {
        var preset = helper.getLevel().registryAccess().registryOrThrow(Registries.WORLD_PRESET)
                .get(ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map")));
        var map = ((FixedMapChunkGenerator) preset.createWorldDimensions().overworld()).terrain().map();
        var distribution = new FixedContamination(map);
        int[][] centers = {{-10050,-10800},{-6100,-5200},{-4100,-3900},{-12400,-5400},{-7000,-6200},{-7800,-7900},{-8500,-8192},{-5600,-8192}};
        var coast = new double[1000][256];
        var river = new RiverCourse.Sample[1000][256];
        var xs = new int[1000]; var zs = new int[1000];
        for (int i = 0; i < 1000; i++) {
            var center = centers[i % centers.length];
            xs[i] = (center[0] >> 4) * 16 + (i % 10 - 5) * 16;
            zs[i] = (center[1] >> 4) * 16 + (i / 10 % 10 - 5) * 16;
            for (int p = 0; p < 256; p++) {
                int x = xs[i] + (p & 15), z = zs[i] + (p >> 4);
                coast[i][p] = map.coastDistance(x, z);
                river[i][p] = map.river(x, z);
            }
        }
        var times = new long[1000];
        double checksum = 0;
        for (int i = -100; i < 1000; i++) {
            int n = Math.max(0, i);
            long before = System.nanoTime();
            for (int p = 0; p < 256; p++)
                checksum += distribution.surface(xs[n] + (p & 15), zs[n] + (p >> 4), coast[n][p], river[n][p]).lead();
            if (i >= 0) times[i] = System.nanoTime() - before;
        }
        Arrays.sort(times);
        LogUtils.getLogger().info("CONTAMINATION_BENCHMARK count=1000 warmup=100 parallelism=1 reused_coast_river=true p50_ms={} p95_ms={} checksum={}",
                times[499] / 1e6, times[949] / 1e6, checksum);
        helper.assertTrue(Double.isFinite(checksum), "分布計算を実行して測定");
        helper.succeed();
    }
}
