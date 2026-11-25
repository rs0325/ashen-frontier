package io.github.rs0325.ashenfrontier.primitive;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;

/** GameTest専用。不可視になったpendingUnloadsをディスク復元と取り違えない。 */
final class ChunkReloadFixture {
    private ChunkReloadFixture() {}

    static boolean unloaded(GameTestHelper helper, BlockEntity original) {
        var pos = original.getBlockPos();
        return original.isRemoved()
                && helper.getLevel().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null;
    }

    static void afterUnload(GameTestHelper helper, BlockEntity original, Runnable verify) {
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(unloaded(helper, original), "元BEの実アンロード完了を待つ"))
                .thenExecute(() -> {
                    helper.getLevel().getChunkSource().chunkMap.flushWorker();
                    verify.run();
                });
    }
}
