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
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400,batch="exposure_frames126")
    @SuppressWarnings("unchecked")
    public static void computedWeatherFramesMatchOriginalJobRows(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            f.ready(h,0,0);f.clock.setGameTime(1800);f.ready(h,0,1800);
            var provider=EnvironmentProvider.get(f.level);
            var field=EnvironmentProvider.class.getDeclaredField("checkpoints");field.setAccessible(true);
            var frames=(java.util.TreeMap<Long,WeatherFrame>)field.get(provider);var saved=new java.util.TreeMap<>(frames);
            var observations=EnvironmentProvider.class.getDeclaredField("observations");observations.setAccessible(true);
            var boundaries=EnvironmentProvider.class.getDeclaredField("boundaries");boundaries.setAccessible(true);
            var frameRows=EnvironmentProvider.class.getDeclaredField("frameRows");frameRows.setAccessible(true);
            int compared=0;
            for(int n=0;n<64;n++) {
                long from=n*37L%1200,to=from+1+(n*53L%600);frames.putAll(saved);
                var warm=provider.exposure(f.pos,from,to).orElseThrow();
                frames.clear();((java.util.Map<?,?>)observations.get(provider)).clear();((java.util.Map<?,?>)boundaries.get(provider)).clear();
                ((java.util.Map<?,?>)frameRows.get(provider)).clear();
                var cold=provider.exposure(f.pos,from,to).orElseThrow();
                h.assertTrue(warm.equals(cold),"計算済み全域フレームの組立てと既存ジョブの区間列が同じ case="+n);compared++;
            }
            h.assertTrue(compared==64,"一般経路との比較を実際に実行");
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400,batch="exposure_frames126")
    public static void completedOutdoorRowsReuseAndInvalidateOnDayLengthChange(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            f.ready(h,0,0);f.clock.setGameTime(20);f.ready(h,0,20);
            var provider=EnvironmentProvider.get(f.level);
            var data=EnvironmentProvider.class.getDeclaredMethod("data",BlockPos.class,long.class,long.class);data.setAccessible(true);
            var field=EnvironmentProvider.class.getDeclaredField("frameRows");field.setAccessible(true);
            var cache=(java.util.Map<?,?>)field.get(provider);
            var before=(java.util.Optional<?>)data.invoke(provider,f.pos.above(),20L,20L);
            var again=(java.util.Optional<?>)data.invoke(provider,f.pos.above(),20L,20L);
            h.assertTrue(before.orElseThrow()==again.orElseThrow(),"同じ確定屋外列を再構築しない");
            cache.clear();
            h.assertTrue(before.equals(data.invoke(provider,f.pos.above(),20L,20L)),"再利用しない屋外列と完全一致");
            h.assertTrue(ClimateClock.get(f.level).changeDayLength(20,12000),"同tickで日長を変更");
            var clock=ClimateClock.get(f.level);WeatherStore.get(f.level).clock(clock.history(),clock.revision(),20);
            f.ready(h,20,20);
            var changed=data.invoke(provider,f.pos.above(),20L,20L);
            h.assertTrue(!before.equals(changed),"変更前の時計の列を再利用しない");
            cache.clear();
            h.assertTrue(changed.equals(data.invoke(provider,f.pos.above(),20L,20L)),"変更後も非再利用の列と完全一致");
            boolean rejected=false;
            try {data.invoke(provider,f.pos.above(),21L,21L);}
            catch(java.lang.reflect.InvocationTargetException e) {rejected=e.getCause() instanceof IllegalArgumentException;}
            h.assertTrue(rejected,"未来の要求をキャッシュで受理しない");
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400)
    public static void addingPositionKeepsCompletedOutdoorRows(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            f.ready(h,0,0);f.clock.setGameTime(1200);var first=f.ready(h,0,1200);
            var other=f.pos.offset(1,0,0);
            EnvironmentQuery.query(f.level,other,0,1200);
            var retained=EnvironmentQuery.samples(f.level,f.pos,0,1200);
            h.assertTrue(retained.isPresent()&&retained.get().equals(first),"新しい位置の再生待ちでも完成した列を保持し、日射・降水を二重加算しない");
            java.util.Optional<List<EnvironmentSample>> result=java.util.Optional.empty();
            for(int n=0;n<20000&&result.isEmpty();n++) {
                result=EnvironmentQuery.samples(f.level,other,0,1200);
                EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);RoomAir.get(f.level).work();
            }
            h.assertTrue(result.isPresent(),"新しい位置も有限の仕事で完成");
            h.assertTrue(EnvironmentQuery.samples(f.level,f.pos,0,1200).orElseThrow().equals(first),"全位置の完成後も既存列が完全一致");
            h.assertTrue(EnvironmentQuery.samples(f.level,other,0,1200).orElseThrow().equals(result.orElseThrow()),"新しい列に重複・欠落がない");
            var provider=EnvironmentProvider.get(f.level);
            var jobs=EnvironmentProvider.class.getDeclaredField("jobs");jobs.setAccessible(true);
            var job=((java.util.Map<?,?>)jobs.get(provider)).values().iterator().next();
            var field=job.getClass().getDeclaredField("cursor");field.setAccessible(true);var cursor=(WeatherReplay)field.get(job);
            var row=EnvironmentProvider.class.getDeclaredMethod("row",WeatherReplay.class,BlockPos.class,long.class,long.class);row.setAccessible(true);
            var observations=EnvironmentProvider.class.getDeclaredField("observations");observations.setAccessible(true);
            long frame=cursor.frame.tick();row.invoke(provider,cursor,f.pos,frame,frame+20);
            var reused=row.invoke(provider,cursor,f.pos,frame+1,frame+19);
            ((java.util.Map<?,?>)observations.get(provider)).clear();
            h.assertTrue(reused.equals(row.invoke(provider,cursor,f.pos,frame+1,frame+19)),"確定フレームの屋外値だけを共有し、区間とGAME時刻は要求ごとに保持");
        }
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400)
    public static void reusedInputsMatchColdQueriesAndSeeSameTickHeat(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            var rooms=RoomAir.get(f.level);var source=java.util.UUID.randomUUID();
            var regions=RoomAir.class.getDeclaredField("regions");regions.setAccessible(true);
            var reset=RoomAir.class.getDeclaredMethod("clearSamples");reset.setAccessible(true);
            var outdoor=EnvironmentProvider.class.getDeclaredField("boundaries");outdoor.setAccessible(true);
            var observations=EnvironmentProvider.class.getDeclaredField("observations");observations.setAccessible(true);
            int compared=0,changed=0;
            for(int n=0;n<64;n++) {
                long tick=n*20L;f.clock.setGameTime(tick);f.ready(h,tick,tick);
                if(n==16) { h.assertTrue(ClimateClock.get(f.level).changeDayLength(tick,12000),"同tickの日長変更");f.ready(h,tick,tick); }
                if(n==32) {
                    var roof=f.pos.offset(1,3,1);f.level.setBlock(roof,Blocks.STONE.defaultBlockState(),3);rooms.changed(roof);f.ready(h,tick,tick);
                }
                var before=rooms.sample(f.pos.above(),tick).state().orElseThrow();
                rooms.respiration(source,n+1,f.pos.above(),tick,tick,.001,.002,1000);
                var warm=EnvironmentProvider.get(f.level).exposure(f.pos,tick,tick).orElseThrow();
                if(warm.sample().temperatureK()>before.temperatureK())changed++;
                for(var region:(java.util.List<?>)regions.get(rooms)) {
                    var version=region.getClass().getDeclaredField("inputRevision");version.setAccessible(true);version.setLong(region,-1);
                }
                reset.invoke(rooms);
                ((java.util.Map<?,?>)outdoor.get(EnvironmentProvider.get(f.level))).clear();
                ((java.util.Map<?,?>)observations.get(EnvironmentProvider.get(f.level))).clear();
                var cold=EnvironmentProvider.get(f.level).exposure(f.pos,tick,tick).orElseThrow();
                h.assertTrue(warm.equals(cold),"再利用と再構築で温度・空気・日射・降水・精度が完全一致");compared++;
                for(long past:new long[]{tick/2,Math.max(0,tick-20),tick}) {
                    var first=rooms.sample(f.pos.above(),past);rooms.sample(f.pos.above(),tick);
                    var reused=rooms.sample(f.pos.above(),past);reset.invoke(rooms);
                    h.assertTrue(first.equals(reused)&&reused.equals(rooms.sample(f.pos.above(),past)),"過去と現在を交互に照会しても再利用の有無で一致");
                }
            }
            h.assertTrue(compared==64&&changed==64,"同tickの新しい熱を全64回反映");
        }
        h.succeed();
    }
    public static final class Fixture implements AutoCloseable {
        public final ServerLevel level;
        final PrimaryLevelData clock;
        final LevelStorageSource.LevelStorageAccess access;
        public final BlockPos pos=new BlockPos(-8500,70,-8192);
        public Fixture(GameTestHelper h) throws Exception {
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
        public List<EnvironmentSample> ready(GameTestHelper h,long from,long to) {
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
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400)
    public static void physiologyExposureSharesIntervalsWithoutCachingLaterHeat(GameTestHelper h) throws Exception {
        try(var f=new Fixture(h)) {
            f.ready(h,0,0);
            f.clock.setGameTime(600);
            var clock=ClimateClock.get(f.level);clock.changeDayLength(600,12000);
            WeatherStore.get(f.level).clock(clock.history(),clock.revision(),600);
            f.clock.setGameTime(1200);
            var rooms=RoomAir.get(f.level);var provider=EnvironmentProvider.get(f.level);
            var rows=f.ready(h,0,1200);
            h.assertTrue(rows.stream().anyMatch(row->row.startTick()==600),"日長と気象の境界を維持");
            for(var row:rows) {
                var value=provider.exposure(f.pos,row.startTick(),1200).orElseThrow();
                var local=rooms.sample(f.pos.above(),row.startTick());
                h.assertTrue(value.sample().equals(row),"C01と生理へ同じ確定区間を渡す");
                h.assertTrue(value.oxygenPa()==local.state().orElseThrow().oxygen()*row.pressurePa()
                        &&value.accuracy()==local.accuracy()&&value.windMps()==.1
                        &&value.co()==local.state().orElseThrow().co()&&value.co2()==local.state().orElseThrow().co2(),"同じ始点の空気と近似区分");
            }
            double before=provider.exposure(f.pos,1200,1200).orElseThrow().sample().temperatureK();
            rooms.receive(new io.github.rs0325.ashenfrontier.core.environment.CombustionEvent(f.level,f.pos,
                    1200,1200,AshenFrontierCore.id("dry_wood"),0,0,-1_000_000,
                    new java.util.UUID(124,135),1));
            f.ready(h,1200,1200);
            var after=provider.exposure(f.pos,1200,1200).orElseThrow();
            h.assertTrue(after.sample().temperatureK()>before,"同tickの後続放熱を古い照会結果で隠さない");
        }
        h.succeed();
    }

    private static final class CoolingOracle {
        final Fixture fixture;
        double expected,before;
        long sequence;
        int notices,intervals,headComparisons;
        boolean enabled;
        CoolingOracle(Fixture fixture) { this.fixture=fixture; }
        void input(EnvironmentQuery query) {
            if(!enabled||query.level()!=fixture.level||!query.position().equals(fixture.pos)||query.startTick()==query.endTick()||query.values().isEmpty())return;
            var sample=query.values().get().getFirst();
            if(query.firstOnly()) {
                enabled=false;
                try {
                    var full=EnvironmentQuery.samples(fixture.level,query.position(),query.startTick(),query.endTick());
                    if(full.isPresent()) {
                        if(!sample.equals(full.get().getFirst()))throw new IllegalStateException("実設備の先頭が全区間と一致しません");
                        headComparisons++;
                    }
                } finally { enabled=true; }
            }
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
                h.assertTrue(longRestores>=32&&sameTime>=64&&changes>=32&&oracle.headComparisons>=32,"実効回数の下限");
                com.mojang.logging.LogUtils.getLogger().info("M3-135 real cooling seed=13520261126 long_restore={} same_time={} day_changes={} intervals={} notices={} head_comparisons={} ms={}",
                        longRestores,sameTime,changes,oracle.intervals,oracle.notices,oracle.headComparisons,(System.nanoTime()-started)/1e6);
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
            EnvironmentHeadGameTests.compare(h,f,0,1);
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
