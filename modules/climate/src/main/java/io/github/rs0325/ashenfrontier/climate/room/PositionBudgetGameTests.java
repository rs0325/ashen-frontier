package io.github.rs0325.ashenfrontier.climate.room;

import io.github.rs0325.ashenfrontier.climate.AshenFrontierClimate;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentRetention;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PositionBudgetGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="position_budget_135",timeoutTicks=400)
    public static void generalEvictionProtectsPhysicalAndUnloadedConsumers(GameTestHelper h) throws Exception {
        var level=h.getLevel();var original=RoomAir.get(level);long now=level.getGameTime();
        var empty=new CompoundTag();empty.putInt("format",10);empty.put("retention",RoomRetentionCodec.save(new RoomRetention()));
        var service=RoomAir.load(level,empty);level.getDataStorage().set(RoomAir.ID,service);
        var air=new RoomPhysics.Air(293.15,101325,0,.00042,.2095,2);
        service.outside((l,p,a,b)->java.util.Optional.of(List.of(new RoomAir.Boundary(a,b,air))));
        int releasedLate=0,full=0;
        long start=System.nanoTime();
        try {
            for(int n=0;n<32;n++) {
                var pos=h.absolutePos(new BlockPos(48+n,0,0)).atY(level.getMaxBuildHeight()-1);
                level.getChunkAt(pos);
                var owner=new UUID(135,n+1);
                EnvironmentRetention.request(level,owner,1,pos,now,now,0,EnvironmentRetention.Action.RETAIN).orElseThrow();
                service.respiration(owner,1,pos,now,now,1e-6,1e-6,1);
                EnvironmentRetention.request(level,owner,1,pos,now,now,1,EnvironmentRetention.Action.END).orElseThrow();
                EnvironmentRetention.request(level,owner,1,pos,now,now,1,EnvironmentRetention.Action.COMMIT).orElseThrow();
                h.assertTrue(EnvironmentRetention.request(level,owner,1,pos,now,now,1,EnvironmentRetention.Action.RELEASE).orElseThrow(),"精算した消費側だけ解放");
                service.respiration(owner,2,pos,now,now,1e-6,1e-6,1);releasedLate++;
            }
            var protectedPos=h.absolutePos(new BlockPos(90,0,0)).atY(level.getMaxBuildHeight()-1);level.getChunkAt(protectedPos);
            long registrationStart=System.nanoTime();
            for(int n=0;n<16385;n++)
                EnvironmentRetention.request(level,new UUID(136,n+1),1,protectedPos,now,now,0,EnvironmentRetention.Action.RETAIN).orElseThrow();
            double registrationMs=(System.nanoTime()-registrationStart)/1e6;
            h.assertTrue(service.retentionUsage().owners()==16385,"注意目安を超えても受付");
            var center=h.absolutePos(new BlockPos(1,2,1));
            for(int x=-2;x<=2;x++)for(int y=-1;y<=3;y++)for(int z=-2;z<=2;z++)
                level.setBlock(center.offset(x,y,z),(x==-2||x==2||z==-2||z==2||y==-1||y==3
                        ?net.minecraft.world.level.block.Blocks.STONE:net.minecraft.world.level.block.Blocks.AIR).defaultBlockState(),3);
            service.sample(center,now);
            for(int retry=0;!service.sample(center,now).confirmed()&&retry<1000;retry++)service.work();
            var baseline=service.balance().orElseThrow();
            double expectedHeat=baseline.retained().heatJ()+baseline.exchange().heatJ()+1234;
            double expectedCo=baseline.retained().coMol()+baseline.exchange().coMol();
            double expectedCo2=baseline.retained().co2Mol()+baseline.exchange().co2Mol()+.02;
            double expectedOxygen=baseline.retained().oxygenMol()+baseline.exchange().oxygenMol()-.01;
            service.respiration(new UUID(135,999999),1,center,now,now,.01,.02,1234);
            for(int n=0;n<100000;n++) {
                var pos=h.absolutePos(new BlockPos(100+n%40,0,100+n/40%40)).atY(level.getMaxBuildHeight()-1-n/1600);
                level.getChunkAt(pos);
                var sample=service.sample(pos,now);
                for(int retry=0;!sample.confirmed()&&retry<256;retry++) { service.work();sample=service.sample(pos,now);full++; }
                h.assertTrue(sample.confirmed(),"一般枠満杯でも整理後に観測を再開");
                var usage=service.retentionUsage();
                h.assertTrue(usage.generalPositions()<=4096&&usage.references()<=16384&&usage.encodedBytes()<=8388608,"三つの一般予算");
            }
            var balance=service.balance().orElseThrow();
            h.assertTrue(Math.abs(balance.retained().heatJ()+balance.exchange().heatJ()-expectedHeat)<1e-6
                    &&Math.abs(balance.retained().coMol()+balance.exchange().coMol()-expectedCo)<1e-9
                    &&Math.abs(balance.retained().co2Mol()+balance.exchange().co2Mol()-expectedCo2)<1e-9
                    &&Math.abs(balance.retained().oxygenMol()+balance.exchange().oxygenMol()-expectedOxygen)<1e-9,
                    "一般整理を挟んでも独立した全J/molの入力総量を維持");
            var bytes=new ByteArrayOutputStream();
            var saved=service.save(new CompoundTag(),level.registryAccess());
            NbtIo.writeCompressed(saved,bytes);
            var raw=new ByteArrayOutputStream();NbtIo.write(saved,new java.io.DataOutputStream(raw));
            System.gc();
            long heapBeforeLoad=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
            long loadStart=System.nanoTime();
            var restored=RoomAir.load(level,saved);restored.outside((l,p,a,b)->java.util.Optional.of(List.of(new RoomAir.Boundary(a,b,air))));
            double loadMs=(System.nanoTime()-loadStart)/1e6;
            System.gc();
            long retainedBytes=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()-heapBeforeLoad;
            java.lang.ref.Reference.reachabilityFence(service);java.lang.ref.Reference.reachabilityFence(saved);
            com.mojang.logging.LogUtils.getLogger().info("M3-135 registered load owners=16385 raw={} gzip={} retained_after_gc={} registration_ms={}",
                    raw.size(),bytes.size(),retainedBytes,registrationMs);
            level.getServer().getWorldData().overworldData().setGameTime(now+10*24L*24000);
            h.assertTrue(restored.sample(protectedPos,now).confirmed(),"10年未ロード相当でも既知の登録を復元");
            h.assertTrue(restored.retentionUsage().owners()==16385,"復元で登録を失効させない");
            h.assertTrue(full>=32&&releasedLate>=32,"満杯と解放後遅延の実効下限");
            com.mojang.logging.LogUtils.getLogger().info("M3-135 budget observations=100000 registrations=16385 full={} released_late={} general={} references={} encoded={} gzip={} load_ms={} total_ms={}",
                    full,releasedLate,restored.retentionUsage().generalPositions(),restored.retentionUsage().references(),
                    restored.retentionUsage().encodedBytes(),bytes.size(),loadMs,(System.nanoTime()-start)/1e6);
        } finally {
            level.getDataStorage().set(RoomAir.ID,original);
            level.getServer().getWorldData().overworldData().setGameTime(now);
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="position_budget_135",timeoutTicks=400)
    public static void sixteenThousandReferencesUseActualNbt(GameTestHelper h) throws Exception {
        var past=new RoomPast();var book=new RoomRetention();int id=1;
        long heapBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        long start=System.nanoTime();
        for(int n=0;n<4096;n++) {
            long cell=new BlockPos(n,100,135).asLong();
            for(int version=0;version<4;version++) {
                if(version>0)past.end(past.outdoor(cell,version-1),version);
                past.observeOutdoor(id++,version,cell,new long[0],true);
            }
            h.assertTrue(book.observe(cell,3,past.references(cell),past.outdoorBytes(cell)),"参照上限内で受付");
        }
        h.assertTrue(book.references==16384&&book.general.size()==4096,"位置と参照の実効上限");
        var saved=new CompoundTag();saved.putInt("format",10);past.save(saved);
        saved.put("retention",RoomRetentionCodec.save(book));
        var raw=new ByteArrayOutputStream();
        NbtIo.write(saved,new java.io.DataOutputStream(raw));
        var compressed=new ByteArrayOutputStream();NbtIo.writeCompressed(saved,compressed);
        System.gc();
        long retainedBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        long loadStart=System.nanoTime();var restored=RoomAir.load(h.getLevel(),saved);
        double loadMs=(System.nanoTime()-loadStart)/1e6;
        System.gc();
        long retainedBytes=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()-retainedBefore;
        java.lang.ref.Reference.reachabilityFence(past);java.lang.ref.Reference.reachabilityFence(book);
        java.lang.ref.Reference.reachabilityFence(saved);
        h.assertTrue(restored.retentionUsage().references()==16384,"参照を保存から再計数");
        long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()-heapBefore;
        com.mojang.logging.LogUtils.getLogger().info("M3-135 references positions=4096 refs=16384 encoded={} raw={} gzip={} load_ms={} heap_delta={} retained_after_gc={} total_ms={}",
                book.bytes+56,raw.size(),compressed.size(),loadMs,heap,retainedBytes,(System.nanoTime()-start)/1e6);
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="position_budget_135",timeoutTicks=400)
    public static void cancelledGeneralPreparationsReleaseTheirReservations(GameTestHelper h) throws Exception {
        var level=h.getLevel();var original=RoomAir.get(level);
        var empty=new CompoundTag();empty.putInt("format",10);empty.put("retention",RoomRetentionCodec.save(new RoomRetention()));
        var service=RoomAir.load(level,empty);level.getDataStorage().set(RoomAir.ID,service);
        var centers=new java.util.ArrayList<BlockPos>();
        try {
            for(int n=0;n<32;n++) {
                var center=h.absolutePos(new BlockPos(500+n*6,2,500));centers.add(center);
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=3;y++)
                    level.setBlock(center.offset(x,y,z),(x==-2||x==2||z==-2||z==2||y==-1||y==3
                            ?net.minecraft.world.level.block.Blocks.STONE:net.minecraft.world.level.block.Blocks.AIR).defaultBlockState(),3);
                service.sample(center,level.getGameTime());
            }
            service.work();
            var usage=service.retentionUsage();
            h.assertTrue(usage.generalPositions()==32&&usage.references()==32&&usage.encodedBytes()>32*220,
                    "一般の準備仕事も三つの予約へ計上: "+usage);
            for(var center:centers)service.changed(center);
            usage=service.retentionUsage();
            h.assertTrue(usage.generalPositions()==0&&usage.references()==0&&usage.encodedBytes()==0
                    &&usage.physicalPositions()==0,"取消で予約を解放し、物理参照へ昇格させない");
            var owner=new UUID(135,777);var pending=centers.getFirst();long now=level.getGameTime();
            EnvironmentRetention.request(level,owner,1,pending,now,now,0,EnvironmentRetention.Action.RETAIN).orElseThrow();
            EnvironmentRetention.request(level,owner,1,pending,now,now,0,EnvironmentRetention.Action.END).orElseThrow();
            EnvironmentRetention.request(level,owner,1,pending,now,now,0,EnvironmentRetention.Action.COMMIT).orElseThrow();
            h.assertTrue(EnvironmentRetention.request(level,owner,1,pending,now,now,0,EnvironmentRetention.Action.RELEASE).orElseThrow(),
                    "未観測の設備を終了したら準備仕事も解放");
            service.work();
            var changes=RoomAir.class.getDeclaredField("changedChunks");changes.setAccessible(true);
            h.assertTrue(((java.util.Map<?,?>)changes.get(service)).isEmpty(),"取消後に準備用の変更索引を残さない");
            var saved=service.save(new CompoundTag(),level.registryAccess());
            h.assertTrue(saved.getList("past_cells",net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty()
                    &&saved.getList("past_models",net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty(),"取消候補の残骸を保存しない");
        } finally { level.getDataStorage().set(RoomAir.ID,original); }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="position_budget_135",timeoutTicks=400)
    public static void releasedZeroInputPositionIsReclaimedEvenWhenGeneralIsFull(GameTestHelper h) {
        var level=h.getLevel();var original=RoomAir.get(level);long now=level.getGameTime();
        var empty=new CompoundTag();empty.putInt("format",10);empty.put("retention",RoomRetentionCodec.save(new RoomRetention()));
        var service=RoomAir.load(level,empty);level.getDataStorage().set(RoomAir.ID,service);
        var air=new RoomPhysics.Air(293.15,101325,0,.00042,.2095,0);
        service.outside((l,p,a,b)->java.util.Optional.of(List.of(new RoomAir.Boundary(a,b,air))));
        try {
            for(int n=0;n<4096;n++) {
                var p=h.absolutePos(new BlockPos(100+n%64,0,100+n/64)).atY(level.getMaxBuildHeight()-1);
                level.getChunkAt(p);service.sample(p,now);
            }
            var pos=h.absolutePos(new BlockPos(90,0,90)).atY(level.getMaxBuildHeight()-1);level.getChunkAt(pos);
            var owner=new UUID(135,54321);
            EnvironmentRetention.request(level,owner,1,pos,now,now,0,EnvironmentRetention.Action.RETAIN).orElseThrow();
            EnvironmentRetention.request(level,owner,1,pos,now,now,0,EnvironmentRetention.Action.END).orElseThrow();
            EnvironmentRetention.request(level,owner,1,pos,now,now,0,EnvironmentRetention.Action.COMMIT).orElseThrow();
            h.assertTrue(!EnvironmentRetention.request(level,owner,1,pos,now,now,0,EnvironmentRetention.Action.RELEASE).orElseThrow(),
                    "監視索引を整理する間は保持を解放しない");
            var saved=service.save(new CompoundTag(),level.registryAccess());
            service=RoomAir.load(level,saved);
            service.outside((l,p,a,b)->java.util.Optional.of(List.of(new RoomAir.Boundary(a,b,air))));
            level.getDataStorage().set(RoomAir.ID,service);
            level.getServer().getWorldData().overworldData().setGameTime(now+1);
            for(int n=0;n<128&&service.retentionUsage().owners()!=0;n++)service.work();
            h.assertTrue(service.retentionUsage().owners()==0&&service.retentionUsage().generalPositions()<=4096,
                    "保存復元と一般枠満杯でも解放整理が完了");
            h.assertTrue(service.sample(pos,now).status()==RoomAir.Status.HISTORY_MISSING,
                    "解放された非物理履歴を予算外で残さない");
            h.assertTrue(EnvironmentRetention.request(level,owner,1,pos,now,now,0,EnvironmentRetention.Action.RELEASE).orElseThrow(),
                    "整理完了後の解放再送は無作用");
        } finally {
            level.getDataStorage().set(RoomAir.ID,original);
            level.getServer().getWorldData().overworldData().setGameTime(now);
        }
        h.succeed();
    }

}
