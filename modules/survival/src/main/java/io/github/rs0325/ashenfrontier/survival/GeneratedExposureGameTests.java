package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.climate.EnvironmentConnectionGameTests;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.core.food.FoodUse;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Random;

@GameTestHolder(AshenFrontierSurvival.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedExposureGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126_generated",timeoutTicks=400)
    public static void generatedOperationsUseIndependentExposureAndReceiptOracle(GameTestHelper h) throws Exception {
        int logins=0,sources=0,escapes=0,shieldChanges=0,unknown=0,intakeReplays=0,sleepGenerations=0,saves=0;
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            ExposureGameTests.flatFloor(fixture.level,fixture.pos);long now=0;
            for(int seed=0;seed<128;seed++) {
                var random=new Random(12620261127L+seed);var input=new ExposureGameTests.Atmosphere(now);
                SurvivalRuntime.INPUTS.put(fixture.level,input);
                try(var person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"generated126_"+seed)) {
                    logins++;person.runtime.data.body.water=40;
                    double expectedCo=0,expectedLead=0,expectedBq=0;long expectedTicks=0;int feeds=0;
                    for(int operation=0;operation<32;operation++) {
                        double fraction=operation%8==0?.0004+random.nextDouble()*.001:0;
                        switch(operation%8) {
                            case 0,2,6 -> {
                                input.set(now,fraction,.000420,21200);sources++;if(fraction==0)escapes++;
                                now+=20;expectedTicks+=20;SurvivalGameTests.time(fixture.level,now);
                                SurvivalGameTests.drain(h,person.runtime);
                                double u=Math.max(0,fraction-.000005)/.0002;
                                expectedCo=u+(expectedCo-u)*Math.exp(-1.0/120);
                            }
                            case 1 -> {
                                var water=IntakeGameTests.water(1);
                                water.set(ItemProperties.CONTAMINATION,new ItemProperties.Contamination(java.util.Map.of("lead",1e-8,"pcb",0.0)));
                                water.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("ashen_frontier:cs137",1,now));
                                water.set(FoodProperties.ACTIVITY_UNCERTAINTY,.1);person.player.setItemInHand(IntakeGameTests.HAND,water);
                                h.assertTrue(IntakeGameTests.action(person,FoodUse.Action.START)&&IntakeGameTests.action(person,FoodUse.Action.FINISH),"実際の部分飲水を確定");
                                SurvivalGameTests.drain(h,person.runtime);feeds++;expectedLead+=2.5e-9;expectedBq+=.25;
                                h.assertTrue(!IntakeGameTests.action(person,FoodUse.Action.FINISH),"同tickの摂取再送を拒否");intakeReplays++;
                                var data=person.player.getData(AshenFrontierSurvival.INTAKE);
                                h.assertTrue(data.issued==feeds,"独立した操作回数と受領台帳が一致");
                            }
                            case 3 -> {
                                input.set(now,.0012,.000420,21200);input.set(now,0,.000420,21200);sources++;
                                person.player.setItemInHand(IntakeGameTests.HAND,ItemStack.EMPTY);
                                var origin=person.player.getEyePosition();var stack=new ItemStack(Items.STICK);
                                stack.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("ashen_frontier:cs137",1e6,now));
                                var entity=new ItemEntity(fixture.level,origin.x+2,origin.y,origin.z,stack);fixture.level.addFreshEntity(entity);
                                var shield=BlockPos.containing(origin.add(1,0,0));fixture.level.setBlock(shield,Blocks.STONE.defaultBlockState(),3);
                                try(var point=ExposureGameTests.measured(person)) {
                                    double expected=9.27e-14/3600*1e6/4*.01;
                                    h.assertTrue(point.known&&point.rates.size()==1&&Math.abs(point.rates.getFirst().lower()-expected)<expected*1e-12,"同tickの線源・遮蔽と独立した逆二乗の期待値");
                                }
                                fixture.level.setBlock(shield,Blocks.AIR.defaultBlockState(),3);entity.discard();shieldChanges++;
                            }
                            case 4 -> {
                                input.clock.change(now,operation%16==4?12000:24000);
                                var stable=person.runtime.data.exposure.snapshot();person.runtime.work(System.nanoTime()+10_000_000,64);
                                h.assertTrue(java.util.Arrays.equals(stable.totals(),person.runtime.data.exposure.snapshot().totals()),"同時刻の再評価は累積を増やさない");
                            }
                            case 5 -> {
                                person.player.setPos(fixture.pos.getX()+1.5,fixture.pos.getY(),fixture.pos.getZ()+.5);person.runtime.capture();
                                person.player.setPos(fixture.pos.getX()+.5,fixture.pos.getY(),fixture.pos.getZ()+.5);person.runtime.capture();escapes++;
                            }
                            case 7 -> {
                                input.pending=true;long cursor=person.runtime.data.cursor;input.set(now,0,.000420,21200);
                                now+=20;expectedTicks+=20;SurvivalGameTests.time(fixture.level,now);person.runtime.work(System.nanoTime()+10_000_000,64);
                                h.assertTrue(person.runtime.data.cursor==cursor,"C01/C06の保留は物理cursorを進めない");
                                input.pending=false;SurvivalGameTests.drain(h,person.runtime);expectedCo*=Math.exp(-1.0/120);
                                var far=fixture.pos.offset(10000,0,10000);person.player.setPos(far.getX()+.5,far.getY(),far.getZ()+.5);
                                h.assertTrue(!fixture.level.hasChunkAt(far),"未ロードの観測位置");
                                try(var point=ExposureGameTests.measured(person)) { h.assertTrue(!point.known,"未ロードの局所線源は未評価"); }
                                h.assertTrue(!fixture.level.hasChunkAt(far),"線量観測でチャンクを強制生成しない");
                                person.player.setPos(fixture.pos.getX()+.5,fixture.pos.getY(),fixture.pos.getZ()+.5);unknown++;
                            }
                            default -> throw new IllegalStateException();
                        }
                        if(operation%4==3){ExposureGameTests.restore(person);logins++;saves++;}
                        var state=person.runtime.data.exposure;
                        h.assertTrue(Math.abs(state.load[0]-expectedCo)<5e-13,"独立した各段階の濃度時間収支 seed="+seed+" operation="+operation);
                        h.assertTrue(Math.abs(state.oralLead-expectedLead)<1e-20&&Math.abs(state.committedLower-expectedBq*1.3e-8)<1e-19,"各段階の経口kgと一回だけのSv");
                        h.assertTrue(Math.abs(state.unassessedSeconds-expectedTicks/20.0)<1e-10,"fixtureの追加線源未観測をゼロ/安全にしない");
                        if(feeds>0)h.assertTrue(Math.abs(state.nuclearIntakes.get("ashen_frontier:cs137").oralLower()-expectedBq)<1e-12,"核種ごとのBqを保存");
                        h.assertTrue(person.runtime.data.cursor==now,"評価末尾を飛ばさない");
                    }
                } finally { SurvivalRuntime.INPUTS.remove(fixture.level); }
                if(seed<32) { sameTickSleep(h,fixture);now=fixture.level.getGameTime();sleepGenerations++; }
            }
        }
        h.assertTrue(logins>=64&&sources>=128&&escapes>=128&&shieldChanges>=64&&unknown>=64&&intakeReplays>=128&&sleepGenerations>=32&&saves>=1024,
                "生成した操作の実効回数の下限");
        com.mojang.logging.LogUtils.getLogger().info("#126 generated seeds=128 operations=4096 login={} source={} escape={} source_shield={} unknown={} intake_replay={} sleep_generation={} save={}",
                logins,sources,escapes,shieldChanges,unknown,intakeReplays,sleepGenerations,saves);
        h.succeed();
    }
    private static void sameTickSleep(GameTestHelper h,EnvironmentConnectionGameTests.Fixture fixture) {
        var level=fixture.level;long start=level.getGameTime();var input=new ExposureGameTests.Atmosphere(start);SurvivalRuntime.INPUTS.put(level,input);
        boolean daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);long day=level.getDayTime();
        try(var person=new SurvivalGameTests.Person(level,fixture.pos,"sleep126")) {
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());level.setDayTime(6000);
            SurvivalGameTests.drain(h,person.runtime);var bed=person.player.blockPosition();
            level.setBlock(bed,Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.HEAD),3);
            level.setBlock(bed.north(),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT),3);
            person.runtime.data.body.sleepDebt=40000;h.assertTrue(person.player.startSleepInBed(bed).right().isPresent(),"実寝床で第1世代を開始");
            NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(person.player));input.pending=true;
            for(int tick=1;tick<=20;tick++) { SurvivalGameTests.time(level,start+tick);person.player.doTick();NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(person.player)); }
            person.player.stopSleeping();person.player.setPos(bed.getX()+.5,bed.getY(),bed.getZ()+.5);person.runtime.capture();
            h.assertTrue(person.player.startSleepInBed(bed).right().isPresent(),"同tickに起床して再就寝");NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(person.player));
            input.set(start+20,0,.2,21200);
            for(int tick=21;tick<=120;tick++) {
                SurvivalGameTests.time(level,start+tick);for(int probe=0;probe<2;probe++)person.runtime.work(Long.MAX_VALUE,0);
                person.player.doTick();NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(person.player));
            }
            h.assertTrue(person.runtime.data.pendingSleep==2,"新しい深睡眠候補を過去の保留より先に返済しない");
            input.pending=false;SurvivalGameTests.drain(h,person.runtime);
            double expected=(.2-.001)/.03*(1-Math.exp(-5.0/30));
            h.assertTrue(Math.abs(person.runtime.data.exposure.load[1]-expected)<1e-12,"独立した第2世代のCO2負荷");
            h.assertTrue(person.runtime.data.invalidSleep==2&&person.runtime.data.consumedSleep==2&&person.runtime.data.lastCredit==-1,"新しい世代のガス曝露で返済を拒否");
            person.player.stopSleeping();person.runtime.capture();
        } finally { SurvivalRuntime.INPUTS.remove(level);level.setDayTime(day);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer()); }
    }
}
