package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.Map;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ThermalDispatchGameTests {
    private static Object field(Object owner,String name) throws Exception {
        var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private static void field(Object owner,String name,Object value) throws Exception {
        var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);
    }
    private static void scheduled(BlockEntity actor) throws Exception {
        var m=actor.getClass().getDeclaredMethod("scheduledWork");m.setAccessible(true);m.invoke(actor);
    }
    private static void exhaust(EnvironmentConnectionGameTests.Fixture f) {
        EnvironmentBudget.beginTick(f.level.getServer());EnvironmentBudget.charge(f.level,EnvironmentBudget.TARGET_NANOS);
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="dispatch126",timeoutTicks=400)
    public static void pendingOldFrontierAndSynchronousInteractionKeepTheirOwners(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var old=ProcessingEnvironmentGameTests.place(f,"hearth");
            var pos=f.pos.offset(0,0,1);f.level.setBlock(pos,BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("hearth")).defaultBlockState(),3);
            var recent=f.level.getBlockEntity(pos);recent.onLoad();
            var placed=recent.getClass().getDeclaredMethod("placed");placed.setAccessible(true);placed.invoke(recent);
            f.ready(h,0,0);f.clock.setGameTime(100);f.ready(h,0,100);
            var before=old.saveWithoutMetadata(f.level.registryAccess());before.putDouble("temperature_k",450);before.putLong("last_tick",0);
            old.loadWithComponents(before,f.level.registryAccess());
            var other=recent.saveWithoutMetadata(f.level.registryAccess());other.putDouble("temperature_k",450);other.putLong("last_tick",80);
            recent.loadWithComponents(other,f.level.registryAccess());
            exhaust(f);scheduled(recent);scheduled(old);
            for(int n=0;n<200&&(long)field(old,"lastTick")==0;n++) {
                f.clock.setGameTime(100+n);EnvironmentBudget.beginTick(f.level.getServer());scheduled(recent);
            }
            h.assertTrue((long)field(old,"lastTick")>0&&(long)field(recent,"lastTick")==80,"先に呼ばれた新しい末尾より、待っている古い末尾へ配分する");
            old.loadWithComponents(before,f.level.registryAccess());recent.loadWithComponents(other,f.level.registryAccess());
            exhaust(f);scheduled(old);scheduled(recent);
            var player=ProcessingEnvironmentGameTests.player(f,126);var queried=new ArrayList<net.minecraft.core.BlockPos>();
            java.util.function.Consumer<EnvironmentQuery> spy=e->{if(e.level()==f.level)queried.add(e.position());};
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,spy);
            try {
                EnvironmentBudget.beginTick(f.level.getServer());player.setPos(pos.getX()+.5,pos.getY()+1,pos.getZ()+.5);
                recent.getClass().getMethod("interact",net.minecraft.server.level.ServerPlayer.class,InteractionHand.class,Direction.class)
                        .invoke(recent,player,InteractionHand.MAIN_HAND,Direction.UP);
                h.assertTrue(!queried.isEmpty()&&queried.getFirst().equals(pos),"同期操作は待ち行列の別設備へすり替えない");
            } finally {NeoForge.EVENT_BUS.unregister(spy);player.discard();}
            var queues=old.getClass().getDeclaredField("WORK");queues.setAccessible(true);
            Object queue=((Map<?,?>)queues.get(null)).get(f.level);old.setRemoved();recent.setRemoved();
            h.assertTrue(((java.util.Collection<?>)field(queue,"ready")).isEmpty()
                    &&((java.util.Collection<?>)field(queue,"waiting")).isEmpty()
                    &&!(boolean)field(field(old,"workRequest"),"queued")&&!(boolean)field(field(recent,"workRequest"),"queued"),"削除した設備の待機本体を残さない");
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="dispatch126",timeoutTicks=400)
    public static void queuedIntervalKeepsFixedTargetAndMatchesDirectProcessing(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var actor=ProcessingEnvironmentGameTests.place(f,"drying_rack");var player=ProcessingEnvironmentGameTests.player(f,127);
            try {
                f.ready(h,0,0);ProcessingEnvironmentGameTests.station(actor).setInput(player,0,ProcessingEnvironmentGameTests.pottery("green_pot"));
                ProcessingEnvironmentGameTests.use(actor,player,ItemStack.EMPTY);
                field(actor,"workTarget",80L);
                f.clock.setGameTime(100);RoomAir.get(f.level).respiration(new java.util.UUID(126,127),1,f.pos.above(),0,20,.01,.01,1);
                f.ready(h,0,100);var before=actor.saveWithoutMetadata(f.level.registryAccess());
                exhaust(f);scheduled(actor);
                for(int n=0;n<200&&(long)field(actor,"workTick")==0;n++) {
                    f.clock.setGameTime(100+n);EnvironmentBudget.beginTick(f.level.getServer());scheduled(actor);
                    EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);
                }
                long through=(long)field(actor,"workTick");
                h.assertTrue(through==20&&(long)field(actor,"workTarget")==80,"固定目標80を現在時刻へ変えず、最初の20まで1区間だけ進める");
                var actual=actor.saveWithoutMetadata(f.level.registryAccess());
                var reference=io.github.rs0325.ashenfrontier.core.recipe.ProcessingStation.load(before.getCompound("processing"),f.level.registryAccess());
                long current=f.level.getGameTime();f.clock.setGameTime(0);
                reference.advance(player,AshenFrontierCore.id("drying_rack"),java.util.Optional.empty(),0);
                f.clock.setGameTime(current);
                var sample=EnvironmentQuery.queryFirst(f.level,f.pos,0,80).values().orElseThrow().getFirst();
                reference.advanceInterval(player,AshenFrontierCore.id("drying_rack"),sample,0,java.util.OptionalDouble.empty(),Double.MAX_VALUE);
                h.assertTrue(sample.endTick()==through&&actual.getLong("last_tick")==through
                        &&reference.save(f.level.registryAccess()).equals(actual.getCompound("processing")),"開始時刻0で再開した直接評価と、加工量・製品・評価末尾が完全一致する");
                var next=EnvironmentQuery.queryFirst(f.level,f.pos,through,80).values().orElseThrow().getFirst();
                for(int n=0;n<200&&(long)field(actor,"workTick")==through;n++) {
                    EnvironmentBudget.beginTick(f.level.getServer());scheduled(actor);
                }
                reference.advanceInterval(player,AshenFrontierCore.id("drying_rack"),next,0,java.util.OptionalDouble.empty(),Double.MAX_VALUE);
                h.assertTrue(f.level.getGameTime()==current&&(long)field(actor,"workTick")==next.endTick()
                        &&(long)field(actor,"workTarget")==-1&&next.endTick()==80
                        &&reference.save(f.level.registryAccess()).equals(actor.saveWithoutMetadata(f.level.registryAccess()).getCompound("processing")),
                        "同じtickでも次の入場で残りの1区間だけを確定し、固定目標80で終える");
            } finally {player.discard();actor.setRemoved();}
        }
        h.succeed();
    }
}
