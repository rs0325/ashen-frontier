package io.github.rs0325.ashenfrontier.world;

import com.mojang.logging.LogUtils;
import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.MiningRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UndergroundGameTests {
    private static FixedMapChunkGenerator generator(GameTestHelper helper) {
        var key = ResourceKey.create(Registries.WORLD_PRESET, AshenFrontierCore.id("fixed_map"));
        return (FixedMapChunkGenerator) helper.getLevel().registryAccess().registryOrThrow(Registries.WORLD_PRESET)
                .getHolderOrThrow(key).value().createWorldDimensions().overworld();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void sectionsEntrancesHostsAndProtectedAreas(GameTestHelper helper) {
        var terrain = generator(helper).terrain();
        var failures = new ArrayList<String>();
        for (var body : terrain.underground().bodies()) {
            var d = body.data();
            int count = 0;
            for (int x = d.x() - d.radii().get(0); x <= d.x() + d.radii().get(0); x++) {
                for (int z = d.z() - d.radii().get(2); z <= d.z() + d.radii().get(2); z++) {
                    var column = terrain.column(x, z);
                    for (int y = body.y() - d.radii().get(1); y <= body.y() + d.radii().get(1); y++) {
                        if (!body.contains(x, y, z, 0)) continue;
                        var state = column.state(y);
                        if (state.equals(body.ore())) {
                            helper.assertTrue(column.rockState(y).equals(body.host()), "母岩条件: " + d.id());
                            helper.assertTrue(!terrain.map().isProtected(x, z), "保護域へ鉱床を置かない");
                            count++;
                        }
                    }
                }
            }
            if (count == 0 || !terrain.column(d.x(), d.z()).state(body.y()).equals(body.ore())) {
                failures.add("鉱床中心: " + d.id());
            }
            LogUtils.getLogger().info("UNDERGROUND_DEPOSIT id={} xyz={},{},{} host={} count={}", d.id(), d.x(), body.y(), d.z(), d.host(), count);
            for (var other : terrain.underground().bodies()) {
                if (body == other) continue;
                helper.assertTrue(Math.abs(d.x() - other.data().x()) > d.radii().get(0) + other.data().radii().get(0)
                        || Math.abs(body.y() - other.y()) > d.radii().get(1) + other.data().radii().get(1)
                        || Math.abs(d.z() - other.data().z()) > d.radii().get(2) + other.data().radii().get(2), "鉱床同士の非重複");
            }
        }
        for (var passage : terrain.underground().passages()) {
            int length = Math.max(Math.abs(passage.bx() - passage.ax()), Math.abs(passage.bz() - passage.az()));
            int previous = passage.ay();
            for (int i = 0; i <= length; i++) {
                int x = passage.ax() + (passage.bx() - passage.ax()) * i / Math.max(1, length);
                int z = passage.az() + (passage.bz() - passage.az()) * i / Math.max(1, length);
                int floor = passage.floor(x, z);
                var column = terrain.column(x, z);
                if (Math.abs(floor - previous) > 1 || column.state(floor).isAir() || !column.state(floor).getFluidState().isEmpty()
                        || !column.state(floor + 1).isAir() || !column.state(floor + 2).isAir()) {
                    failures.add("通路: " + passage.id() + " " + x + "," + floor + "," + z
                            + " protected=" + terrain.map().isProtected(x, z) + " surface=" + column.surfaceY()
                            + " floor=" + column.state(floor) + " head=" + column.state(floor + 1));
                    break;
                }
                previous = floor;
            }
        }
        for (int x = -8620; x <= -8380; x += 4) for (int z = -8312; z <= -8072; z += 4) {
            var column = terrain.column(x, z);
            if (terrain.map().isProtected(x, z)) for (int y = 75; y <= Math.min(100, column.surfaceY()); y++) {
                helper.assertTrue(!column.state(y).isAir(), "シェルター床と入口保護");
            }
        }
        for (var failure : failures) LogUtils.getLogger().error("UNDERGROUND_FAILURE {}", failure);
        helper.assertTrue(failures.isEmpty(), failures.toString());
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void generatedDepositsMatchAcrossWorldSeeds(GameTestHelper helper) {
        var generator = generator(helper);
        var level = helper.getLevel();
        var settings = level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var noise = level.registryAccess().lookupOrThrow(Registries.NOISE);
        var a = RandomState.create(settings, noise, 1L);
        var b = RandomState.create(settings, noise, -9918273L);
        for (var body : generator.terrain().underground().bodies()) {
            var d = body.data();
            var pos = new ChunkPos(d.x() >> 4, d.z() >> 4);
            var first = chunk(helper, generator, pos, a);
            var second = chunk(helper, generator, pos, b);
            for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
                var column = generator.getBaseColumn(x, z, level, b);
                for (int y = -64; y < 320; y++) {
                    var at = new BlockPos(x, y, z);
                    helper.assertTrue(first.getBlockState(at).equals(second.getBlockState(at)), "別seedの配置・量一致: " + d.id());
                    helper.assertTrue(first.getBlockState(at).equals(column.getBlock(y)), "実チャンクとサンプラー一致");
                }
            }
        }
        helper.succeed();
    }

    private static ProtoChunk chunk(GameTestHelper helper, FixedMapChunkGenerator generator, ChunkPos pos, RandomState random) {
        var level = helper.getLevel();
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
        return chunk;
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void deepOilSourcesAreFinite(GameTestHelper helper) {
        var conversion = helper.getLevel().getGameRules().getRule(GameRules.RULE_WATER_SOURCE_CONVERSION);
        boolean previousConversion = conversion.get();
        var oils = generator(helper).terrain().underground().bodies().stream().filter(b -> b.data().id().startsWith("oil_")).toList();
        var positions = List.of(new BlockPos(2, 2, 2), new BlockPos(7, 2, 2), new BlockPos(12, 2, 2), new BlockPos(2, 2, 7));
        helper.assertTrue(oils.size() == 4, "原油4品質の深部油層");
        for (int i = 0; i < oils.size(); i++) {
            var body = oils.get(i);
            helper.assertTrue(body.y() < 0 && body.ore().getFluidState().isSource(), "深部のsource品質");
            var pos = positions.get(i);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(pos.offset(dx, -1, dz), Blocks.STONE);
                helper.setBlock(pos.offset(dx, 0, dz), Blocks.STONE);
            }
            helper.setBlock(pos.west(), body.ore());
            helper.setBlock(pos.north(), body.ore());
            helper.setBlock(pos, body.ore());
            helper.assertTrue(helper.getBlockState(pos.west()).getFluidState().isSource()
                    && helper.getBlockState(pos.north()).getFluidState().isSource(), "隣接する同品質の源2つを用意");
            helper.setBlock(pos, Blocks.AIR);
            helper.getLevel().scheduleTick(helper.absolutePos(pos.west()), body.ore().getFluidState().getType(), 1);
            helper.getLevel().scheduleTick(helper.absolutePos(pos.north()), body.ore().getFluidState().getType(), 1);
        }
        var control = new BlockPos(7, 2, 7);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            helper.setBlock(control.offset(dx, -1, dz), Blocks.STONE);
            helper.setBlock(control.offset(dx, 0, dz), Blocks.STONE);
        }
        helper.setBlock(control.west(), Blocks.WATER);
        helper.setBlock(control.north(), Blocks.WATER);
        helper.setBlock(control, Blocks.AIR);
        helper.runAfterDelay(40, () -> {
            try {
                conversion.set(true, helper.getLevel().getServer());
                for (int round = 0; round < 8; round++) {
                    for (int i = 0; i <= oils.size(); i++) {
                        var pos = i == oils.size() ? control : positions.get(i);
                        for (var source : List.of(pos.west(), pos.north(), pos)) {
                            var absolute = helper.absolutePos(source);
                            helper.getLevel().getFluidState(absolute).tick(helper.getLevel(), absolute);
                        }
                    }
                }
                helper.assertTrue(helper.getBlockState(control).getFluidState().isSource(), "対照のバニラ水は隣接源から再生する: rule="
                        + conversion.get() + " center=" + helper.getBlockState(control) + " west=" + helper.getBlockState(control.west())
                        + " north=" + helper.getBlockState(control.north()) + " floor=" + helper.getBlockState(control.below()));
                for (int i = 0; i < oils.size(); i++) {
                    var pos = positions.get(i);
                    helper.assertTrue(!helper.getBlockState(pos).getFluidState().isSource(), "隣接源があっても消費した油源が再生しない");
                    helper.assertTrue(helper.getBlockState(pos.west()).getFluidState().isSource()
                            && helper.getBlockState(pos.north()).getFluidState().isSource(), "隣接する油源を維持");
                }
                helper.succeed();
            } finally {
                conversion.set(previousConversion, helper.getLevel().getServer());
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void generatedOreUsesCoreMiningRules(GameTestHelper helper) {
        var terrain = generator(helper).terrain();
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "underground_miner"));
        player.setOnGround(true);
        for (var body : terrain.underground().bodies()) {
            if (!body.data().block().endsWith("_ore") || Boolean.TRUE.equals(body.data().vanilla())) continue;
            var state = terrain.column(body.data().x(), body.data().z()).state(body.y());
            var pos = helper.absolutePos(new BlockPos(1, 1, 1));
            helper.getLevel().setBlock(pos, state, 2);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(!MiningRules.canMine(state, ItemStack.EMPTY), "鉱石は素手で掘れない");
            helper.assertTrue(state.getDestroyProgress(player, helper.getLevel(), pos) == 0, "素手の採掘進行0");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            float oreTime = state.getDestroyProgress(player, helper.getLevel(), pos);
            float hostTime = body.host().getDestroyProgress(player, helper.getLevel(), pos);
            helper.assertTrue(oreTime > 0 && Math.abs(oreTime - hostTime) < 0.00001, "母岩の硬さに従う採掘: " + body.data().id() + " state=" + state + " ore=" + oreTime + " host=" + hostTime);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void undergroundGenerationBenchmark(GameTestHelper helper) {
        var generator = generator(helper);
        var random = helper.getLevel().getChunkSource().randomState();
        var bodies = generator.terrain().underground().bodies();
        var underground = generator.terrain().underground();
        var groups = new LinkedHashMap<String, LinkedHashSet<ChunkPos>>();
        groups.put("deposits", new LinkedHashSet<>());
        for (var body : bodies) {
            for (int dx = -1; dx <= 1; dx++) groups.get("deposits").add(new ChunkPos((body.data().x() >> 4) + dx, body.data().z() >> 4));
        }
        for (var passage : underground.passages()) {
            String group = passage.id().endsWith("_entry") ? "entrances" : switch (passage.id()) {
                case "city_metro", "mining_gallery", "industry_utility", "fixed_cave" -> passage.id();
                default -> "resource_access";
            };
            var points = groups.computeIfAbsent(group, ignored -> new LinkedHashSet<>());
            int length = Math.max(Math.abs(passage.bx() - passage.ax()), Math.abs(passage.bz() - passage.az()));
            for (int i = 0; i <= length; i++) {
                int x = passage.ax() + (passage.bx() - passage.ax()) * i / Math.max(1, length);
                int z = passage.az() + (passage.bz() - passage.az()) * i / Math.max(1, length);
                for (int dx : new int[]{-passage.width(), passage.width()}) for (int dz : new int[]{-passage.width(), passage.width()}) {
                    points.add(new ChunkPos((x + dx) >> 4, (z + dz) >> 4));
                }
            }
        }
        var all = new LinkedHashSet<ChunkPos>();
        groups.values().forEach(all::addAll);
        var dense = all.stream().sorted((a, b) -> Integer.compare(candidateCount(underground, b), candidateCount(underground, a))).limit(8).toList();
        groups.put("dense", new LinkedHashSet<>(dense));
        helper.assertTrue(candidateCount(underground, dense.getFirst()) >= 2, "複数の候補が交差するチャンクを計測");
        LogUtils.getLogger().info("UNDERGROUND_BENCHMARK_DENSE chunks={} max_candidates={}", dense, candidateCount(underground, dense.getFirst()));
        long[] combined = new long[groups.size() * 200];
        int offset = 0;
        for (var entry : groups.entrySet()) {
            List<ChunkPos> points = List.copyOf(entry.getValue());
            long[] times = new long[200];
            for (int i = -20; i < times.length; i++) {
                var pos = points.get(Math.floorMod(i, points.size()));
                long before = System.nanoTime();
                chunk(helper, generator, pos, random);
                if (i >= 0) times[i] = System.nanoTime() - before;
            }
            System.arraycopy(times, 0, combined, offset, times.length);
            offset += times.length;
            Arrays.sort(times);
            LogUtils.getLogger().info("UNDERGROUND_BENCHMARK_GROUP group={} unique_chunks={} count=200 warmup=20 p50_ms={} p95_ms={} max_ms={}",
                    entry.getKey(), points.size(), times[99] / 1e6, times[189] / 1e6, times[199] / 1e6);
        }
        Arrays.sort(combined);
        LogUtils.getLogger().info("UNDERGROUND_BENCHMARK count={} groups={} unique_chunks={} warmup_per_group=20 parallelism=1 p50_ms={} p95_ms={} max_ms={}",
                combined.length, groups.size(), all.size(), combined[combined.length / 2 - 1] / 1e6,
                combined[(int) (combined.length * .95) - 1] / 1e6, combined[combined.length - 1] / 1e6);
        helper.succeed();
    }

    private static int candidateCount(FixedUnderground underground, ChunkPos pos) {
        var cell = underground.cell(pos.getMinBlockX(), pos.getMinBlockZ());
        return cell.bodies().size() + cell.passages().size();
    }
}
