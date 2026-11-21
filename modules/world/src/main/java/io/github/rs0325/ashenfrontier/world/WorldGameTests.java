package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.JsonOps;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Set;

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
    public static void terrainColumnsAndBoundary(GameTestHelper helper) {
        var generator = generator(helper);
        var level = helper.getLevel();
        var random = level.getChunkSource().randomState();
        for (int x : new int[]{-16385, -16384, 16383, 16384}) {
            var chunk = new ProtoChunk(new ChunkPos(Math.floorDiv(x, 16), 0), UpgradeData.EMPTY, level,
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
            var column = generator.getBaseColumn(x, 0, level, random);
            for (int y : new int[]{-64, -63, 63, 64, 319}) {
                helper.assertTrue(chunk.getBlockState(new BlockPos(x, y, 0)).equals(column.getBlock(y)), "実チャンクと列サンプルの一致");
            }
            boolean inside = MapCoordinates.contains(x, 0);
            helper.assertTrue(column.getBlock(63).is(inside ? Blocks.STONE : Blocks.AIR), "端の内外の地形");
            helper.assertTrue(generator.getBaseHeight(x, 0, Heightmap.Types.WORLD_SURFACE_WG, level, random)
                    == (inside ? 64 : -64), "高さサンプルの一致");
        }
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
