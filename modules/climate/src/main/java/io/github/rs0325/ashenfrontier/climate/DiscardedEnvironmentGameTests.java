package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiscardedEnvironmentGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="discarded_135",timeoutTicks=400)
    public static void discardedHotItemSettlesAfterSaveWithoutReleasingEarly(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var entity=ProcessingEnvironmentGameTests.place(f,"hearth");f.ready(h,0,0);
            var tag=entity.saveWithFullMetadata(f.level.registryAccess());
            tag.putDouble("temperature_k",1000);tag.putDouble("fuel_j",16000000);
            tag.putDouble("heating_value",16000000);tag.putString("fuel_profile","ashen_frontier:dry_wood");tag.putLong("last_tick",0);
            entity.loadWithComponents(tag,f.level.registryAccess());
            var stack=ProcessingEnvironmentGameTests.item("hearth",1);entity.saveToItem(stack,f.level.registryAccess());
            f.level.getChunkAt(f.pos).removeBlockEntity(f.pos);
            f.clock.setGameTime(24000);
            var item=new ItemEntity(f.level,f.pos.getX()+.5,f.pos.getY()+1,f.pos.getZ()+.5,stack);
            f.level.setChunkForced(f.pos.getX()>>4,f.pos.getZ()>>4,true);
            for(int n=0;n<4;n++)f.level.getChunkSource().tick(()->true,false);
            var managerField=ServerLevel.class.getDeclaredField("entityManager");managerField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var manager=(net.minecraft.world.level.entity.PersistentEntitySectionManager<net.minecraft.world.entity.Entity>)managerField.get(f.level);
            // 私設levelはサーバーのレベル一覧外なので、実チャンク状態の通知を明示する。
            manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(f.pos),net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING);
            f.level.addFreshEntity(item);
            manager.tick();
            h.assertTrue(f.level.getEntity(item.getUUID())==item,"実チャンクのエンティティ追跡を開始");
            item.discard();
            var type=Class.forName("io.github.rs0325.ashenfrontier.primitive.PendingThermal");
            var get=type.getDeclaredMethod("get",ServerLevel.class);get.setAccessible(true);
            var pending=(SavedData)get.invoke(null,f.level);
            var saved=pending.save(new CompoundTag(),f.level.registryAccess());
            h.assertTrue(saved.getList("jobs",net.minecraft.nbt.Tag.TAG_COMPOUND).size()==1,"廃棄の未精算を保持 removed="+item.getRemovalReason()+" tag="+stack.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA)+" jobs="+saved);
            h.assertTrue(RoomAir.get(f.level).retentionUsage().owners()==1,"処理待ちの登録を解放しない");
            var load=type.getDeclaredMethod("load",ServerLevel.class,CompoundTag.class);load.setAccessible(true);
            int rejected=0;
            for(int n=0;n<2;n++) {
                var broken=saved.copy();
                if(n==0)broken.remove("jobs");
                else broken.getList("jobs",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putUUID("owner",new java.util.UUID(135,999));
                try { load.invoke(null,f.level,broken); }
                catch(java.lang.reflect.InvocationTargetException e) {
                    if(e.getCause() instanceof IllegalArgumentException)rejected++;else throw e;
                }
            }
            h.assertTrue(rejected==2,"未精算キューの欠落/所有者違いを拒否");
            pending=(SavedData)load.invoke(null,f.level,saved);
            f.level.getDataStorage().set("ashen_frontier_pending_thermal",pending);
            var work=type.getDeclaredMethod("work",long.class);work.setAccessible(true);
            double[] netHeat={0};int[] notices={0};
            java.util.function.Consumer<CombustionEvent> receiver=e-> {
                if(e.level()!=f.level)return;
                if(e.burnedKg()!=0||e.energyJoules()!=0)throw new IllegalStateException("廃棄時に燃焼を進めた");
                netHeat[0]-=e.retainedHeatJoules();notices[0]++;
            };
            double[] ambient={Double.NaN};
            java.util.function.Consumer<EnvironmentQuery> observations=q-> {
                if(q.level()==f.level&&q.startTick()==q.endTick()&&q.values().isPresent())
                    ambient[0]=q.values().get().getFirst().temperatureK();
            };
            NeoForge.EVENT_BUS.addListener(receiver);
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,observations);
            try {
                int attempts=0;
                for(;attempts<10000;attempts++) {
                    work.invoke(pending,Long.MAX_VALUE);
                    if(pending.save(new CompoundTag(),f.level.registryAccess()).getList("jobs",net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty())break;
                    EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);RoomAir.get(f.level).work();
                }
                h.assertTrue(attempts<10000&&notices[0]>0&&netHeat[0]>0,"保存された廃棄の放熱を過去時点で精算");
                h.assertTrue(Double.isFinite(ambient[0])&&Math.abs(netHeat[0]-500*(1000-ambient[0]))<1e-6,
                        "廃棄の全放熱は独立した初期蓄熱と終端環境温度の差に一致");
                h.assertTrue(RoomAir.get(f.level).retentionUsage().owners()==0,"精算完了後だけ登録を解放");
                h.assertTrue(RoomAir.get(f.level).retentionUsage().physicalPositions()>0,"必要な物理参照は維持");
                int before=notices[0];work.invoke(pending,Long.MAX_VALUE);
                var duplicate=new ItemEntity(f.level,f.pos.getX()+.5,f.pos.getY()+1,f.pos.getZ()+.5,stack.copy());
                f.level.addFreshEntity(duplicate);duplicate.discard();
                h.assertTrue(pending.save(new CompoundTag(),f.level.registryAccess()).getList("jobs",net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty()
                        &&notices[0]==before,"精算済みの古い保存を再通知しない");
            } finally { NeoForge.EVENT_BUS.unregister(receiver);NeoForge.EVENT_BUS.unregister(observations); }
        }
        h.succeed();
    }
}
