package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 受信済みチャンクだけを参照し、地図の元タイルへ1ブロックずつ実測値を書き込む。 */
final class MapChunkSampler {
    private static final Set<Fluid> WATER_FLUIDS = waterFluids();

    private MapChunkSampler() {}

    private static Set<Fluid> waterFluids() {
        var fluids = NatureContent.fluids();
        return NatureCatalog.entries().stream().filter(entry -> entry.kind().equals("water"))
                .flatMap(entry -> Stream.of(fluids.get(entry.id()).source().get(), fluids.get(entry.id()).flowing().get()))
                .collect(Collectors.toUnmodifiableSet());
    }

    static boolean isWater(FluidState state) {
        return state.is(FluidTags.WATER) || WATER_FLUIDS.contains(state.getType());
    }

    static void sample(Level level, LevelChunk chunk, MapTileData data) {
        int startX = chunk.getPos().getMinBlockX(), startZ = chunk.getPos().getMinBlockZ();
        var pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = startX + x, wz = startZ + z;
            int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int y = surface;
            MapColor color;
            do {
                pos.set(wx, y, wz);
                var state = chunk.getBlockState(pos);
                if (isWater(state.getFluidState()) && state.getCollisionShape(level, pos).isEmpty()) {
                    color = MapColor.WATER;
                    break;
                }
                color = state.getMapColor(level, pos);
                if (color != MapColor.NONE) break;
                y--;
            } while (y >= level.getMinBuildHeight());
            int depth = 0;
            var state = chunk.getBlockState(pos);
            if (isWater(state.getFluidState()) && state.getCollisionShape(level, pos).isEmpty()) {
                while (y >= level.getMinBuildHeight() && isWater(chunk.getFluidState(pos.set(wx, y, wz)))) {
                    depth++;
                    y--;
                }
            }
            double slope = (neighborHeight(level, wx - 1, wz, surface) + neighborHeight(level, wx, wz - 1, surface)) / 2.0 - surface;
            data.set(MapTileKey.pixel(0, wx), MapTileKey.pixel(0, wz),
                    MapViewMath.blockColor(color.col, depth, slope), MapViewMath.color(surface, depth, slope), surface);
        }
    }

    private static int neighborHeight(Level level, int x, int z, int fallback) {
        var chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        return chunk == null ? fallback : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
    }
}
