package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.climate.EnvironmentConnectionGameTests;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.world.BlockContamination;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Map;

@GameTestHolder(AshenFrontierSurvival.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Review126GameTests {
    private static RadiationField.Point finish(RadiationField.Point point) {
        for(int n=0;n<10000&&!point.done;n++)point.work(System.nanoTime()+1_000_000);
        if(!point.done)throw new IllegalStateException("局所線源の有限観測が完了しません");
        return point;
    }
    private static ItemStack source(long tick) {
        var stack=new ItemStack(Items.STICK);
        stack.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("ashen_frontier:cs137",1e6,tick));
        return stack;
    }
    private static CompoundTag payload(CompoundTag player) {
        return player.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).getCompound(AshenFrontierSurvival.id("physiology").toString());
    }
    private static void rejected(GameTestHelper h,SurvivalGameTests.Person person,CompoundTag saved) {
        var raw=payload(saved).copy();person.player.removeData(AshenFrontierSurvival.DATA);person.player.load(saved);
        h.assertTrue(person.player.getData(AshenFrontierSurvival.DATA).blocked(),"矛盾した実Attachmentを正常復元しない");
        person.login();
        h.assertTrue(!AshenFrontierSurvival.ACTIVE.containsKey(person.player),"破損保存の評価を開始しない");
        h.assertTrue(person.player.serializeAttachments(person.player.registryAccess()).get(AshenFrontierSurvival.id("physiology").toString()).equals(raw),"破損原文を保持する");
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review126",timeoutTicks=400)
    @SuppressWarnings("unchecked")
    public static void actualDeathRespawnReplacesMenuAndMovementWatch(GameTestHelper h) throws Exception {
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(0,2,0));
        SurvivalRuntime.INPUTS.put(level,new ExposureGameTests.Atmosphere(level.getGameTime()));
        try(var person=new SurvivalGameTests.Person(level,pos,"respawn126")) {
            var field=ExposureSources.class.getDeclaredField("PLAYERS");field.setAccessible(true);
            var watches=(Map<?,?>)field.get(null);int initial=watches.size();Object oldWatch=watches.get(person.player);
            var slots=oldWatch.getClass().getDeclaredField("slots");slots.setAccessible(true);
            var list=level.getServer().getPlayerList();ServerPlayer respawned=null;
            try {
                level.addNewPlayer(person.player);
                var players=net.minecraft.server.players.PlayerList.class.getDeclaredField("players");players.setAccessible(true);
                ((java.util.List<ServerPlayer>)players.get(list)).add(person.player);
                var byId=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");byId.setAccessible(true);
                ((Map<java.util.UUID,ServerPlayer>)byId.get(list)).put(person.player.getUUID(),person.player);
                person.player.setRespawnPosition(level.dimension(),pos,0,true,false);
                person.player.hurt(level.damageSources().genericKill(),Float.MAX_VALUE);
                h.assertTrue(person.player.isDeadOrDying(),"実際の死亡を経由する");
                respawned=list.respawn(person.player,false,Entity.RemovalReason.KILLED);
                respawned.connection.player=respawned;respawned.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
                h.assertTrue(respawned!=person.player&&!watches.containsKey(person.player)&&watches.containsKey(respawned)
                        &&watches.size()==initial&&((Map<?,?>)slots.get(oldWatch)).isEmpty(),"旧watchとmenuを除き、新しいプレイヤーに一つだけ登録する");
                var chest=new SimpleContainer(27);var menu=ChestMenu.threeRows(126,respawned.getInventory(),chest);
                respawned.containerMenu=menu;NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(respawned));
                chest.setItem(0,source(0));menu.broadcastChanges();
                try(var before=finish(new RadiationField.Point(respawned))) {
                    menu.quickMoveStack(respawned,0);menu.broadcastChanges();
                    h.assertTrue(before.validUntil()==level.getGameTime(),"リスポーン後のチェストから所持品への移送を検出");
                }
                try(var before=finish(new RadiationField.Point(respawned))) {
                    menu.setCarried(source(0));NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(respawned));
                    h.assertTrue(before.validUntil()==level.getGameTime(),"リスポーン後のカーソル変更を検出");
                }
                try(var before=finish(new RadiationField.Point(respawned))) {
                    var block=respawned.blockPosition();respawned.setPos(respawned.position().add(.125,0,0));
                    NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(respawned));
                    h.assertTrue(respawned.blockPosition().equals(block)&&before.validUntil()==level.getGameTime(),"同ブロック内の移動も新watchが検出");
                }
            } finally { if(respawned!=null)list.remove(respawned);else list.remove(person.player); }
            h.assertTrue(!watches.containsKey(person.player)&&(respawned==null||!watches.containsKey(respawned)),"終了後に新旧watchを残さない");
        } finally { SurvivalRuntime.INPUTS.remove(level); }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review126",timeoutTicks=400)
    public static void closedObservationDoesNotReuseGroundAcrossChangesOrDimensions(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h);
            var person=new SurvivalGameTests.Person(f.level,f.pos,"gap126")) {
            ExposureGameTests.flatFloor(f.level,f.pos);
            var soil=f.pos.offset(2,-1,0);var wall=f.pos.offset(1,0,0);
            for(int x=0;x<=2;x++)for(int y=0;y<=2;y++)f.level.setBlock(f.pos.offset(x,y,0),Blocks.AIR.defaultBlockState(),3);
            f.level.setBlock(soil,Blocks.DIRT.defaultBlockState(),3);double[][] results=new double[2][2];int comparisons=0;
            for(int mutation=0;mutation<2;mutation++)for(int order=0;order<2;order++) {
                long tick=20L*(++comparisons);SurvivalGameTests.time(f.level,tick);
                person.player.setItemInHand(IntakeGameTests.HAND,ItemStack.EMPTY);person.player.inventoryMenu.broadcastChanges();
                f.level.setBlock(wall,Blocks.AIR.defaultBlockState(),3);
                BlockContamination.set(f.level,soil,new SoilContamination(0,0,.05,6.5,100000,0));
                try(var old=finish(new RadiationField.Point(person.player))) {
                    double before=old.ground;
                    if(order==0){person.player.setItemInHand(IntakeGameTests.HAND,source(tick));person.player.inventoryMenu.broadcastChanges();}
                    if(mutation==0)BlockContamination.set(f.level,soil,new SoilContamination(0,0,.05,6.5,200000,0));
                    else f.level.setBlock(wall,Blocks.STONE.defaultBlockState(),3);
                    if(order==1){person.player.setItemInHand(IntakeGameTests.HAND,source(tick));person.player.inventoryMenu.broadcastChanges();}
                    try(var reused=finish(new RadiationField.Point(person.player,old));var fresh=finish(new RadiationField.Point(person.player))) {
                        h.assertTrue(reused.known&&fresh.known&&Math.abs(reused.ground-fresh.ground)<1e-22,"監視の空白期間の後は現在の床・遮蔽を再観測する");
                        h.assertTrue(mutation==0?reused.ground>before*1.9:reused.ground<before*.99,"実際に線量率が変わる配置で検査する");
                        results[mutation][order]=reused.ground;
                    }
                }
            }
            for(var result:results)h.assertTrue(Math.abs(result[0]-result[1])<1e-22,"線源と床・遮蔽の操作順で結果を変えない");
            h.assertTrue(comparisons==4,"床・遮蔽と前後順の実効4条件");
        }
        sameCoordinatesAcrossDimensions(h);
        h.succeed();
    }
    private static void sameCoordinatesAcrossDimensions(GameTestHelper h) throws Exception {
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(0,2,0)).atY(80);
        SurvivalRuntime.INPUTS.put(level,new ExposureGameTests.Atmosphere(level.getGameTime()));
        try(var person=new SurvivalGameTests.Person(level,pos,"dimension126")) {
            person.logout();ExposureGameTests.flatFloor(level,pos);
            var soil=pos.offset(2,-1,0);level.setBlock(soil,Blocks.DIRT.defaultBlockState(),3);
            BlockContamination.set(level,soil,new SoilContamination(0,0,.05,6.5,100000,0));
            for(int x=0;x<=2;x++)for(int y=0;y<=2;y++)level.setBlock(pos.offset(x,y,0),Blocks.AIR.defaultBlockState(),3);
            try(var old=finish(new RadiationField.Point(person.player))) {
                var target=level.getServer().getLevel(Level.NETHER);h.assertTrue(target!=null,"検査用の別次元がある");
                var position=person.player.position();ExposureGameTests.flatFloor(target,pos);
                for(int x=0;x<=2;x++)for(int y=0;y<=2;y++)target.setBlock(pos.offset(x,y,0),Blocks.AIR.defaultBlockState(),3);
                level.addNewPlayer(person.player);
                person.player.changeDimension(new DimensionTransition(target,position,Vec3.ZERO,0,0,DimensionTransition.DO_NOTHING));
                h.assertTrue(person.player.serverLevel()==target&&person.player.position().equals(position),"同座標の実次元移動");
                var closed=RadiationField.Point.class.getDeclaredField("closed");closed.setAccessible(true);
                h.assertTrue(!closed.getBoolean(old),"閉鎖印だけに頼らずlevelの一致も検査する");
                try(var reused=finish(new RadiationField.Point(person.player,old));var fresh=finish(new RadiationField.Point(person.player))) {
                    h.assertTrue(old.ground>0&&fresh.ground==0&&reused.ground==fresh.ground,"別levelの古い地面を同座標へコピーしない");
                }
            }
        } finally { SurvivalRuntime.INPUTS.remove(level); }
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review126",timeoutTicks=400)
    public static void futureWarningsStopButDelayedWarningsSurviveActualAttachment(GameTestHelper h) {
        var level=h.getLevel();long now=level.getGameTime();var input=new ExposureGameTests.Atmosphere(now);SurvivalRuntime.INPUTS.put(level,input);
        try {
            for(int kind=0;kind<2;kind++)try(var p=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"future126_"+kind)) {
                var tag=new CompoundTag();p.player.saveWithoutId(tag);p.logout();var body=payload(tag);
                body.getCompound("exposure").putBoolean("notified",true);
                body.getCompound("exposure").putLong("notification_tick",now+(kind==0?1:50));
                if(kind==1){body.putLong("observed",now+100);body.putLong("cursor",now+100);}
                rejected(h,p,tag);
            }
            try(var p=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"delayed126")) {
                input.set(now,.0012,.00042,21200);p.runtime.data.exposure.load[0]=2;p.runtime.data.exposure.stages[0]=3;
                SurvivalGameTests.time(level,now+40);
                for(int work=0;work<10000&&!p.runtime.data.exposure.notified;work++)p.runtime.work(System.nanoTime()+10_000_000,1);
                h.assertTrue(p.runtime.data.exposure.notificationTick>p.runtime.data.cursor,"物理cursorより後に実際の警告がある");
                var tag=new CompoundTag();p.player.saveWithoutId(tag);p.logout();p.player.removeData(AshenFrontierSurvival.DATA);p.player.load(tag);p.login();
                h.assertTrue(!p.runtime.data.blocked()&&p.runtime.data.exposure.notificationTick==now+40&&p.runtime.data.observed==now+40,"observed以下の正当な遅延警告を保持");
            }
        } finally { SurvivalRuntime.INPUTS.remove(level);SurvivalGameTests.time(level,now); }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,batch="review126",timeoutTicks=400)
    public static void uncommittedVitaminsAreRejectedAndZeroReservationIsInert(GameTestHelper h) {
        var level=h.getLevel();long now=level.getGameTime();SurvivalRuntime.INPUTS.put(level,new ExposureGameTests.Atmosphere(now));
        try {
            for(int flags=1;flags<=3;flags++)try(var p=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"reserve126_"+flags)) {
                p.runtime.data.addFrame(new PhysiologyData.Frame(PhysiologyData.Kind.INTAKE,p.runtime.data.currentLease,now,now,Activity.REST,false,0,0,0,0,(flags&1)!=0,(flags&2)!=0,0));
                var tag=new CompoundTag();p.player.saveWithoutId(tag);p.logout();rejected(h,p,tag);
            }
            try(var p=new SurvivalGameTests.Person(level,h.absolutePos(new BlockPos(0,2,0)),"zero126")) {
                p.runtime.data.body.vitaminC=1000;p.runtime.data.body.vitaminFat=2000;
                p.runtime.data.addFrame(new PhysiologyData.Frame(PhysiologyData.Kind.INTAKE,p.runtime.data.currentLease,now,now,Activity.REST,false,0,0,0,0,false,false,0));
                var tag=new CompoundTag();p.player.saveWithoutId(tag);p.logout();p.player.removeData(AshenFrontierSurvival.DATA);p.player.load(tag);p.login();
                var before=p.runtime.data.body.values();SurvivalGameTests.drain(h,p.runtime);
                h.assertTrue(p.runtime.data.frames.isEmpty()&&java.util.Arrays.equals(before,p.runtime.data.body.values()),"通常の量0予約は復元しても栄養・ビタミンへ無作用");
            }
        } finally { SurvivalRuntime.INPUTS.remove(level); }
        h.succeed();
    }
}
