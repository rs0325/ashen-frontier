package io.github.rs0325.ashenfrontier.survival;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@GameTestHolder(AshenFrontierSurvival.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurvivalGameTests {
    static final class Inputs implements SurvivalRuntime.Inputs {
        final DayLengthHistory clock;boolean waiting;double oxygen=21200;
        Inputs(long start) { clock=new DayLengthHistory(start,24000); }
        public Optional<DayLengthHistory> clock(ServerLevel level) { return Optional.of(clock); }
        public Optional<SurvivalRuntime.Observation> sample(ServerLevel level,BlockPos pos,long from,long to,boolean submerged) {
            if(waiting)return Optional.empty();
            var interval=clock.intervals(from,to).getFirst();
            return Optional.of(new SurvivalRuntime.Observation(from,interval.endTick(),interval.ticksPerDay(),
                    new Physiology.Air(295.15,.5,.1,0,101325,oxygen,0,true,submerged)));
        }
    }
    static final class Person implements AutoCloseable {
        final ServerPlayer player;final ArrayList<PhysiologyUpdate> packets=new ArrayList<>();
        SurvivalRuntime runtime;
        Person(ServerLevel level,BlockPos pos,String name) {
            player=new ServerPlayer(level.getServer(),level,new GameProfile(UUID.randomUUID(),name),net.minecraft.server.level.ClientInformation.createDefault());
            new ServerGamePacketListenerImpl(level.getServer(),new Connection(PacketFlow.SERVERBOUND),player,
                    CommonListenerCookie.createInitial(player.getGameProfile(),false)) {
                @Override public void send(Packet<?> packet) { record(packet); }
                @Override public void send(Packet<?> packet,PacketSendListener listener) { record(packet); }
                @Override public boolean hasChannel(ResourceLocation id) { return id.equals(PhysiologyUpdate.TYPE.id()); }
            };
            player.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);login();
        }
        void record(Packet<?> packet) {
            if(packet instanceof ClientboundCustomPayloadPacket p&&p.payload() instanceof PhysiologyUpdate value)packets.add(value);
        }
        void login() {
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
            runtime=AshenFrontierSurvival.ACTIVE.get(player);
        }
        void logout() { NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player)); }
        public void close() { logout();player.discard(); }
    }
    static void drain(GameTestHelper h,SurvivalRuntime runtime) {
        for(int n=0;n<10000;n++) {
            runtime.work(System.nanoTime()+10_000_000,32);
            if(runtime.data.frames.isEmpty())return;
        }
        h.fail("有限の生理仕事が完了しません cursor="+runtime.data.cursor);
    }
    static void time(ServerLevel level,long tick) { ((net.minecraft.world.level.storage.PrimaryLevelData)level.getLevelData()).setGameTime(tick); }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,timeoutTicks=400,batch="survival_fixture")
    public static void generatedPendingSaveLogoutAndDayLength(GameTestHelper h) {
        var level=h.getLevel();long original=level.getGameTime();var inputs=new Inputs(original);
        SurvivalRuntime.INPUTS.put(level,inputs);
        int saves=0,same=0,changes=0,pending=0,feeds=0,logouts=0;
        double expectedEnergy=20e6,expectedWater=42,expectedProtein=.12,expectedFat=.08;
        try(var person=new Person(level,h.absolutePos(new BlockPos(0,2,0)),"generated124")) {
            var random=new Random(12420261126L);long now=original;long day=24000;
            for(int n=0;n<64;n++) {
                var runtime=person.runtime;long elapsed=1+random.nextInt(19);
                time(level,now+elapsed);runtime.capture();inputs.waiting=true;
                var before=runtime.data.body.values();runtime.work(System.nanoTime()+100_000_000,64);
                h.assertTrue(java.util.Arrays.equals(before,runtime.data.body.values()),"未観測は安全値で進めない");
                h.assertTrue(!runtime.data.frames.isEmpty(),"保留の実区間がある");
                var saved=runtime.data.save();var restored=PhysiologyData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,saved).getOrThrow();saves++;
                person.logout();person.player.setData(AshenFrontierSurvival.DATA,restored);person.login();runtime=person.runtime;
                inputs.waiting=false;drain(h,runtime);pending++;
                double game=elapsed*86400.0/day;
                expectedEnergy-=10e6*game/86400;expectedWater-=3*game/86400;
                expectedProtein-=.06*game/86400;expectedFat-=.04*game/86400;
                h.assertTrue(Math.abs(runtime.data.body.energy-expectedEnergy)<1e-6,"独立のGAME食料収支");
                h.assertTrue(Math.abs(runtime.data.body.water-expectedWater)<1e-6,"独立のGAME水収支");
                now+=elapsed;
                var stable=runtime.data.body.values();runtime.work(System.nanoTime()+10_000_000,32);
                h.assertTrue(java.util.Arrays.equals(stable,runtime.data.body.values()),"同時刻を二重適用しない");same++;
                SurvivalIntake.accepted(person.player,.001,100,.00001,.00001,false,false);drain(h,runtime);feeds++;
                expectedWater=Math.min(42,expectedWater+.001);expectedEnergy=Math.min(20e6,expectedEnergy+100);
                expectedProtein=Math.min(.18,expectedProtein+.00001);expectedFat=Math.min(.12,expectedFat+.00001);
                h.assertTrue(Math.abs(runtime.data.body.protein-expectedProtein)<1e-10
                        &&Math.abs(runtime.data.body.fat-expectedFat)<1e-10,"kgの補給も独立照合");
                saved=runtime.data.save();PhysiologyData.load(saved);saves++;
                day=day==24000?12000:24000;inputs.clock.change(now,day);changes++;
                person.logout();saved=runtime.data.save();
                time(level,now+20);now+=20;
                person.player.setData(AshenFrontierSurvival.DATA,PhysiologyData.load(saved));person.login();logouts++;
                drain(h,person.runtime);
                h.assertTrue(Math.abs(person.runtime.data.body.energy-expectedEnergy)<1e-6,"オフラインで悪化/回復しない");
            }
            h.assertTrue(saves>=32&&same>=64&&changes>=32&&pending>=32&&feeds>=32&&logouts>=32,"生成検査の実効下限");
            com.mojang.logging.LogUtils.getLogger().info("M3-124 generated saves={} same={} day_changes={} pending_resumes={} feed_saves={} logouts={}",
                    saves,same,changes,pending,feeds,logouts);
        } finally { SurvivalRuntime.INPUTS.remove(level);time(level,original); }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,timeoutTicks=400,batch="survival_fixture")
    public static void actualActivityDamageSleepAndPrivateDisabledSync(GameTestHelper h) {
        var level=h.getLevel();long original=level.getGameTime();long oldDay=level.getDayTime();
        boolean daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT),effects=AshenFrontierSurvival.EFFECTS.get();
        var inputs=new Inputs(original);SurvivalRuntime.INPUTS.put(level,inputs);
        try(var a=new Person(level,h.absolutePos(new BlockPos(0,2,0)),"sleeper124");
            var b=new Person(level,h.absolutePos(new BlockPos(2,2,0)),"awake124")) {
            drain(h,a.runtime);drain(h,b.runtime);b.packets.clear();
            a.player.setSprinting(true);NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(a.player));
            h.assertTrue(a.runtime.data.activity==Activity.RUN,"活動の本番通知");
            a.player.setSprinting(false);a.player.setInvulnerable(false);a.player.hurt(a.player.damageSources().fellOutOfWorld(),2);drain(h,a.runtime);
            h.assertTrue(a.runtime.data.body.injury==10,"実被弾後の簡略負傷 I="+a.runtime.data.body.injury);
            NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.LivingEvent.LivingJumpEvent(a.player));drain(h,a.runtime);
            h.assertTrue(a.runtime.data.body.stamina==90,"跳躍イベント");
            h.assertTrue(!a.packets.isEmpty()&&b.packets.isEmpty(),"本人だけに同期する");
            inputs.oxygen=15000;time(level,original+1);a.runtime.work(System.nanoTime()+100_000_000,100);
            a.runtime.work(System.nanoTime()+100_000_000,100);
            var denied=NeoForge.EVENT_BUS.post(new CanPlayerSleepEvent(a.player,a.player.blockPosition(),null));
            h.assertTrue(denied.getProblem()!=null,"低酸素では就寝できない");
            inputs.oxygen=21200;level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());
            level.setDayTime(6000);time(level,original+2);drain(h,a.runtime);drain(h,a.runtime);
            var bed=a.player.blockPosition();
            level.setBlock(bed,Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.HEAD),3);
            level.setBlock(bed.north(),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT),3);
            var monster=net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
            monster.setPos(bed.getX()+2,bed.getY(),bed.getZ());level.addFreshEntity(monster);
            var unsafe=NeoForge.EVENT_BUS.post(new CanPlayerSleepEvent(a.player,bed,Player.BedSleepingProblem.NOT_POSSIBLE_NOW));
            h.assertTrue(unsafe.getProblem()==Player.BedSleepingProblem.NOT_SAFE,"固定昼間でもバニラの怪物拒否を維持");
            monster.discard();
            a.runtime.data.body.sleepDebt=40000;
            h.assertTrue(a.player.startSleepInBed(bed).right().isPresent(),"実寝床の就寝入口");
            NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(a.player));drain(h,a.runtime);
            int sleepTicks=0;
            for(int i=1;i<=101;i++) {
                time(level,original+2+i);a.player.doTick();NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(a.player));
                if(i<100)drain(h,a.runtime);
                else { inputs.waiting=true;a.runtime.work(System.nanoTime()+10_000_000,32); }
                sleepTicks++;
            }
            h.assertTrue(a.runtime.data.pendingSleep==1&&a.runtime.data.lastCredit==-1,"深睡眠の候補も入力待ちを追い越さない");
            var pendingSave=PhysiologyData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,a.runtime.data).getOrThrow();
            a.logout();a.player.stopSleeping();
            a.player.setData(AshenFrontierSurvival.DATA,PhysiologyData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,pendingSave).getOrThrow());
            a.login();inputs.waiting=false;drain(h,a.runtime);
            h.assertTrue(sleepTicks>=100,"本人の深睡眠まで実tickを進める");
            h.assertTrue(a.runtime.data.lastCredit==original+102&&a.runtime.data.consumedSleep==1,"本人の100tick深睡眠で一度だけ返済");
            h.assertTrue(a.runtime.data.body.sleepDebt==0,"B2返済の対象は睡眠負債");
            h.assertTrue(b.runtime.data.lastCredit==-1,"起きている他人へ返済しない");
            var tag=a.runtime.data.save();var restored=PhysiologyData.load(tag);
            h.assertTrue(!restored.credit(1,level.getGameTime(),999999,true),"復元後の重複返済を拒否");
            double fatigue=a.runtime.data.body.fatigue;a.runtime.data.body.sleepDebt=50000;a.runtime.data.sleepGeneration=2;
            h.assertTrue(!a.runtime.data.credit(2,level.getGameTime(),0,true),"12GAME時以内の新しい就寝も返済なし");
            h.assertTrue(a.runtime.data.body.fatigue==fatigue,"返済は疲労に触れない");
            a.player.stopSleeping();a.runtime.capture();
            a.runtime.data.body.mental=0;double wear=0;
            for(int n=0;n<4;n++)wear+=io.github.rs0325.ashenfrontier.core.ToolWearEvent.committed(a.player,1);
            h.assertTrue(wear==5&&a.runtime.data.body.wearFraction==0,"追加摩耗の端数を確定時だけ精算");
            a.runtime.data.body.mental=80;AshenFrontierSurvival.EFFECTS.set(false);
            a.runtime.data.body.damage=1;a.runtime.data.body.energy=1;float health=a.player.getHealth();
            drain(h,a.runtime);
            h.assertTrue(a.player.getHealth()==health&&a.runtime.data.body.damage==0,"OFF中は状態を保ち効果を適用しない");
            h.assertTrue(a.packets.stream().anyMatch(p->!p.enabled()),"OFFを本人へ明示");
            var bad=tag.copy();var wrong=new net.minecraft.nbt.ListTag();wrong.add(net.minecraft.nbt.LongTag.valueOf(1));bad.put("frames",wrong);
            boolean rejected=false;try{PhysiologyData.load(bad);}catch(RuntimeException expected){rejected=true;}
            h.assertTrue(rejected,"不正なNBTリストを空として復元しない");
        } finally {
            AshenFrontierSurvival.EFFECTS.set(effects);SurvivalRuntime.INPUTS.remove(level);time(level,original);
            level.setDayTime(oldDay);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());
        }
        h.succeed();
    }

}
