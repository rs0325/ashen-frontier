package io.github.rs0325.ashenfrontier.climate.room;

import io.github.rs0325.ashenfrontier.climate.AshenFrontierClimate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoomOutdoorGameTests {
    private static final RoomPhysics.Air AIR = new RoomPhysics.Air(293.15, 101325, 0, .00042, .2095, 1.5);
    private static RoomAir fixture(GameTestHelper h) {
        var tag = new CompoundTag(); tag.putInt("format",10); tag.put("retention",RoomRetentionCodec.save(new RoomRetention()));
        return restore(h,tag);
    }
    private static RoomAir restore(GameTestHelper h, CompoundTag tag) {
        var service=RoomAir.load(h.getLevel(),tag);
        service.outside((l,p,a,b)->Optional.of(List.of(new RoomAir.Boundary(a,b,AIR))));
        return service;
    }
    private static BlockPos point(GameTestHelper h) {
        return h.absolutePos(new BlockPos(2,2,2)).atY(h.getLevel().getMaxBuildHeight()-4);
    }
    private static void block(GameTestHelper h,RoomAir service,BlockPos pos,boolean solid) {
        h.getLevel().setBlockAndUpdate(pos,(solid?Blocks.STONE:Blocks.AIR).defaultBlockState());
        service.changed(pos);
    }
    private static RoomAir.Sample discover(RoomAir service,BlockPos pos,long tick) {
        var sample=service.sample(pos,tick);
        for(int n=0;n<256&&sample.status()==RoomAir.Status.SCANNING;n++) {
            service.work();sample=service.sample(pos,tick);
        }
        return sample;
    }
    @GameTest(template="room",templateNamespace=AshenFrontierClimate.MOD_ID,batch="room_outdoor_history")
    public static void observedOutdoorRemainsQueryableAfterTimeAndSave(GameTestHelper h) {
        var clock=h.getLevel().getServer().getWorldData().overworldData();long start=clock.getGameTime();
        try {
            var service=fixture(h);var pos=point(h);
            block(h,service,pos,false);block(h,service,pos.above(),false);
            h.assertTrue(service.sample(pos,start).status()==RoomAir.Status.OUTDOOR,"現在の露天を観測");
            clock.setGameTime(start+1);
            h.assertTrue(service.sample(pos,start).status()==RoomAir.Status.OUTDOOR,"観測した屋外を翌tickにも過去照会できる");
            service=restore(h,service.save(new CompoundTag(),h.getLevel().registryAccess()));
            h.assertTrue(service.sample(pos,start).status()==RoomAir.Status.OUTDOOR,"保存復元後も観測した屋外を返す");
            h.assertTrue(service.sample(pos.offset(4,0,0),start).status()==RoomAir.Status.HISTORY_MISSING,"未観測の空間を屋外で埋めない");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }
    @GameTest(template="room",templateNamespace=AshenFrontierClimate.MOD_ID,batch="room_outdoor_history")
    public static void outdoorCoverAndRemovalKeepTheirOwnIntervals(GameTestHelper h) {
        var clock=h.getLevel().getServer().getWorldData().overworldData();long start=clock.getGameTime();
        try {
            var service=fixture(h);var pos=point(h);
            for(var direction:Direction.values()) block(h,service,pos.relative(direction),direction!=Direction.UP);
            block(h,service,pos,false);
            h.assertTrue(service.sample(pos,start).status()==RoomAir.Status.OUTDOOR,"屋根がない期間を観測");
            clock.setGameTime(start+1);block(h,service,pos.above(),true);
            h.assertTrue(discover(service,pos,start+1).status()==RoomAir.Status.INDOOR,"屋根を閉じた期間を観測");
            clock.setGameTime(start+2);block(h,service,pos.above(),false);
            h.assertTrue(service.sample(pos,start+2).status()==RoomAir.Status.OUTDOOR,"屋根を外した期間を観測");
            clock.setGameTime(start+3);
            service=restore(h,service.save(new CompoundTag(),h.getLevel().registryAccess()));
            h.assertTrue(service.sample(pos,start).status()==RoomAir.Status.OUTDOOR,"最初の屋外を現在の幾何で置き換えない");
            h.assertTrue(service.sample(pos,start+1).status()==RoomAir.Status.INDOOR,"途中の覆いを維持");
            h.assertTrue(service.sample(pos,start+2).status()==RoomAir.Status.OUTDOOR,"復旧した屋外を維持");
            var cuts=service.boundaries(pos,start,start+3);
            h.assertTrue(cuts.containsAll(List.of(start,start+1,start+2,start+3)),"屋外の切替もC06の区間境界へ出す");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template="room",templateNamespace=AshenFrontierClimate.MOD_ID,batch="room_outdoor_history")
    public static void invalidationDoesNotFillAnUnobservedGap(GameTestHelper h) {
        var clock=h.getLevel().getServer().getWorldData().overworldData();long start=clock.getGameTime();
        try {
            var service=fixture(h);var pos=point(h);
            block(h,service,pos,false);block(h,service,pos.above(),false);
            service.sample(pos,start);
            clock.setGameTime(start+1);block(h,service,pos.above(),true);
            clock.setGameTime(start+3);block(h,service,pos.above(),false);service.sample(pos,start+3);
            clock.setGameTime(start+4);
            service=restore(h,service.save(new CompoundTag(),h.getLevel().registryAccess()));
            h.assertTrue(service.sample(pos,start).status()==RoomAir.Status.OUTDOOR,"無効化前の期間を保持");
            h.assertTrue(service.sample(pos,start+2).status()==RoomAir.Status.HISTORY_MISSING,"観測しなかった変更後の期間を屋外で補完しない");
            h.assertTrue(service.sample(pos,start+3).status()==RoomAir.Status.OUTDOOR,"新しい観測から再開");
            h.succeed();
        } finally { clock.setGameTime(start); }
    }

    @GameTest(template="room",templateNamespace=AshenFrontierClimate.MOD_ID,batch="room_outdoor_unload",timeoutTicks=400)
    public static void unloadedCurrentPositionKeepsObservedPastWithoutLoadingChunks(GameTestHelper h) throws Exception {
        var level=h.getLevel();var near=point(h).offset(512,0,0);
        var pos=new BlockPos((near.getX()&~15)+8,near.getY(),(near.getZ()&~15)+8);
        int x=pos.getX()>>4,z=pos.getZ()>>4;
        h.assertTrue(!level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(x,z)),"専用の遠方チャンク");
        level.setChunkForced(x,z,true);level.getChunkAt(pos);
        level.setBlockAndUpdate(pos.below(),Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
        var original=level.getBlockEntity(pos.below());var service=fixture(h);long start=level.getGameTime();
        h.assertTrue(service.sample(pos,start).status()==RoomAir.Status.OUTDOOR,"ロード中の露天を観測");
        var saved=service.save(new CompoundTag(),level.registryAccess());
        level.getChunkSource().save(true);level.setChunkForced(x,z,false);
        var fixture=Class.forName("io.github.rs0325.ashenfrontier.primitive.ChunkReloadFixture");
        var after=fixture.getDeclaredMethod("afterUnload",GameTestHelper.class,net.minecraft.world.level.block.entity.BlockEntity.class,Runnable.class);
        after.setAccessible(true);
        after.invoke(null,h,original,(Runnable)()->{
            var restored=restore(h,saved);
            h.assertTrue(level.getChunkSource().getChunkNow(x,z)==null,"実アンロード後のチャンク");
            h.assertTrue(restored.sample(pos,level.getGameTime()).status()==RoomAir.Status.UNLOADED,"現在の未ロードを屋外で補完しない");
            h.assertTrue(restored.sample(pos,start).status()==RoomAir.Status.OUTDOOR,"観測済みの過去は未ロードでも復元");
            h.assertTrue(restored.sample(pos.offset(2,0,0),start).status()==RoomAir.Status.HISTORY_MISSING,"隣の未観測位置は不明");
            h.assertTrue(level.getChunkSource().getChunkNow(x,z)==null,"過去照会で強制ロードしない");
            level.getChunkAt(pos);
            h.assertTrue(level.getBlockEntity(pos.below())!=original,"保存から別のBEを復元");
            h.assertTrue(restored.sample(pos,level.getGameTime()).status()==RoomAir.Status.OUTDOOR,"再ロードした実幾何で屋外を確認");
            level.setBlockAndUpdate(pos.below(),Blocks.AIR.defaultBlockState());
            h.succeed();
        });
    }
    @GameTest(template="room",templateNamespace=AshenFrontierClimate.MOD_ID,batch="room_outdoor_history")
    public static void retentionMustNotTurnUnobservedPeriodsIntoOutdoor(GameTestHelper h) {
        var clock=h.getLevel().getServer().getWorldData().overworldData();long start=clock.getGameTime();
        try {
            var service=fixture(h);var pos=point(h);
            block(h,service,pos,false);block(h,service,pos.above(),false);
            for(int n=0;n<65;n++) {
                clock.setGameTime(start+n*3);service.sample(pos,clock.getGameTime());
                clock.setGameTime(start+n*3+1);block(h,service,pos.above(),true);
                clock.setGameTime(start+n*3+2);block(h,service,pos.above(),false);
                if(n==63) {
                    var missing=service.sample(pos,start+1);
                    h.assertTrue(missing.status()==RoomAir.Status.HISTORY_MISSING && missing.accuracy()==RoomAir.Accuracy.UNKNOWN,"64件までは未観測期間を保持");
                    h.assertTrue(service.sample(pos,start).accuracy()==RoomAir.Accuracy.OBSERVED,"観測済みの期間はまだ代表化していない");
                }
            }
            var before=service.sample(pos,start+1);
            service=restore(h,service.save(new CompoundTag(),h.getLevel().registryAccess()));
            var after=service.sample(pos,start+1);
            h.assertTrue(before.status()==RoomAir.Status.OUTDOOR && after.status()==RoomAir.Status.OUTDOOR
                    && before.accuracy()==RoomAir.Accuracy.APPROXIMATE && after.accuracy()==RoomAir.Accuracy.APPROXIMATE
                    && before.confirmed() && after.confirmed(),
                    "65件目からは分類と独立した近似の印を返し、保存復元後も計算を続けられる: before="+before+" after="+after);
            h.succeed();
        } finally { clock.setGameTime(start); }
    }


    @GameTest(template="room",templateNamespace=AshenFrontierClimate.MOD_ID,batch="room_outdoor_history")
    public static void unrelatedOutdoorObservationsDoNotStarveRegistration(GameTestHelper h) throws Exception {
        var base=h.absolutePos(new BlockPos(1,1,1)).atY(h.getLevel().getMaxBuildHeight()-24);
        for(int x=0;x<10;x++) for(int z=0;z<10;z++) for(int y=0;y<10;y++) {
            var pos=base.offset(x,y,z);h.getLevel().getChunkAt(pos);
            boolean wall=x==0||x==9||y==0||y==9||z==0||z==9;
            h.getLevel().setBlockAndUpdate(pos,(wall?Blocks.STONE:Blocks.AIR).defaultBlockState());
        }
        var service=fixture(h);var origin=base.offset(1,1,1);
        service.sample(origin,h.getLevel().getGameTime());
        int completed=-1;
        for(int n=0;n<120;n++) {
            var outdoor=base.offset(n%16,22,n/16);
            h.getLevel().getChunkAt(outdoor);
            h.getLevel().setBlockAndUpdate(outdoor,Blocks.AIR.defaultBlockState());
            h.assertTrue(service.sample(outdoor,h.getLevel().getGameTime()).status()==RoomAir.Status.OUTDOOR,"更新ごとに新しい露天を観測");
            service.work();
            if(completed<0 && service.sample(origin,h.getLevel().getGameTime()).status()==RoomAir.Status.INDOOR) completed=n+1;
        }
        h.assertTrue(completed>0 && completed<=120,"毎更新の無関係な屋外観測中も512セルの登録が有限回で完了する: "+completed);
        h.assertTrue(service.sample(origin,h.getLevel().getGameTime()).volumeM3()==512,"実際の512セルを登録");
        java.nio.file.Files.writeString(java.nio.file.Path.of("room-outdoor-progress.txt"),"cells=512 interrupts=120 completed_update="+completed);
        h.succeed();
    }

    @GameTest(template="room",templateNamespace=AshenFrontierClimate.MOD_ID,batch="room_outdoor_history")
    public static void everyWatchedPassageClosesOutdoorAfterSave(GameTestHelper h) {
        assertWatchedPassages(h,fixture(h),h.absolutePos(new BlockPos(3,1,1)),true);
        h.succeed();
    }

    static void assertWatchedPassages(GameTestHelper h,RoomAir service,BlockPos column,boolean finish) {
        var pos=column.atY(h.getLevel().getMinBuildHeight()+1);
        long tick=h.getLevel().getGameTime();
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) for(int y=-1;y<=1;y++)
            block(h,service,pos.offset(x,y,z),true);
        block(h,service,pos,false);block(h,service,pos.below(),false);
        h.assertTrue(discover(service,pos,tick).status()==RoomAir.Status.OUTDOOR,"監視座標を持つ屋外経路を準備");
        var targets=new ArrayList<BlockPos>();
        var initial=service.save(new CompoundTag(),h.getLevel().registryAccess());
        for(var element:initial.getList("past_models",10)) {
            var model=(CompoundTag)element;
            if(model.getBoolean("outdoor") && !model.getBoolean("sky") && model.getLong("until")==Long.MAX_VALUE)
                for(long cell:model.getLongArray("watch")) {
                    var target=BlockPos.of(cell);
                    if(target.getY()>=h.getLevel().getMinBuildHeight()) targets.add(target);
                }
        }
        h.assertTrue(targets.size()>2 && targets.contains(pos) && targets.contains(pos.below()),"閉塞対象を含む全ての変更可能な監視座標");
        for(boolean reload:new boolean[]{false,true}) for(var target:targets) {
            for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) for(int y=-1;y<=1;y++)
                block(h,service,pos.offset(x,y,z),true);
            block(h,service,pos,false);block(h,service,pos.below(),false);
            h.assertTrue(discover(service,pos,tick).status()==RoomAir.Status.OUTDOOR,"下端に通じる複数座標の経路を観測");
            var saved=service.save(new CompoundTag(),h.getLevel().registryAccess());
            boolean multiple=false;int observedId=-1;
            for(var element:saved.getList("past_models",10)) {
                var model=(CompoundTag)element;
                if(model.getBoolean("outdoor") && !model.getBoolean("sky") && model.getLong("until")==Long.MAX_VALUE) {
                    var watch=model.getLongArray("watch");
                    long inside=java.util.Arrays.stream(watch).filter(cell->BlockPos.of(cell).getY()>=h.getLevel().getMinBuildHeight()).count();
                    multiple=inside==targets.size() && targets.stream().allMatch(p->java.util.Arrays.stream(watch).anyMatch(cell->cell==p.asLong()));
                    observedId=model.getInt("id");
                }
            }
            h.assertTrue(multiple,"複数の監視座標を持つ屋外の試験");
            if(reload) {
                for(var element:saved.getList("past_models",10)) {
                    var model=(CompoundTag)element;
                    var watch=model.getLongArray("watch");
                    for(int left=0,right=watch.length-1;left<right;left++,right--) {
                        long swap=watch[left];watch[left]=watch[right];watch[right]=swap;
                    }
                    if(watch.length>0) model.putLongArray("watch",watch);
                }
                service=restore(h,saved);
            }
            boolean closing=RoomSearch.passable(h.getLevel(),target);
            block(h,service,target,closing);
            h.assertTrue(RoomSearch.passable(h.getLevel(),target)!=closing,"実際に監視対象の空気/固体を切り替えた");
            var invalidated=service.save(new CompoundTag(),h.getLevel().registryAccess());
            boolean ended=false;
            for(var element:invalidated.getList("past_models",10)) {
                var model=(CompoundTag)element;
                if(model.getInt("id")==observedId) ended=model.getLong("until")==tick;
            }
            h.assertTrue(ended,"再観測の前に旧期間を閉じる: target="+target+" reload="+reload);
            var result=service.sample(pos,tick);
            if(closing) h.assertTrue(result.status()!=RoomAir.Status.OUTDOOR,"通路の閉塞後に旧屋外を返さない: "+result);
            if(finish && target.equals(pos.below())) {
                var fresh=discover(service,pos,tick);
                h.assertTrue(fresh.status()==RoomAir.Status.INDOOR,"下端を塞ぐと新しい探索は室内: reload="+reload+" result="+fresh);
            }
        }
        if(!finish) block(h,service,pos,true);
    }

    static void measureGrowth(GameTestHelper h,RoomAir service,CompoundTag baseline) throws Exception {
        int before=encodedSize(baseline);
        var origin=h.absolutePos(new BlockPos(2,2,2));
        var pos=new BlockPos((origin.getX()&~15)+8,h.getLevel().getMaxBuildHeight()-2,(origin.getZ()&~15)+8);
        h.getLevel().setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        service.sample(pos,h.getLevel().getGameTime());
        int one=encodedSize(service.save(new CompoundTag(),h.getLevel().registryAccess()));
        for(int n=0;n<10000;n++) service.sample(pos,h.getLevel().getGameTime());
        int repeated=encodedSize(service.save(new CompoundTag(),h.getLevel().registryAccess()));
        h.assertTrue(one==repeated,"同じ観測を繰り返しても保存量を増やさない");
        var times=new java.util.ArrayList<Long>();
        try(var profile=new RoomSearchProfile("outdoor")) {
            for(int y=0;y<4;y++) for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
                var target=new BlockPos((origin.getX()&~15)+x,h.getLevel().getMaxBuildHeight()-2-y,(origin.getZ()&~15)+z);
                h.getLevel().setBlockAndUpdate(target,Blocks.AIR.defaultBlockState());
                times.add(profile.measure(()->{
                    var result=service.sample(target,h.getLevel().getGameTime());
                    h.assertTrue(result.status()==RoomAir.Status.OUTDOOR,"実露天の1024位置を観測");
                }));
            }
        }
        var saved=service.save(new CompoundTag(),h.getLevel().registryAccess());
        var bytes=new java.io.ByteArrayOutputStream();net.minecraft.nbt.NbtIo.writeCompressed(saved,bytes);
        long loadStart=System.nanoTime();restore(h,saved);double loadMs=(System.nanoTime()-loadStart)/1e6;
        times.sort(Long::compare);
        var result="sources=40 inputs=3456000 baseline_bytes="+before+" one_outdoor_bytes="+one
                +" repeated_10000_bytes="+repeated+" outdoors=1024 raw_bytes="+encodedSize(saved)
                +" gzip_bytes="+bytes.size()+" sample_p95_ms="+times.get((int)Math.ceil(times.size()*.95)-1)/1e6
                +" sample_max_ms="+times.getLast()/1e6+" load_ms="+loadMs;
        java.nio.file.Files.writeString(java.nio.file.Path.of("room-outdoor-growth.txt"),result);
        com.mojang.logging.LogUtils.getLogger().info("M3-146 outdoor history {}",result);
    }
    private static int encodedSize(CompoundTag tag) throws java.io.IOException {
        var bytes=new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.write(tag,new java.io.DataOutputStream(bytes));return bytes.size();
    }
}
