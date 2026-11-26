package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.climate.EnvironmentConnectionGameTests;
import io.github.rs0325.ashenfrontier.climate.EnvironmentProvider;
import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.food.CultivationQuery;
import io.github.rs0325.ashenfrontier.core.food.FoodEvidence;
import io.github.rs0325.ashenfrontier.core.food.FoodOrigin;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.core.food.FoodUse;
import io.github.rs0325.ashenfrontier.core.food.IntakeRules;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(AshenFrontierSurvival.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Review125GameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review125",timeoutTicks=400)
    public static void failedHistoryReleasesDescendantRetention(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            fixture.ready(h,0,0);SurvivalRuntime.INPUTS.put(fixture.level,new SurvivalGameTests.Inputs(0));
            try(var a=new SurvivalGameTests.Person(fixture.level,fixture.pos,"failed_a125");
                var b=new SurvivalGameTests.Person(fixture.level,fixture.pos.offset(1,0,0),"failed_b125");
                var c=new SurvivalGameTests.Person(fixture.level,fixture.pos.offset(0,0,1),"failed_c125")) {
                a.runtime.data.body.energy=b.runtime.data.body.energy=c.runtime.data.body.energy=1e6;
                var rooms=RoomAir.get(fixture.level);int baseline=rooms.retentionUsage().owners();
                a.player.setItemInHand(IntakeGameTests.HAND,IntakeGameTests.food("canned_food",4,0));
                h.assertTrue(IntakeGameTests.action(a,FoodUse.Action.START),"Aの食品を保持");IntakeGameTests.action(a,FoodUse.Action.CANCEL);
                SurvivalGameTests.time(fixture.level,600);
                b.player.setItemInHand(IntakeGameTests.HAND,a.player.getItemInHand(IntakeGameTests.HAND).split(2));
                h.assertTrue(IntakeGameTests.action(b,FoodUse.Action.START),"未精算のままAからBへ分岐");IntakeGameTests.action(b,FoodUse.Action.CANCEL);
                SurvivalGameTests.time(fixture.level,1200);
                c.player.setItemInHand(IntakeGameTests.HAND,b.player.getItemInHand(IntakeGameTests.HAND).split(1));
                h.assertTrue(IntakeGameTests.action(c,FoodUse.Action.START),"未精算のままBからCへ分岐");IntakeGameTests.action(c,FoodUse.Action.CANCEL);
                h.assertTrue(rooms.retentionUsage().owners()==baseline+3,"食品の保持が3件存在");
                var data=FoodStorageData.get(fixture.level);var failures=new java.util.concurrent.atomic.AtomicInteger();
                java.util.function.Consumer<io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery> missing=query->{
                    if(query.level()==fixture.level&&query.position().equals(a.player.blockPosition())&&query.startTick()<query.endTick()&&query.values().isEmpty()) {
                        query.pending("environment.ashen_frontier_climate.history_missing");failures.incrementAndGet();
                    }
                };
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,missing);
                try {
                    for(int n=0;n<1000&&!data.missing(c.player.getItemInHand(IntakeGameTests.HAND));n++) {
                        io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget.beginTick(fixture.level.getServer());data.work();
                    }
                } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(missing); }
                h.assertTrue(failures.get()>0&&data.missing(a.player.getItemInHand(IntakeGameTests.HAND))
                        &&data.missing(b.player.getItemInHand(IntakeGameTests.HAND))&&data.missing(c.player.getItemInHand(IntakeGameTests.HAND)),"履歴の失敗が全世代へ伝播");
                var saved=data.save(new net.minecraft.nbt.CompoundTag(),fixture.level.registryAccess());
                for(var entry:io.github.rs0325.ashenfrontier.core.NbtLists.read(saved,"holders",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    var row=(net.minecraft.nbt.CompoundTag)entry;
                    if(java.util.Set.of(a.player.getUUID(),b.player.getUUID(),c.player.getUUID()).contains(row.getUUID("id")))
                        h.assertTrue(io.github.rs0325.ashenfrontier.core.NbtLists.read(row,"spans",net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty(),"起点と子孫の開いた保持区間を残さない");
                }
                fixture.ready(h,0,1200);
                for(int n=0;n<20000&&rooms.retentionUsage().owners()>baseline;n++) {
                    EnvironmentProvider.get(fixture.level).work(Long.MAX_VALUE,8192);rooms.work();
                }
                h.assertTrue(rooms.retentionUsage().owners()==baseline,"C06の食品保持を全て解放し、生理の保持は維持");
            } finally { SurvivalRuntime.INPUTS.remove(fixture.level); }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review125",timeoutTicks=400)
    public static void carriedCursorPreservesWholeAndPartialFoodHistory(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            fixture.ready(h,0,0);SurvivalRuntime.INPUTS.put(fixture.level,new SurvivalGameTests.Inputs(0));
            try(var person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"cursor125")) {
                person.runtime.data.body.energy=1e6;
                for(int button:new int[]{0,1}) {
                    long now=fixture.level.getGameTime();
                    var food=IntakeGameTests.food("canned_food",4,now);person.player.setItemInHand(IntakeGameTests.HAND,food);
                    h.assertTrue(IntakeGameTests.action(person,FoodUse.Action.START),"食品を本番台帳へ登録");IntakeGameTests.action(person,FoodUse.Action.CANCEL);
                    var key=food.get(FoodProperties.STORAGE);
                    person.player.containerMenu.clicked(36,button,net.minecraft.world.inventory.ClickType.PICKUP,person.player);
                    h.assertTrue(person.player.containerMenu.getCarried().getCount()==(button==0?4:2),"全量または半分をカーソルへ移す");
                    SurvivalGameTests.time(fixture.level,now+20);
                    FoodStorageService.player(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(person.player));
                    h.assertTrue(!FoodStorageData.get(fixture.level).missing(person.player.containerMenu.getCarried()),"20 tick照合後もカーソルの台帳を保持");
                    person.player.containerMenu.clicked(36,0,net.minecraft.world.inventory.ClickType.PICKUP,person.player);
                    var returned=person.player.getItemInHand(IntakeGameTests.HAND);
                    h.assertTrue(returned.getCount()==4&&key.equals(returned.get(FoodProperties.STORAGE)),"同じ履歴へ全量を戻す");
                    fixture.ready(h,now,now+20);
                    var data=FoodStorageData.get(fixture.level);java.util.Optional<ItemStack> evaluated=java.util.Optional.empty();
                    for(int attempt=0;attempt<10000&&evaluated.isEmpty();attempt++) {
                        io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget.beginTick(fixture.level.getServer());
                        evaluated=data.query(returned,now+20);
                    }
                    h.assertTrue(evaluated.isPresent(),"カーソルから戻しても腐敗履歴を有限の仕事で確定");
                    h.assertTrue(IntakeGameTests.action(person,FoodUse.Action.START)&&IntakeGameTests.action(person,FoodUse.Action.FINISH),"戻した食品を一度摂取できる");
                    h.assertTrue(person.player.getItemInHand(IntakeGameTests.HAND).getCount()==3,"実消費は一個だけ");
                    FoodStorageData.get(fixture.level).reconcile(person.player.getUUID(),java.util.Map.of());
                }
            } finally { SurvivalRuntime.INPUTS.remove(fixture.level); }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review125_storage_load",timeoutTicks=400)
    public static void manyStacksObserveWithoutGlobalCleanup(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            fixture.ready(h,0,0);SurvivalRuntime.INPUTS.put(fixture.level,new SurvivalGameTests.Inputs(0));
            try(var person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"stacks125")) {
                var clock=new io.github.rs0325.ashenfrontier.core.testing.ThreadCpuClock();boolean cpu=clock.useCpu("food_storage_observe",1_000_000);
                for(int size:new int[]{32,128,512,2048}) {
                    var stacks=new java.util.ArrayList<ItemStack>();var data=FoodStorageData.get(fixture.level);
                    for(int i=0;i<size;i++) {
                        var stack=IntakeGameTests.food("canned_food",1,0);stacks.add(stack);
                        stack.getItem().inventoryTick(stack,fixture.level,person.player,0,false);
                    }
                    long[] times=new long[32],walls=new long[32];int operations=0;
                    for(int pass=-8;pass<32;pass++) {
                        long startCpu=clock.now(),startWall=System.nanoTime();
                        for(var stack:stacks)stack.getItem().inventoryTick(stack,fixture.level,person.player,0,false);
                        long wall=System.nanoTime()-startWall,thread=clock.now()-startCpu;
                        if(pass>=0){times[pass]=cpu?thread:wall;walls[pass]=wall;operations+=size;}
                    }
                    java.util.Arrays.sort(times);java.util.Arrays.sort(walls);
                    h.assertTrue(operations==32*size&&stacks.stream().noneMatch(data::missing),"全スタックを更新して履歴を保持");
                    System.getLogger(Review125GameTests.class.getName()).log(System.Logger.Level.INFO,
                            "M3-125 storage stacks="+size+" calls="+operations+" selected="+(cpu?"cpu":"wall")
                            +" p95_ms="+times[30]/1e6+" wall_p95_ms="+walls[30]/1e6);
                    data.reconcile(person.player.getUUID(),java.util.Map.of());
                    h.assertTrue(stacks.stream().allMatch(data::missing),"変化した保管者の台帳を全て解放");
                }
            } finally { SurvivalRuntime.INPUTS.remove(fixture.level); }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review125",timeoutTicks=400)
    public static void carriedSplitAndPendingHistorySurviveSave(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            fixture.ready(h,0,0);SurvivalRuntime.INPUTS.put(fixture.level,new SurvivalGameTests.Inputs(0));
            try(var a=new SurvivalGameTests.Person(fixture.level,fixture.pos,"carry_a125");
                var b=new SurvivalGameTests.Person(fixture.level,fixture.pos.offset(1,0,0),"carry_b125")) {
                a.runtime.data.body.energy=b.runtime.data.body.energy=1e6;
                a.player.setItemInHand(IntakeGameTests.HAND,IntakeGameTests.food("canned_food",4,0));
                h.assertTrue(IntakeGameTests.action(a,FoodUse.Action.START),"保管者Aへ登録");IntakeGameTests.action(a,FoodUse.Action.CANCEL);
                SurvivalGameTests.time(fixture.level,1200);
                b.player.setItemInHand(IntakeGameTests.HAND,a.player.getItemInHand(IntakeGameTests.HAND).split(2));
                h.assertTrue(IntakeGameTests.action(b,FoodUse.Action.START),"分割した2個をBへ移送");IntakeGameTests.action(b,FoodUse.Action.CANCEL);
                h.assertTrue(!a.player.getItemInHand(IntakeGameTests.HAND).get(FoodProperties.STORAGE).equals(b.player.getItemInHand(IntakeGameTests.HAND).get(FoodProperties.STORAGE)),"異なる保管者の履歴を分岐");
                var data=FoodStorageData.get(fixture.level);var saved=data.save(new net.minecraft.nbt.CompoundTag(),fixture.level.registryAccess());
                var restored=FoodStorageData.load(fixture.level.getServer(),saved,fixture.level.registryAccess());
                fixture.level.getServer().overworld().getDataStorage().set("ashen_frontier_food_storage",restored);
                restored.capture(a.player.getUUID(),fixture.level,a.player.blockPosition());restored.capture(b.player.getUUID(),fixture.level,b.player.blockPosition());
                var environment=fixture.ready(h,0,1200);double expected=0;
                for(var sample:environment)expected+=(sample.endTick()-sample.startTick())*86400.0/sample.ticksPerDay()*Math.pow(2,(sample.temperatureK()-293.15)/10);
                java.util.Optional<ItemStack> first=java.util.Optional.empty(),second=java.util.Optional.empty();
                for(int attempt=0;attempt<20000&&(first.isEmpty()||second.isEmpty());attempt++) {
                    io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget.beginTick(fixture.level.getServer());
                    restored.work();first=restored.query(a.player.getItemInHand(IntakeGameTests.HAND),1200);second=restored.query(b.player.getItemInHand(IntakeGameTests.HAND),1200);
                }
                h.assertTrue(first.isPresent()&&second.isPresent(),"保存した未確定分岐を有限の仕事で確定");
                double rounded=Math.ceil(expected/600)*600;
                h.assertTrue(first.orElseThrow().get(ItemProperties.ROT).referenceSeconds()==rounded
                        &&second.orElseThrow().get(ItemProperties.ROT).referenceSeconds()==rounded,"独立した温度区間の積算が両方に一致");
                h.assertTrue(IntakeGameTests.action(a,FoodUse.Action.START)&&IntakeGameTests.action(a,FoodUse.Action.FINISH),"Aで一個だけ摂取");
                h.assertTrue(IntakeGameTests.action(b,FoodUse.Action.START)&&IntakeGameTests.action(b,FoodUse.Action.FINISH),"Bで一個だけ摂取");
                h.assertTrue(a.player.getItemInHand(IntakeGameTests.HAND).getCount()==1&&b.player.getItemInHand(IntakeGameTests.HAND).getCount()==1,"分割・復元・摂取後も総数を保存");
                var broken=saved.copy();var wrong=new net.minecraft.nbt.ListTag();wrong.add(net.minecraft.nbt.LongTag.valueOf(1));broken.put("holders",wrong);
                var stopped=FoodStorageData.load(fixture.level.getServer(),broken,fixture.level.registryAccess());
                h.assertTrue(stopped.missing(a.player.getItemInHand(IntakeGameTests.HAND))&&stopped.save(new net.minecraft.nbt.CompoundTag(),fixture.level.registryAccess()).equals(broken),"不正なリストは初期化せず原文を保全");
            } finally { SurvivalRuntime.INPUTS.remove(fixture.level); }
        }
        h.succeed();
    }
    private static FoodOrigin origin(String map,String manifest,String profile) {
        return new FoodOrigin(map,manifest,profile,UUID.randomUUID(),FoodOrigin.State.CONFIRMED);
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review125",timeoutTicks=400)
    public static void cultivationChecksTheWaterEvidence(GameTestHelper h) {
        var soil=origin("earth_001","starter","shelter_food_soil");
        for(var water:new FoodOrigin[]{origin("earth_002","starter","clean_water"),origin("earth_001","other","clean_water"),origin("earth_001","starter","unknown")})
            h.assertTrue(CultivationQuery.query(soil,"barley","fertile_soil","clean_water",SoilContamination.Stages.CLEAN,water).status()==IntakeRules.Status.UNKNOWN,"確認状態だけで水を許可しない");
        h.assertTrue(CultivationQuery.query(soil,"barley","fertile_soil","clean_water",SoilContamination.Stages.CLEAN,
                origin("earth_001","starter","ashen_frontier:clean_water")).accepted(),"同版・同manifest・一致する水質プロファイル");
        h.assertTrue(CultivationQuery.query(soil,"barley","fertile_soil","clean_water",SoilContamination.Stages.CLEAN,
                origin("earth_001","starter","ashen_frontier:concentrated_residue")).status()==IntakeRules.Status.INELIGIBLE,"名前空間付きの濃縮残水を拒否");
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review125",timeoutTicks=400)
    public static void twoDrinksPreserveConcentrationAndReduceTotalActivity(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            fixture.ready(h,0,0);SurvivalRuntime.INPUTS.put(fixture.level,new SurvivalGameTests.Inputs(0));
            try(var person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"water125review")) {
                person.runtime.data.body.water=40;
                var water=IntakeGameTests.water(1);water.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("ashen_frontier:cs137",1,0));
                person.player.setItemInHand(IntakeGameTests.HAND,water);
                double previous=1;
                for(int n=1;n<=2;n++) {
                    h.assertTrue(IntakeGameTests.action(person,FoodUse.Action.START)&&IntakeGameTests.action(person,FoodUse.Action.FINISH),"飲用可能な数値の水を一回ずつ飲む");
                    var remaining=person.player.getItemInHand(IntakeGameTests.HAND);double mass=1-.25*n;
                    var radio=remaining.get(ItemProperties.RADIOACTIVITY);double upper=radio.becquerels()+remaining.get(FoodProperties.ACTIVITY_UNCERTAINTY);
                    h.assertTrue(remaining.get(RelicProperties.CONTENT_MASS)==mass&&radio.becquerels()<previous,"残量と総Bqを減らす");
                    h.assertTrue(Math.abs(upper-1.1*mass)<1e-12&&Math.abs(upper/mass-1.1)<1e-12,"丸めの上端幅も割合で減らし濃度を保存");
                    previous=radio.becquerels();
                }
                SurvivalGameTests.drain(h,person.runtime);h.assertTrue(person.runtime.data.body.water==40.5,"水分は実消費0.5kgだけ");
            } finally { SurvivalRuntime.INPUTS.remove(fixture.level); }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review125_real",timeoutTicks=1200)
    public static void actualThirtyTwoTickUseUsesProductionCarriedHistory(GameTestHelper h) throws Exception {
        var scenario=new RealUse(h);
        h.onEachTick(()->{
            if(scenario.done)return;
            try { scenario.step(h); }
            catch(Exception|Error failure){try{scenario.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
                if(failure instanceof Error error)throw error;if(failure instanceof RuntimeException runtime)throw runtime;throw new RuntimeException(failure);}
        });
    }
    private static final class RealUse implements AutoCloseable {
        final EnvironmentConnectionGameTests.Fixture fixture;
        final SurvivalGameTests.Person person;
        int phase;long now,start;boolean done;
        RealUse(GameTestHelper h) throws Exception {
            fixture=new EnvironmentConnectionGameTests.Fixture(h);fixture.ready(h,0,0);
            SurvivalRuntime.INPUTS.put(fixture.level,new SurvivalGameTests.Inputs(0));
            person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"real_use125");person.player.setNoGravity(true);
            person.runtime.data.body.energy=1e6;person.runtime.data.body.water=40;begin(h);
        }
        void begin(GameTestHelper h) {
            start=now;ItemStack stack=phase==2?IntakeGameTests.water(1):IntakeGameTests.food(phase==0?"canned_food":"preserved_rations",1,now);
            person.player.setItemInHand(IntakeGameTests.HAND,stack);
            h.assertTrue(stack.getItem().getUseDuration(stack,person.player)==32,"通常の使用時間32 tick");
            h.assertTrue(stack.getItem().use(fixture.level,person.player,IntakeGameTests.HAND).getResult().consumesAction(),"本番の通常使用を開始");
        }
        void step(GameTestHelper h) throws Exception {
            if(++now>1000){close();h.fail("携帯履歴と使用完了が有限時間で確定しません");return;}
            SurvivalGameTests.time(fixture.level,now);person.player.doTick();
            FoodStorageService.player(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(person.player));
            RoomAir.get(fixture.level).work();EnvironmentProvider.get(fixture.level).work(System.nanoTime()+10_000_000,8192);
            FoodStorageData.get(fixture.level).work();IntakeService.retry();
            var held=person.player.getItemInHand(IntakeGameTests.HAND);
            boolean consumed=phase==2?held.get(RelicProperties.CONTENT_MASS)<1:held.isEmpty();
            if(now-start<32)h.assertTrue(!consumed,"32 tickより前には摂取しない");
            if(consumed) {
                var intake=person.runtime.data.frames.stream().filter(f->f.kind==PhysiologyData.Kind.INTAKE).toList();
                h.assertTrue(intake.size()==1&&intake.getFirst().generation>0&&intake.getFirst().from==start+32&&intake.getFirst().to==start+32,"遅延しても使用完了32 tickに健康を一回だけ記録");
                var frame=intake.getFirst();double expectedWater=phase==0?.132:phase==1?.010:.25;
                double expectedEnergy=phase==0?1450000:phase==1?4500000:0;
                h.assertTrue(frame.a==expectedWater&&frame.b==expectedEnergy,"独立した各操作のkg/J");
                for(int attempt=0;person.runtime.data.frames.peekFirst()!=frame&&attempt<128;attempt++)person.runtime.work(System.nanoTime()+10_000_000,1);
                h.assertTrue(person.runtime.data.frames.peekFirst()==frame,"健康の予約まで有限の評価で到達");
                double beforeWater=person.runtime.data.body.water,beforeEnergy=person.runtime.data.body.energy;
                person.runtime.work(System.nanoTime()+10_000_000,1);
                h.assertTrue(Math.abs(person.runtime.data.body.water-beforeWater-expectedWater)<1e-10
                        &&Math.abs(person.runtime.data.body.energy-beforeEnergy-expectedEnergy)<1e-7,"実際の吸収量を一回分だけ増やす");
            }
            person.runtime.work(System.nanoTime()+10_000_000,64);
            if(!consumed)return;
            h.assertTrue(now-start>=32,"実際に32 tickを経過");
            if(++phase<3){begin(h);return;}
            h.assertTrue(Math.abs(person.runtime.data.body.energy-(6950000-10e6*now/24000.0))<1e-5,"独立した3操作のJ収支");
            com.mojang.logging.LogUtils.getLogger().info("M3-125 actual use foods=2 water_kg=0.25 elapsed_ticks={} history=production",now);
            close();h.succeed();
        }
        public void close() throws Exception {
            if(done)return;done=true;person.close();SurvivalRuntime.INPUTS.remove(fixture.level);fixture.close();
        }
    }
}
