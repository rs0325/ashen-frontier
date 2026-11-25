package io.github.rs0325.ashenfrontier.climate.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import java.util.ArrayDeque;

/** 未ロードchunkを取得しない上限付き探索。空気・開いた扉を連結し、空の見える隣接セルは開口境界にする。 */
final class RoomSearch {
    private static final long WORK_NANOS = 1_000_000;
    enum Status { SCANNING, INDOOR, OUTDOOR, UNLOADED, LIMIT, BLOCKED, HISTORY_MISSING }
    final BlockPos origin;
    final long epoch;
    final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    final LongAVLTreeSet cells = new LongAVLTreeSet();
    final LongOpenHashSet portals = new LongOpenHashSet();
    final LongOpenHashSet visited = new LongOpenHashSet();
    Status status = Status.SCANNING;
    int openings;
    int walls;
    int minimumAirY = Integer.MAX_VALUE;
    int maximumOpeningY = Integer.MIN_VALUE;
    int visits;

    RoomSearch(BlockPos origin, long epoch) {
        this.origin = origin.immutable(); this.epoch = epoch; queue.add(this.origin);
    }

    static boolean passable(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.isAir() || ((state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock)
                && state.getValue(BlockStateProperties.OPEN));
    }

    static boolean exposed(ServerLevel level, BlockPos pos) {
        return pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
    }

    void work(ServerLevel level, int budget, int limit) {
        int used = 0;
        int polled = 0;
        long started = System.nanoTime();
        while (status == Status.SCANNING && !queue.isEmpty() && used < budget) {
            // 重複セルの排出も時間予算に含め、少なくとも1件は進める。
            if (polled > 0 && System.nanoTime() - started >= WORK_NANOS) break;
            polled++;
            var pos = queue.removeFirst();
            if (!visited.add(pos.asLong())) continue;
            used++; visits++;
            if (!level.hasChunkAt(pos)) { status = Status.UNLOADED; break; }
            if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
                status = Status.OUTDOOR; break;
            }
            if (!passable(level, pos)) { if (pos.equals(origin)) status = Status.BLOCKED; else walls++; continue; }
            if (exposed(level, pos)) {
                if (pos.equals(origin)) status = Status.OUTDOOR;
                else { portals.add(pos.asLong()); openings++; maximumOpeningY = Math.max(maximumOpeningY, pos.getY()); }
                continue;
            }
            cells.add(pos.asLong());
            minimumAirY = Math.min(minimumAirY, pos.getY());
            if (cells.size() > limit) { status = Status.LIMIT; break; }
            for (Direction direction : Direction.values()) queue.addLast(pos.relative(direction));
        }
        if (status == Status.SCANNING && queue.isEmpty()) status = Status.INDOOR;
    }

    double openingHeight() { return openings == 0 ? 0 : Math.max(1, maximumOpeningY - minimumAirY); }
}
