package io.github.rs0325.ashenfrontier.primitive;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Queue;

/** GameTest専用。空き時間に依存せず、実アンロードと保存IOの完了を待つ。 */
final class ChunkReloadFixture {
    private ChunkReloadFixture() {}

    static boolean unloaded(GameTestHelper helper, BlockEntity original) {
        var pos = original.getBlockPos();
        return original.isRemoved()
                && helper.getLevel().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null;
    }

    @SuppressWarnings("unchecked")
    private static Queue<Runnable> unloadQueue(GameTestHelper helper) {
        try {
            var field = ChunkMap.class.getDeclaredField("unloadQueue");
            field.setAccessible(true);
            return (Queue<Runnable>) field.get(helper.getLevel().getChunkSource().chunkMap);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("実アンロード用の処理キューを取得できません", error);
        }
    }

    static void afterUnload(GameTestHelper helper, BlockEntity original, Runnable verify) {
        var queue = unloadQueue(helper);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    // 再登録された処理は次のtickへ回し、キューを無期限に排出しない。
                    for (int remaining = queue.size(); remaining > 0; remaining--) {
                        var task = queue.poll();
                        if (task == null) break;
                        task.run();
                    }
                    helper.assertTrue(unloaded(helper, original), "元BEの実アンロード完了を待つ");
                })
                .thenExecute(() -> {
                    helper.getLevel().getChunkSource().chunkMap.flushWorker();
                    verify.run();
                });
    }
}
