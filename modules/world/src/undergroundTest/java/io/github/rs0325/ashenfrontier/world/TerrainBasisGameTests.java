package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.testing.ThreadCpuClock;
import com.mojang.logging.LogUtils;
import java.security.MessageDigest;
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 骨格・水系・細地形の実地形を使う検査。通常のワールドプリセットは切り替えない。 */
@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerrainBasisGameTests {
    static FixedMapChunkGenerator generator(GameTestHelper helper) throws Exception {
        var original = TerrainPreviewGameTests.generator(helper);
        try (var stream = TerrainBasisGameTests.class.getResourceAsStream("/data/ashen_frontier/fixed_map/earth_002/manifest.json")) {
            var version = new MapVersion("earth_002", 1, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
            return new FixedMapChunkGenerator(((FixedMapBiomeSource)original.getBiomeSource()).withMapVersion(version), version);
        }
    }

    static ProtoChunk chunk(GameTestHelper helper, FixedMapChunkGenerator generator, ChunkPos pos, RandomState random) {
        var level = helper.getLevel();
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(-128,704), level.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
        return chunk;
    }

    static long hash(ProtoChunk chunk) {
        long hash = 0xcbf29ce484222325L;
        var pos = new BlockPos.MutableBlockPos();
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) for (int y=-128;y<576;y++)
            hash = (hash ^ Block.getId(chunk.getBlockState(pos.set(chunk.getPos().getMinBlockX()+x,y,chunk.getPos().getMinBlockZ()+z)))) * 0x100000001b3L;
        return hash;
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void basisActualChunksAndOrder(GameTestHelper helper) throws Exception {
        var generator=generator(helper); var level=helper.getLevel();
        var settings=level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var first=RandomState.create(settings,level.registryAccess().lookupOrThrow(Registries.NOISE),1L);
        var second=RandomState.create(settings,level.registryAccess().lookupOrThrow(Registries.NOISE),-987654321L);
        int[][] positions={{-10600,-13200},{-4200,-12100},{-4624,-8112},{-6800,-4000}};
        long[] expected=new long[positions.length];
        for (int i=0;i<positions.length;i++) {
            var p=positions[i];var pos=new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16));
            var generated=chunk(helper,generator,pos,first);expected[i]=hash(generated);
            for (int dx=0;dx<16;dx++) for (int dz=0;dz<16;dz++) {
                int x=pos.getMinBlockX()+dx,z=pos.getMinBlockZ()+dz;
                var column=generator.terrain().column(x,z);
                helper.assertTrue(column.state(column.surfaceY()-16).equals(column.rockState(column.surfaceY()-16)),"D4の層序を細地形と雪氷の下へ適用する");
                for(int y=-128;y<576;y++) helper.assertTrue(generated.getBlockState(new BlockPos(x,y,z)).equals(column.state(y)),"実チャンクと全Yの列の一致");
            }
        }
        for(int i=positions.length-1;i>=0;i--) {
            var p=positions[i];
            helper.assertTrue(hash(chunk(helper,generator,new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16)),second))==expected[i],"seed・生成順から独立");
        }
        var parallel=IntStream.range(0,positions.length).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            var p=positions[i];
            return hash(chunk(helper,generator,new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16)),second))==expected[i];
        })).toList();
        for(var result:parallel)helper.assertTrue(result.join(),"並列順からも独立");
        var old=TerrainPreviewGameTests.generator(helper);
        for(var p:new int[][]{{8192,8192},{-15000,-8192},{-768,-3840}}) {
            var pos=new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16));
            helper.assertTrue(hash(chunk(helper,generator,pos,first))==hash(chunk(helper,old,pos,first)),"他大陸・海底・島を変更しない");
        }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=12000)
    public static void basisGenerationBenchmark(GameTestHelper helper) throws Exception {
        var generator=generator(helper); var random=helper.getLevel().getChunkSource().randomState();
        var clock=new ThreadCpuClock();boolean useCpu=clock.useCpu("earth002_generation",25_000_000);
        var cpu=(ThreadMXBean)ManagementFactory.getThreadMXBean();cpu.setThreadAllocatedMemoryEnabled(true);
        LogUtils.getLogger().info("D2_BENCH_ENV map={} hash={} jvm={} os={} cpu={} processors={}",generator.mapVersion().mapVersion(),generator.mapVersion().manifestHash(),System.getProperty("java.version"),System.getProperty("os.name"),System.getenv("PROCESSOR_IDENTIFIER"),Runtime.getRuntime().availableProcessors());
        String[] names={"range","massif","lowland","lake_shore","confluence","source","terrace","caldera","glacier","basin","canyon","lava"};
        int[][] origins={{-650,-800},{-330,-780},{-300,-520},{-460,-672},{-400,-660},{-650,-814},{-844,-489},{-639,-694},{-655,-835},{-511,-368},{-331,-727},{-552,-665}};
        for(int group=0;group<origins.length;group++)for(String order:new String[]{"east","reverse","shuffled"}) {
            int[] sequence=IntStream.range(0,1000).toArray();
            if(order.equals("reverse"))for(int i=0;i<500;i++) {int at=sequence[i];sequence[i]=sequence[999-i];sequence[999-i]=at;}
            if(order.equals("shuffled")) {
                var shuffle=new java.util.Random(0x990011L);
                for(int i=sequence.length-1;i>0;i--) {int j=shuffle.nextInt(i+1),at=sequence[i];sequence[i]=sequence[j];sequence[j]=at;}
            }
            long[] times=new long[1000],cpuTimes=new long[1000],allocated=new long[1000];var origin=origins[group];int waterChunks=0;int[] coverage=new int[256];
            int feature=switch(names[group]) {
                case "terrace"->31;case "caldera"->40;case "glacier"->39;
                case "basin"->38;case "canyon"->37;case "lava"->32;default->0;
            };
            long[] featureTimes=new long[feature==0?0:1000];int featureCount=0;
            for(int i=-100;i<1000;i++) {
                int index=sequence[Math.max(0,i)];var pos=new ChunkPos(origin[0]+index%25,origin[1]+index/25);
                int landform=generator.terrain().map().landformId(pos.getMinBlockX(),pos.getMinBlockZ());
                helper.assertTrue(landform!=0,"計測対象はearth_002北西大陸");
                long beforeCpu=clock.now(),beforeAllocation=cpu.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();var generated=chunk(helper,generator,pos,random);
                if(i>=0) {times[i]=System.nanoTime()-start;cpuTimes[i]=clock.now()-beforeCpu;allocated[i]=cpu.getThreadAllocatedBytes(Thread.currentThread().threadId())-beforeAllocation;coverage[landform]++;if(landform==feature)featureTimes[featureCount++]=useCpu?cpuTimes[i]:times[i];}
                if(i>=0 && (group>=3 && group<=5 || names[group].equals("canyon"))) {
                    boolean wet=false;
                    for(int x=0;x<16 && !wet;x++)for(int z=0;z<16;z++)if(generator.terrain().map().hydrology().owner(pos.getMinBlockX()+x,pos.getMinBlockZ()+z)>0){wet=true;break;}
                    if(wet)waterChunks++;
                }
                helper.assertTrue(generated.getBlockState(new BlockPos(pos.getMinBlockX(),-128,pos.getMinBlockZ())).is(Blocks.BEDROCK),"実チャンクを計測");
            }
            LogUtils.getLogger().info("D3_BENCH_COVERAGE group={} order={} counts={}",names[group],order,
                    IntStream.range(0,coverage.length).filter(i->coverage[i]>0).mapToObj(i->i+":"+coverage[i]).collect(java.util.stream.Collectors.joining(",")));
            if(names[group].equals("terrace"))helper.assertTrue(coverage[31]>=200,"段丘31の実チャンクを十分に含む計測窓");
            if(feature!=0) {
                helper.assertTrue(featureCount>=100,"D3の対象区分を含む性能群");
                Arrays.sort(featureTimes,0,featureCount);
                double featureP95=featureTimes[(int)Math.ceil(featureCount*.95)-1]/1e6;
                LogUtils.getLogger().info("D3_FEATURE_BENCH group={} feature={} count={} p95_ms={} p99_ms={} max_ms={} clock={} order={}",
                        names[group],feature,featureCount,featureP95,featureTimes[(int)Math.ceil(featureCount*.99)-1]/1e6,
                        featureTimes[featureCount-1]/1e6,useCpu?"cpu":"wall",order);
                helper.assertTrue(featureP95<=25,"D3の対象区分だけでもp95<=25ms: "+names[group]+" "+order+" "+featureP95);
            }
            Arrays.sort(times);Arrays.sort(cpuTimes);Arrays.sort(allocated);double p95=(useCpu?cpuTimes:times)[949]/1e6;
            LogUtils.getLogger().info("BASIS_BENCHMARK group={} count=1000 warmup=100 parallelism=1 p50_ms={} p95_ms={} max_ms={} order={} wall_p99_ms={}",names[group],times[499]/1e6,times[949]/1e6,times[999]/1e6,order,times[989]/1e6);
            LogUtils.getLogger().info("D2_BENCH_DETAIL group={} cpu_p95_ms={} allocated_p95_bytes={} water_chunks={} order={} cpu_p99_ms={} cpu_max_ms={}",names[group],cpuTimes[949]/1e6,allocated[949],waterChunks,order,cpuTimes[989]/1e6,cpuTimes[999]/1e6);
            if(group>=3 && group<=5 || names[group].equals("canyon"))helper.assertTrue(waterChunks>0,"水系の実チャンクを含む性能群");
            var memory=Runtime.getRuntime();
            LogUtils.getLogger().info("D3_GROUP_MEMORY group={} order={} used_heap_bytes={} committed_heap_bytes={} max_heap_bytes={} pid={}",
                    names[group],order,memory.totalMemory()-memory.freeMemory(),memory.totalMemory(),memory.maxMemory(),ProcessHandle.current().pid());
            helper.assertTrue(p95<=25,"earth_002の各地形で"+(useCpu?"CPU":"壁時計")+" p95<=25ms: "+names[group]+" "+order+" "+p95);
        }
        var runtime=Runtime.getRuntime();
        LogUtils.getLogger().info("D3_MEMORY used_heap_bytes={} committed_heap_bytes={} max_heap_bytes={} pid={}",runtime.totalMemory()-runtime.freeMemory(),runtime.totalMemory(),runtime.maxMemory(),ProcessHandle.current().pid());
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void hydrologyActualChunksAndOrder(GameTestHelper helper) throws Exception {
        var generator=generator(helper);var level=helper.getLevel();
        var settings=level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var first=RandomState.create(settings,level.registryAccess().lookupOrThrow(Registries.NOISE),1L);
        var second=RandomState.create(settings,level.registryAccess().lookupOrThrow(Registries.NOISE),-981237L);
        int[][] positions={{-10220,-12812},{-7000,-10400},{-6510,-10300},{-6200,-10300},{-4800,-7800},{-12587,-8770},{-2432,-5792},{-13125,-9178},{-5313,-13954},
                {-13232,-7808},{-8500,-10400},{-6600,-10500},{-5100,-9000},{-3000,-6100},{-4300,-7200},{-5200,-11300},
                {-8000,-5600},{-10380,-12720},{-10200,-13080},{-10220,-13230},{-10020,-13480},{-10000,-10800},{-10170,-11080}};
        long[] expected=new long[positions.length];int spills=0;
        for(int i=0;i<positions.length;i++) {
            var p=positions[i];var pos=new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16));var actual=chunk(helper,generator,pos,first);expected[i]=hash(actual);
            for(int dx=0;dx<16;dx++)for(int dz=0;dz<16;dz++) {
                int x=pos.getMinBlockX()+dx,z=pos.getMinBlockZ()+dz;var column=generator.terrain().column(x,z);var map=generator.terrain().map();
                helper.assertTrue(column.surfaceY()==generator.terrain().surfaceY(x,z),"公開地表と列が一致");
                int water=map.waterSurface(x,z);
                if(water!=Integer.MIN_VALUE) {
                    helper.assertTrue(column.surfaceY()<water,"水の下に床がある");
                    helper.assertTrue(!column.state(water).isAir(),"公開水面に実ブロックがある");
                    helper.assertTrue(map.waterInventoryId(x,z)>0 && map.waterQuality(x,z)>0,"所有・品質も同じ列");
                    for(int y=water+1;y<=column.topY();y++) {
                        helper.assertTrue(column.state(y).getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL)==8,"落水は非水源の表示");spills++;
                    }
                }
                for(int y=-128;y<576;y++)helper.assertTrue(actual.getBlockState(new BlockPos(x,y,z)).equals(column.state(y)),"水面・床・落水を含む全Yの一致");
            }
        }
        for(int i=positions.length-1;i>=0;i--) {
            var p=positions[i];helper.assertTrue(hash(chunk(helper,generator,new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16)),second))==expected[i],"水系もseed・逆順から独立");
        }
        var parallel=IntStream.range(0,positions.length).mapToObj(i->CompletableFuture.supplyAsync(()->{
            var p=positions[i];return hash(chunk(helper,generator,new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16)),second))==expected[i];
        })).toList();
        for(var result:parallel)helper.assertTrue(result.join(),"水系の並列生成も一致");
        helper.assertTrue(spills>0,"源頭の落水を実列で検査した");
        helper.succeed();
    }
}
