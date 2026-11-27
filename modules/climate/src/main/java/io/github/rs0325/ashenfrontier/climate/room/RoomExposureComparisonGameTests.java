package io.github.rs0325.ashenfrontier.climate.room;

import io.github.rs0325.ashenfrontier.climate.AshenFrontierClimate;
import io.github.rs0325.ashenfrontier.climate.EnvironmentConnectionGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Random;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoomExposureComparisonGameTests {
    private static RoomAir.Sample ready(EnvironmentConnectionGameTests.Fixture f,net.minecraft.core.BlockPos position,long tick) {
        for(int n=0;n<20000;n++) {
            var sample=RoomAir.get(f.level).sample(position,tick);if(sample.confirmed())return sample;
            io.github.rs0325.ashenfrontier.climate.EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);RoomAir.get(f.level).work();
        }
        throw new IllegalStateException("室の有限作業が完了しません");
    }
    private static Object region(RoomAir rooms,long position) throws Exception {
        var field=RoomAir.class.getDeclaredField("regions");field.setAccessible(true);
        for(var r:(java.util.List<?>)field.get(rooms)) {
            var cells=r.getClass().getDeclaredField("cells");cells.setAccessible(true);
            if(java.util.Arrays.binarySearch((long[])cells.get(r),position)>=0)return r;
        }
        throw new IllegalStateException("観測済みの室がありません");
    }
    private static long count(Object region,String name) throws Exception {
        var field=region.getClass().getDeclaredField(name);field.setAccessible(true);return field.getLong(region);
    }
    private static void clearSamples(RoomAir rooms) throws Exception {
        var method=RoomAir.class.getDeclaredMethod("clearSamples");method.setAccessible(true);method.invoke(rooms);
    }
    private static RoomAir copy(RoomAir source,EnvironmentConnectionGameTests.Fixture f,boolean replay) throws Exception {
        var tag=source.save(new net.minecraft.nbt.CompoundTag(),f.level.registryAccess());
        var result=RoomAir.load(f.level,tag);
        var outside=RoomAir.class.getDeclaredField("outside");outside.setAccessible(true);
        var revision=RoomAir.class.getDeclaredField("outsideRevision");revision.setAccessible(true);
        result.outside((RoomAir.Outside)outside.get(source),(java.util.function.LongSupplier)revision.get(source));
        var enabled=RoomAir.class.getDeclaredField("replayEnabled");enabled.setAccessible(true);enabled.setBoolean(result,replay);
        return result;
    }
    private static long metric(RoomAir rooms,String name) throws Exception {
        var field=RoomAir.class.getDeclaredField("regions");field.setAccessible(true);long sum=0;
        for(var r:(java.util.List<?>)field.get(rooms))sum+=count(r,name);return sum;
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400,batch="exposure_response126")
    @SuppressWarnings("unchecked")
    public static void exactCheckpointDoesNotRebuildUnchangedInputTimeline(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var rooms=RoomAir.get(f.level);f.ready(h,0,0);
            ((PrimaryLevelData)f.level.getLevelData()).setGameTime(100);
            ready(f,f.pos.above(),100);
            var r=region(rooms,f.pos.above().asLong());
            var checkpoints=r.getClass().getDeclaredField("checkpoints");checkpoints.setAccessible(true);
            var entry=((java.util.NavigableMap<Long,?>)checkpoints.get(r)).firstEntry();
            h.assertTrue(entry!=null,"実際の積分境界checkpointを使う");
            var revision=r.getClass().getDeclaredField("inputRevision");revision.setAccessible(true);revision.setLong(r,-1);
            long builds=count(r,"inputBuilds");clearSamples(rooms);
            var sample=rooms.sample(f.pos.above(),entry.getKey());
            var state=entry.getValue().getClass().getDeclaredMethod("state");state.setAccessible(true);
            h.assertTrue(sample.confirmed()&&sample.state().orElseThrow().equals(state.invoke(entry.getValue())),"確定境界の状態をそのまま返す");
            h.assertTrue(count(r,"inputBuilds")==builds,"経過0の確定境界で入力列を再構築しない");
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400,batch="exposure_response126")
    public static void replayPrefixMatchesUncachedPastCurrentPastWithLateInputsAndRestore(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var original=RoomAir.get(f.level);f.ready(h,0,0);
            var actual=copy(original,f,true);var reference=copy(original,f,false);
            var initial=actual.sample(f.pos.above(),0);double capacity=RoomPhysics.capacity(initial.volumeM3(),count(region(actual,f.pos.above().asLong()),"walls"));
            double heat=capacity*(271-initial.state().orElseThrow().temperatureK());
            var start=new java.util.UUID(126,202);var source=new java.util.UUID(126,203);long sequence=0;
            actual.respiration(start,1,f.pos.above(),0,0,0,0,heat);reference.respiration(start,1,f.pos.above(),0,0,0,0,heat);
            int compared=0,clamped=0,crossings=0,restores=0,splits=0,merges=0;long hits=0;double last=271;
            for(int n=0;n<96;n++) {
                long now=(n+1)*20L;((PrimaryLevelData)f.level.getLevelData()).setGameTime(now);
                double oxygen=n%12==0?4000:.05;double thermal=n%2==0?400:-1200;long from=Math.max(0,now-100);
                actual.respiration(source,++sequence,f.pos.above(),from,now,oxygen,.2,thermal);
                reference.respiration(source,sequence,f.pos.above(),from,now,oxygen,.2,thermal);
                if(n%4==0) {
                    long at=Math.max(0,now-40);
                    actual.respiration(source,++sequence,f.pos.above(),at,at,0,.1,200);
                    reference.respiration(source,sequence,f.pos.above(),at,at,0,.1,200);
                }
                for(long tick:new long[]{Math.max(0,now-160),now,Math.max(0,now-160),Math.max(0,now-80)}) {
                    clearSamples(actual);clearSamples(reference);
                    var fast=actual.sample(f.pos.above(),tick);var cold=reference.sample(f.pos.above(),tick);
                    for(int work=0;work<20000&&(!fast.confirmed()||!cold.confirmed());work++) {
                        io.github.rs0325.ashenfrontier.climate.EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);
                        actual.work();reference.work();
                        if(!fast.confirmed())fast=actual.sample(f.pos.above(),tick);
                        if(!cold.confirmed())cold=reference.sample(f.pos.above(),tick);
                    }
                    h.assertTrue(fast.confirmed()&&fast.equals(cold),"同じ元の境界列の純再生と旧再生が状態・流量・精度まで一致 n="+n+" tick="+tick+" actual="+fast+" reference="+cold);
                    compared++;if(fast.state().orElseThrow().oxygen()==0)clamped++;
                    if(tick==now) {
                        double temperature=fast.state().orElseThrow().temperatureK();if(last<=273.15&&temperature>273.15)crossings++;last=temperature;
                    }
                }
                if(n%16==15) {
                    boolean closed=(n/16)%2==0;
                    for(int y=0;y<3;y++)for(int z=-1;z<=1;z++) {
                        var pos=f.pos.offset(1,y,z);f.level.setBlock(pos,(closed?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);actual.changed(pos);reference.changed(pos);
                    }
                    boolean complete=false;
                    for(int work=0;work<20000;work++) {
                        var a=actual.sample(f.pos.above(),now);var b=reference.sample(f.pos.above(),now);
                        if(a.confirmed()&&b.confirmed()){h.assertTrue(a.equals(b),"分割・合流後も純メモと旧再生が一致");complete=true;break;}
                        io.github.rs0325.ashenfrontier.climate.EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);actual.work();reference.work();
                    }
                    h.assertTrue(complete,"構成変更を有限の仕事で確定");if(closed)splits++;else merges++;
                }
                if(n%24==23) {hits+=metric(actual,"replayHits");actual=copy(actual,f,true);reference=copy(reference,f,false);restores++;}
            }
            hits+=metric(actual,"replayHits");
            h.assertTrue(compared>=384&&clamped>=1&&crossings>=1&&restores>=4&&splits>=3&&merges>=3&&hits>=128,
                    "A2実効下限 compared="+compared+" clamp="+clamped+" freeze="+crossings+" restore="+restores+" hits="+hits);
            com.mojang.logging.LogUtils.getLogger().info("#126 A2 comparisons={} oxygen_clamp={} freezing={} restores={} split={} merge={} cached_steps={}",compared,clamped,crossings,restores,splits,merges,hits);
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400,batch="exposure_response126")
    public static void sameTickEmissionInvalidatesOnlyItsRoomAndPreservesEarlierState(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var other=f.pos.offset(8,0,0);
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=3;y++)
                f.level.setBlock(other.offset(x,y,z),(Math.abs(x)==2||Math.abs(z)==2||y==-1||y==3?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
            var rooms=RoomAir.get(f.level);f.ready(h,0,0);ready(f,other.above(),0);
            ((PrimaryLevelData)f.level.getLevelData()).setGameTime(20);
            var atStart=ready(f,f.pos.above(),0);var before=ready(f,f.pos.above(),20);var unrelated=ready(f,other.above(),20);
            // 同tickの最終キャッシュを揃えてから通知する。
            atStart=rooms.sample(f.pos.above(),0);before=rooms.sample(f.pos.above(),20);unrelated=rooms.sample(other.above(),20);
            var a=region(rooms,f.pos.above().asLong());var b=region(rooms,other.above().asLong());
            long buildsA=count(a,"inputBuilds"),buildsB=count(b,"inputBuilds");
            var source=new java.util.UUID(126,101);
            rooms.respiration(source,1,f.pos.above(),0,20,.1,.2,500);
            h.assertTrue(rooms.sample(other.above(),20)==unrelated,"別室の同tickメモを失効させない");
            h.assertTrue(rooms.sample(f.pos.above(),0)==atStart,"連続入力の始点以前の状態を保つ");
            var changed=rooms.sample(f.pos.above(),20);
            h.assertTrue(changed.state().orElseThrow().co2()>before.state().orElseThrow().co2(),"対象室の連続呼吸を反映");
            rooms.respiration(source,2,f.pos.above(),20,20,0,.1,0);
            h.assertTrue(rooms.sample(other.above(),20)==unrelated,"瞬時入力も別室のメモを保つ");
            h.assertTrue(rooms.sample(f.pos.above(),20).state().orElseThrow().co2()>changed.state().orElseThrow().co2(),"瞬時入力は作用時刻を含めて失効");
            h.assertTrue(count(b,"inputBuilds")==buildsB&&count(a,"inputBuilds")>=buildsA+2,"無関係室の一覧再構築は0、対象室だけ更新");
            com.mojang.logging.LogUtils.getLogger().info("#126 A1 target_rebuilds={} unrelated_rebuilds={}",count(a,"inputBuilds")-buildsA,count(b,"inputBuilds")-buildsB);
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400,batch="exposure_response126")
    public static void stablePastResponseMatchesGeneralIncludingExchange(GameTestHelper h) throws Exception {
        try(var fixture=new EnvironmentConnectionGameTests.Fixture(h)) {
            var rooms=RoomAir.get(fixture.level);fixture.ready(h,0,0);
            ((PrimaryLevelData)fixture.level.getLevelData()).setGameTime(1200);fixture.ready(h,0,1200);
            var history=RoomAir.class.getDeclaredField("past");history.setAccessible(true);var past=(RoomPast)history.get(rooms);
            var provider=RoomAir.class.getDeclaredField("outside");provider.setAccessible(true);var outside=(RoomAir.Outside)provider.get(rooms);
            RoomPast.Boundaries boundaries=(pos,a,b)->outside.intervals(fixture.level,pos,a,b);
            var refresh=RoomAir.class.getDeclaredMethod("refreshPast");refresh.setAccessible(true);refresh.invoke(rooms);
            var random=new Random(126137);int compared=0;
            for(int i=0;i<512;i++) {
                long from=random.nextInt(600),to=i%4==0?from:from+1+random.nextInt(500);
                var input=new RoomPhysics.Amount(random.nextDouble()*20000-10000,random.nextDouble(),random.nextDouble(),-random.nextDouble());
                var fast=past.response(fixture.pos.above().asLong(),from,to,1200,input,-1,boundaries);
                var general=past.responseGeneral(fixture.pos.above().asLong(),from,to,1200,input,-1,boundaries);
                h.assertTrue(fast.isPresent()&&fast.equals(general),"一定幾何の集約応答と一般解は各セルの4量・交換収支まで一致 case="+i);
                compared++;
            }
            var changed=fixture.pos.offset(1,3,1);fixture.level.setBlock(changed,Blocks.STONE.defaultBlockState(),3);rooms.changed(changed);
            fixture.ready(h,1200,1200);((PrimaryLevelData)fixture.level.getLevelData()).setGameTime(1800);fixture.ready(h,1200,1800);refresh.invoke(rooms);
            var amount=new RoomPhysics.Amount(1000,.1,.2,-.3);
            h.assertTrue(past.response(fixture.pos.above().asLong(),1100,1300,1800,amount,-1,boundaries)
                    .equals(past.responseGeneral(fixture.pos.above().asLong(),1100,1300,1800,amount,-1,boundaries)),
                    "構成変更をまたぐときは一般解へ戻り、収支を変えない");
            h.assertTrue(compared>=512,"比較の実効件数");
        }
        h.succeed();
    }
}
