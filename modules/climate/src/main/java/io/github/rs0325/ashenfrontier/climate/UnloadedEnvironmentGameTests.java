package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkMap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UnloadedEnvironmentGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="unloaded_135",timeoutTicks=400)
    public static void actualChunkUnloadAndSavedRoomRestore(GameTestHelper h) throws Exception { verify(h,1); }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="unloaded_135",timeoutTicks=400)
    public static void actualTenYearChunkAndRoomRestore(GameTestHelper h) throws Exception { verify(h,10); }
    @SuppressWarnings("unchecked")
    private static void verify(GameTestHelper h,int years) throws Exception {
        long start=System.nanoTime();
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var original=ProcessingEnvironmentGameTests.place(f,"hearth");
            f.ready(h,0,0);
            var tag=original.saveWithFullMetadata(f.level.registryAccess());
            tag.putDouble("temperature_k",1000);tag.putDouble("fuel_j",16000000);
            tag.putDouble("heating_value",16000000);tag.putString("fuel_profile","ashen_frontier:dry_wood");
            tag.putLong("last_tick",0);
            original.loadWithComponents(tag,f.level.registryAccess());
            var roomTag=RoomAir.get(f.level).save(new net.minecraft.nbt.CompoundTag(),f.level.registryAccess());
            var chunk=f.level.getChunkAt(f.pos);int x=chunk.getPos().x,z=chunk.getPos().z;
            f.level.setChunkForced(x,z,true);f.level.getChunkSource().save(true);f.level.setChunkForced(x,z,false);
            var field=ChunkMap.class.getDeclaredField("unloadQueue");field.setAccessible(true);
            var queue=(java.util.Queue<Runnable>)field.get(f.level.getChunkSource().chunkMap);
            boolean unloaded=false;
            for(int n=0;n<1000;n++) {
                f.level.getChunkSource().tick(()->true,false);
                for(int remaining=queue.size();remaining>0;remaining--) {
                    var task=queue.poll();if(task==null)break;task.run();
                }
                if(original.isRemoved()&&f.level.getChunkSource().getChunkNow(x,z)==null) { unloaded=true;break; }
                f.level.getChunkSource().chunkMap.flushWorker();
            }
            h.assertTrue(unloaded,"チャンクを実アンロード");
            var load=RoomAir.class.getDeclaredMethod("load",net.minecraft.server.level.ServerLevel.class,net.minecraft.nbt.CompoundTag.class);
            load.setAccessible(true);
            f.level.getDataStorage().set("ashen_frontier_room_air",(RoomAir)load.invoke(null,f.level,roomTag));
            f.clock.setGameTime(years*24L*24000);
            f.level.getChunkAt(f.pos);var restored=f.level.getBlockEntity(f.pos);
            h.assertTrue(restored!=null&&restored!=original,"ディスクから別BEへ復元");
            restored.onLoad();
            int attempts=0;
            for(;attempts<40000;attempts++) {
                restored.getClass().getMethod("work").invoke(restored);
                if(restored.saveWithoutMetadata(f.level.registryAccess()).getLong("last_tick")==f.level.getGameTime())break;
                EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);RoomAir.get(f.level).work();
            }
            h.assertTrue(attempts<40000,"実未ロードとC06保存復元から再開 years="+years+" state="+restored.saveWithoutMetadata(f.level.registryAccess()));
            var result=restored.saveWithoutMetadata(f.level.registryAccess());
            h.assertTrue(result.getDouble("fuel_j")==16000000&&!result.getBoolean("lit"),"無人の復元で燃焼を進めない");
            var sequence=result.getLong("notification_sequence");
            restored.getClass().getMethod("work").invoke(restored);
            h.assertTrue(restored.saveWithoutMetadata(f.level.registryAccess()).getLong("notification_sequence")==sequence,
                    "実再ロード後の同時刻操作で二重放熱なし");
        }
        com.mojang.logging.LogUtils.getLogger().info("M3-135 actual chunk restore years={} wall_ms={}",years,(System.nanoTime()-start)/1e6);
        h.succeed();
    }
}
