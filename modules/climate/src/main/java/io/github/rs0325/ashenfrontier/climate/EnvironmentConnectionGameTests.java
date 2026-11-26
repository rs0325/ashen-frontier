package io.github.rs0325.ashenfrontier.climate;

import com.mojang.serialization.Lifecycle;
import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import io.github.rs0325.ashenfrontier.world.FixedMapChunkGenerator;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EnvironmentConnectionGameTests {
    static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final PrimaryLevelData clock;
        final LevelStorageSource.LevelStorageAccess access;
        final BlockPos pos=new BlockPos(-8500,70,-8192);
        Fixture(GameTestHelper h) throws Exception {
            var server=h.getLevel().getServer();
            var preset=ResourceKey.create(Registries.WORLD_PRESET,AshenFrontierCore.id("fixed_map"));
            var generator=(FixedMapChunkGenerator)h.getLevel().registryAccess().registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(preset).value().createWorldDimensions().overworld();
            var type=h.getLevel().registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                    .getHolderOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE,AshenFrontierCore.id("fixed_map")));
            clock=new PrimaryLevelData(server.getWorldData().getLevelSettings(),new WorldOptions(47,false,false),
                    PrimaryLevelData.SpecialWorldProperty.NONE,Lifecycle.stable());
            clock.setGameTime(0);
            access=LevelStorageSource.createDefault(Files.createTempDirectory(Path.of("."),"environment-135-")).createAccess("fixture");
            level=new ServerLevel(server,Util.backgroundExecutor(),access,clock,Level.OVERWORLD,new LevelStem(type,generator),
                    LoggerChunkProgressListener.createCompleted(),false,47,List.of(),false,null);
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=3;y++)
                level.setBlock(pos.offset(x,y,z),(x==-2||x==2||z==-2||z==2||y==-1||y==3?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
            for(int y=pos.getY()+2;y<level.getMaxBuildHeight();y++)
                level.setBlock(new BlockPos(pos.getX()+1,y,pos.getZ()+1),Blocks.AIR.defaultBlockState(),3);
        }
        List<EnvironmentSample> ready(GameTestHelper h,long from,long to) {
            for(int n=0;n<20000;n++) {
                var result=EnvironmentQuery.query(level,pos,from,to);
                if(result.values().isPresent())return result.values().get();
                EnvironmentProvider.get(level).work(Long.MAX_VALUE,8192);
                RoomAir.get(level).work();
            }
            var result=EnvironmentQuery.query(level,pos,from,to);
            throw new IllegalStateException("実環境が応答しません: "+result.reason()+" "+RoomAir.get(level).sample(pos.above(),from));
        }
        public void close() throws Exception {
            EnvironmentProvider.detach(level);
            level.getDataStorage().save();IOUtilities.waitUntilIOWorkerComplete();
            level.close();access.close();
        }
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400)
    public static void realWeatherAndRoomBoundaries(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            var initial=f.ready(h,0,0).getFirst();
            h.assertTrue(initial.covered()&&initial.ventilated(),"実形状の覆いと煙突を供給");
            h.assertTrue(initial.temperatureK()>0&&initial.pressurePa()>0,"実気候のSI値");
            f.clock.setGameTime(1200);
            var rows=f.ready(h,0,1200);
            h.assertTrue(rows.getFirst().startTick()==0&&rows.getLast().endTick()==1200,"全要求の境界を供給");
            h.assertTrue(rows.size()>=2,"気象境界を平均化しない");
            var cut=f.pos.offset(1,3,1);f.level.setBlock(cut,Blocks.STONE.defaultBlockState(),3);
            RoomAir.get(f.level).changed(cut);
            var closed=f.ready(h,1200,1200).getFirst();
            h.assertTrue(closed.covered()&&!closed.ventilated(),"煙突閉塞を安全な換気へ補完しない");
            h.assertTrue(EnvironmentQuery.samples(h.getLevel(),f.pos,0,0).isEmpty(),"未対応の生成器に本番の固定環境を置かない");
            com.mojang.logging.LogUtils.getLogger().info("M3-135 real provider K={} Pa={} RH={} intervals={}",
                    initial.temperatureK(),initial.pressurePa(),initial.relativeHumidity(),rows.size());
        }
        h.succeed();
    }
    private static final class CoolingOracle {
        final Fixture fixture;
        double expected,before;
        long sequence;
        int notices,intervals;
        boolean enabled;
        CoolingOracle(Fixture fixture) { this.fixture=fixture; }
        void input(EnvironmentQuery query) {
            if(!enabled||query.level()!=fixture.level||!query.position().equals(fixture.pos)||query.startTick()==query.endTick()||query.values().isEmpty())return;
            var sample=query.values().get().getFirst();
            before=expected;
            double g=(sample.endTick()-sample.startTick())*86400.0/sample.ticksPerDay();
            expected=sample.temperatureK()+(expected-sample.temperatureK())*Math.exp(-g/7200);
            intervals++;
        }
        void emission(io.github.rs0325.ashenfrontier.core.environment.CombustionEvent event) {
            if(!enabled||event.level()!=fixture.level)return;
            if(event.burnedKg()!=0||event.energyJoules()!=0||event.sequence()<=sequence)
                throw new IllegalStateException("未ロードの冷却で燃焼/重複通知が生じた");
            double error=Math.abs(event.retainedHeatJoules()-500*(expected-before));
            if(error>1e-6)throw new IllegalStateException("冷却の独立収支が不一致: "+error);
            sequence=event.sequence();notices++;
        }
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="saved_restores_135",timeoutTicks=400)
    public static void generatedUnloadedCoolingWithRealWeather(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            var registry=f.level.registryAccess();
            var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("hearth"));
            f.level.setBlock(f.pos,block.defaultBlockState(),3);
            var entity=f.level.getBlockEntity(f.pos);
            entity.onLoad();var placed=entity.getClass().getDeclaredMethod("placed");placed.setAccessible(true);placed.invoke(entity);
            f.ready(h,0,0);f.clock.setGameTime(1);f.ready(h,1,1);
            var oracle=new CoolingOracle(f);
            java.util.function.Consumer<EnvironmentQuery> queries=oracle::input;
            java.util.function.Consumer<io.github.rs0325.ashenfrontier.core.environment.CombustionEvent> emissions=oracle::emission;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,queries);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,emissions);
            int longRestores=0,sameTime=0,changes=0;
            long started=System.nanoTime();
            try {
                var random=new java.util.Random(13520261126L);
                for(int column=0;column<32;column++) {
                    long from=f.level.getGameTime(),day=column%2==0?12000:24000;
                    if(from>0) {
                        var clock=ClimateClock.get(f.level);clock.changeDayLength(from,day);
                        WeatherStore.get(f.level).clock(clock.history(),clock.revision(),from);changes++;
                    }
                    var tag=entity.saveWithFullMetadata(registry);
                    oracle.expected=900+random.nextDouble()*200;
                    tag.putDouble("temperature_k",oracle.expected);tag.putDouble("fuel_j",16000000);
                    tag.putDouble("heating_value",16000000);tag.putString("fuel_profile","ashen_frontier:dry_wood");
                    tag.putLong("last_tick",from);tag.putLong("cooling_target",-1);
                    entity.loadWithComponents(tag,registry);
                    var saved=entity.saveWithFullMetadata(registry);
                    f.level.getChunkAt(f.pos).removeBlockEntity(f.pos);
                    long end=from+24*day*(column==0?10:1);
                    f.clock.setGameTime(end);
                    entity=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(f.pos,block.defaultBlockState(),saved,registry);
                    f.level.setBlockEntity(entity);entity.onLoad();longRestores++;
                    boolean completed=false;
                    for(int work=0;work<30000;work++) {
                        oracle.enabled=true;
                        try { entity.getClass().getMethod("work").invoke(entity); } finally { oracle.enabled=false; }
                        var actual=entity.saveWithoutMetadata(registry);
                        h.assertTrue(Math.abs(actual.getDouble("temperature_k")-oracle.expected)<1e-8,"各段階の独立した温度期待値");
                        h.assertTrue(actual.getDouble("fuel_j")==16000000,"未ロードでREAL燃焼を進めない");
                        if(actual.getLong("last_tick")==end) { completed=true;break; }
                        EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);
                        RoomAir.get(f.level).work();
                    }
                    h.assertTrue(completed,"長期未ロードを永久保留しない col="+column+" last="+entity.saveWithoutMetadata(registry).getLong("last_tick")+" end="+end+" target="+entity.saveWithoutMetadata(registry).getLong("cooling_target"));
                    if(column%8==0)com.mojang.logging.LogUtils.getLogger().info("M3-135 cooling column={} through={} elapsed_ms={}",column,end,(System.nanoTime()-started)/1e6);
                    for(int n=0;n<2;n++) {
                        int count=oracle.notices;
                        oracle.enabled=true;try { entity.getClass().getMethod("work").invoke(entity); } finally { oracle.enabled=false; }
                        h.assertTrue(count==oracle.notices,"同時刻の操作で二重放熱しない");sameTime++;
                    }
                }
                h.assertTrue(longRestores>=32&&sameTime>=64&&changes>=32,"実効回数の下限");
                com.mojang.logging.LogUtils.getLogger().info("M3-135 real cooling seed=13520261126 long_restore={} same_time={} day_changes={} intervals={} notices={} ms={}",
                        longRestores,sameTime,changes,oracle.intervals,oracle.notices,(System.nanoTime()-started)/1e6);
            } finally {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(queries);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(emissions);
            }
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="approximate_135",timeoutTicks=400)
    public static void approximateHistoryIsConsumedInsteadOfWaiting(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            f.ready(h,0,0);
            var chimney=f.pos.offset(1,3,1);
            for(int n=1;n<=70;n++) {
                f.clock.setGameTime(n);
                f.level.setBlock(chimney,(n%2==0?Blocks.AIR:Blocks.STONE).defaultBlockState(),3);
                RoomAir.get(f.level).changed(chimney);
                f.ready(h,n,n);
            }
            var sample=RoomAir.get(f.level).sample(f.pos.above(),0);
            h.assertTrue(sample.confirmed()&&sample.accuracy()==RoomAir.Accuracy.APPROXIMATE,"64件超を未知と混同しない");
            h.assertTrue(!f.ready(h,0,1).isEmpty(),"近似の過去もC01へ供給して計算を続ける");
        }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="equipment_retention_135",timeoutTicks=400)
    public static void existingEquipmentRetainsInputUntilReloadPause(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("water_filter"));
            f.level.setBlock(f.pos,block.defaultBlockState(),3);
            var entity=f.level.getBlockEntity(f.pos);entity.onLoad();f.ready(h,0,0);
            var retain=entity.getClass().getDeclaredMethod("retainEnvironment",long.class);retain.setAccessible(true);
            retain.invoke(entity,0L);
            h.assertTrue(RoomAir.get(f.level).retentionUsage().owners()==1,"既存設備も未確定入力を保持");
            var saved=entity.saveWithFullMetadata(f.level.registryAccess());
            f.clock.setGameTime(10*24000L);RoomAir.get(f.level).work();
            h.assertTrue(RoomAir.get(f.level).retentionUsage().owners()==1,"一般期限で評価中の登録を消さない");
            f.level.getChunkAt(f.pos).removeBlockEntity(f.pos);
            var restored=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(f.pos,block.defaultBlockState(),saved,f.level.registryAccess());
            f.level.setBlockEntity(restored);restored.onLoad();
            h.assertTrue(RoomAir.get(f.level).retentionUsage().owners()==0,"未ロードの加工時間は既存どおり停止し、確定点までの保持を解放");
        }
        h.succeed();
    }

}
