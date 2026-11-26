package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.recipe.ProcessingStation;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProcessingEnvironmentGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="processing_135",timeoutTicks=400)
    public static void shelfCountsOnlySuitableActualIntervals(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var entity=place(f,"drying_rack");var player=player(f,1);
            try {
                var station=station(entity);
                station.setInput(player,0,pottery("green_pot"));
                f.ready(h,0,0);use(entity,player,ItemStack.EMPTY);
                h.assertTrue(station.active(),"実環境の棚を開始");
                double expected=0;int bad=0,dayChanges=0;
                var chimney=f.pos.offset(1,3,1);
                for(int n=0;n<300&&station.output(0).isEmpty();n++) {
                    long from=f.level.getGameTime();
                    boolean closed=n%6<3;
                    f.level.setBlock(chimney,(closed?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
                    RoomAir.get(f.level).changed(chimney);
                    f.ready(h,from,from);
                    if(n==30) {
                        var clock=ClimateClock.get(f.level);clock.changeDayLength(from,12000);
                        WeatherStore.get(f.level).clock(clock.history(),clock.revision(),from);dayChanges++;
                    }
                    f.clock.setGameTime(from+200);
                    var rows=f.ready(h,from,from+200);
                    for(var row:rows) {
                        if(row.covered()&&row.ventilated()&&row.temperatureK()>273.15&&row.relativeHumidity()<=.8)
                            expected+=(row.endTick()-row.startTick())*86400.0/row.ticksPerDay();
                        else bad++;
                    }
                    settle(entity,f,()->executedTick(entity)==f.level.getGameTime());
                    if(station.active())h.assertTrue(Math.abs(station.save(f.level.registryAccess()).getDouble("seconds")-expected)<1e-7,
                            "不適合時間を加算しない: "+expected+"/"+station.save(f.level.registryAccess()));
                    if(!station.output(0).isEmpty())break;
                }
                h.assertTrue(bad>=3&&dayChanges==1&&!station.output(0).isEmpty(),"換気閉塞と日長変更を経て乾燥完了 expected="+expected+" bad="+bad+" station="+station.save(f.level.registryAccess()));
            } catch(Exception e) {
                com.mojang.logging.LogUtils.getLogger().error("M3-135 加工検査",e);throw e;
            } finally { player.discard(); }
        }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="processing_135",timeoutTicks=400)
    public static void frictionIgnitionAndFiringUseActualClimate(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var entity=place(f,"hearth");var player=player(f,2);
            try {
                f.ready(h,0,0);
                use(entity,player,item("tinder",1));
                player.setShiftKeyDown(true);use(entity,player,item("dry_branch",16));player.setShiftKeyDown(false);
                var drill=item("fire_drill",1);drill.set(ItemProperties.WEAR,ItemProperties.Wear.of(0,64));
                use(entity,player,drill);
                h.assertTrue(bool(entity,"igniting"),"実環境で摩擦を開始");
                for(int n=0;n<30;n++)advance(entity,f,h,20);
                h.assertTrue(entity.saveWithoutMetadata(f.level.registryAccess()).getBoolean("lit"),"30 REAL秒で着火");
                var station=station(entity);boolean started=false;
                for(int n=0;n<1000&&station.output(0).isEmpty();n++) {
                    if(!started&&entity.saveWithoutMetadata(f.level.registryAccess()).getDouble("temperature_k")>=975) {
                        station.setInput(player,0,pottery("dried_pot"));use(entity,player,ItemStack.EMPTY);started=station.active();
                    }
                    advance(entity,f,h,20);
                }
                h.assertTrue(started&&!station.output(0).isEmpty(),"実環境の着火・燃焼・焼成を完了 started="+started+" state="+entity.saveWithoutMetadata(f.level.registryAccess()));
            } catch(Exception e) {
                com.mojang.logging.LogUtils.getLogger().error("M3-135 加工検査",e);throw e;
            } finally { player.discard(); }
        }
        h.succeed();
    }

    static BlockEntity place(EnvironmentConnectionGameTests.Fixture f,String id) {
        f.level.setBlock(f.pos,BuiltInRegistries.BLOCK.get(AshenFrontierCore.id(id)).defaultBlockState(),3);
        var entity=f.level.getBlockEntity(f.pos);entity.onLoad();try { var placed=entity.getClass().getDeclaredMethod("placed");placed.setAccessible(true);placed.invoke(entity); }
        catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }return entity;
    }
    static ProcessingStation station(BlockEntity entity) throws Exception {
        var method=entity.getClass().getDeclaredMethod("station");method.setAccessible(true);return (ProcessingStation)method.invoke(entity);
    }
    static ServerPlayer player(EnvironmentConnectionGameTests.Fixture f,int n) {
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(f.level,
                new com.mojang.authlib.GameProfile(new java.util.UUID(135,500+n),"processing135_"+n));
        player.setPos(f.pos.getX()+.5,f.pos.getY()+1,f.pos.getZ()+.5);f.level.addNewPlayer(player);return player;
    }
    static ItemStack item(String id,int count) { return new ItemStack(BuiltInRegistries.ITEM.get(AshenFrontierCore.id(id)),count); }
    @SuppressWarnings("unchecked")
    static ItemStack pottery(String id) {
        var stack=item(id,1);
        stack.set((net.minecraft.core.component.DataComponentType<String>)BuiltInRegistries.DATA_COMPONENT_TYPE.get(AshenFrontierCore.id("pottery_form")),"pot");
        return stack;
    }
    static void use(BlockEntity entity,ServerPlayer player,ItemStack held) throws Exception {
        io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget.beginTick(player.serverLevel().getServer());
        player.setItemInHand(InteractionHand.MAIN_HAND,held);
        entity.getClass().getMethod("interact",ServerPlayer.class,InteractionHand.class,Direction.class)
                .invoke(entity,player,InteractionHand.MAIN_HAND,Direction.UP);
    }
    static void advance(BlockEntity entity,EnvironmentConnectionGameTests.Fixture f,GameTestHelper h,long ticks) throws Exception {
        long from=f.level.getGameTime();f.clock.setGameTime(from+ticks);f.ready(h,from,from+ticks);
        for(int n=0;n<1000;n++) {
            entity.getClass().getMethod("work").invoke(entity);
            var tag=entity.saveWithoutMetadata(f.level.registryAccess());
            if(executedTick(entity)==f.level.getGameTime()||tag.getLong("last_tick")==f.level.getGameTime())return;
            EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);RoomAir.get(f.level).work();
        }
        throw new IllegalStateException("加工を再開できません");
    }
    private static boolean bool(BlockEntity entity,String name) throws Exception {
        var field=entity.getClass().getDeclaredField(name);field.setAccessible(true);return field.getBoolean(entity);
    }
    private static long executedTick(BlockEntity entity) {
        try { var field=entity.getClass().getDeclaredField("workTick");field.setAccessible(true);return field.getLong(entity); }
        catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static void settle(BlockEntity entity,EnvironmentConnectionGameTests.Fixture f,java.util.function.BooleanSupplier done) throws Exception {
        for(int n=0;n<2000;n++) {
            entity.getClass().getMethod("work").invoke(entity);
            if(done.getAsBoolean())return;
            EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);RoomAir.get(f.level).work();
        }
        throw new IllegalStateException("棚の再試行が進みません");
    }
}
