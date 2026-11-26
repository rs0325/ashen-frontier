package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.Arrays;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EnvironmentPerformanceGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,batch="performance_135",timeoutTicks=400)
    public static void combinedWeatherRoomsAndFortyStations(GameTestHelper h) throws Exception {
        runWorkload(h,(level,players)->{});h.succeed();
    }
    /** #124も同じ実加工/着火の仕事を実行する。計測/判定は既存のまま。 */
    public static void runWorkload(GameTestHelper h,java.util.function.BiConsumer<net.minecraft.server.level.ServerLevel,
            java.util.List<net.minecraft.server.level.ServerPlayer>> hook) throws Exception {
        try(var f=new EnvironmentConnectionGameTests.Fixture(h)) {
            var rooms=RoomAir.get(f.level);
            var actors=new ArrayList<net.minecraft.world.level.block.entity.BlockEntity>();
            var positions=new ArrayList<BlockPos>();
            var hearth=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("hearth"));
            for(int n=0;n<64;n++) {
                var p=f.pos.offset((n%8)*8,0,(n/8)*8);positions.add(p.above());
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=3;y++)
                    f.level.setBlock(p.offset(x,y,z),(x==-2||x==2||z==-2||z==2||y==-1||y==3?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
                for(int y=p.getY()+2;y<f.level.getMaxBuildHeight();y++)
                    f.level.setBlock(new BlockPos(p.getX()+1,y,p.getZ()+1),Blocks.AIR.defaultBlockState(),3);
                if(n<40) {
                    var block=n<24?hearth:net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("drying_rack"));
                    f.level.setBlock(p,block.defaultBlockState(),3);
                    var entity=f.level.getBlockEntity(p);entity.onLoad();
                    var placed=entity.getClass().getDeclaredMethod("placed");placed.setAccessible(true);placed.invoke(entity);
                    var tag=entity.saveWithFullMetadata(f.level.registryAccess());
                    tag.putDouble("temperature_k",n<4?450:1100);tag.putDouble("fuel_j",n<4?0:64000000);tag.putString("fuel_profile","ashen_frontier:dry_wood");
                    tag.putDouble("heating_value",16000000);tag.putLong("last_tick",0);
                    entity.loadWithComponents(tag,f.level.registryAccess());actors.add(entity);
                }
                rooms.sample(p.above(),0);
            }
            for(int attempt=0;attempt<10000;attempt++) {
                int ready=0;
                for(var p:positions)if(rooms.sample(p,0).confirmed())ready++;
                if(ready==64)break;
                rooms.work();EnvironmentProvider.get(f.level).work(Long.MAX_VALUE,8192);
                h.assertTrue(attempt<9999,"64室の準備を完了");
            }
            var players=new ArrayList<net.minecraft.server.level.ServerPlayer>();
            for(int n=0;n<4;n++) {
                var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(f.level,
                        new com.mojang.authlib.GameProfile(new java.util.UUID(135,400+n),"climate135_"+n));
                player.setPos(f.pos.getX()+n*8,f.pos.getY()+1,f.pos.getZ());
                f.level.addNewPlayer(player);players.add(player);
            }
            var stations=new ArrayList<io.github.rs0325.ashenfrontier.core.recipe.ProcessingStation>();
            for(var player:players)ResearchManager.report(player,AshenFrontierCore.id("primitive_event"),"friction_ignition");
            var setLit=actors.getFirst().getClass().getDeclaredMethod("setLit",boolean.class);setLit.setAccessible(true);
            var igniting=actors.getFirst().getClass().getDeclaredField("igniting");igniting.setAccessible(true);
            var activeThrough=actors.getFirst().getClass().getDeclaredField("workTick");activeThrough.setAccessible(true);
            var drills=new ArrayList<ItemStack>();
            for(int n=0;n<40;n++) {
                var actor=actors.get(n);var player=players.get(n%4);var station=ProcessingEnvironmentGameTests.station(actor);stations.add(station);
                player.setPos(actor.getBlockPos().getX()+.5,actor.getBlockPos().getY()+1,actor.getBlockPos().getZ()+.5);
                if(n>=4) {
                    if(n<24)setLit.invoke(actor,true);
                    station.setInput(player,0,ProcessingEnvironmentGameTests.pottery(n<24?"dried_pot":"green_pot"));
                    ProcessingEnvironmentGameTests.use(actor,player,ItemStack.EMPTY);
                    h.assertTrue(station.active(),"実レシピの加工を開始: "+n);
                }
            }
            for(int n=0;n<4;n++) {
                var actor=actors.get(n);var player=players.get(n);
                player.setPos(actor.getBlockPos().getX()+.5,actor.getBlockPos().getY()+1,actor.getBlockPos().getZ()+.5);
                player.setShiftKeyDown(true);
                ProcessingEnvironmentGameTests.use(actor,player,ProcessingEnvironmentGameTests.item("tinder",64));
                ProcessingEnvironmentGameTests.use(actor,player,ProcessingEnvironmentGameTests.item("dry_branch",64));
                player.setShiftKeyDown(false);
                var drill=ProcessingEnvironmentGameTests.item("fire_drill",1);
                drill.set(ItemProperties.WEAR,ItemProperties.Wear.of(0,64));drills.add(drill);
                ProcessingEnvironmentGameTests.use(actor,player,drill);
                h.assertTrue(igniting.getBoolean(actor),"4人が実操作で着火開始");
            }
            long[] combustion={0};double[] chemical={0};
            java.util.function.Consumer<CombustionEvent> emissions=e->{
                if(e.level()==f.level&&e.energyJoules()>0) { combustion[0]++;chemical[0]+=e.energyJoules(); }
            };
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,emissions);
            long processingTicks=0,ignitionTicks=0,overlapTicks=0,playerOperations=0;
            var work=actors.getFirst().getClass().getDeclaredMethod("scheduledWork");work.setAccessible(true);
            long[] elapsed=new long[2000];long[][] stages=new long[5][2000];int firstActor=0;
            com.mojang.logging.LogUtils.getLogger().info("M3-135 workload start");
            long heapBefore=usedHeap(),gcBefore=gcMillis(),wall=System.nanoTime();
            h.assertTrue(EnvironmentBudget.remaining(f.level)!=Long.MAX_VALUE,"実providerの共有予算が有効");
            var recording=new jdk.jfr.Recording();
            recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
            recording.enable("jdk.GarbageCollection");recording.start();
            try {
                for(int tick=1;tick<=2500;tick++) {
                    f.clock.setGameTime(tick);
                    EnvironmentBudget.beginTick(f.level.getServer());
                    long start=System.nanoTime();
                    hook.accept(f.level,players);
                    NeoForge.EVENT_BUS.post(new ServerTickEvent.Pre(()->true,f.level.getServer()));
                    long afterPre=System.nanoTime();
                    WeatherService.work(f.level);
                    long afterWeather=System.nanoTime();
                    NeoForge.EVENT_BUS.post(new LevelTickEvent.Post(()->true,f.level));
                    long afterRooms=System.nanoTime();
                    for(int n=0;n<actors.size();n++)work.invoke(actors.get((firstActor+n)%actors.size()));
                    if(tick%5==0) {
                        int n=(tick/5)%4;var actor=actors.get(n);var player=players.get(n);
                        if(!igniting.getBoolean(actor)) {
                            player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);player.setShiftKeyDown(true);
                            actor.getClass().getMethod("interact",net.minecraft.server.level.ServerPlayer.class,InteractionHand.class,Direction.class)
                                    .invoke(actor,player,InteractionHand.MAIN_HAND,Direction.DOWN);
                            player.setShiftKeyDown(false);playerOperations++;
                            player.setItemInHand(InteractionHand.MAIN_HAND,drills.get(n));
                            actor.getClass().getMethod("interact",net.minecraft.server.level.ServerPlayer.class,InteractionHand.class,Direction.class)
                                    .invoke(actor,player,InteractionHand.MAIN_HAND,Direction.UP);
                            playerOperations++;
                        }
                    }
                    if(tick>500) {
                        int processing=0,ignitions=0;
                        for(var station:stations)if(station.active())processing++;
                        for(int n=0;n<4;n++)if(igniting.getBoolean(actors.get(n)))ignitions++;
                        processingTicks+=processing;ignitionTicks+=ignitions;
                        if(processing>=16&&ignitions>0)overlapTicks++;
                    }
                    long afterStations=System.nanoTime();
                    firstActor=(firstActor+1)%actors.size();
                    NeoForge.EVENT_BUS.post(new ServerTickEvent.Post(()->true,f.level.getServer()));
                    if(tick>500) {
                        int index=tick-501;long end=System.nanoTime();elapsed[index]=end-start;
                        stages[0][index]=afterPre-start;stages[1][index]=afterWeather-afterPre;
                        stages[2][index]=afterRooms-afterWeather;stages[3][index]=afterStations-afterRooms;
                        stages[4][index]=end-afterStations;
                    }
                }
                Arrays.sort(elapsed);
                double p95=elapsed[1899]/1e6,p99=elapsed[1979]/1e6;
                for(int n=0;n<stages.length;n++) {
                    Arrays.sort(stages[n]);
                    com.mojang.logging.LogUtils.getLogger().info("M3-135 stage={} p95_ms={} p99_ms={} max_ms={}",n,stages[n][1899]/1e6,stages[n][1979]/1e6,stages[n][1999]/1e6);
                }
                h.assertTrue(rooms.retentionUsage().owners()>=40,"40設備の保持登録を含める");
                long minThrough=Long.MAX_VALUE,maxThrough=0;
                for(var actor:actors) {
                    long through=activeThrough.getLong(actor);
                    if(actors.indexOf(actor)<4&&!igniting.getBoolean(actor)&&!actor.getBlockState().getValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT))
                        through=actor.saveWithoutMetadata(f.level.registryAccess()).getLong("last_tick");
                    minThrough=Math.min(minThrough,through);maxThrough=Math.max(maxThrough,through);
                    com.mojang.logging.LogUtils.getLogger().info("M3-135 actor={} through={} lit={} igniting={} active={}",
                            actors.indexOf(actor),through,actor.getBlockState().hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                            &&actor.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT),
                            igniting.getBoolean(actor),stations.get(actors.indexOf(actor)).active());
                }
                com.mojang.logging.LogUtils.getLogger().info("M3-135 progress stations=40 through_min={} through_max={} current=2500",minThrough,maxThrough);

                com.mojang.logging.LogUtils.getLogger().info(
                        "M3-135 combined ticks=2000 warmup=500 rooms=64 stations=40 fake_players=4 p95_ms={} p99_ms={} max_ms={} wall_ms={} heap_delta={} gc_ms={}",
                        p95,p99,elapsed[1999]/1e6,(System.nanoTime()-wall)/1e6,usedHeap()-heapBefore,gcMillis()-gcBefore);
                for(int n=4;n<40;n++) {
                    var station=stations.get(n);
                    double seconds=station.save(f.level.registryAccess()).getDouble("seconds");
                    h.assertTrue(!station.output(0).isEmpty()||seconds>=(n<24?60:6000),
                            "加工フラグだけでなく、設備ごとの実加工量を確認: "+n+" seconds="+seconds);
                }
                double wear=drills.stream().mapToDouble(d->d.get(ItemProperties.WEAR).value()).sum();
                com.mojang.logging.LogUtils.getLogger().info(
                        "M3-135 active processing_station_ticks={} ignition_station_ticks={} overlap_ticks={} player_operations={} drill_wear={} burn_notices={} chemical_J={}",
                        processingTicks,ignitionTicks,overlapTicks,playerOperations,wear,combustion[0],chemical[0]);
                h.assertTrue(processingTicks>=32000&&ignitionTicks>=500&&overlapTicks>=100
                        &&wear>=4&&combustion[0]>=100&&chemical[0]>1000000,"加工/4人の着火完了/燃焼が実行された負荷を測定");
                h.assertTrue(minThrough>=1900,"40設備すべてが継続前進し、末尾との差を気象1周期600 tick以内に保つ: "+minThrough);
                h.assertTrue(p95<1&&p99<3,"統合目標 p95<1/p99<3 ms: "+p95+"/"+p99);
            } finally {
                recording.stop();recording.dump(java.nio.file.Path.of("environment-135.jfr"));recording.close();
                NeoForge.EVENT_BUS.unregister(emissions);players.forEach(net.minecraft.server.level.ServerPlayer::discard);
            }
        }
    }
    private static long usedHeap() {
        var runtime=Runtime.getRuntime();return runtime.totalMemory()-runtime.freeMemory();
    }
    private static long gcMillis() {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean->Math.max(0,bean.getCollectionTime())).sum();
    }
}
