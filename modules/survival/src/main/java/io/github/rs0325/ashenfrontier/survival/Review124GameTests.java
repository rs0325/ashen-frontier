package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.core.environment.EnvironmentRetention;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierSurvival.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Review124GameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,timeoutTicks=400,batch="review_124")
    public static void brokenAttachmentDoesNotBecomeHealthy(GameTestHelper h) {
        try(var person=new SurvivalGameTests.Person(h.getLevel(),h.absolutePos(BlockPos.ZERO),"broken124")) {
            person.runtime.data.body.water=39;person.runtime.data.body.energy=7e6;
            person.runtime.data.sleepGeneration=3;person.runtime.data.consumedSleep=3;
            person.runtime.data.lastCredit=person.runtime.data.observed;
            var entity=new CompoundTag();person.player.saveWithoutId(entity);person.logout();
            var attachments=entity.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY);
            var bad=attachments.getCompound(AshenFrontierSurvival.id("physiology").toString());
            var wrong=new ListTag();wrong.add(LongTag.valueOf(1));bad.put("frames",wrong);
            var original=bad.copy();
            person.player.removeData(AshenFrontierSurvival.DATA);
            person.player.load(entity);person.login();
            var state=person.player.getData(AshenFrontierSurvival.DATA);
            h.assertTrue(state.body==null,"破損Attachmentに初期体水分/食料を与えない");
            h.assertTrue(person.packets.stream().anyMatch(PhysiologyUpdate::corrupted),"破損停止の理由を本人へ同期");
            h.assertTrue(!AshenFrontierSurvival.ACTIVE.containsKey(person.player),"破損状態を生理評価へ登録しない");
            var saved=person.player.serializeAttachments(h.getLevel().registryAccess());
            h.assertTrue(saved.get(AshenFrontierSurvival.id("physiology").toString()).equals(original),"原文の破損NBTを再保存して保全");
        }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,timeoutTicks=400,batch="review_124")
    public static void dimensionRetentionNumbersAreLocal(GameTestHelper h) {
        var a=h.getLevel();var b=a.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        var inputA=new SurvivalGameTests.Inputs(a.getGameTime());
        var inputB=new SurvivalGameTests.Inputs(b.getGameTime());
        SurvivalRuntime.INPUTS.put(a,inputA);SurvivalRuntime.INPUTS.put(b,inputB);
        var issued=new java.util.IdentityHashMap<ServerLevel,Long>();
        var released=new java.util.IdentityHashMap<ServerLevel,Integer>();
        java.util.function.Consumer<EnvironmentRetention> ledger=event->{
            if(event.level()!=a&&event.level()!=b)return;
            if(event.action()==EnvironmentRetention.Action.RETAIN) {
                long next=issued.getOrDefault(event.level(),0L)+1;
                if(event.generation()!=next)throw new IllegalArgumentException("dimensionごとの保持世代が連続していません");
                issued.put(event.level(),next);event.provide(true);
            } else if(event.action()==EnvironmentRetention.Action.RELEASE) {
                released.merge(event.level(),1,Integer::sum);event.provide(true);
            } else event.provide(true);
        };
        NeoForge.EVENT_BUS.addListener(ledger);
        try(var person=new SurvivalGameTests.Person(a,h.absolutePos(BlockPos.ZERO),"dimension124")) {
            SurvivalGameTests.drain(h,person.runtime);
            person.player.setServerLevel(b);person.runtime.capture();SurvivalGameTests.drain(h,person.runtime);
            person.player.setServerLevel(a);person.runtime.capture();SurvivalGameTests.drain(h,person.runtime);
            var saved=PhysiologyData.load(person.runtime.data.save());
            h.assertTrue(issued.get(a)==2&&issued.get(b)==1,"往復でAの2、Bの1を発行");
            h.assertTrue(released.getOrDefault(a,0)>0&&released.getOrDefault(b,0)>0,"旧dimensionの保持を解放");
            h.assertTrue(saved.leases.size()==person.runtime.data.leases.size(),"dimension別発行状態を保存復元");
            person.logout();person.player.setData(AshenFrontierSurvival.DATA,saved);person.login();
            person.player.setServerLevel(b);person.runtime.capture();SurvivalGameTests.drain(h,person.runtime);
            h.assertTrue(issued.get(a)==3&&issued.get(b)==2,"解放済みdimensionの発行番号も復元する");
        } finally {NeoForge.EVENT_BUS.unregister(ledger);SurvivalRuntime.INPUTS.remove(a);SurvivalRuntime.INPUTS.remove(b);}
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,timeoutTicks=400,batch="review_124")
    public static void ordinaryMovementPacketUsesWalkingMetabolism(GameTestHelper h) {
        var level=h.getLevel();var input=new SurvivalGameTests.Inputs(level.getGameTime());
        long original=level.getGameTime();SurvivalRuntime.INPUTS.put(level,input);
        try(var person=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"walker124")) {
            level.addNewPlayer(person.player);
            person.player.connection.resetPosition();
            person.player.setDeltaMovement(Vec3.ZERO);
            person.player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(person.player.getX()+.1,
                    person.player.getY(),person.player.getZ(),true));
            h.assertTrue(person.player.getKnownMovement().horizontalDistanceSqr()>0,"通常の移動パケットを受理");
            h.assertTrue(person.player.getDeltaMovement().horizontalDistanceSqr()==0,"物理速度を持たない歩行");
            person.runtime.capture();
            h.assertTrue(person.runtime.data.activity==Activity.WALK,"通常歩行はRESTではなく2 MET");
            person.runtime.capture();
            h.assertTrue(person.runtime.data.activity==Activity.WALK,"同tickの再操作でも歩行を消さない");
            SurvivalGameTests.time(level,original+1);person.runtime.capture();
            h.assertTrue(person.runtime.data.activity==Activity.REST,"位置が止まれば古いknownMovementを歩行へ流用しない");
        } finally {SurvivalRuntime.INPUTS.remove(level);SurvivalGameTests.time(level,original);}
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,timeoutTicks=400,batch="review_124")
    public static void disabledDamageFractionIsNotCarriedForward(GameTestHelper h) {
        var level=h.getLevel();var input=new SurvivalGameTests.Inputs(level.getGameTime());
        long original=level.getGameTime();
        boolean enabled=AshenFrontierSurvival.EFFECTS.get();SurvivalRuntime.INPUTS.put(level,input);
        try(var person=new SurvivalGameTests.Person(level,h.absolutePos(BlockPos.ZERO),"off124")) {
            AshenFrontierSurvival.EFFECTS.set(false);person.runtime.data.body.damage=.49;
            SurvivalGameTests.drain(h,person.runtime);
            h.assertTrue(person.runtime.data.body.damage==0,"OFF中は0.5未満も残さない");
            AshenFrontierSurvival.EFFECTS.set(true);person.runtime.data.body.damage=.01;
            SurvivalGameTests.drain(h,person.runtime);
            h.assertTrue(Math.abs(person.runtime.data.body.damage-.01)<1e-12,"ONの端数だけを持つ");
            AshenFrontierSurvival.EFFECTS.set(false);person.runtime.capture();
            person.runtime.data.body.water=37;input.waiting=true;
            SurvivalGameTests.time(level,original+20);person.runtime.capture();
            person.runtime.work(System.nanoTime()+100_000_000,32);
            var saved=person.runtime.data.save();person.logout();
            AshenFrontierSurvival.EFFECTS.set(true);
            person.player.setData(AshenFrontierSurvival.DATA,PhysiologyData.load(saved));person.login();
            input.waiting=false;SurvivalGameTests.drain(h,person.runtime);
            h.assertTrue(person.runtime.data.body.damage==0,"OFF区間の保存/遅延応答をONの損傷にしない");
            SurvivalGameTests.time(level,original+40);SurvivalGameTests.drain(h,person.runtime);
            h.assertTrue(Math.abs(person.runtime.data.body.damage-1.0/120)<1e-10,"ON後の1 REAL秒だけを計上");
        } finally {
            AshenFrontierSurvival.EFFECTS.set(enabled);SurvivalRuntime.INPUTS.remove(level);SurvivalGameTests.time(level,original);
        }
        h.succeed();
    }
}
