package io.github.rs0325.ashenfrontier.world;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** 受信済みチャンクだけを参照し、地図の元タイルへ1ブロックずつ実測値を書き込む。 */
final class MapChunkSampler {
    private MapChunkSampler() {}

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
                color = chunk.getBlockState(pos).getMapColor(level, pos);
                if (color != MapColor.NONE) break;
                y--;
            } while (y >= level.getMinBuildHeight());
            int depth = 0;
            var state = chunk.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.WATER) && state.getCollisionShape(level, pos).isEmpty()) {
                while (y >= level.getMinBuildHeight() && chunk.getFluidState(pos.set(wx, y, wz)).is(FluidTags.WATER)) {
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
