package io.github.rs0325.ashenfrontier.world;

import com.mojang.logging.LogUtils;
import io.github.rs0325.ashenfrontier.core.vanilla.RemovedItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;

/** 表層・海底・高さ拡張の検査と、配布対象外の比較データ。 */
@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReliefRevisionGameTests {
    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=2400)
    public static void surfacesFollowSlopesAndKeepTheShoreClear(GameTestHelper helper) throws Exception {
        var terrain = TerrainPreviewGameTests.generator(helper).terrain();
        var map = terrain.map();
        var rows = new StringBuilder("area,x,z,height,coast,slope,hollow,rock,cliff,shelf,material,snow,layers\n");
        int cliffs=0, bare=0, drift=0;
        var materials=new HashSet<String>();
        for (var area : new int[][]{{0,-12100,-12500,-10500,-11000},{1,-5350,-15200,-4400,-14400},
                {2,-4600,-14700,-3300,-13800},{3,-11600,-7300,-10800,-6500}}) {
            for(int z=area[2];z<=area[4];z+=8) for(int x=area[1];x<=area[3];x+=8) {
                var column=terrain.column(x,z);int ground=column.surfaceY();
                double east=terrain.surfaceY(x+8,z),west=terrain.surfaceY(x-8,z),north=terrain.surfaceY(x,z-8),south=terrain.surfaceY(x,z+8);
                double slope=Math.hypot((east-west)/16,(south-north)/16),hollow=(east+west+north+south)/4-ground;
                double coast=map.coastDistance(x,z);var shore=OceanRelief.shore(map,x,z);
                var state=column.state(ground);var cap=column.state(ground+1);
                int snow=0;while(column.state(ground-snow).is(Blocks.SNOW_BLOCK))snow++;
                String material=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                helper.assertTrue(!RemovedItems.isRemoved(new ItemStack(state.getBlock())),"削除済みの表層を復活させない");
                if(coast>=0 && map.waterSurface(x,z)==Integer.MIN_VALUE && !map.isProtected(x,z)) {
                    // 整数Yの差分には最大√2/16の丸め誤差がある。
                    if(slope>=.99 && !state.isAir()) {
                        helper.assertTrue(!cap.is(Blocks.SNOW) && snow==0,"急斜面に雪を留めない");
                        helper.assertTrue(state.equals(FixedUnderground.state(terrain.underground().cell(x,z),x,ground,z,column.rockState(ground))),
                                "急斜面の母岩・鉱化部を露出させる: "+x+","+z+" "+state);bare++;
                    }
                    if(coast<24) helper.assertTrue(snow==0,"水際に厚い雪を置かない");
                    if(coast<96 && shore.cliff()>.6) cliffs++;
                    if(snow>=2)drift++;
                    materials.add(material);
                }
                if(output()!=null) rows.append(area[0]).append(',').append(x).append(',').append(z).append(',').append(ground).append(',')
                        .append(coast).append(',').append(slope).append(',').append(hollow).append(',').append(shore.rock()).append(',')
                        .append(shore.cliff()).append(',').append(shore.shelfWidth()).append(',').append(material).append(',').append(snow).append(',')
                        .append(cap.is(Blocks.SNOW)?cap.getValue(SnowLayerBlock.LAYERS).intValue():0).append('\n');
            }
        }
        helper.assertTrue(bare>100 && cliffs>50 && drift>100,"岩肌・海食崖・吹きだまりの検査数");
        helper.assertTrue(materials.size()>=5,"地形条件で変わる表層材料");
        LogUtils.getLogger().info("REVISION_SURFACE bare={} cliffs={} drifts={} materials={}",bare,cliffs,drift,materials);
        if(output()!=null)Files.writeString(output().resolve("surfaces.csv"),rows);
        var underground=terrain.underground();int seaChecks=0;
        for(var body:underground.bodies()) {
            var d=body.data();
            for(int z=d.z()-d.radii().get(2)-3;z<=d.z()+d.radii().get(2)+3;z++)
                for(int x=d.x()-d.radii().get(0)-3;x<=d.x()+d.radii().get(0)+3;x++) if(map.coastDistance(x,z)<0) {
                    helper.assertTrue(body.y()+d.radii().get(1)+3<terrain.surfaceY(x,z),"海底と固定鉱床が交差: "+d.id());seaChecks++;
                }
            LogUtils.getLogger().info("REVISION_DEPOSIT id={} xyz={},{},{} depth={}",d.id(),d.x(),body.y(),d.z(),d.depth());
        }
        for(var passage:underground.passages()) for(int z=Math.min(passage.az(),passage.bz())-passage.width();z<=Math.max(passage.az(),passage.bz())+passage.width();z++)
            for(int x=Math.min(passage.ax(),passage.bx())-passage.width();x<=Math.max(passage.ax(),passage.bx())+passage.width();x++) {
                int floor=passage.floor(x,z);
                if(floor!=Integer.MIN_VALUE && map.coastDistance(x,z)<0) {
                    helper.assertTrue(floor+passage.height()<terrain.surfaceY(x,z),"海底と固定地下経路が交差: "+passage.id());seaChecks++;
                }
            }
        LogUtils.getLogger().info("REVISION_SEABED_INTERSECTIONS checked_sea_columns={} intersections=0",seaChecks);
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=2400)
    public static void extendedHeightMatchesColumnsAndMeasuresCost(GameTestHelper helper) throws Exception {
        var generator=TerrainPreviewGameTests.generator(helper);var level=helper.getLevel();
        var type=level.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE,
                ResourceLocation.fromNamespaceAndPath("ashen_frontier","fixed_map"))).value();
        helper.assertTrue(type.minY()==-128 && type.height()==704 && type.logicalHeight()==704 && generator.getGenDepth()==704,"独自の高さ704ディメンション");
        int x=-11456,z=-11856;var pos=new ChunkPos(x>>4,z>>4);
        var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(-128,704),level.registryAccess().registryOrThrow(Registries.BIOME),null);
        generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),chunk).join();
        var column=generator.terrain().column(x,z);var base=generator.getBaseColumn(x,z,chunk,null);
        helper.assertTrue(column.surfaceY()>320 && column.topY()<=521,"高い峰と50以上の建築余地");
        for(int y=-128;y<576;y++) {
            helper.assertTrue(chunk.getBlockState(new BlockPos(x,y,z)).equals(base.getBlock(y)),"上限拡張後の実生成とbase column");
            helper.assertTrue(base.getBlock(y).equals(column.state(y)),"上限拡張後のcolumn");
        }
        helper.assertTrue(generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,chunk,null)==column.topY()+1,"峰のbase height");
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        for(int height:new int[]{640,704}) {
            long[] times=new long[100];long allocated=0,bytes=0,heightmapBytes=0;
            for(int i=-20;i<100;i++) {
                var target=new ChunkPos(-590+Math.max(0,i)%10,-580+Math.max(0,i)/10);
                long memory=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
                var sample=new ProtoChunk(target,UpgradeData.EMPTY,LevelHeightAccessor.create(height==640?-64:-128,height),level.registryAccess().registryOrThrow(Registries.BIOME),null);
                generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),sample).join();
                if(i>=0) {times[i]=System.nanoTime()-start;allocated+=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-memory;
                    for(var section:sample.getSections())bytes+=section.getSerializedSize();
                    heightmapBytes += 8L * (sample.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).getRawData().length
                            + sample.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG).getRawData().length);}
            }
            Arrays.sort(times);
            LogUtils.getLogger().info("HEIGHT_COST height={} count=100 warmup=20 p50_ms={} p95_ms={} max_ms={} allocated_bytes_per_chunk={} section_payload_bytes_per_chunk={} heightmaps_bytes_per_chunk={}",
                    height,times[49]/1e6,times[94]/1e6,times[99]/1e6,allocated/100,bytes/100,heightmapBytes/100);
        }
        long[] times=new long[200];
        for(int i=-30;i<200;i++) {
            var target=new ChunkPos(pos.x+Math.max(0,i)%10-5,pos.z+Math.max(0,i)/10-10);
            long start=System.nanoTime();
            var sample=new ProtoChunk(target,UpgradeData.EMPTY,LevelHeightAccessor.create(-128,704),level.registryAccess().registryOrThrow(Registries.BIOME),null);
            generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),sample).join();
            if(i>=0)times[i]=System.nanoTime()-start;
        }
        Arrays.sort(times);
        LogUtils.getLogger().info("HIGH_MOUNTAIN_BENCHMARK count=200 warmup=30 parallelism=1 p50_ms={} p95_ms={} max_ms={}",times[99]/1e6,times[189]/1e6,times[199]/1e6);
        helper.succeed();
    }

    static Path output() throws Exception {
        String phase=System.getenv("ASHEN_TERRAIN_PREVIEW");
        if(phase==null)return null;
        var output=Path.of("../../../../../tools/fixed-map/preview",phase);Files.createDirectories(output);return output;
    }
}
