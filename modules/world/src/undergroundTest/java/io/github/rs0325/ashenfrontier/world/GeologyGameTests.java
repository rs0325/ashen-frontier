package io.github.rs0325.ashenfrontier.world;

import com.mojang.logging.LogUtils;
import com.sun.management.ThreadMXBean;
import io.github.rs0325.ashenfrontier.core.testing.ThreadCpuClock;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** D4の制作マスクを、最終列・実チャンクと照合する。人工採掘跡は生成しない。 */
@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeologyGameTests {
    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void regionalProfilesReachActualRockColumns(GameTestHelper helper) throws Exception {
        var terrain=TerrainBasisGameTests.generator(helper).terrain();var map=terrain.map();
        helper.assertTrue(map.geology().get("profile_version").getAsString().equals("geology_003"),"D4が旧版の地質JSONを継承しない");
        var found=new HashSet<Integer>();
        for(int x=-14000;x<0;x+=128)for(int z=-15000;z<-3500;z+=128) {
            int id=map.geologyProfile(x,z);
            if(id<6 || id>17 || found.contains(id) || map.coastDistance(x,z)<0
                    || FixedUnderground.voxels(terrain.underground().cell(x,z),x,z)!=null)continue;
            var definition=map.geology().getAsJsonObject("profiles").getAsJsonObject(Integer.toString(id));
            var allowed=new HashSet<ResourceLocation>();
            allowed.add(ResourceLocation.parse(definition.get("basement").getAsString()));
            for(var layer:definition.getAsJsonArray("layers_top_to_bottom"))
                allowed.add(ResourceLocation.parse(layer.getAsJsonObject().get("rock").getAsString()));
            var column=terrain.column(x,z);var rocks=new HashSet<ResourceLocation>();
            for(int y=-127;y<=column.surfaceY()-3;y+=3) {
                var rock=BuiltInRegistries.BLOCK.getKey(column.rockState(y).getBlock());rocks.add(rock);
                helper.assertTrue(allowed.contains(rock),"地域地質の実列母岩 "+id+" "+rock);
            }
            found.add(id);
            LogUtils.getLogger().info("D4_REGIONAL_PROFILE id={} x={} z={} ground={} rocks={}",id,x,z,column.surfaceY(),rocks);
        }
        helper.assertTrue(found.size()==12,"11地質系統とC01局所変種の実ラスター・実列を使用 "+found);
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void residualBodiesHostsAndExposures(GameTestHelper helper) throws Exception {
        var generator=TerrainBasisGameTests.generator(helper);
        var terrain=generator.terrain();
        var layout=UndergroundLayout.read(generator.mapVersion());
        helper.assertTrue(layout.d4()!=null && layout.deposits().size()==40,"D4の40枠");
        int total=0,required=0;
        for(var deposit:layout.deposits()) {
            if(deposit.required())required++;
            int count=0;
            var handoff=layout.d4().handoffs().stream().filter(p->p.id().equals(deposit.id())).findFirst().orElseThrow();
            for(var oreColumn:deposit.ore_columns()) {
                var column=terrain.column(oreColumn.x(),oreColumn.z());
                helper.assertTrue(terrain.map().waterSurface(oreColumn.x(),oreColumn.z())==Integer.MIN_VALUE,"鉱体を水域へ配置しない");
                for(int y:oreColumn.ys()) {
                    var expected=BuiltInRegistries.BLOCK.get(
                            ResourceLocation.parse(FixedUnderground.id(deposit.block()))).defaultBlockState();
                    var host=BuiltInRegistries.BLOCK.get(
                            ResourceLocation.parse(FixedUnderground.id(deposit.host()))).defaultBlockState();
                    helper.assertTrue(column.state(y).equals(expected),"最終列の残鉱 "+deposit.id()+" "+oreColumn.x()+","+y+","+oreColumn.z());
                    helper.assertTrue(column.rockState(y).equals(host),"登録母岩 "+deposit.id());
                    count++;
                }
            }
            helper.assertTrue(count==deposit.residual_blocks() && count==handoff.residual_blocks(),"残存NとD5引継ぎ "+deposit.id());
            helper.assertTrue(!handoff.necessary_portals().isEmpty(),"D5へ必要な口を渡す");
            if(deposit.required() || deposit.id().equals("sulfur_fumarole")) {
                int exposed=0;
                for(var face:handoff.faces()) {
                    var column=terrain.column(face.get(0),face.get(2));
                    helper.assertTrue(column.surfaceY()==face.get(1),"浅掘り後の面の高さ");
                    helper.assertTrue(column.state(face.get(1)+1).isAir() && column.state(face.get(1)+2).isAir(),"接面の2B頭上");
                    exposed++;
                }
                helper.assertTrue(exposed==(deposit.required()?48:24),"必須/S-Aの露出数をNの内数で保証");
            }
            if(deposit.id().startsWith("oil_"))helper.assertTrue(count==655 && handoff.oil_seal(),"油の有限量・封止");
            LogUtils.getLogger().info("D4_RESIDUAL id={} count={} center_y={} faces={} bounds={}",deposit.id(),count,deposit.center_y(),handoff.faces().size(),handoff.bounds());
            total+=count;
        }
        helper.assertTrue(required==8 && total==53611,"8必須・合計量");
        helper.assertTrue(terrain.underground().passages().isEmpty(),"旧直線坑道・人工採掘室をD4へ持ち込まない");
        helper.succeed();
    }

    private static int longestEdge(Set<BlockPos> points) {
        int longest=0;
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            var edge=new HashSet<BlockPos>();
            for(var p:points)if(!points.contains(p.offset(d[0],0,d[1])))edge.add(p);
            for(var p:edge) {
                if(edge.contains(p.offset(-d[1],0,-d[0])))continue;
                int n=0;
                while(edge.contains(p.offset(n*d[1],0,n*d[0])))n++;
                longest=Math.max(longest,n);
            }
        }
        return longest;
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void shallowFacesDoNotMakeRectanglesOrThinFins(GameTestHelper helper) throws Exception {
        var generator=TerrainBasisGameTests.generator(helper);var terrain=generator.terrain();
        var layout=UndergroundLayout.read(generator.mapVersion());
        var rectangle=new HashSet<BlockPos>();
        for(int x=0;x<12;x++)for(int z=0;z<4;z++)rectangle.add(new BlockPos(x,0,z));
        helper.assertTrue(longestEdge(rectangle)>6,"同じ検査で48Bの矩形切り込みを拒否できる");
        for(var deposit:layout.deposits()) {
            if(!deposit.required() && !deposit.id().equals("sulfur_fumarole"))continue;
            var handoff=layout.d4().handoffs().stream().filter(v->v.id().equals(deposit.id())).findFirst().orElseThrow();
            var cut=new HashSet<BlockPos>();var area=new HashSet<BlockPos>();
            for(var face:handoff.faces()) {
                int x=face.get(0),z=face.get(2);
                if(terrain.column(x,z).surfaceY()!=FixedTerrain.surfaceY(terrain.map(),x,z))
                    cut.add(new BlockPos(x,0,z));
            }
            helper.assertTrue(cut.size()==(deposit.required()?48:24),"最終列で浅掘りを測る");
            helper.assertTrue(longestEdge(cut)<=6,"浅掘りの長い直線/矩形/平行な切り込み "+deposit.id());
            area.addAll(cut);
            for(var p:cut)for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})area.add(p.offset(d[0],0,d[1]));
            int fins=0;
            for(var p:area)for(int[] d:new int[][]{{1,0},{0,1}}) {
                int x=p.getX(),z=p.getZ();
                int old=FixedTerrain.surfaceY(terrain.map(),x,z)-Math.max(FixedTerrain.surfaceY(terrain.map(),x+d[0],z+d[1]),FixedTerrain.surfaceY(terrain.map(),x-d[0],z-d[1]));
                int now=terrain.column(x,z).surfaceY()-Math.max(terrain.column(x+d[0],z+d[1]).surfaceY(),terrain.column(x-d[0],z-d[1]).surfaceY());
                if(now>=2 && old<2)fins++;
            }
            helper.assertTrue(fins==0,"浅掘りで新たに2B高以上の1B幅の板/針を作らない "+deposit.id());
            LogUtils.getLogger().info("D4_SHALLOW_SHAPE id={} cut_columns={} longest_edge={} new_thin_fins={}",deposit.id(),cut.size(),longestEdge(cut),fins);
        }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void caveColumnsAndActualChunks(GameTestHelper helper) throws Exception {
        var generator=TerrainBasisGameTests.generator(helper);
        var terrain=generator.terrain();var layout=UndergroundLayout.read(generator.mapVersion());
        var cave=layout.d4().cave();int checked=0;
        var floors=new HashMap<BlockPos,Integer>();
        for(var c:layout.d4().columns()) {
            if(c.x()<-8460 || c.x()>=-7800 || c.z()<-9020 || c.z()>=-8500)continue;
            var column=terrain.column(c.x(),c.z());
            for(int at=0;at<c.runs().size();at+=3) {
                var run=c.runs();
                if(!layout.d4().palette().get(run.get(at+2)).getFirst().equals("minecraft:air"))continue;
                helper.assertTrue(terrain.map().waterSurface(c.x(),c.z())==Integer.MIN_VALUE,"自然洞の水域非衝突");
                helper.assertTrue(run.get(at+1)-run.get(at)+1<=7,"自然洞の合流部も高さ7B以内");
                for(int y=run.get(at);y<=run.get(at+1);y++) {
                    helper.assertTrue(column.state(y).isAir(),"最終列の自然洞空間");checked++;
                }
                int floor=run.get(at)-1;
                if(!column.state(floor).isAir() && column.state(floor+1).isAir() && column.state(floor+2).isAir())
                    floors.put(new BlockPos(c.x(),0,c.z()),floor);
                if(Math.hypot(c.x()-cave.entrance().get(0),c.z()-cave.entrance().get(1))>70)
                    helper.assertTrue(column.surfaceY()-run.get(at+1)>=12,"開口遷移外の実列天井厚");
            }
        }
        helper.assertTrue(checked>2000,"自然洞の実列標本数");
        var reached=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();
        var start=new BlockPos(cave.entrance().get(0),0,cave.entrance().get(1));
        helper.assertTrue(floors.containsKey(start),"自然洞入口の実列踏面");
        reached.add(start);queue.add(start);
        while(!queue.isEmpty()) {
            var p=queue.removeFirst();
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                var q=p.offset(d[0],0,d[1]);
                if(floors.containsKey(q) && !reached.contains(q) && Math.abs(floors.get(q)-floors.get(p))<=1) {
                    reached.add(q);queue.addLast(q);
                }
            }
        }
        for(var axis:cave.axes()) for(var point:axis) {
            int xx=point.get(0).intValue(),zz=point.get(1).intValue(),yy=point.get(2).intValue(),walkable=0;
            helper.assertTrue(reached.contains(new BlockPos(xx,0,zz)),"自然洞の主洞・支洞へ実列で歩行接続");
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                var c=terrain.column(xx+dx,zz+dz);
                if(!c.state(yy).isAir() && c.state(yy+1).isAir() && c.state(yy+2).isAir())walkable++;
            }
            helper.assertTrue(walkable>=4,"自然洞の制御断面に2B踏面・2B頭上");
        }
        int x=cave.entrance().get(0),z=cave.entrance().get(1),floor=cave.entrance().get(2);
        var entrance=terrain.column(x,z);
        helper.assertTrue(!entrance.state(floor).isAir() && entrance.state(floor+1).isAir() && entrance.state(floor+2).isAir(),"C01入口の床と頭上");
        var settings=helper.getLevel().registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var random=RandomState.create(settings,helper.getLevel().registryAccess().lookupOrThrow(Registries.NOISE),1);
        for(int[] point:new int[][]{{x,z},{-7900,-9200},{-8992,-8648},{-5630,-9110}}) {
            var pos=new ChunkPos(point[0]>>4,point[1]>>4);
            var chunk=TerrainBasisGameTests.chunk(helper,generator,pos,random);
            var block=new BlockPos.MutableBlockPos();
            for(int xx=pos.getMinBlockX();xx<pos.getMinBlockX()+16;xx++)for(int zz=pos.getMinBlockZ();zz<pos.getMinBlockZ()+16;zz++) {
                var column=terrain.column(xx,zz);
                for(int y=-128;y<=column.topY();y++)
                    helper.assertTrue(chunk.getBlockState(block.set(xx,y,zz)).equals(column.state(y)),"列と実チャンクの不一致");
            }
        }
        LogUtils.getLogger().info("D4_CAVE air_voxels={} entrance={} columns={}",checked,cave.entrance(),layout.d4().columns().size());
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void geologySeedOrderAndReload(GameTestHelper helper) throws Exception {
        var generator=TerrainBasisGameTests.generator(helper);
        var settings=helper.getLevel().registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var first=RandomState.create(settings,helper.getLevel().registryAccess().lookupOrThrow(Registries.NOISE),1);
        var second=RandomState.create(settings,helper.getLevel().registryAccess().lookupOrThrow(Registries.NOISE),-987654321L);
        var positions=new ArrayList<ChunkPos>();
        for(int[] p:new int[][]{{-7900,-9200},{-8420,-10020},{-8992,-8648},{-5630,-9110},{-8000,-8720},{-8140,-8770}})
            positions.add(new ChunkPos(p[0]>>4,p[1]>>4));
        var expected=new HashMap<ChunkPos,Long>();
        for(var p:positions)expected.put(p,TerrainBasisGameTests.hash(TerrainBasisGameTests.chunk(helper,generator,p,first)));
        Collections.reverse(positions);
        for(var p:positions)helper.assertTrue(expected.get(p)==TerrainBasisGameTests.hash(TerrainBasisGameTests.chunk(helper,generator,p,second)),"別seed/逆順の実チャンク");
        var parallel=positions.stream().map(p->CompletableFuture.supplyAsync(() ->
                expected.get(p)==TerrainBasisGameTests.hash(TerrainBasisGameTests.chunk(helper,generator,p,second)))).toList();
        for(var result:parallel)helper.assertTrue(result.join(),"D4の並列生成も一致");
        var reloaded=TerrainBasisGameTests.generator(helper);
        Collections.shuffle(positions,new Random(520026));
        for(var p:positions)helper.assertTrue(expected.get(p)==TerrainBasisGameTests.hash(TerrainBasisGameTests.chunk(helper,reloaded,p,first)),"再読込み/ばらばら順の実チャンク");
        int untouched=0;
        var terrain=generator.terrain();
        for(int x=-14000;x<0;x+=128)for(int z=-15000;z<-3500;z+=128) {
            if(FixedUnderground.voxels(terrain.underground().cell(x,z),x,z)!=null)continue;
            helper.assertTrue(terrain.column(x,z).surfaceY()==FixedTerrain.surfaceY(terrain.map(),x,z),"D4非対象列のD1〜D3地表を維持");
            untouched++;
        }
        LogUtils.getLogger().info("D4_REPRODUCTION actual_chunks={} world_seeds=2 orders=3 parallel=true untouched_columns={}",positions.size(),untouched);
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void minedColumnsSurviveStandardDiskStorage(GameTestHelper helper) throws Exception {
        var generator=TerrainBasisGameTests.generator(helper);
        var settings=helper.getLevel().registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var random=RandomState.create(settings,helper.getLevel().registryAccess().lookupOrThrow(Registries.NOISE),1);
        var layout=UndergroundLayout.read(generator.mapVersion());
        var codec=PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,BlockState.CODEC,PalettedContainer.Strategy.SECTION_STATES,Blocks.AIR.defaultBlockState());
        var out=Path.of("build","d4-save-test");Files.createDirectories(out);
        for(String id:List.of("mining_iron","oil_light_low_sulfur","fixed_cave")) {
            boolean cave=id.equals("fixed_cave");
            var deposit=cave?null:layout.deposits().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();
            var probe=cave?new BlockPos(-8090,175,-8722):new BlockPos(deposit.x(),deposit.center_y(),deposit.z());
            var pos=new ChunkPos(probe);var chunk=TerrainBasisGameTests.chunk(helper,generator,pos,random);
            var original=chunk.getBlockState(probe);
            helper.assertTrue(cave?original.isAir():!original.isAir(),"保存前の実チャンク");
            if(!cave)chunk.setBlockState(probe,Blocks.AIR.defaultBlockState(),false);
            chunk.setPersistedStatus(ChunkStatus.FEATURES);
            var file=out.resolve(id+".nbt");
            NbtIo.writeCompressed(ChunkSerializer.write(helper.getLevel(),chunk),file);
            var saved=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());
            helper.assertTrue(saved.getString("Status").equals("minecraft:features"),"保存済み段階を維持して地形を再生成しない");
            var sections=new HashMap<Integer,PalettedContainer<BlockState>>();
            for(var value:saved.getList("sections",10)) {
                var section=(CompoundTag)value;
                if(section.contains("block_states",10))
                    sections.put((int)section.getByte("Y"),codec.parse(NbtOps.INSTANCE,section.getCompound("block_states")).getOrThrow());
            }
            int checked=0;var point=new BlockPos.MutableBlockPos();
            String message="標準NBTの全ブロック列往復 "+id;
            int low=helper.getLevel().getMinBuildHeight(),high=helper.getLevel().getMaxBuildHeight();
            helper.assertTrue(probe.getY()>=low && probe.getY()<high,"保存先ディメンションに対象の資源/洞窟を含む");
            for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=low;y<high;y++) {
                var restored=sections.get(Math.floorDiv(y,16));
                var state=restored==null?Blocks.AIR.defaultBlockState():restored.get(x,y&15,z);
                helper.assertTrue(state.equals(chunk.getBlockState(point.set(pos.getMinBlockX()+x,y,pos.getMinBlockZ()+z))),message);
                checked++;
            }
            var restored=sections.get(Math.floorDiv(probe.getY(),16)).get(probe.getX()&15,probe.getY()&15,probe.getZ()&15);
            helper.assertTrue(restored.isAir(),"採取済み空気/天然洞を初期Nで補充しない");
            LogUtils.getLogger().info("D4_DISK_STORAGE id={} checked_blocks={} removed={} bytes={} min_y={} max_y={}",id,checked,!cave,Files.size(file),low,high);
        }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void geologyGenerationBenchmark(GameTestHelper helper) throws Exception {
        long before=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        var generator=TerrainBasisGameTests.generator(helper);
        var layout=UndergroundLayout.read(generator.mapVersion());
        var settings=helper.getLevel().registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var random=RandomState.create(settings,helper.getLevel().registryAccess().lookupOrThrow(Registries.NOISE),1);
        var groups=new HashMap<String,LinkedHashSet<ChunkPos>>();
        for(var deposit:layout.deposits()) {
            String group=deposit.id().startsWith("oil_")?"oil":deposit.required()?"required":"deep_ore";
            var points=groups.computeIfAbsent(group,k->new LinkedHashSet<>());
            for(var column:deposit.ore_columns())points.add(new ChunkPos(column.x()>>4,column.z()>>4));
        }
        for(var c:layout.d4().columns()) {
            if(c.x()>=-8460 && c.x()<-7800 && c.z()>=-9020 && c.z()<-8500)
                groups.computeIfAbsent("cave",k->new LinkedHashSet<>()).add(new ChunkPos(c.x()>>4,c.z()>>4));
        }
        var threads=(ThreadMXBean)ManagementFactory.getThreadMXBean();threads.setThreadAllocatedMemoryEnabled(true);
        long arrayBytes=layout.d4().columns().stream().mapToLong(c->4L*c.runs().size()).sum();
        LogUtils.getLogger().info("D4_MEMORY interval_int_bytes={} voxel_columns={} heap_before={} heap_after={} os={} processors={}",
                arrayBytes,layout.d4().columns().size(),before,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                System.getProperty("os.name"),Runtime.getRuntime().availableProcessors());
        for(String group:List.of("required","deep_ore","oil","cave")) {
            var original=new ArrayList<>(groups.get(group));
            original.sort(Comparator.<ChunkPos>comparingInt(p->p.x).thenComparingInt(p->p.z));
            for(String order:List.of("east","reverse","shuffled")) {
                var positions=new ArrayList<>(original);
                if(order.equals("reverse"))Collections.reverse(positions);
                if(order.equals("shuffled"))Collections.shuffle(positions,new Random(520026));
                var clock=new ThreadCpuClock();
                boolean cpu=clock.useCpu("d4_"+group+"_"+order,25_000_000);
                for(int i=0;i<100;i++)TerrainBasisGameTests.chunk(helper,generator,positions.get(i%positions.size()),random);
                long[] wall=new long[500],time=new long[500],allocated=new long[500];
                for(int i=0;i<wall.length;i++) {
                    long memory=threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    long c=clock.now(),start=System.nanoTime();
                    TerrainBasisGameTests.chunk(helper,generator,positions.get(i%positions.size()),random);
                    wall[i]=System.nanoTime()-start;time[i]=clock.now()-c;
                    allocated[i]=threads.getThreadAllocatedBytes(Thread.currentThread().threadId())-memory;
                }
                Arrays.sort(wall);Arrays.sort(time);Arrays.sort(allocated);long[] selected=cpu?time:wall;
                double p95=selected[474]/1e6;
                LogUtils.getLogger().info("D4_BENCHMARK group={} order={} count=500 warmup=100 unique={} clock={} p95_ms={} p99_ms={} max_ms={} wall_p95_ms={} wall_p99_ms={} wall_max_ms={}",
                        group,order,positions.size(),cpu?"cpu":"wall",p95,selected[494]/1e6,selected[499]/1e6,wall[474]/1e6,wall[494]/1e6,wall[499]/1e6);
                LogUtils.getLogger().info("D4_BENCH_DETAIL group={} order={} cpu_p95_ms={} cpu_p99_ms={} cpu_max_ms={} allocated_p95_bytes={}",
                        group,order,time[474]/1e6,time[494]/1e6,time[499]/1e6,allocated[474]);
                helper.assertTrue(p95<=25,"D4生成p95<=25ms "+group+" "+order+" "+p95);
            }
        }
        helper.succeed();
    }
}
