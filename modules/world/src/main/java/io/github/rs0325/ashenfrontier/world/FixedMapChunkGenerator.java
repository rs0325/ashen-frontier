package io.github.rs0325.ashenfrontier.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** 固定マップ生成の入口。現在はY=63までの仮地形で、バニラの構造物・装飾は生成しない。 */
public final class FixedMapChunkGenerator extends ChunkGenerator {
    public static final MapCodec<FixedMapChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            FixedMapBiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> (FixedMapBiomeSource) g.biomeSource),
            MapVersion.CODEC.fieldOf("map").forGetter(FixedMapChunkGenerator::mapVersion)
    ).apply(i, FixedMapChunkGenerator::new));
    private final MapVersion mapVersion;

    /** 同梱版との一致を検証する。不足・不一致なら生成開始前に例外で停止する。 */
    public FixedMapChunkGenerator(FixedMapBiomeSource biomeSource, MapVersion mapVersion) {
        super(biomeSource);
        BundledMaps.require(mapVersion);
        this.mapVersion = mapVersion;
    }

    /** このワールドが選択した不変の版。最新同梱版への自動更新はしない。 */
    public MapVersion mapVersion() { return mapVersion; }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> lookup, RandomState random, long seed) {
        return ChunkGeneratorStructureState.createForFlat(random, seed, biomeSource, Stream.<Holder<StructureSet>>empty());
    }

    private BlockState sample(int x, int y, int z) {
        if (!MapCoordinates.contains(x, z) || y < -64 || y > 63) return Blocks.AIR.defaultBlockState();
        return (y == -64 ? Blocks.BEDROCK : Blocks.STONE).defaultBlockState();
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
        if (!MapCoordinates.contains(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ())) {
            return CompletableFuture.completedFuture(chunk);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        for (int y = Math.max(-64, chunk.getMinBuildHeight()); y < Math.min(64, chunk.getMaxBuildHeight()); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int blockX = chunk.getPos().getMinBlockX() + x;
                    int blockZ = chunk.getPos().getMinBlockZ() + z;
                    BlockState state = sample(blockX, y, blockZ);
                    chunk.setBlockState(pos.set(blockX, y, blockZ), state, false);
                    floor.update(x, y, z, state);
                    surface.update(x, y, z, state);
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        for (int y = Math.min(63, level.getMaxBuildHeight() - 1); y >= level.getMinBuildHeight(); y--) {
            if (type.isOpaque().test(sample(x, y, z))) return y + 1;
        }
        return level.getMinBuildHeight();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        BlockState[] states = new BlockState[level.getHeight()];
        for (int i = 0; i < states.length; i++) states[i] = sample(x, level.getMinBuildHeight() + i, z);
        return new NoiseColumn(level.getMinBuildHeight(), states);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structures, RandomState random, ChunkAccess chunk) {}
    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomes,
                             StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {}
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {}
    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {}
    @Override
    public int getMinY() { return -64; }
    @Override
    public int getGenDepth() { return 384; }
    @Override
    public int getSeaLevel() { return MapCoordinates.SEA_LEVEL; }
    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("Fixed map: " + mapVersion.mapVersion());
    }
}
