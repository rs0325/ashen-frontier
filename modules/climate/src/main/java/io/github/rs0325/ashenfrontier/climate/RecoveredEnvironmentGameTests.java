package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecoveredEnvironmentGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="recovered_135",timeoutTicks=400)
    @SuppressWarnings("unchecked")
    public static void carriedHearthUsesOldPositionUntilActualPlacement(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var old=f.pos;var next=old.offset(8,20,0);
            var entity=ProcessingEnvironmentGameTests.place(f,"hearth");f.ready(h,0,0);
            var tag=entity.saveWithFullMetadata(f.level.registryAccess());
            tag.putDouble("temperature_k",1000);tag.putDouble("fuel_j",16000000);
            tag.putDouble("heating_value",16000000);tag.putString("fuel_profile","ashen_frontier:dry_wood");
            tag.putLong("last_tick",0);entity.loadWithComponents(tag,f.level.registryAccess());
            var owner=tag.getUUID("source_id");
            double[] temperature={1000},before={1000};BlockPos[] reference={old};
            long[] from={0},to={0},sequence={0};int[] oldNotices={0},newNotices={0};
            java.util.function.Consumer<EnvironmentQuery> input=q-> {
                if(q.level()!=f.level||q.startTick()==q.endTick()||q.values().isEmpty()
                        ||!q.position().equals(old)&&!q.position().equals(next))return;
                var row=q.values().get().getFirst();before[0]=temperature[0];
                temperature[0]=row.temperatureK()+(temperature[0]-row.temperatureK())
                        *Math.exp(-(row.endTick()-row.startTick())*86400.0/row.ticksPerDay()/7200);
                reference[0]=q.position();from[0]=row.startTick();to[0]=row.endTick();
            };
            java.util.function.Consumer<CombustionEvent> emission=e-> {
                if(e.level()!=f.level)return;
                if(!e.sourceId().equals(owner)||e.sequence()<=sequence[0]||e.burnedKg()!=0||e.energyJoules()!=0
                        ||!e.position().equals(reference[0])||e.startTick()!=from[0]||e.endTick()!=to[0]
                        ||Math.abs(e.retainedHeatJoules()-500*(temperature[0]-before[0]))>1e-6)
                    throw new IllegalStateException("回収・再配置の独立温度/J/作用位置が不一致");
                sequence[0]=e.sequence();
                if(e.position().equals(old))oldNotices[0]++;else newNotices[0]++;
            };
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,input);
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,emission);
            var player=ProcessingEnvironmentGameTests.player(f,3);
            try {
                f.clock.setGameTime(600);settle(h,f,entity,temperature);
                var field=net.minecraft.server.level.ServerLevel.class.getDeclaredField("entityManager");field.setAccessible(true);
                var manager=(net.minecraft.world.level.entity.PersistentEntitySectionManager<net.minecraft.world.entity.Entity>)field.get(f.level);
                manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(old),net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING);
                h.assertTrue(player.gameMode.destroyBlock(old),"熱を持つ炉を実撤去");
                var drops=f.level.getEntitiesOfClass(ItemEntity.class,new AABB(old).inflate(2));
                h.assertTrue(drops.size()==1,"内容を持つ設備を回収");
                var carried=drops.getFirst().getItem().copy();
                drops.getFirst().setItem(ItemStack.EMPTY);drops.getFirst().discard();
                f.clock.setGameTime(1200);
                for(int y=next.getY();y<f.level.getMaxBuildHeight();y++)
                    f.level.setBlock(new BlockPos(next.getX(),y,next.getZ()),Blocks.AIR.defaultBlockState(),3);
                f.level.setBlock(next.below(),Blocks.STONE.defaultBlockState(),3);
                player.setPos(next.getX()+.5,next.getY()+1,next.getZ()+.5);
                player.setItemInHand(InteractionHand.MAIN_HAND,carried);
                player.gameMode.useItemOn(player,f.level,carried,InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(next.below()).add(0,.5,0),Direction.UP,next.below(),false));
                entity=f.level.getBlockEntity(next);h.assertTrue(entity!=null,"回収した設備を実配置");
                settle(h,f,entity,temperature);
                var actual=entity.saveWithoutMetadata(f.level.registryAccess());
                h.assertTrue(actual.getLong("heat_reference")==next.asLong()&&actual.getLong("lease_generation")==2,
                        "配置時刻で参照位置/世代を切替");
                h.assertTrue(newNotices[0]==0&&oldNotices[0]>=2,"運搬中の放熱は旧位置の区間へ通知");
                f.clock.setGameTime(1800);settle(h,f,entity,temperature);
                h.assertTrue(newNotices[0]>0,"配置後の放熱は新位置へ通知");
                var saved=entity.saveWithFullMetadata(f.level.registryAccess());
                var state=entity.getBlockState();f.level.getChunkAt(next).removeBlockEntity(next);
                entity=BlockEntity.loadStatic(next,state,saved,f.level.registryAccess());f.level.setBlockEntity(entity);entity.onLoad();
                long last=sequence[0];settle(h,f,entity,temperature);
                h.assertTrue(last==sequence[0],"再保存・復元・同時刻操作でも二重放熱しない");
            } finally {
                player.discard();NeoForge.EVENT_BUS.unregister(input);NeoForge.EVENT_BUS.unregister(emission);
            }
        }
        h.succeed();
    }
    private static void settle(GameTestHelper h,EnvironmentConnectionGameTests.Fixture f,BlockEntity entity,double[] expected) throws Exception {
        for(int n=0;n<20000;n++) {
            entity.getClass().getMethod("work").invoke(entity);
            var state=entity.saveWithoutMetadata(f.level.registryAccess());
            h.assertTrue(Math.abs(state.getDouble("temperature_k")-expected[0])<1e-8,"各段階を独立温度と照合");
            h.assertTrue(state.getDouble("fuel_j")==16000000,"回収・運搬中に燃焼しない");
            if(state.getLong("last_tick")==f.level.getGameTime())return;
            EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);RoomAir.get(f.level).work();
        }
        throw new IllegalStateException("回収した設備の再生が完了しない");
    }
}
