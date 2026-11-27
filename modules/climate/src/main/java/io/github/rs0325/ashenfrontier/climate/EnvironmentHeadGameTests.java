package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.climate.room.RoomPhysics;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EnvironmentHeadGameTests {
    static void compare(GameTestHelper h,EnvironmentConnectionGameTests.Fixture f,long from,long to) {
        var full=f.ready(h,from,to);
        var head=EnvironmentQuery.queryFirst(f.level,f.pos,from,to);
        h.assertTrue(head.values().orElseThrow().equals(List.of(full.getFirst())),"全区間と先頭要求の値・末尾・積算が完全一致");
        h.assertTrue(EnvironmentProvider.get(f.level).exposure(f.pos,from,to).orElseThrow().sample().equals(full.getFirst()),"生理も同じ先頭解決を使用");
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="head126",timeoutTicks=400)
    public static void headModePreservesFullValidationAndAcceptsLegacyProvider(GameTestHelper h) {
        var position=h.absolutePos(new net.minecraft.core.BlockPos(0,2,0));int[] mode={0};
        var first=new io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample(0,10,24000,295.15,101325,.5,0,3,Optional.empty(),true,false);
        var second=new io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample(10,20,12000,280.15,90000,.8,0,5,Optional.empty(),false,true);
        java.util.function.Consumer<EnvironmentQuery> provider=query->{
            if(query.level()!=h.getLevel()||!query.position().equals(position))return;
            if(mode[0]==1||mode[0]==0&&query.firstOnly())query.provide(first);
            else if(mode[0]==2)query.provide(new io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample(0,0,24000,295.15,101325,.5,0,0,Optional.empty(),true,false));
            else query.provide(List.of(first,second));
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(provider);
        try {
            h.assertTrue(EnvironmentQuery.samples(h.getLevel(),position,0,20).orElseThrow().equals(List.of(first,second)),"全区間要求は完全な列");
            h.assertTrue(EnvironmentQuery.queryFirst(h.getLevel(),position,0,20).values().orElseThrow().equals(List.of(first)),"先頭要求は確定した先頭だけを採用");
            mode[0]=3;
            h.assertTrue(EnvironmentQuery.queryFirst(h.getLevel(),position,0,20).values().orElseThrow().equals(List.of(first)),"従来の全区間提供者でも先頭要求へ接続できる");
            mode[0]=1;boolean rejected=false;
            try {EnvironmentQuery.query(h.getLevel(),position,0,20);}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected,"全区間要求への欠落列は従来どおり拒否");
            mode[0]=2;rejected=false;
            try {EnvironmentQuery.queryFirst(h.getLevel(),position,0,20);}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected,"正長の先頭要求へ長さ0を返して進行を止めない");
        } finally {net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(provider);}
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="head126",timeoutTicks=400)
    public static void generatedHeadMatchesDelayedHeatDayLengthSplitMergeAndRestore(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            f.ready(h,0,0);long now=0,sequence=0;var source=new UUID(126,9);var random=new Random(1269001);
            int heat=0,changes=0,split=0,merge=0,restores=0,compared=0;
            for(int n=0;n<128;n++) {
                long before=now;now+=20+random.nextInt(40);f.clock.setGameTime(now);f.ready(h,before,now);
                if(n%3==0) {
                    RoomAir.get(f.level).receive(new CombustionEvent(f.level,f.pos,before,now,AshenFrontierCore.id("dry_wood"),0,0,random.nextDouble()*2000-1000,source,++sequence));heat++;
                }
                if(n%8==0) {
                    boolean closed=n%16==0;
                    for(int y=0;y<3;y++)for(int z=-1;z<=1;z++) {
                        var pos=f.pos.offset(1,y,z);f.level.setBlock(pos,(closed?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);RoomAir.get(f.level).changed(pos);
                    }
                    f.ready(h,now,now);if(closed)split++;else merge++;
                }
                if(n%16==1) {
                    var clock=ClimateClock.get(f.level);clock.changeDayLength(now,n%32==1?12000:24000);
                    WeatherStore.get(f.level).clock(clock.history(),clock.revision(),now);changes++;
                }
                if(n%16==7) {
                    var saved=RoomAir.get(f.level).save(new net.minecraft.nbt.CompoundTag(),f.level.registryAccess());
                    var load=RoomAir.class.getDeclaredMethod("load",net.minecraft.server.level.ServerLevel.class,net.minecraft.nbt.CompoundTag.class);load.setAccessible(true);
                    EnvironmentProvider.detach(f.level);f.level.getDataStorage().set(RoomAir.ID,(RoomAir)load.invoke(null,f.level,saved));
                    EnvironmentProvider.get(f.level);restores++;
                }
                compare(h,f,Math.max(0,now-600),now);compared++;
                compare(h,f,now,now);compared++;
            }
            h.assertTrue(compared>=256&&heat>=40&&changes>=8&&split>=8&&merge>=8&&restores>=8,"固定seedの実効条件下限");
            com.mojang.logging.LogUtils.getLogger().info("#126 head comparisons={} delayed_heat={} day_changes={} split={} merge={} restore={}",compared,heat,changes,split,merge,restores);
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="head126",timeoutTicks=400)
    public static void headKeepsFreezingBoundaryAndMissingInputReason(GameTestHelper h) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            f.ready(h,0,0);var rooms=RoomAir.get(f.level);
            var roof=f.pos.above(3);
            for(int y=roof.getY();y<f.level.getMaxBuildHeight();y++)f.level.setBlock(new net.minecraft.core.BlockPos(roof.getX(),y,roof.getZ()),Blocks.AIR.defaultBlockState(),3);
            rooms.changed(roof);f.ready(h,0,0);
            h.assertTrue(rooms.sample(f.pos.above(),0).status()==RoomAir.Status.OUTDOOR,"凍結Fixtureの観測位置を実際に露天にする");
            rooms.outside((level,pos,a,b)->{
                var rows=new java.util.ArrayList<RoomAir.Boundary>();
                long cut=a<20&&b>20?20:b;
                rows.add(new RoomAir.Boundary(a,cut,new RoomPhysics.Air(a<20?273.14:274,101325,1e-7,420e-6,.2095,2)));
                if(cut<b)rows.add(new RoomAir.Boundary(cut,b,new RoomPhysics.Air(274,101325,1e-7,420e-6,.2095,2)));
                return Optional.of(List.copyOf(rows));
            },()->0L);
            f.clock.setGameTime(1200);var full=f.ready(h,0,1200);
            h.assertTrue(full.getFirst().temperatureK()<273.15&&full.getFirst().endTick()==20,"0℃を跨ぐ最初の自然境界を作る first="+full.getFirst()+" local="+rooms.sample(f.pos.above(),0));
            compare(h,f,0,1200);
            boolean futureRejected=false;
            try {EnvironmentQuery.queryFirst(f.level,f.pos,0,1201);}catch(IllegalArgumentException expected){futureRejected=true;}
            h.assertTrue(futureRejected,"先頭へ絞る前に元の要求の未来時刻を拒否");
            var unknown=f.pos.offset(1000,0,1000);
            var missing=EnvironmentQuery.queryFirst(f.level,unknown,0,1200);
            h.assertTrue(missing.values().isEmpty()&&missing.reason().isPresent(),"未観測の過去を安全な先頭で補わない");
            h.assertTrue(EnvironmentQuery.queryFirst(h.getLevel(),f.pos,0,0).values().isEmpty(),"未接続を既定値で補わない");
        }
        h.succeed();
    }
}
