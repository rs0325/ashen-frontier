package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.climate.ClimateClock;
import io.github.rs0325.ashenfrontier.climate.EnvironmentConnectionGameTests;
import io.github.rs0325.ashenfrontier.climate.EnvironmentProvider;
import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.core.food.FoodUse;
import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import io.github.rs0325.ashenfrontier.world.BlockContamination;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

@GameTestHolder(AshenFrontierSurvival.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExposureGameTests {
    static final class Atmosphere implements SurvivalRuntime.Inputs {
        record Gas(double co,double co2,double oxygen) {}
        final DayLengthHistory clock;
        final TreeMap<Long,Gas> gases=new TreeMap<>();
        boolean pending;
        Atmosphere(long tick) { clock=new DayLengthHistory(tick,24000);set(tick,0,.000420,21200); }
        void set(long tick,double co,double co2,double oxygen) { gases.put(tick,new Gas(co,co2,oxygen)); }
        @Override public Optional<DayLengthHistory> clock(ServerLevel level) { return Optional.of(clock); }
        @Override public Optional<SurvivalRuntime.Observation> sample(ServerLevel level,BlockPos position,long from,long to,boolean submerged) {
            if(pending&&from<to)return Optional.empty();
            var interval=clock.intervals(from,to).getFirst();var gas=gases.floorEntry(from).getValue();var next=gases.higherKey(from);
            long end=Math.min(interval.endTick(),next==null?Long.MAX_VALUE:next);
            return Optional.of(new SurvivalRuntime.Observation(from,end,interval.ticksPerDay(),
                    new Physiology.Air(295.15,.5,.1,0,101325,gas.oxygen,0,true,submerged),
                    new Exposure.Air(gas.co,gas.co2,gas.oxygen,0,true),false,false));
        }
    }
    static void restore(SurvivalGameTests.Person person) {
        var tag=new CompoundTag();person.player.saveWithoutId(tag);person.logout();
        person.player.removeData(AshenFrontierSurvival.DATA);person.player.load(tag);person.login();
    }
    static void flatFloor(ServerLevel level,BlockPos feet) {
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)if(x*x+z*z<=64)
            level.setBlock(feet.offset(x,-1,z),Blocks.STONE.defaultBlockState(),3);
    }
    static RadiationField.Point measured(SurvivalGameTests.Person person) {
        var point=new RadiationField.Point(person.player);
        for(int work=0;work<10000&&!point.done;work++)point.work(System.nanoTime()+1_000_000);
        if(!point.done)throw new IllegalStateException("局所線源の有限観測が完了しません");
        return point;
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure_cold126",timeoutTicks=400)
    public static void fourMovingPeopleReportColdCaptureAndSavedBytes(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            flatFloor(f.level,f.pos);f.ready(h,0,0);
            var people=new java.util.ArrayList<SurvivalGameTests.Person>();
            var clock=new io.github.rs0325.ashenfrontier.core.testing.ThreadCpuClock();boolean cpu=clock.useCpu("exposure_cold_diagnostic",1_000_000);
            long[] walls=new long[64],cpus=new long[64];int moves=0;long bytes=0;
            try {
                for(int n=0;n<4;n++)people.add(new SurvivalGameTests.Person(f.level,f.pos.offset(n%2,0,n/2),"cold126_"+n));
                settle(h,f,people,0);
                for(int step=0;step<64;step++) {
                    long tick=(step+1)*20L;SurvivalGameTests.time(f.level,tick);
                    long wall=System.nanoTime(),started=clock.now();
                    for(int n=0;n<people.size();n++) {
                        var p=f.pos.offset((n+step)%3-1,0,(n+step/3)%3-1);
                        people.get(n).player.setPos(p.getX()+.5,p.getY(),p.getZ()+.5);people.get(n).runtime.capture();moves++;
                    }
                    settle(h,f,people,tick);cpus[step]=clock.now()-started;walls[step]=System.nanoTime()-wall;
                }
                for(var person:people) {
                    var raw=new java.io.ByteArrayOutputStream();
                    try(var output=new java.io.DataOutputStream(raw)) { net.minecraft.nbt.NbtIo.write(person.runtime.data.save(),output); }
                    bytes+=raw.size();
                }
                h.assertTrue(moves>=256,"4人の実移動とcold観測の実効回数");
                java.util.Arrays.sort(walls);java.util.Arrays.sort(cpus);long[] selected=cpu?cpus:walls;
                com.mojang.logging.LogUtils.getLogger().info("#126 cold diagnostic moves={} four_person_events=64 selected={} event_p95_ms={} event_p99_ms={} wall_event_p95_ms={} wall_event_p99_ms={} cpu_event_p95_ms={} cpu_event_p99_ms={} four_body_bytes={}",
                        moves,cpu?"cpu":"wall",selected[60]/1e6,selected[63]/1e6,walls[60]/1e6,walls[63]/1e6,cpus[60]/1e6,cpus[63]/1e6,bytes);
            } finally { people.forEach(SurvivalGameTests.Person::close); }
        }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126",timeoutTicks=400)
    public static void actualHighAltitudeMoveAndReturnUsePartialPressure(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            var low=fixture.pos.offset(20,0,0);var high=new BlockPos(low.getX(),300,low.getZ());
            for(int y=low.getY();y<fixture.level.getMaxBuildHeight();y++)fixture.level.setBlock(new BlockPos(low.getX(),y,low.getZ()),Blocks.AIR.defaultBlockState(),3);
            try(var person=new SurvivalGameTests.Person(fixture.level,low,"altitude126")) {
                var people=List.of(person);settle(h,fixture,people,0);
                double lowOxygen=readyExposure(fixture,low,0).oxygenPa();
                person.player.setPos(high.getX()+.5,high.getY(),high.getZ()+.5);person.runtime.capture();settle(h,fixture,people,0);
                for(int second=1;second<=30;second++)settle(h,fixture,people,second*20L);
                double score=person.runtime.data.exposure.load[2];
                h.assertTrue(readyExposure(fixture,high,600).oxygenPa()<16000&&lowOxygen>16000&&score>0,"実固定マップの仮想標高を圧力へ一度だけ適用");
                person.player.setPos(low.getX()+.5,low.getY(),low.getZ()+.5);person.runtime.capture();settle(h,fixture,people,600);
                for(int second=31;second<=60;second++)settle(h,fixture,people,second*20L);
                h.assertTrue(person.runtime.data.exposure.load[2]<score,"低地への実移動で低酸素負荷が回復");
            }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126",timeoutTicks=400)
    public static void miningUsesSuccessfulRemovalWithoutRequiringDrops(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            var input=new Atmosphere(0);SurvivalRuntime.INPUTS.put(fixture.level,input);
            try(var person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"mining126")) {
                person.player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                var pos=fixture.pos.offset(1,0,0);
                var digging=new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(io.github.rs0325.ashenfrontier.core.AshenFrontierCore.id("digging_stick")));
                digging.set(ItemProperties.WEAR,ItemProperties.Wear.of(0,64));person.player.setItemInHand(IntakeGameTests.HAND,digging);
                fixture.level.setBlock(pos,Blocks.DIRT.defaultBlockState(),3);
                BlockContamination.set(fixture.level,pos,new SoilContamination(.02,.003,.05,6.5,100000,0));
                boolean success=person.player.gameMode.destroyBlock(pos);
                h.assertTrue(fixture.level.getBlockState(pos).isAir(),"実際の除去成功 result="+success+" state="+fixture.level.getBlockState(pos)+" mode="+person.player.gameMode.getGameModeForPlayer()+" build="+person.player.getAbilities().mayBuild);
                SurvivalGameTests.drain(h,person.runtime);
                var state=person.runtime.data.exposure;
                h.assertTrue(Math.abs(state.pulseLead-.5e-6/8*.02)<1e-20
                        &&Math.abs(state.pulsePcb-.5e-6/8*.003)<1e-20
                        &&Math.abs(state.pulseBq-.5e-6/8*100000)<1e-15,"除去前の連続値を採掘パルスへ");
                double pulse=state.pulse;
                fixture.level.setBlock(pos,Blocks.STONE.defaultBlockState(),3);
                person.player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                person.player.gameMode.destroyBlock(pos);
                SurvivalGameTests.drain(h,person.runtime);
                h.assertTrue(Math.abs(state.pulse-pulse-.5e-6/8)<1e-20,"ドロップが出ないクリエイティブの石も成功した採掘");
                pulse=state.pulse;
                fixture.level.setBlock(pos,Blocks.DIRT.defaultBlockState(),3);
                java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.BreakEvent> cancel=event->{
                    if(event.getPlayer()==person.player)event.setCanceled(true);
                };
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(cancel);
                try { h.assertTrue(!person.player.gameMode.destroyBlock(pos),"取消は成功しない"); }
                finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(cancel); }
                fixture.level.destroyBlock(pos,true);
                SurvivalGameTests.drain(h,person.runtime);
                h.assertTrue(state.pulse==pulse,"取消とプレイヤー以外の破壊はパルスにしない");
            } finally { SurvivalRuntime.INPUTS.remove(fixture.level); }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126",timeoutTicks=400)
    public static void groundBackgroundSplitsReobservationAndRestoreMatch(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            flatFloor(fixture.level,fixture.pos);
            var soil=fixture.pos.offset(2,-1,0);fixture.level.setBlock(soil,Blocks.DIRT.defaultBlockState(),3);
            BlockContamination.set(fixture.level,soil,new SoilContamination(1e-5,0,.05,6.5,100000,0));
            try(var person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"ground126")) {
                double distance=person.player.getEyePosition().distanceTo(new Vec3(soil.getX()+.5,soil.getY()+1,soil.getZ()+.5));
                double rate=9.27e-14/3600*(100000*80)/(distance*distance);
                try(var point=measured(person)) {
                    h.assertTrue(point.known&&Math.abs(point.ground-rate)<rate*1e-12,"#79の連続Bq/kg×80kgを実距離で換算");
                    var complete=new Exposure.State();var split=new Exposure.State();
                    Exposure.radiation(complete,List.of(new Exposure.Source(point.ground,point.ground,0)),20,1440,true);
                    for(int n=0;n<20;n++)Exposure.radiation(split,List.of(new Exposure.Source(point.ground,point.ground,0)),1,72,true);
                    h.assertTrue(Math.abs(complete.externalUpper-rate*20)<1e-20&&Math.abs(split.externalUpper-complete.externalUpper)<1e-20,
                            "地面の時間分割で線量が変わらない");
                    SurvivalGameTests.time(fixture.level,400);
                    var restored=RadiationField.Point.load(point.save(400));
                    Exposure.radiation(split,List.of(new Exposure.Source(restored.ground,restored.ground,0)),1,72,true);
                    h.assertTrue(Math.abs(split.externalUpper-rate*21)<1e-20,"保存復元でも地面を減衰し直さない");
                }
                SurvivalGameTests.time(fixture.level,24000);
                try(var repeated=measured(person)) { h.assertTrue(Math.abs(repeated.ground-rate)<rate*1e-12,"翌GAME日の再観測も背景値は同じ"); }
                BlockContamination.set(fixture.level,soil,SoilContamination.CLEAN);
                try(var cleaned=measured(person)) { h.assertTrue(cleaned.ground<rate/100,"置換後は現在の正本の連続値を読む"); }
                person.player.setItemInHand(IntakeGameTests.HAND,new ItemStack(Items.DIRT));
                try(var unknown=measured(person)) { h.assertTrue(!unknown.known,"段階だけの土に総Bqを捏造しない"); }
            }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126",timeoutTicks=400)
    public static void localItemShieldCursorAndUnknownNuclide(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            flatFloor(fixture.level,fixture.pos);
            try(var person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"gamma126")) {
                var stack=new ItemStack(Items.STICK,2);stack.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("ashen_frontier:cs137",1e6,0));
                stack.set(FoodProperties.ACTIVITY_UNCERTAINTY,.1);
                person.player.containerMenu.setCarried(stack);
                try(var own=measured(person)) {
                    double expected=9.27e-14/3600*2e6/.25;
                    h.assertTrue(own.known&&own.rates.size()==1&&Math.abs(own.rates.getFirst().lower()-expected)<expected*1e-12,"カーソルの実個数を重複せず携帯源へ");
                }
                person.player.containerMenu.setCarried(ItemStack.EMPTY);
                var source=person.player.getEyePosition().add(2,0,0);
                var entity=new ItemEntity(fixture.level,source.x,source.y,source.z,stack);fixture.level.addFreshEntity(entity);
                double before;
                try(var open=measured(person)) { before=open.rates.getFirst().lower();h.assertTrue(open.known,"地上ItemEntityの観測"); }
                var shield=BlockPos.containing(person.player.getEyePosition().add(1,0,0));fixture.level.setBlock(shield,Blocks.STONE.defaultBlockState(),3);
                try(var protectedPoint=measured(person)) {
                    double after=protectedPoint.rates.getFirst().lower();h.assertTrue(Math.abs(after-before*.01)<before*1e-12,"石質の実長と透過率の下限");
                }
                stack.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("test:unregistered",1e6,0));entity.setItem(stack);
                try(var unknown=measured(person)) { h.assertTrue(!unknown.known&&unknown.rates.isEmpty(),"未登録核種は未評価"); }
                entity.discard();
            }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126",timeoutTicks=400)
    public static void sourceEventsCloseOldObservationsWithoutInventoryPolling(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            flatFloor(fixture.level,fixture.pos);
            try(var person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"sources126")) {
                var stack=new ItemStack(Items.STICK,2);stack.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("ashen_frontier:cs137",1e6,0));
                person.player.setItemInHand(IntakeGameTests.HAND,stack);person.player.inventoryMenu.broadcastChanges();
                var one=measured(person);double first=one.rates.getFirst().lower();
                SurvivalGameTests.time(fixture.level,20);
                person.player.containerMenu.setCarried(person.player.getItemInHand(IntakeGameTests.HAND));person.player.setItemInHand(IntakeGameTests.HAND,ItemStack.EMPTY);
                person.player.inventoryMenu.broadcastChanges();
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(person.player));
                h.assertTrue(one.validUntil()==20,"全量をカーソルへ移したtickで旧観測を閉じる");
                var two=measured(person);double second=two.rates.getFirst().lower();
                double lambda=Math.log(2)/(30.05*365.25*86400)*72;
                var dose=new Exposure.State();Exposure.radiation(dose,List.of(new Exposure.Source(first,first,lambda)),1,72,true);
                Exposure.radiation(dose,List.of(new Exposure.Source(second,second,lambda)),1,72,true);
                double expected=9.27e-14/3600*2e6/.25*-Math.expm1(-lambda*2)/lambda;
                h.assertTrue(Math.abs(dose.externalLower-expected)<1e-20,"カーソル移動の前後で総Bq・線量を保存");
                SurvivalGameTests.time(fixture.level,40);
                person.player.setItemInHand(IntakeGameTests.HAND,person.player.containerMenu.getCarried().split(1));person.player.inventoryMenu.broadcastChanges();
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(person.player));
                h.assertTrue(two.validUntil()==40,"一部の移動でも旧区間を閉じる");
                try(var partial=measured(person)) {
                    double total=partial.rates.stream().mapToDouble(RadiationField.Rate::lower).sum();
                    h.assertTrue(partial.rates.size()==2&&Math.abs(total-first*Math.exp(-lambda*2))<first*1e-12,"在庫とカーソルの一部を重複せず合算");
                }
                one.close();two.close();person.player.setItemInHand(IntakeGameTests.HAND,ItemStack.EMPTY);person.player.containerMenu.setCarried(ItemStack.EMPTY);
                person.player.inventoryMenu.broadcastChanges();net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(person.player));
                var origin=person.player.getEyePosition();
                for(int x=1;x<=4;x++)fixture.level.setBlock(BlockPos.containing(origin.add(x,0,0)),Blocks.AIR.defaultBlockState(),3);
                var radioactive=new ItemStack(Items.STICK);
                radioactive.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("ashen_frontier:cs137",1e6,40));
                var item=new ItemEntity(fixture.level,origin.x+2,origin.y,origin.z,radioactive);fixture.level.addFreshEntity(item);
                var beforeMove=measured(person);double before=beforeMove.rates.getFirst().lower();
                SurvivalGameTests.time(fixture.level,60);item.setPos(origin.add(4,0,0));
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.EntityTickEvent.Post(item));
                h.assertTrue(beforeMove.validUntil()==60,"地上線源の移動は旧幾何の境界");
                var moved=measured(person);
                h.assertTrue(Math.abs(moved.rates.getFirst().lower()-before*Math.exp(-lambda)/4)<before*1e-12,"移動後の実距離と減衰を評価");
                SurvivalGameTests.time(fixture.level,80);item.discard();h.assertTrue(moved.validUntil()==80,"実際の消滅通知で線源を閉じる");
                beforeMove.close();moved.close();
                try(var receiver=measured(person)) {
                    SurvivalGameTests.time(fixture.level,100);person.player.setPos(person.player.position().add(.125,0,0));
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(person.player));
                    h.assertTrue(receiver.validUntil()==100,"同じブロック内の本人の移動も旧射線の境界");
                }
            }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126",timeoutTicks=400)
    public static void corruptedExposureUsesActualAttachmentRestore(GameTestHelper h) {
        var level=h.getLevel();var clock=new Atmosphere(level.getGameTime());SurvivalRuntime.INPUTS.put(level,clock);
        try {
            for(int damage=0;damage<8;damage++)try(var person=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"bad126_"+damage)) {
                var tag=new CompoundTag();person.player.saveWithoutId(tag);person.logout();
                var saved=tag.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).getCompound(AshenFrontierSurvival.id("physiology").toString());
                switch(damage) {
                    case 0 -> saved.remove("exposure");
                    case 1 -> { var wrong=new ListTag();wrong.add(LongTag.valueOf(1));saved.getCompound("exposure").put("loads",wrong); }
                    case 2 -> saved.getCompound("exposure").putLong("notification_tick",-2);
                    case 3 -> saved.putInt("format",2);
                    case 4 -> saved.getCompound("exposure").remove("totals");
                    case 7 -> {
                        long from=saved.getLong("observed"),to=from+1;
                        var row=new CompoundTag();row.putLong("lease",saved.getLong("current_lease"));
                        row.putLong("from",from);row.putLong("to",to);row.putLong("generation",0);
                        row.putInt("kind",PhysiologyData.Kind.SPAN.ordinal());row.putInt("activity",Activity.REST.ordinal());
                        for(String key:new String[]{"a","b","c","d"})row.putDouble(key,0);
                        for(String key:new String[]{"submerged","vc","vf","effects"})row.putBoolean(key,false);
                        var frames=new ListTag();frames.add(row);saved.put("frames",frames);saved.putLong("observed",to);
                        var point=new CompoundTag();point.putLong("tick",from);point.putLong("until",to);
                        point.putDouble("ground",0);point.putBoolean("known",true);point.put("rates",new ListTag());row.put("radiation",point);
                        PhysiologyData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,saved).getOrThrow();
                        point.putLong("until",to+1);
                    }
                    case 5,6 -> {var wrong=new ListTag();wrong.add(LongTag.valueOf(1));saved.getCompound("exposure").put(damage==5?"nuclear_intakes":"unidentified_oral_kg",wrong); }
                }
                var original=saved.copy();person.player.removeData(AshenFrontierSurvival.DATA);person.player.load(tag);person.login();
                h.assertTrue(person.player.getData(AshenFrontierSurvival.DATA).blocked()&&!AshenFrontierSurvival.ACTIVE.containsKey(person.player),"破損や旧試験形式を新規状態へ戻さない");
                h.assertTrue(person.player.serializeAttachments(level.registryAccess()).get(AshenFrontierSurvival.id("physiology").toString()).equals(original),"失敗原文を実保存経路で保全");
            }
        } finally { SurvivalRuntime.INPUTS.remove(level); }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126",timeoutTicks=400)
    public static void delayedGasNotificationOfflineAndDisabledFractionsDoNotBackCharge(GameTestHelper h) throws Exception {
        boolean effects=AshenFrontierSurvival.EFFECTS.get();
        AshenFrontierSurvival.EFFECTS.set(true);
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            var input=new Atmosphere(0);input.set(0,.0012,.000420,21200);SurvivalRuntime.INPUTS.put(fixture.level,input);
            try(var a=new SurvivalGameTests.Person(fixture.level,fixture.pos,"grace126");
                var b=new SurvivalGameTests.Person(fixture.level,fixture.pos.offset(1,0,0),"private126")) {
                SurvivalGameTests.drain(h,a.runtime);SurvivalGameTests.drain(h,b.runtime);b.packets.clear();
                input.pending=true;SurvivalGameTests.time(fixture.level,1200);a.runtime.work(System.nanoTime()+10_000_000,64);
                h.assertTrue(a.runtime.data.cursor==0,"入力の保留を現在時刻へ飛ばさない");input.pending=false;SurvivalGameTests.drain(h,a.runtime);
                var state=a.runtime.data.exposure;
                h.assertTrue(state.stages[0]==3&&state.notificationTick==1200&&state.grace==30&&state.damage==0,"遅れて評価した危険は実際の通知から30秒の猶予");
                restore(a);a.logout();SurvivalGameTests.time(fixture.level,2400);a.login();SurvivalGameTests.drain(h,a.runtime);state=a.runtime.data.exposure;
                h.assertTrue(state.grace==30&&state.notificationTick==1200,"保存・再ログイン・offlineで猶予を消化も再配布もしない");
                for(int second=1;second<=30;second++){SurvivalGameTests.time(fixture.level,2400+second*20L);SurvivalGameTests.drain(h,a.runtime);}
                h.assertTrue(state.grace==0&&state.damage==0&&a.player.getHealth()==20,"通知後のオンライン30秒より前に追徴しない");
                state.damage=.49;AshenFrontierSurvival.EFFECTS.set(false);a.runtime.work(System.nanoTime()+10_000_000,64);
                h.assertTrue(state.damage==0&&!state.enabled,"OFFは損傷の端数と危険通知の状態も清算");
                AshenFrontierSurvival.EFFECTS.set(true);a.runtime.capture();SurvivalGameTests.time(fixture.level,3020);SurvivalGameTests.drain(h,a.runtime);
                h.assertTrue(state.grace==30&&state.notificationTick==3020,"ONへ戻った危険には新しい猶予");
                for(int second=1;second<=30;second++){SurvivalGameTests.time(fixture.level,3020+second*20L);SurvivalGameTests.drain(h,a.runtime);}
                SurvivalGameTests.time(fixture.level,3624);SurvivalGameTests.drain(h,a.runtime);
                h.assertTrue(Math.abs(state.damage-.01)<1e-10&&a.player.getHealth()==20,"ONの0.01HPへOFFの0.49HPを合算しない");
                input.set(3624,0,.000420,21200);double before=state.damage;
                SurvivalGameTests.time(fixture.level,4024);SurvivalGameTests.drain(h,a.runtime);a.runtime.work(System.nanoTime()+10_000_000,64);
                h.assertTrue(state.load[0]>1&&state.damage==before,"原因除去は残る主観症状と区別し、新しい損傷を止める");
                h.assertTrue(b.packets.isEmpty()&&a.packets.stream().anyMatch(p->p.stage(13)>=2),"曝露の主観表示は本人だけへ");
                h.assertTrue(java.util.Arrays.stream(PhysiologyUpdate.class.getRecordComponents()).allMatch(c->c.getType()==int.class||c.getType()==boolean.class),"未測定のppm・Svの数値をパケットに含めない");
            } finally { SurvivalRuntime.INPUTS.remove(fixture.level); }
        } finally { AshenFrontierSurvival.EFFECTS.set(effects); }
        h.succeed();
    }
    private static EnvironmentProvider.Exposure readyExposure(EnvironmentConnectionGameTests.Fixture fixture,BlockPos position,long tick) {
        for(int n=0;n<20000;n++) {
            var result=EnvironmentProvider.get(fixture.level).exposure(position,tick,tick);
            if(result.isPresent())return result.get();
            EnvironmentProvider.get(fixture.level).work(Long.MAX_VALUE,8192);RoomAir.get(fixture.level).work();
        }
        throw new IllegalStateException("瞬時の実環境が有限の仕事で応答しません");
    }
    static void settle(GameTestHelper h,EnvironmentConnectionGameTests.Fixture fixture,List<SurvivalGameTests.Person> people,long tick) {
        settle(h,fixture,people,tick,null);
    }
    static void settle(GameTestHelper h,EnvironmentConnectionGameTests.Fixture fixture,List<SurvivalGameTests.Person> people,long tick,double[] oracle) {
        SurvivalGameTests.time(fixture.level,tick);
        for(int n=0;n<20000;n++) {
            EnvironmentProvider.get(fixture.level).work(Long.MAX_VALUE,8192);RoomAir.get(fixture.level).work();
            boolean completed=true;
            for(int index=0;index<people.size();index++) {
                var person=people.get(index);person.runtime.capture();long before=person.runtime.data.cursor;
                if(oracle!=null&&before<tick) {
                    var frame=person.runtime.data.frames.getFirst();var lease=person.runtime.data.leases.get(frame.lease);
                    var request=EnvironmentProvider.get(fixture.level).exposure(lease.pos,before,Math.min(tick,(before/20+1)*20));
                    var air=RoomAir.get(fixture.level).sample(lease.pos.above(),before);
                    if(request.isEmpty()||!air.confirmed()) {person.runtime.work(System.nanoTime()+10_000_000,0);completed=false;continue;}
                    long end=request.get().sample().endTick();double fraction=air.state().orElseThrow().co();
                    double expected=Math.max(0,fraction-5e-6)/200e-6;
                    expected+=(oracle[index]-expected)*Math.exp(-(end-before)/20.0/120);
                    person.runtime.work(System.nanoTime()+10_000_000,1);
                    if(person.runtime.data.cursor>before) {
                        h.assertTrue(person.runtime.data.cursor==end,"実際に評価した末尾の独立照合");oracle[index]=expected;
                        h.assertTrue(Math.abs(person.runtime.data.exposure.load[0]-expected)<1e-12,"C06の実COから独立計算した症状負荷");
                    }
                } else person.runtime.work(System.nanoTime()+10_000_000,64);
                completed&=person.runtime.data.cursor==tick&&person.runtime.data.frames.isEmpty();
            }
            if(completed)return;
        }
        h.fail("有限の実環境・生理仕事が完了しません tick="+tick);
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126",timeoutTicks=400)
    public static void actualFourPeopleRespirationAndHearthVentilation(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            flatFloor(fixture.level,fixture.pos);
            var cap=fixture.pos.offset(1,3,1);fixture.level.setBlock(cap,Blocks.STONE.defaultBlockState(),3);
            var rooms=RoomAir.get(fixture.level);rooms.changed(cap);fixture.ready(h,0,0);
            var people=new java.util.ArrayList<SurvivalGameTests.Person>();
            try {
                for(int n=0;n<4;n++)people.add(new SurvivalGameTests.Person(fixture.level,fixture.pos.offset(n%2,0,n/2),"breathing126_"+n));
                settle(h,fixture,people,0);
                double before=rooms.sample(fixture.pos.above(),0).state().orElseThrow().co2();
                settle(h,fixture,people,20);
                fixture.ready(h,20,20);
                double after=rooms.sample(fixture.pos.above(),20).state().orElseThrow().co2();
                double expected=4*104.76/20422.5224/22.414*.85/(27*101325/(8.314462618*293.15));
                h.assertTrue(Math.abs(after-before-expected)<1e-11,"4人の実代謝からC06へ一度だけCO2を通知");
                for(var person:people)h.assertTrue(person.runtime.data.breathSerials.get("minecraft:overworld")==1,"dimension別の呼吸連番");
                settle(h,fixture,people,20);fixture.ready(h,20,20);
                h.assertTrue(Math.abs(rooms.sample(fixture.pos.above(),20).state().orElseThrow().co2()-after)<1e-13,"同tickの再操作で呼吸を再投入しない");
                fixture.level.setBlock(cap,Blocks.AIR.defaultBlockState(),3);rooms.changed(cap);
                var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(io.github.rs0325.ashenfrontier.core.AshenFrontierCore.id("hearth"));
                fixture.level.setBlock(fixture.pos,block.defaultBlockState(),3);rooms.changed(fixture.pos);
                var actor=fixture.level.getBlockEntity(fixture.pos);actor.onLoad();
                var placed=actor.getClass().getDeclaredMethod("placed");placed.setAccessible(true);placed.invoke(actor);
                var tag=actor.saveWithFullMetadata(fixture.level.registryAccess());tag.putDouble("temperature_k",295.15);
                tag.putDouble("fuel_j",16e6);tag.putString("fuel_profile","ashen_frontier:dry_wood");tag.putDouble("heating_value",16e6);tag.putLong("last_tick",20);
                actor.loadWithComponents(tag,fixture.level.registryAccess());
                var lit=actor.getClass().getDeclaredMethod("setLit",boolean.class);lit.setAccessible(true);lit.invoke(actor,true);
                fixture.level.addNewPlayer(people.getFirst().player);fixture.ready(h,20,20);
                people.getFirst().player.setItemInHand(IntakeGameTests.HAND,ItemStack.EMPTY);
                actor.getClass().getMethod("interact",net.minecraft.server.level.ServerPlayer.class,net.minecraft.world.InteractionHand.class,net.minecraft.core.Direction.class)
                        .invoke(actor,people.getFirst().player,IntakeGameTests.HAND,net.minecraft.core.Direction.UP);
                var work=actor.getClass().getMethod("work");
                double[] oracle=new double[4];
                for(int second=2;second<=82;second++) {
                    long tick=second*20L;SurvivalGameTests.time(fixture.level,tick);
                    for(int attempt=0;attempt<1000;attempt++) {
                        work.invoke(actor);EnvironmentProvider.get(fixture.level).work(Long.MAX_VALUE,8192);rooms.work();
                        var saved=actor.saveWithFullMetadata(fixture.level.registryAccess());
                        if(saved.getLong("last_tick")==tick)break;
                        h.assertTrue(attempt<999,"実炉の有限仕事が完了");
                    }
                    settle(h,fixture,people,tick,oracle);
                }
                h.assertTrue(java.util.Arrays.stream(oracle).anyMatch(value->value>0),"換気フラグだけでゼロとせず実炉の濃度から負荷を評価");
                com.mojang.logging.LogUtils.getLogger().info("#126 hearth actual_CO={} flow_m3_s={} score={} remaining_fuel_J={}",
                        rooms.sample(fixture.pos.above(),1640).state().orElseThrow().co(),rooms.sample(fixture.pos.above(),1640).flowM3S(),oracle[0],
                        actor.saveWithFullMetadata(fixture.level.registryAccess()).getDouble("fuel_j"));
                double initial=rooms.sample(fixture.pos.above(),1640).state().orElseThrow().co();
                lit.invoke(actor,false);
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)if(Math.abs(x)==2||Math.abs(z)==2)
                    for(int y=0;y<=2;y++){var pos=fixture.pos.offset(x,y,z);fixture.level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);rooms.changed(pos);}
                fixture.ready(h,1640,1640);
                for(int second=83;second<=102;second++)settle(h,fixture,people,second*20L);
                fixture.ready(h,2040,2040);
                h.assertTrue(rooms.sample(fixture.pos.above(),2040).state().orElseThrow().co()<initial,"熱源停止と換気で実COが減少");
            } finally { people.forEach(SurvivalGameTests.Person::close); }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="exposure126_real_use",timeoutTicks=1200)
    public static void thirtyTwoTickFoodAndTwoPartialDrinksKeepKgBqAndSvAtomic(GameTestHelper h) throws Exception {
        var scenario=new RealIntake(h);
        h.onEachTick(()->{
            if(scenario.done)return;
            try { scenario.step(h); }
            catch(Exception|Error failure) {
                try{scenario.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
                if(failure instanceof Error error)throw error;if(failure instanceof RuntimeException runtime)throw runtime;
                throw new RuntimeException(failure);
            }
        });
    }
    private static final class RealIntake implements AutoCloseable {
        final EnvironmentConnectionGameTests.Fixture fixture;
        final SurvivalGameTests.Person person;
        final Atmosphere input;
        long now,start;int phase;boolean done;
        double lower,upper,expectedLower,expectedUpper;
        long activityTick;
        RealIntake(GameTestHelper h) throws Exception {
            fixture=new EnvironmentConnectionGameTests.Fixture(h);fixture.ready(h,0,0);input=new Atmosphere(0);
            SurvivalRuntime.INPUTS.put(fixture.level,input);person=new SurvivalGameTests.Person(fixture.level,fixture.pos,"real_intake126");
            person.player.setNoGravity(true);person.runtime.data.body.energy=1e6;person.runtime.data.body.water=40;begin(h);
        }
        void begin(GameTestHelper h) throws Exception {
            start=now;ItemStack stack;
            if(phase<2) {
                stack=IntakeGameTests.food(phase==0?"canned_food":"preserved_rations",1,now);
                stack.set(ItemProperties.CONTAMINATION,new ItemProperties.Contamination(java.util.Map.of("lead",phase==0?1e-7:5e-8,"pcb",1e-7)));
                lower=phase==0?8:10;upper=lower+.1;activityTick=now;
            } else if(phase==2) {
                stack=IntakeGameTests.water(1);stack.set(ItemProperties.CONTAMINATION,new ItemProperties.Contamination(java.util.Map.of("lead",5e-9,"pcb",2e-10)));
                lower=1;upper=1.1;activityTick=now;
                var get=ClimateClock.class.getDeclaredMethod("get",ServerLevel.class);get.setAccessible(true);
                var change=ClimateClock.class.getDeclaredMethod("changeDayLength",long.class,long.class);change.setAccessible(true);
                change.invoke(get.invoke(null,fixture.level),now,12000L);input.clock.change(now,12000);
            } else stack=person.player.getItemInHand(IntakeGameTests.HAND);
            if(phase<3) {
                stack.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("ashen_frontier:cs137",lower,activityTick));
                stack.set(FoodProperties.ACTIVITY_UNCERTAINTY,upper-lower);
            }
            person.player.setItemInHand(IntakeGameTests.HAND,stack);
            h.assertTrue(stack.getItem().getUseDuration(stack,person.player)==32,"実際の32tick使用");
            h.assertTrue(stack.getItem().use(fixture.level,person.player,IntakeGameTests.HAND).getResult().consumesAction(),"通常の飲食を開始");
        }
        void step(GameTestHelper h) throws Exception {
            if(++now>1000){close();h.fail("実摂取と履歴の確定が有限時間で終わりません");return;}
            SurvivalGameTests.time(fixture.level,now);person.player.doTick();
            FoodStorageService.player(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(person.player));
            RoomAir.get(fixture.level).work();EnvironmentProvider.get(fixture.level).work(System.nanoTime()+10_000_000,8192);
            FoodStorageData.get(fixture.level).work();IntakeService.retry();
            var held=person.player.getItemInHand(IntakeGameTests.HAND);
            boolean consumed=phase<2?held.isEmpty():held.get(RelicProperties.CONTENT_MASS)<(phase==2?1:.75);
            if(now-start<32)h.assertTrue(!consumed,"32tick以前には量も受領印も増やさない");
            if(consumed) {
                double portion=phase<2?1:phase==2?.25:1.0/3;
                double elapsed=0;
                for(var interval:input.clock.intervals(activityTick,start+32))elapsed+=(interval.endTick()-interval.startTick())*86400.0/interval.ticksPerDay();
                double decay=Math.pow(2,-elapsed/(30.05*365.25*86400));
                expectedLower+=lower*portion*decay;expectedUpper+=upper*portion*decay;
                var frame=person.runtime.data.frames.stream().filter(f->f.kind==PhysiologyData.Kind.INTAKE).findFirst().orElseThrow();
                h.assertTrue(frame.intakeExposure!=null&&frame.intakeExposure.tick()==start+32,"実kg/Bqを同じ完了時刻のFrameへ");
            }
            person.runtime.work(System.nanoTime()+10_000_000,64);
            if(!consumed)return;
            SurvivalGameTests.drain(h,person.runtime);
            h.assertTrue(!IntakeGameTests.action(person,FoodUse.Action.FINISH),"完了の再送で量を増やさない");
            var dose=person.runtime.data.exposure.nuclearIntakes.get("ashen_frontier:cs137");
            h.assertTrue(Math.abs(dose.oralLower()-expectedLower)<1e-12&&Math.abs(dose.oralUpper()-expectedUpper)<1e-12,"独立した実摂取Bqの上下端");
            h.assertTrue(Math.abs(person.runtime.data.exposure.committedLower-expectedLower*1.3e-8)<1e-19
                    &&Math.abs(person.runtime.data.exposure.committedUpper-expectedUpper*1.3e-8)<1e-19,"E50を一回だけ加算し累積を減衰しない");
            if(phase==2) { lower=.7;upper=.825;h.assertTrue(Math.abs(held.get(ItemProperties.RADIOACTIVITY).becquerels()-.7)<1e-12,"残った水の切捨てBq"); }
            if(++phase<4){begin(h);return;}
            var state=person.runtime.data.exposure;
            h.assertTrue(Math.abs(state.oralMassKg-.95)<1e-12&&Math.abs(state.oralLead-3.5e-8)<1e-20&&Math.abs(state.oralPcb-4.51e-8)<1e-20,"食品と部分飲水の実質量・物質量を保存");
            restore(person);h.assertTrue(Math.abs(person.runtime.data.exposure.committedUpper-expectedUpper*1.3e-8)<1e-19,"実Attachmentの再ログインでも線量と受領印を保つ");
            close();h.succeed();
        }
        @Override public void close() throws Exception {
            if(done)return;done=true;person.close();SurvivalRuntime.INPUTS.remove(fixture.level);fixture.close();
        }
    }
}
