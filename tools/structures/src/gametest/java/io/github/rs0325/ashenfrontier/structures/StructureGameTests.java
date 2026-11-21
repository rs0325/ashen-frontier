package io.github.rs0325.ashenfrontier.structures;

import com.google.gson.Gson;
import io.github.rs0325.ashenfrontier.world.AshenFrontierWorld;
import io.github.rs0325.ashenfrontier.world.structure.FacilityManifest;
import io.github.rs0325.ashenfrontier.world.structure.FacilityPlacement;
import io.github.rs0325.ashenfrontier.world.structure.LootMarkerProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.github.rs0325.ashenfrontier.core.catalog.RelicEquipmentEntity;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StructureGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void lootProcessorCodecAndSeed(GameTestHelper helper) {
        var pos = new BlockPos(-17, 80, -1);
        var marker = new LootMarkerProcessor.Marker(pos, ResourceLocation.parse("ashen_frontier:buildings/temperate/fixture"));
        var processor = new LootMarkerProcessor(12L, List.of(marker));
        var ops = JsonOps.INSTANCE;
        var encoded = LootMarkerProcessor.CODEC.codec().encodeStart(ops, processor).getOrThrow();
        var restored = LootMarkerProcessor.CODEC.codec().parse(ops, encoded).getOrThrow();
        var original = new StructureTemplate.StructureBlockInfo(pos, Blocks.CHEST.defaultBlockState(), new CompoundTag());
        var settings = new StructurePlaceSettings();
        var first = processor.processBlock(helper.getLevel(), BlockPos.ZERO, BlockPos.ZERO, original, original, settings);
        var again = restored.processBlock(helper.getLevel(), BlockPos.ZERO, BlockPos.ZERO, original, original, settings);
        var otherSeed = new LootMarkerProcessor(13L, List.of(marker)).processBlock(helper.getLevel(), BlockPos.ZERO, BlockPos.ZERO, original, original, settings);
        helper.assertTrue(first.equals(again), "lootプロセッサCodecとseedの再現性");
        helper.assertTrue(first.nbt().getLong("LootTableSeed") != otherSeed.nbt().getLong("LootTableSeed"), "loot seedはワールドごとに変わる");
        long zeroSeed = Mth.getSeed(pos) ^ 0x58d3a72e6914bc01L;
        var zero = new LootMarkerProcessor(zeroSeed, List.of(marker)).processBlock(helper.getLevel(), BlockPos.ZERO, BlockPos.ZERO, original, original, settings);
        helper.assertTrue(zero.nbt().getLong("LootTableSeed") != 0, "標準lootの非固定乱数を意味するseed=0を避ける");
        helper.succeed();
    }
    private static FacilityManifest fixture() {
        var stream = StructureGameTests.class.getResourceAsStream("/data/ashen_frontier/structures/fixture_001/index.json");
        if (stream == null) throw new IllegalStateException("変換fixtureがありません");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, FacilityManifest[].class)[0];
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** WorldGenLevelの境界外アクセスを失敗させ、標準template自体のチャンク制約を検証する。 */
    private static WorldGenLevel bounded(GameTestHelper helper, ChunkPos allowed) {
        var level = helper.getLevel();
        return (WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
            if (args != null) for (var arg : args) if (arg instanceof BlockPos pos) {
                if (!new ChunkPos(pos).equals(allowed)) throw new AssertionError("隣接チャンクにアクセス: " + method.getName() + " " + pos);
            }
            if (method.getName().equals("ensureCanWrite")) return true;
            try { return method.invoke(level, args); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }

    private static ProtoChunk chunk(GameTestHelper helper, ChunkPos pos) {
        var level = helper.getLevel();
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
        chunk.setPersistedStatus(ChunkStatus.CARVERS);
        return chunk;
    }

    private static Map<BlockPos, String> snapshot(GameTestHelper helper, FacilityManifest manifest) throws Exception {
        Map<BlockPos, String> result = new HashMap<>();
        for (var piece : manifest.pieces()) {
            var id = ResourceLocation.parse(piece.template());
            try (var stream = StructureGameTests.class.getResourceAsStream("/data/" + id.getNamespace() + "/structure/" + id.getPath() + ".nbt")) {
                var tag = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
                for (var entry : tag.getList("blocks", Tag.TAG_COMPOUND)) {
                    var block = (CompoundTag)entry;
                    var local = StructureInput.listPos(block.getList("pos", Tag.TAG_INT)).offset(FacilityManifest.pos(piece.offset()));
                    var pos = manifest.transform(local);
                    var state = helper.getLevel().getBlockState(pos);
                    var be = helper.getLevel().getBlockEntity(pos);
                    var data = be == null ? "" : be.saveWithFullMetadata(helper.getLevel().registryAccess()).toString();
                    result.put(pos, state + ":" + data);
                }
            }
        }
        return result;
    }

    private static void initialize(GameTestHelper helper, FacilityManifest manifest) {
        for (var cell : FixtureWriter.cells())
            helper.getLevel().setBlock(manifest.transform(cell.pos()), Blocks.GOLD_BLOCK.defaultBlockState(), 18);
    }

    private static FacilityManifest at(FacilityManifest source, BlockPos origin, Rotation rotation, Mirror mirror) {
        var manifest = new FacilityManifest(1, source.id(), new int[]{origin.getX(), origin.getY(), origin.getZ()}, rotation.name(), mirror.name(),
                source.sourceOffset(), source.rights(), source.pieces(), source.protectedPaths(), source.loot(), source.decay(), Map.of());
        return new FacilityManifest(1, manifest.id(), manifest.origin(), manifest.rotation(), manifest.mirror(), manifest.sourceOffset(),
                manifest.rights(), manifest.pieces(), manifest.protectedPaths(), manifest.loot(), manifest.decay(), manifest.index());
    }

    @GameTest(template = "structure_boundary", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void splitOrdersRotationsProtectionAndRevisit(GameTestHelper helper) throws Exception {
        var fixture = fixture();
        helper.assertTrue(fixture.pieces().size() == 4, "48を超えるX/Yを4断片へ分割");
        var origin = helper.absolutePos(new BlockPos(70, 2, 70));
        for (var rotation : Rotation.values()) for (var mirror : Mirror.values()) {
            var manifest = at(fixture, origin, rotation, mirror);
            var placement = new FacilityPlacement(List.of(manifest));
            var chunks = manifest.chunks().keySet().stream().map(key -> key.split(",")).map(p -> new ChunkPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]))).toList();
            var sentinel = manifest.transform(new BlockPos(0,2,0));
            var air = manifest.transform(new BlockPos(1,1,1));
            initialize(helper, manifest);
            var initial = snapshot(helper, manifest);
            for (var pos : chunks) placement.placeChunk(bounded(helper, pos), chunk(helper, pos));
            helper.assertTrue(helper.getLevel().getBlockState(sentinel).is(Blocks.GOLD_BLOCK), "structure_voidは元ブロックを保持");
            helper.assertTrue(helper.getLevel().getBlockState(air).isAir(), "airは元ブロックを消す");
            helper.assertTrue(helper.getLevel().getBlockEntity(manifest.transform(new BlockPos(5,1,1)))
                    instanceof RelicEquipmentEntity, "coreの静的設備BEを保持");
            var floor = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("ashen_frontier:reinforced_concrete_floor"));
            for (int x = 0; x < 65; x++) helper.assertTrue(helper.getLevel().getBlockState(manifest.transform(new BlockPos(x,1,0))).is(floor), "保護経路は劣化しない");
            for (var raw : FixtureWriter.cells()) {
                var state = raw.state();
                var pos = manifest.transform(raw.pos());
                if (state.getBlock() instanceof DoorBlock) {
                    var expected = StructureInput.state("ashen_frontier:corroded_steel_door");
                    for (var prop : state.getProperties()) expected = copy(expected, state, prop);
                    helper.assertTrue(helper.getLevel().getBlockState(pos).equals(expected.mirror(mirror).rotate(rotation)), "断片を跨ぐ扉の向きと上下");
                } else if (state.getBlock() instanceof BedBlock || state.getBlock() instanceof ChestBlock) {
                    helper.assertTrue(helper.getLevel().getBlockState(pos).equals(state.mirror(mirror).rotate(rotation)), "ベッド・二重チェストの対応と回転");
                }
            }
            for (var loot : manifest.loot()) {
                var be = helper.getLevel().getBlockEntity(manifest.transform(FacilityManifest.pos(loot.pos())));
                helper.assertTrue(be instanceof RandomizableContainer, "遅延loot容器");
                var tag = be.saveWithFullMetadata(helper.getLevel().registryAccess());
                helper.assertTrue(tag.getString("LootTable").equals(loot.table()) && !tag.contains("Items"), "入力の実物を削除し承認lootだけを設定");
            }
            var expected = snapshot(helper, manifest);
            var reverse = new ArrayList<>(chunks); Collections.reverse(reverse);
            initialize(helper, manifest);
            helper.assertTrue(initial.equals(snapshot(helper, manifest)), "逆順配置の前に同一の初期盤面と空のBEを復元");
            var reversedPlacement = new FacilityPlacement(List.of(manifest));
            for (var pos : reverse) reversedPlacement.placeChunk(bounded(helper, pos), chunk(helper, pos));
            helper.assertTrue(expected.equals(snapshot(helper, manifest)), "同じ初期盤面からの正順・逆順でブロック・BE・loot seedが一致");
            var edited = manifest.transform(new BlockPos(0,1,0));
            helper.getLevel().setBlock(edited, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
            var reloaded = new FacilityPlacement(List.of(new Gson().fromJson(new Gson().toJson(manifest), FacilityManifest.class)));
            for (var pos : reverse) {
                var completed = chunk(helper, pos); completed.setPersistedStatus(ChunkStatus.FEATURES);
                reloaded.placeChunk(bounded(helper, pos), completed);
            }
            helper.assertTrue(helper.getLevel().getBlockState(edited).is(Blocks.DIAMOND_BLOCK), "保存済み生成状態による再訪・再起動後の改変保持");
        }
        helper.succeed();
    }

    private static <T extends Comparable<T>> BlockState copy(
            BlockState target, BlockState source,
            Property<T> property) {
        return target.setValue(property, source.getValue(property));
    }
}
