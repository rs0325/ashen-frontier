package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.MaterialData;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.food.*;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(AshenFrontierSurvival.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IntakeGameTests {
    static final InteractionHand HAND=InteractionHand.MAIN_HAND;
    static FoodOrigin origin(String profile) {
        return new FoodOrigin("fixture125","fixture125",profile,UUID.randomUUID(),FoodOrigin.State.CONFIRMED);
    }
    static ItemStack food(String id,int count,long now) {
        var stack=new ItemStack(RelicContent.item(id),count);
        var origin=origin(id);
        stack.set(FoodProperties.ORIGIN,origin);
        stack.set(FoodProperties.EVIDENCE,new FoodEvidence(origin,"ashen_frontier:"+id,id,true,"m2_fixture",1));
        stack.set(ItemProperties.ROT,new ItemProperties.Rot(now,now,0));
        return stack;
    }
    static ItemStack water(int count) {
        var stack=new ItemStack(RelicContent.item("sealed_water_container"),count);
        var origin=origin("clean_water");
        stack.set(FoodProperties.EVIDENCE,new FoodEvidence(origin,"ashen_frontier:clean_water","water",true,"m2_fixture",1));
        return stack;
    }
    static void check(GameTestHelper h,boolean test,String message) { h.assertTrue(test,message); }
    static boolean action(SurvivalGameTests.Person person,FoodUse.Action action) { return FoodUse.request(person.player,HAND,action); }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="intake125_fixture",timeoutTicks=400)
    public static void initialFoodWaterAndCookedIntakeAreAtomic(GameTestHelper h) {
        var level=h.getLevel();var inputs=new SurvivalGameTests.Inputs(level.getGameTime());SurvivalRuntime.INPUTS.put(level,inputs);
        try(var person=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"intake125")) {
            var p=person.player;var body=person.runtime.data.body;body.energy=1e6;body.water=40;
            p.getFoodData().setFoodLevel(7);
            var cans=food("canned_food",2,level.getGameTime());p.setItemInHand(HAND,cans);
            check(h,MaterialData.get(cans.getItem()).orElseThrow().massKg()==.2,"缶詰の質量0.20kg");
            check(h,cans.getItem().use(level,p,HAND).getResult().consumesAction(),"缶詰の通常使用開始");
            cans.getItem().finishUsingItem(cans,level,p);
            check(h,!action(person,FoodUse.Action.FINISH),"再送は確定しない");
            check(h,p.getItemInHand(HAND).getCount()==1,"食品1個のみ消費");
            check(h,p.getFoodData().getFoodLevel()==7,"旧nutrition6を加算しない");
            SurvivalGameTests.drain(h,person.runtime);
            check(h,Math.abs(body.energy-2.45e6)<1e-7&&Math.abs(body.water-40.132)<1e-10,"実際の1個分のJ/kgを生理へ");
            var ration=food("preserved_rations",1,level.getGameTime());p.setItemInHand(HAND,ration);
            check(h,MaterialData.get(ration.getItem()).orElseThrow().massKg()==.25,"保存食の質量0.25kg");
            check(h,action(person,FoodUse.Action.START)&&action(person,FoodUse.Action.FINISH),"保存食も同じ受付");
            SurvivalGameTests.drain(h,person.runtime);check(h,Math.abs(body.energy-6.95e6)<1e-7,"固定栄養表");
            p.setItemInHand(HAND,water(1));
            for(int n=0;n<4;n++)check(h,action(person,FoodUse.Action.START)&&action(person,FoodUse.Action.FINISH),"飲水の未評価INTAKEも容量へ算入");
            SurvivalGameTests.drain(h,person.runtime);
            check(h,p.getItemInHand(HAND).get(RelicProperties.CONTENT_MASS)==0,"同じIDの空容器を残す");
            check(h,Math.abs(body.water-41.142)<1e-9,"食中水と飲水を二重計上しない");
            check(h,!action(person,FoodUse.Action.START),"空容器は消費しない");
            var cooked=food("canned_food",1,level.getGameTime());
            cooked.set(FoodProperties.NUTRITION,new NutritionData(1234,.001,.002,.003,Set.of("c")));
            var proof=cooked.get(FoodProperties.EVIDENCE);
            cooked.set(FoodProperties.EVIDENCE,new FoodEvidence(proof.origin(),proof.scope(),"cooked_staple",true,"cooking_fixture",1));
            body.vitaminC=12345;body.vitaminFat=23456;
            p.setItemInHand(HAND,cooked);
            check(h,action(person,FoodUse.Action.START)&&action(person,FoodUse.Action.FINISH),"料理fixtureの個別栄養を優先");
            SurvivalGameTests.drain(h,person.runtime);check(h,Math.abs(body.energy-6951234)<1e-7,"個別Jは固定表に置換しない");
            check(h,body.vitaminC==0&&body.vitaminFat==23456,"定性ビタミン区分だけを摂取へ反映");
            body.energy=19.5e6;p.setItemInHand(HAND,food("canned_food",1,level.getGameTime()));
            check(h,action(person,FoodUse.Action.START)&&action(person,FoodUse.Action.FINISH),"不足があれば食品1個を消費");
            SurvivalGameTests.drain(h,person.runtime);check(h,body.energy==20e6&&p.getItemInHand(HAND).isEmpty(),"体内容量超過を別の食品へ返さない");
            body.water=41.95;p.setItemInHand(HAND,water(1));
            check(h,action(person,FoodUse.Action.START)&&action(person,FoodUse.Action.FINISH),"飲水は体水分不足分で制限");
            SurvivalGameTests.drain(h,person.runtime);
            check(h,body.water==42&&Math.abs(p.getItemInHand(HAND).get(RelicProperties.CONTENT_MASS)-.95)<1e-9,"不足0.05kgだけ飲む");
            h.succeed();
        } finally { SurvivalRuntime.INPUTS.remove(level); }
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="intake125_fixture",timeoutTicks=400)
    public static void cancelFullReturnCompetingUsesAndReloginDoNotDuplicate(GameTestHelper h) {
        var level=h.getLevel();SurvivalRuntime.INPUTS.put(level,new SurvivalGameTests.Inputs(level.getGameTime()));
        try(var a=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"a125");
            var b=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(1,2,0)),"b125")) {
            a.runtime.data.body.energy=1e6;a.runtime.data.body.water=41;
            var item=food("canned_food",2,level.getGameTime());a.player.setItemInHand(HAND,item);
            check(h,action(a,FoodUse.Action.START),"開始");action(a,FoodUse.Action.CANCEL);
            check(h,!action(a,FoodUse.Action.FINISH)&&a.player.getItemInHand(HAND).getCount()==2,"取消は未消費");
            check(h,action(a,FoodUse.Action.START),"再開");
            a.player.getItemInHand(HAND).shrink(1);
            check(h,!action(a,FoodUse.Action.FINISH),"同時変更があれば取引しない");
            check(h,!action(b,FoodUse.Action.FINISH),"別人の確定は操作を持たない");
            check(h,action(a,FoodUse.Action.START),"保存前開始");
            var saved=a.runtime.data.save();a.logout();a.player.setData(AshenFrontierSurvival.DATA,PhysiologyData.CODEC.parse(NbtOps.INSTANCE,saved).getOrThrow());a.login();
            check(h,!action(a,FoodUse.Action.FINISH),"未完了の使用は再ログインで取消");
            for(int n=0;n<a.player.getInventory().items.size();n++)a.player.getInventory().items.set(n,new ItemStack(Items.STONE,64));
            a.player.setItemInHand(HAND,water(2));
            check(h,action(a,FoodUse.Action.START)&&!action(a,FoodUse.Action.FINISH),"返却先満杯は消費前に拒否");
            check(h,a.player.getItemInHand(HAND).getCount()==2&&a.player.getItemInHand(HAND).get(RelicProperties.CONTENT_MASS)==1,"水と容器を保持");
            a.player.getInventory().items.set(10,ItemStack.EMPTY);
            check(h,action(a,FoodUse.Action.START)&&action(a,FoodUse.Action.FINISH),"空き確保後に一回だけ確定");
            check(h,a.player.getInventory().items.get(10).get(RelicProperties.CONTENT_MASS)==.75,"部分飲用容器を返却");
            SurvivalGameTests.drain(h,a.runtime);check(h,Math.abs(a.runtime.data.body.water-41.25)<1e-9,"0.25kgのみ反映");
            a.runtime.data.body.water=42;
            check(h,!action(a,FoodUse.Action.START),"満水時は0kg、未消費");
            h.succeed();
        } finally { SurvivalRuntime.INPUTS.remove(level); }
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="intake125_fixture",timeoutTicks=400)
    public static void dayLengthTemperatureSaveAndQuantitativeInheritance(GameTestHelper h) {
        long from=h.getLevel().getGameTime();
        var input=food("canned_food",6,from);var old=new ItemProperties.Rot(from,from,864000);input.set(ItemProperties.ROT,old);
        var job=new FoodAging(old);
        job.add(new EnvironmentSample(from,from+100,24000,293.15,101325,.5,0,0,java.util.Optional.empty(),true,true));
        job=FoodAging.load(job.save());
        job.add(new EnvironmentSample(from+100,from+200,12000,303.15,101325,.5,0,0,java.util.Optional.empty(),true,true));
        var end=job.finish(from+200);
        check(h,end.createdAt()==from&&end.lastCalculatedAt()==from+200&&end.referenceSeconds()==865800,"日長と温度の分割を独立算術360+1440で検算");
        input.set(ItemProperties.ROT,end);
        input.set(ItemProperties.CONTAMINATION,new ItemProperties.Contamination(Map.of("lead",1e-7,"pcb",1e-8)));
        input.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("ashen_frontier:cs137",1.2,from));
        var output=food("preserved_rations",2,from+200);output.set(FoodProperties.PROFILE,"dried_vegetables");
        FoodInheritance.apply(input,6,output,"drying_fixture");
        check(h,Math.abs(output.get(FoodProperties.NUTRITION).energyJoules()*2-1450000*6)<1e-6,"栄養の量保存");
        check(h,Math.abs(output.get(ItemProperties.CONTAMINATION).amounts().get("lead")*.25*2-1e-7*.2*6)<1e-18,"化学物質量を保存");
        check(h,Math.abs(output.get(ItemProperties.RADIOACTIVITY).becquerels()*2-1.2*6)<.200001,"総Bqの既存0.1丸め");
        check(h,output.get(FoodProperties.ACTIVITY_UNCERTAINTY)>=.3,"放射能の切捨て幅も引き継ぐ");
        check(h,output.get(ItemProperties.ROT).createdAt()==from,"加工で作成時刻を若返らせない");
        double rho=end.referenceSeconds()/(180*86400.0), after=output.get(ItemProperties.ROT).referenceSeconds()/(200*86400.0);
        check(h,after>=rho&&after-rho<=600/(200*86400.0),"相対劣化率を保持");
        var ops=RegistryOps.create(NbtOps.INSTANCE,h.getLevel().registryAccess());
        var encoded=ItemStack.CODEC.encodeStart(ops,output).getOrThrow();var restored=ItemStack.CODEC.parse(ops,encoded).getOrThrow();
        var split=restored.split(1);check(h,ItemStack.isSameItemSameComponents(split,restored),"保存・分割・同成分合体で由来と劣化を保持");
        restored.grow(1);check(h,restored.getCount()==2&&restored.get(FoodProperties.EVIDENCE).equals(output.get(FoodProperties.EVIDENCE)),"由来はリセットしない");
        var clock=new DayLengthHistory(from,24000);clock.change(from+100,12000);
        double decayed=FoodAging.activity(new ItemProperties.Radioactivity("ashen_frontier:cs137",100,from),from+200,clock);
        check(h,Math.abs(decayed-100*Math.exp(-Math.log(2)*1080/(30.05*365.25*86400)))<1e-10,"Csの半減期もGAME履歴");
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="intake125_fixture",timeoutTicks=400)
    public static void originReplacementAndUnknownIntakeAreRejected(GameTestHelper h) {
        var root=origin("shelter_food_soil");var water=origin("clean_water");
        check(h,CultivationQuery.query(root,"barley","fertile_soil","clean_water",SoilContamination.Stages.CLEAN,water).accepted(),"M2由来の食用床");
        var ops=RegistryOps.create(NbtOps.INSTANCE,h.getLevel().registryAccess());
        var soil=new ItemStack(Items.DIRT);soil.set(FoodProperties.ORIGIN,root);
        var moved=ItemStack.CODEC.parse(ops,ItemStack.CODEC.encodeStart(ops,soil).getOrThrow()).getOrThrow();
        check(h,CultivationQuery.query(moved.get(FoodProperties.ORIGIN),"soybean","fertile_soil","clean_water",SoilContamination.Stages.CLEAN,water).accepted(),"運搬再設置で同じ由来");
        check(h,!CultivationQuery.query(null,"barley","fertile_soil","clean_water",SoilContamination.Stages.CLEAN,water).accepted(),"未知の床へ交換すると清浄段階だけでは許可しない");
        check(h,!CultivationQuery.query(root,"flax","fertile_soil","clean_water",SoilContamination.Stages.CLEAN,water).accepted(),"非食用を食用へ混ぜない");
        var level=h.getLevel();SurvivalRuntime.INPUTS.put(level,new SurvivalGameTests.Inputs(level.getGameTime()));
        try(var person=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"unknown125")) {
            person.runtime.data.body.energy=1e6;
            var item=food("canned_food",1,level.getGameTime());item.remove(ItemProperties.ROT);person.player.setItemInHand(HAND,item);
            check(h,!action(person,FoodUse.Action.START),"欠けた腐敗履歴を新品にしない");
            item=food("canned_food",1,Math.max(0,level.getGameTime()-1));person.player.setItemInHand(HAND,item);
            check(h,action(person,FoodUse.Action.START)&&!action(person,FoodUse.Action.FINISH),"温度履歴が未提供なら過去を捏造しない");
            item=food("canned_food",1,level.getGameTime());item.set(ItemProperties.CONTAMINATION,new ItemProperties.Contamination(Map.of("unknown",0.0)));person.player.setItemInHand(HAND,item);
            check(h,!action(person,FoodUse.Action.START),"未知の汚染種は0でも拒否");
            item=food("canned_food",1,level.getGameTime());item.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("unknown",0,level.getGameTime()));person.player.setItemInHand(HAND,item);
            check(h,!action(person,FoodUse.Action.START),"未知核種を0でも拒否");
            item=food("canned_food",1,level.getGameTime());item.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("",0,level.getGameTime()));person.player.setItemInHand(HAND,item);
            check(h,!action(person,FoodUse.Action.START),"明示された空核種を基準ゼロにしない");
            item=water(1);var evidence=item.get(FoodProperties.EVIDENCE);
            item.set(FoodProperties.EVIDENCE,new FoodEvidence(evidence.origin(),evidence.scope(),"concentrated_residue",true,"boiling_fixture",1));person.player.setItemInHand(HAND,item);
            check(h,!action(person,FoodUse.Action.START),"清浄品質名でも濃縮残水を拒否");
            item=new ItemStack(Items.STONE);item.set(FoodProperties.EVIDENCE,new FoodEvidence(origin("canned_food"),"minecraft:stone","cooked_staple",true,"fixture",1));
            person.player.setItemInHand(HAND,item);check(h,!action(person,FoodUse.Action.START),"未定義の栄養を0栄養の食品にしない");
            h.succeed();
        } finally { SurvivalRuntime.INPUTS.remove(level); }
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="intake125_fixture",timeoutTicks=400)
    public static void generatedIndependentTransactions(GameTestHelper h) {
        var level=h.getLevel();SurvivalRuntime.INPUTS.put(level,new SurvivalGameTests.Inputs(level.getGameTime()));
        int accepted=0,cancelled=0,replayed=0,full=0,saved=0;
        try(var person=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"generated125")) {
            var random=new Random(1257720261126L);
            for(int n=0;n<1024;n++) {
                var body=person.runtime.data.body;body.energy=1e6;body.water=40;body.protein=0;body.fat=0;
                boolean can=random.nextBoolean();String id=can?"canned_food":"preserved_rations";
                int count=1+random.nextInt(8);var item=food(id,count,level.getGameTime());person.player.setItemInHand(HAND,item);
                int kind=n%4;
                if(kind==0) { body.energy=20e6;check(h,!action(person,FoodUse.Action.START),"満腹");full++; }
                else {
                    check(h,action(person,FoodUse.Action.START),"生成開始");
                    if(kind==1) { action(person,FoodUse.Action.CANCEL);cancelled++; }
                    else { check(h,action(person,FoodUse.Action.FINISH),"生成確定");accepted++; }
                }
                check(h,!action(person,FoodUse.Action.FINISH),"同ID再送");replayed++;
                SurvivalGameTests.drain(h,person.runtime);
                boolean ate=kind>=2;
                check(h,person.player.getItemInHand(HAND).getCount()==count-(ate?1:0),"独立の個数判定");
                double expectedEnergy=kind==0?20e6:1e6+(ate?(can?1450000:4500000):0);
                check(h,Math.abs(body.energy-expectedEnergy)<1e-6,"独立のJ判定 step="+n);
                check(h,Math.abs(body.water-(40+(ate?(can?.132:.010):0)))<1e-9,"独立の水kg判定");
                check(h,Math.abs(body.protein-(ate?(can?.042:.025):0))<1e-9&&Math.abs(body.fat-(ate?(can?.021:.040):0))<1e-9,"独立の蛋白脂質判定");
                if(n%16==0) {
                    var tag=person.runtime.data.save();person.logout();person.player.setData(AshenFrontierSurvival.DATA,PhysiologyData.CODEC.parse(NbtOps.INSTANCE,tag).getOrThrow());person.login();saved++;
                }
            }
            check(h,accepted>=512&&cancelled>=256&&replayed>=1024&&full>=256&&saved>=64,"実効成立回数の下限");
            com.mojang.logging.LogUtils.getLogger().info("M3-125 generated accepted={} cancel={} replay={} full={} restore={}",accepted,cancelled,replayed,full,saved);
            h.succeed();
        } finally { SurvivalRuntime.INPUTS.remove(level); }
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="intake125_fixture",timeoutTicks=400)
    public static void generatedAgingSaveAndDelayedOwnerResponse(GameTestHelper h) {
        var random=new Random(1258820261126L);int segments=0,restores=0,changes=0;
        long now=h.getLevel().getGameTime();
        for(int seed=0;seed<128;seed++) {
            var job=new FoodAging(new ItemProperties.Rot(now,now,600));double expected=600;long cursor=now;
            for(int n=0;n<32;n++) {
                long ticks=1+random.nextInt(600);long day=n%2==0?24000:12000;double kelvin=273.15+random.nextDouble()*60;
                job.add(new EnvironmentSample(cursor,cursor+ticks,day,kelvin,101325,.5,0,0,java.util.Optional.empty(),true,true));
                expected+=ticks*86400.0/day*Math.exp(Math.log(2)*(kelvin-293.15)/10);cursor+=ticks;segments++;
                if(n%4==0) { job=FoodAging.load(job.save());restores++; }
                if(n>0)changes++;
            }
            var result=job.finish(cursor);
            check(h,result.referenceSeconds()==Math.ceil(expected/600)*600&&result.lastCalculatedAt()==cursor&&result.createdAt()==now,"独立の温度/日長/保存判定 seed="+seed);
        }
        check(h,segments>=4096&&restores>=1024&&changes>=3968,"腐敗の実効回数下限");
        var level=h.getLevel();SurvivalRuntime.INPUTS.put(level,new SurvivalGameTests.Inputs(now));
        try(var person=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"history125")) {
            person.runtime.data.body.energy=1e6;boolean[] ready={false};
            java.util.function.Consumer<FoodHistoryQuery> provider=event->{
                if(event.player()!=person.player||!ready[0])return;
                var copy=event.source();var rot=copy.get(ItemProperties.ROT);
                var job=new FoodAging(rot);
                job.add(new EnvironmentSample(rot.lastCalculatedAt(),event.through(),24000,293.15,101325,.5,0,0,java.util.Optional.empty(),true,true));
                copy.set(ItemProperties.ROT,job.finish(event.through()));event.provide(copy);
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(provider);
            try {
                check(h,now>0,"過去を含む履歴試験");
                var item=food("canned_food",1,now-1);person.player.setItemInHand(HAND,item);
                check(h,action(person,FoodUse.Action.START)&&!action(person,FoodUse.Action.FINISH),"遅延応答の間は未消費");
                check(h,person.player.getItemInHand(HAND).getCount()==1&&person.runtime.data.body.energy==1e6,"遅延で部分反映しない");
                ready[0]=true;
                check(h,action(person,FoodUse.Action.START)&&action(person,FoodUse.Action.FINISH),"所有者が履歴を確定した後だけ通常使用を再開");
                SurvivalGameTests.drain(h,person.runtime);check(h,person.runtime.data.body.energy==2.45e6,"遅延再試行で1個分だけ");
            } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(provider); }
        } finally { SurvivalRuntime.INPUTS.remove(level); }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="intake125_fixture",timeoutTicks=400)
    public static void receiptSurvivesActualPlayerSaveAndCorruptionStopsIntake(GameTestHelper h) {
        var level=h.getLevel();SurvivalRuntime.INPUTS.put(level,new SurvivalGameTests.Inputs(level.getGameTime()));
        try(var person=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"receipt125")) {
            person.runtime.data.body.energy=1e6;
            person.player.setItemInHand(HAND,food("canned_food",3,level.getGameTime()));
            long operation=FoodUse.begin(person.player,HAND).orElseThrow();
            check(h,FoodUse.request(person.player,HAND,operation,FoodUse.Action.START).accepted(),"同じ開始を再送しても番号を増やさない");
            check(h,FoodUse.request(person.player,HAND,operation,FoodUse.Action.FINISH).accepted(),"番号付き確定");
            var entity=new net.minecraft.nbt.CompoundTag();person.player.saveWithoutId(entity);person.logout();
            person.player.removeData(AshenFrontierSurvival.DATA);person.player.removeData(AshenFrontierSurvival.INTAKE);
            person.player.load(entity);person.login();
            check(h,!FoodUse.request(person.player,HAND,operation,FoodUse.Action.START).accepted(),"保存済みの番号を新しい使用へ戻さない");
            check(h,!FoodUse.request(person.player,HAND,operation,FoodUse.Action.FINISH).accepted(),"復元後の確定再送を拒否");
            SurvivalGameTests.drain(h,person.runtime);check(h,person.runtime.data.body.energy==2.45e6,"保存した未評価INTAKEも一回だけ");
            check(h,person.player.getItemInHand(HAND).getCount()==2,"保存前後の在庫も一回だけ");
            long next=FoodUse.begin(person.player,HAND).orElseThrow();check(h,next==operation+1,"本人の番号は連続");
            FoodUse.request(person.player,HAND,next,FoodUse.Action.CANCEL);
            entity=new net.minecraft.nbt.CompoundTag();person.player.saveWithoutId(entity);person.logout();
            var key=AshenFrontierSurvival.id("intake_receipts").toString();
            var raw=entity.getCompound(net.neoforged.neoforge.attachment.AttachmentHolder.ATTACHMENTS_NBT_KEY).getCompound(key);
            raw.putString("issued","broken");var broken=raw.copy();
            person.player.removeData(AshenFrontierSurvival.DATA);person.player.removeData(AshenFrontierSurvival.INTAKE);
            person.player.load(entity);person.login();
            check(h,!action(person,FoodUse.Action.START),"破損した受領番号を初期状態にしない");
            check(h,person.player.getData(AshenFrontierSurvival.INTAKE).failure!=null,"破損を新規と区別する");
            check(h,person.player.serializeAttachments(level.registryAccess()).get(key).equals(broken),"破損原文を保全");
            h.succeed();
        } finally { SurvivalRuntime.INPUTS.remove(level); }
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="intake125_fixture",timeoutTicks=400)
    public static void storageWorkUsesBoundedIntervalsAndResumes(GameTestHelper h) {
        var level=h.getLevel();long from=level.getGameTime(),target=from+1999;
        var position=h.absolutePos(new BlockPos(3,2,3));int[] queries={0};
        java.util.function.Consumer<io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery> provider=event->{
            if(event.level()!=level||!event.position().equals(position))return;
            var values=new java.util.ArrayList<EnvironmentSample>();
            values.add(new EnvironmentSample(event.startTick(),event.startTick()+1,24000,293.15,101325,.5,0,0,java.util.Optional.empty(),true,true));
            if(event.startTick()+1<event.endTick())values.add(new EnvironmentSample(event.startTick()+1,event.endTick(),24000,293.15,101325,.5,0,0,java.util.Optional.empty(),true,true));
            event.provide(values);queries[0]++;
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(provider);
        int calls=0,restores=0;
        try {
            var job=new FoodAging(new ItemProperties.Rot(from,from,0));
            while(job.through()<target&&calls<10000) {
                io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget.beginTick(level.getServer());
                int before=queries[0];long prior=job.through();
                job.work(level,position,target);calls++;
                check(h,queries[0]-before<=16&&job.through()-prior<=16,"1仕事の区間上限を守る");
                if(calls%3==0){job=FoodAging.load(job.save());restores++;}
            }
            check(h,job.through()==target&&calls>=125&&restores>=41,"有限仕事の完了と実効継続回数");
            check(h,job.finish(target).referenceSeconds()==7200,"小区間の反復で600秒丸めを重ねない");
            var invalid=job.save();invalid.remove("reference");
            boolean rejected=false;try { FoodAging.load(invalid); } catch(IllegalArgumentException expected) { rejected=true; }
            check(h,rejected,"欠落した継続保存を新品として読まない");
        } finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(provider); }
        h.succeed();
    }
}
