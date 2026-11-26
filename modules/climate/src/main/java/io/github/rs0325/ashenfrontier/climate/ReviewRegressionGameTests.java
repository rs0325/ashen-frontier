package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReviewRegressionGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID)
    public static void wrongElementTypesAreNotEmptySaves(GameTestHelper h) throws Exception {
        var pending=Class.forName("io.github.rs0325.ashenfrontier.primitive.PendingThermal")
                .getDeclaredMethod("load",ServerLevel.class,CompoundTag.class);pending.setAccessible(true);
        var codec=Class.forName("io.github.rs0325.ashenfrontier.climate.room.RoomRetentionCodec")
                .getDeclaredMethod("load",CompoundTag.class,java.util.function.LongToIntFunction.class,java.util.function.LongUnaryOperator.class);
        codec.setAccessible(true);
        var roomLoad=RoomAir.class.getDeclaredMethod("load",ServerLevel.class,CompoundTag.class);roomLoad.setAccessible(true);
        int rejected=0,total=0;
        for(int type:new int[]{Tag.TAG_LONG,Tag.TAG_STRING,Tag.TAG_INT_ARRAY}) {
            var invalid=new ListTag();
            invalid.add(switch(type) {
                case Tag.TAG_LONG -> LongTag.valueOf(1);
                case Tag.TAG_STRING -> net.minecraft.nbt.StringTag.valueOf("broken");
                default -> new net.minecraft.nbt.IntArrayTag(new int[]{1});
            });
            var queue=new CompoundTag();queue.putInt("format",1);queue.put("jobs",invalid);
            total++;if(rejects(()->pending.invoke(null,h.getLevel(),queue)))rejected++;
            for(String field:new String[]{"heads","leases","general"}) {
                var book=emptyRetention();book.put(field,invalid);
                total++;if(rejects(()->codec.invoke(null,book,(java.util.function.LongToIntFunction)c->1,
                        (java.util.function.LongUnaryOperator)c->220)))rejected++;
            }
            for(String field:new String[]{"past_models","past_cells","geometries","regions","receipts","displaced",
                    "cumulative","emissions","input_cumulative","input_recent","pending_cumulative","pending_recent"}) {
                var room=new CompoundTag();room.putInt("format",10);room.put("retention",emptyRetention());room.put(field,invalid);
                total++;if(rejects(()->roomLoad.invoke(null,h.getLevel(),room)))rejected++;
            }
        }
        var queue=new CompoundTag();queue.putInt("format",1);queue.put("jobs",new ListTag());
        pending.invoke(null,h.getLevel(),queue);
        codec.invoke(null,emptyRetention(),(java.util.function.LongToIntFunction)c->1,(java.util.function.LongUnaryOperator)c->220);
        h.assertTrue(rejected==total,"異なる要素型を空として受け入れない: "+rejected+"/"+total);
        com.mojang.logging.LogUtils.getLogger().info("M3-135 review NBT rejected={} cases={}",rejected,total);
        h.succeed();
    }
    private static CompoundTag emptyRetention() {
        var tag=new CompoundTag();for(String key:new String[]{"heads","leases","general"})tag.put(key,new ListTag());
        tag.putLongArray("physical",new long[0]);return tag;
    }
    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static boolean rejects(Attempt action) throws Exception {
        try { action.run();return false; }
        catch(InvocationTargetException e) {
            if(e.getCause() instanceof IllegalArgumentException||e.getCause() instanceof IllegalStateException)return true;
            throw e;
        }
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400)
    public static void delayedWorkContinuesFromTheEvaluatedEnd(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var entity=ProcessingEnvironmentGameTests.place(f,"hearth");
            var player=ProcessingEnvironmentGameTests.player(f,90);
            var tag=entity.saveWithFullMetadata(f.level.registryAccess());
            tag.putDouble("temperature_k",1100);tag.putDouble("fuel_j",16000000);
            tag.putDouble("heating_value",16000000);tag.putString("fuel_profile","ashen_frontier:dry_wood");tag.putLong("last_tick",0);
            entity.loadWithComponents(tag,f.level.registryAccess());
            var worker=entity.getClass().getDeclaredField("worker");worker.setAccessible(true);worker.set(entity,player.getUUID());
            var workTick=entity.getClass().getDeclaredField("workTick");workTick.setAccessible(true);workTick.setLong(entity,0);
            var lit=entity.getClass().getDeclaredMethod("setLit",boolean.class);lit.setAccessible(true);lit.invoke(entity,true);
            io.github.rs0325.ashenfrontier.core.research.ResearchManager.report(player,
                    io.github.rs0325.ashenfrontier.core.AshenFrontierCore.id("primitive_event"),"friction_ignition");
            var station=ProcessingEnvironmentGameTests.station(entity);
            station.setInput(player,0,ProcessingEnvironmentGameTests.pottery("dried_pot"));
            h.assertTrue(station.start(player,io.github.rs0325.ashenfrontier.core.AshenFrontierCore.id("primitive/fire_pot"),
                    io.github.rs0325.ashenfrontier.core.AshenFrontierCore.id("hearth"),
                    Optional.of(new EnvironmentSample(0,0,24000,293.15,101325,.3,0,0,Optional.empty(),true,true)),
                    java.util.OptionalDouble.of(1100)),"遅延前に実加工を開始");
            var values=EnvironmentQuery.class.getDeclaredField("samples");values.setAccessible(true);
            boolean[] hold={true};var burned=new ArrayList<long[]>();double[] chemical={0};
            java.util.function.Consumer<EnvironmentQuery> input=q->{
                if(q.level()!=f.level||!q.position().equals(f.pos))return;
                try {
                    if(hold[0]&&q.startTick()!=q.endTick())values.set(q,Optional.empty());
                    else q.provide(new EnvironmentSample(q.startTick(),q.endTick(),24000,293.15,101325,.3,0,0,Optional.empty(),true,true));
                } catch(IllegalAccessException e) { throw new IllegalStateException(e); }
            };
            java.util.function.Consumer<CombustionEvent> output=e->{
                if(e.level()==f.level&&e.energyJoules()>0) { burned.add(new long[]{e.startTick(),e.endTick()});chemical[0]+=e.energyJoules(); }
            };
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,input);
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,output);
            try {
                f.clock.setGameTime(20);entity.getClass().getMethod("work").invoke(entity);
                h.assertTrue(workTick.getLong(entity)==0,"保留では確定点を進めない");
                hold[0]=false;f.clock.setGameTime(40);entity.getClass().getMethod("work").invoke(entity);
                long first=workTick.getLong(entity);
                f.clock.setGameTime(60);entity.getClass().getMethod("work").invoke(entity);
                h.assertTrue(first==20&&burned.size()==2&&burned.get(0)[0]==0&&burned.get(0)[1]==20
                        &&burned.get(1)[0]==20&&burned.get(1)[1]==60&&Math.abs(chemical[0]-360000)<1e-8
                        &&Math.abs(station.save(f.level.registryAccess()).getDouble("seconds")-3)<1e-8,
                        "遅延成功後も未評価20〜40を燃焼から落とさない: first="+first+" chemical="+chemical[0]+" station="+station.save(f.level.registryAccess()));
            } finally { NeoForge.EVENT_BUS.unregister(input);NeoForge.EVENT_BUS.unregister(output);player.discard(); }
        }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400)
    public static void blockedSearchesReturnTheirGeneralReservations(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var rooms=RoomAir.get(f.level);Object first=null;BlockPos firstPosition=null;
            for(int n=0;n<8192;n++) {
                var p=f.pos.offset(128+n%128,0,128+n/128);
                f.level.getChunkAt(p);f.level.setBlock(p,Blocks.STONE.defaultBlockState(),2);
                rooms.sample(p,0);
                var job=searches(rooms).get(p.asLong());
                h.assertTrue(job!=null,"実探索を開始");
                if(n==0) { first=job;firstPosition=p;rooms.changed(p.offset(300,0,300)); }
                finishSearch(h,rooms,job,"BLOCKED");
            }
            assertNoSearchState(h,rooms,"8192 BLOCKED");
            rooms.sample(firstPosition,0);
            var restarted=searches(rooms).get(firstPosition.asLong());
            h.assertTrue(restarted!=null&&restarted!=first&&searchStatus(restarted).equals("SCANNING")
                    &&searchCollection(restarted,"visited")==0,"再観測は新しい探索");
            finishSearch(h,rooms,restarted,"BLOCKED");
            assertNoSearchState(h,rooms,"再観測後");
            var sky=f.pos.offset(400,0,400).atY(f.level.getMaxBuildHeight()-1);f.level.getChunkAt(sky);
            rooms.sample(sky,0);
            h.assertTrue(rooms.retentionUsage().generalPositions()==1,"終端探索の後も露天を受付");
            com.mojang.logging.LogUtils.getLogger().info("M3-135 terminal BLOCKED=8193 retained_jobs=0 retained_cells=0 reservations=0");
        }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400)
    public static void limitSearchesReleaseVisitedCellsAndRestart(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var rooms=RoomAir.get(f.level);var base=f.pos.offset(512,0,128);
            for(int x=0;x<20;x++)for(int y=0;y<20;y++)for(int z=0;z<20;z++)
                f.level.setBlock(base.offset(x,y,z),(x==0||x==19||y==0||y==19||z==0||z==19?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
            Object first=null;BlockPos firstPosition=null;long largest=0;
            for(int n=0;n<64;n++) {
                var p=base.offset(1+n%8,1+n/8,1);
                rooms.sample(p,0);var job=searches(rooms).get(p.asLong());
                h.assertTrue(job!=null,"大室の実探索を開始");
                if(n==0) { first=job;firstPosition=p; }
                finishSearch(h,rooms,job,"LIMIT");
                largest=Math.max(largest,searchCollection(job,"cells"));
            }
            h.assertTrue(largest==4097,"実際に4096セルを越えたLIMITを検査");
            assertNoSearchState(h,rooms,"64 LIMIT");
            rooms.sample(firstPosition,0);var retried=searches(rooms).get(firstPosition.asLong());
            h.assertTrue(retried!=null&&retried!=first&&searchStatus(retried).equals("SCANNING"),"LIMIT後も再探索");
            finishSearch(h,rooms,retried,"LIMIT");
            assertNoSearchState(h,rooms,"LIMIT再探索後");
            com.mojang.logging.LogUtils.getLogger().info("M3-135 terminal LIMIT=65 largest_removed_cells={} retained_jobs=0 retained_cells=0",largest);
        }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400)
    public static void unloadedSearchesReleaseQueuesAndRestart(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var rooms=RoomAir.get(f.level);Object first=null;BlockPos firstPosition=null;
            for(int n=0;n<64;n++) {
                int x=(f.pos.getX()>>4)+100+n*32,z=(f.pos.getZ()>>4)+100;
                f.level.getChunkAt(new BlockPos(x*16+8,f.pos.getY(),z*16+8));
                while(f.level.hasChunk(x+1,z))x++;
                var p=new BlockPos(x*16+15,f.pos.getY(),z*16+8);
                f.level.setBlock(p,Blocks.AIR.defaultBlockState(),18);
                f.level.setBlock(p.above(2),Blocks.STONE.defaultBlockState(),18);
                h.assertTrue(!f.level.hasChunk(x+1,z),"隣chunkは未ロードのまま");
                rooms.sample(p,0);var job=searches(rooms).get(p.asLong());
                h.assertTrue(job!=null,"ロード済み起点から実探索");
                if(n==0) { first=job;firstPosition=p; }
                finishSearch(h,rooms,job,"UNLOADED");
                h.assertTrue(!f.level.hasChunk(x+1,z),"探索で隣chunkをロードしない");
            }
            assertNoSearchState(h,rooms,"64 UNLOADED");
            rooms.sample(firstPosition,0);var retried=searches(rooms).get(firstPosition.asLong());
            h.assertTrue(retried!=null&&retried!=first&&searchStatus(retried).equals("SCANNING"),"UNLOADED後も再探索");
            finishSearch(h,rooms,retried,"UNLOADED");assertNoSearchState(h,rooms,"UNLOADED再探索後");
            com.mojang.logging.LogUtils.getLogger().info("M3-135 terminal UNLOADED=65 retained_jobs=0 retained_cells=0 retained_queue=0");
        }
        h.succeed();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Long,Object> searches(RoomAir rooms) throws Exception {
        return (java.util.Map<Long,Object>)roomField(rooms,"jobs");
    }
    private static Object roomField(RoomAir rooms,String name) throws Exception {
        var field=RoomAir.class.getDeclaredField(name);field.setAccessible(true);return field.get(rooms);
    }
    private static String searchStatus(Object job) throws Exception {
        var field=job.getClass().getDeclaredField("status");field.setAccessible(true);return field.get(job).toString();
    }
    private static int searchCollection(Object job,String name) throws Exception {
        var field=job.getClass().getDeclaredField(name);field.setAccessible(true);return ((java.util.Collection<?>)field.get(job)).size();
    }
    private static void finishSearch(GameTestHelper h,RoomAir rooms,Object job,String expected) throws Exception {
        for(int n=0;n<512&&searchStatus(job).equals("SCANNING");n++)rooms.work();
        h.assertTrue(searchStatus(job).equals(expected),"実探索の終端: "+expected+"/"+searchStatus(job));
    }
    private static void assertNoSearchState(GameTestHelper h,RoomAir rooms,String label) throws Exception {
        long cells=0,queue=0;
        for(var job:searches(rooms).values()) {
            cells+=searchCollection(job,"cells")+searchCollection(job,"visited")+searchCollection(job,"portals");
            queue+=searchCollection(job,"queue");
        }
        var registrations=(java.util.Map<?,?>)roomField(rooms,"registrations");
        var changes=(java.util.Map<?,?>)roomField(rooms,"changedChunks");
        var usage=rooms.retentionUsage();
        h.assertTrue(searches(rooms).isEmpty()&&cells==0&&queue==0&&registrations.isEmpty()&&changes.isEmpty()
                &&usage.generalPositions()==0&&usage.references()==0&&usage.encodedBytes()==0,
                label+" jobs="+searches(rooms).size()+" cells="+cells+" queue="+queue+" registrations="+registrations.size()+" changes="+changes.size()+" usage="+usage);
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID)
    public static void completedPastInputDoesNotWaitForFartherPrefetch(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            f.clock.setGameTime(24000);
            var provider=EnvironmentProvider.get(f.level);var pos=f.pos.above();
            var data=EnvironmentProvider.class.getDeclaredMethod("data",BlockPos.class,long.class,long.class,boolean.class);
            data.setAccessible(true);data.invoke(provider,pos,0L,100L,true);
            var jobs=EnvironmentProvider.class.getDeclaredField("jobs");jobs.setAccessible(true);
            boolean prepared=false;
            for(int n=0;n<2000;n++) {
                provider.work(Long.MAX_VALUE,32);
                var job=((java.util.Map<?,?>)jobs.get(provider)).values().iterator().next();
                var rows=job.getClass().getDeclaredField("rows");rows.setAccessible(true);
                var values=(java.util.List<?>)((java.util.Map<?,?>)rows.get(job)).get(pos);
                if(!values.isEmpty()) { prepared=true;break; }
            }
            h.assertTrue(prepared,"実気象の最初のページだけ準備");
            var job=((java.util.Map<?,?>)jobs.get(provider)).values().iterator().next();
            var done=job.getClass().getDeclaredField("done");done.setAccessible(true);
            h.assertTrue(!done.getBoolean(job),"現在までの先読みはまだ未完了");
            var outside=EnvironmentProvider.class.getDeclaredMethod("outside",ServerLevel.class,BlockPos.class,long.class,long.class);
            outside.setAccessible(true);
            @SuppressWarnings("unchecked")
            var answer=(Optional<List<RoomAir.Boundary>>)outside.invoke(provider,f.level,pos,0L,100L);
            h.assertTrue(answer.isPresent()&&answer.get().getFirst().from()==0&&answer.get().getLast().to()==100,
                    "要求した全域が完成済みなら、遠い未完了の先読みで保留しない");
        }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID)
    public static void clickingPendingShelfPreservesItsUnprocessedTime(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var entity=ProcessingEnvironmentGameTests.place(f,"drying_rack");
            var player=ProcessingEnvironmentGameTests.player(f,91);
            var station=ProcessingEnvironmentGameTests.station(entity);
            station.setInput(player,0,ProcessingEnvironmentGameTests.pottery("green_pot"));
            var values=EnvironmentQuery.class.getDeclaredField("samples");values.setAccessible(true);
            boolean[] hold={false};
            java.util.function.Consumer<EnvironmentQuery> input=q->{
                if(q.level()!=f.level||!q.position().equals(f.pos))return;
                try {
                    if(hold[0]&&q.startTick()!=q.endTick())values.set(q,Optional.empty());
                    else q.provide(new EnvironmentSample(q.startTick(),q.endTick(),24000,293.15,101325,.3,0,0,Optional.empty(),true,true));
                } catch(IllegalAccessException e) { throw new IllegalStateException(e); }
            };
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,input);
            try {
                ProcessingEnvironmentGameTests.use(entity,player,ItemStack.EMPTY);
                h.assertTrue(station.active(),"棚の実加工を開始");
                hold[0]=true;f.clock.setGameTime(20);
                ProcessingEnvironmentGameTests.use(entity,player,ItemStack.EMPTY);
                hold[0]=false;f.clock.setGameTime(40);entity.getClass().getMethod("work").invoke(entity);
                f.clock.setGameTime(60);entity.getClass().getMethod("work").invoke(entity);
                h.assertTrue(Math.abs(station.save(f.level.registryAccess()).getDouble("seconds")-216)<1e-8,
                        "保留中の再操作でも未評価期間を捨てない");
                player.setShiftKeyDown(true);ProcessingEnvironmentGameTests.use(entity,player,ItemStack.EMPTY);player.setShiftKeyDown(false);
                hold[0]=true;f.clock.setGameTime(70);entity.getClass().getMethod("work").invoke(entity);
                f.clock.setGameTime(80);station.setInput(player,0,ProcessingEnvironmentGameTests.pottery("green_pot"));
                ProcessingEnvironmentGameTests.use(entity,player,ItemStack.EMPTY);
                hold[0]=false;f.clock.setGameTime(100);entity.getClass().getMethod("work").invoke(entity);
                f.clock.setGameTime(120);entity.getClass().getMethod("work").invoke(entity);
                h.assertTrue(Math.abs(station.save(f.level.registryAccess()).getDouble("seconds")-144)<1e-8,
                        "新規作業は古い保留末尾を引き継がず、開始後の144 GAME秒を加算");

            } finally { NeoForge.EVENT_BUS.unregister(input);player.discard(); }
        }
        h.succeed();
    }
}
