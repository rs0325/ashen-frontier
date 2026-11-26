package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.testing.ThreadCpuClock;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import javax.imageio.ImageIO;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HeadwaterGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void headwaterGenerationStaysWithinBudget(GameTestHelper helper) {
        var generator=TerrainPreviewGameTests.generator(helper);var map=generator.terrain().map();var level=helper.getLevel();
        var positions=new ArrayList<ChunkPos>();int x=-4000;
        for(int z=-12112;positions.size()<1000;z+=16) {
            x=centerX(map,x,z);
            for(int dx=-2;dx<=2;dx++)positions.add(new ChunkPos((x>>4)+dx,z>>4));
        }
        var cpu=new ThreadCpuClock();boolean useCpu=cpu.useCpu("headwater_generation",25_000_000);long[] times=new long[1000],cpuTimes=new long[1000];
        for(int i=-100;i<1000;i++) {
            var position=positions.get(Math.floorMod(i,1000));long beforeCpu=cpu.now(),before=System.nanoTime();
            var chunk=new ProtoChunk(position,UpgradeData.EMPTY,LevelHeightAccessor.create(-128,704),level.registryAccess().registryOrThrow(Registries.BIOME),null);
            generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),chunk).join();
            long wall=System.nanoTime()-before,usedCpu=cpu.now()-beforeCpu;
            if(i>=0){times[i]=wall;cpuTimes[i]=usedCpu;}
        }
        Arrays.sort(times);Arrays.sort(cpuTimes);
        com.mojang.logging.LogUtils.getLogger().info("HEADWATERS_BENCHMARK count=1000 warmup=100 parallelism=1 p50_ms={} p95_ms={} max_ms={} wall_p99_ms={} cpu_p95_ms={} cpu_p99_ms={} cpu_max_ms={}",times[499]/1e6,times[949]/1e6,times[999]/1e6,times[989]/1e6,cpuTimes[949]/1e6,cpuTimes[989]/1e6,cpuTimes[999]/1e6);
        helper.assertTrue((useCpu?cpuTimes:times)[949]<=25_000_000,"源流から中流までの生成"+(useCpu?"CPU":"壁時計")+" p95は25ms以内: "+(useCpu?cpuTimes:times)[949]/1e6);
        helper.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void brooksHaveSupportedWaterAndGrowDownstream(GameTestHelper helper) {
        var generator=TerrainPreviewGameTests.generator(helper);var terrain=generator.terrain();var map=terrain.map();
        int centerX=-4000,previous=1000,wet=0,tributaries=0,snow=0;
        double firstWidth=0,lastWidth=0;
        for(int z=-12112;z<=-10000;z+=4) {
            centerX=centerX(map,centerX,z);var center=map.river(centerX,z);
            helper.assertTrue(!center.tributary() && center.surface()<=previous,"主流の水位は下流へ非増加: "+centerX+","+z);
            previous=(int)center.surface();
            if(z==-12112){firstWidth=center.width();helper.assertTrue(firstWidth<=1 && center.depth()==1,"源流は幅1〜2・水深1");}
            lastWidth=center.width();
            for(int dx=-180;dx<=180;dx++) {
                int x=centerX+dx;var river=map.river(x,z);var column=terrain.column(x,z);
                int water=(int)river.surface(),bed=column.surfaceY();
                if(column.state(column.topY()).is(net.minecraft.world.level.block.Blocks.SNOW))snow++;
                if(river.distance()>=river.width())continue;
                helper.assertTrue(!column.state(water).getFluidState().isEmpty(),"沢の水面を実ブロックで維持: "+x+","+z);
                helper.assertTrue(bed<water && !column.state(bed).isAir() && column.state(bed).getFluidState().isEmpty(),"水は固い河床に支えられる");
                for(int y=bed+1;y<=water;y++)helper.assertTrue(!column.state(y).isAir(),"水柱の途中に空気を作らない");
                if(z<-12016)helper.assertTrue(water-bed==1,"谷頭の沢は浅い");
                for(int side:new int[]{-1,1}) {
                    var neighbor=map.river(x+side,z);
                    if(neighbor.distance()>=neighbor.width())helper.assertTrue(terrain.surfaceY(x+side,z)>=water,"水面より低い岸へ水を浮かせない: "+x+","+z);
                }
                for(var offset:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
                    var neighbor=map.river(x+offset[0],z+offset[1]);
                    if(neighbor.distance()<neighbor.width())helper.assertTrue(Math.abs(neighbor.surface()-water)<=1,"隣接する水面に大きな落差を作らない: "+x+","+z);
                }
                if(river.tributary())tributaries++;wet++;
            }
            if((z+12112)%128==0) {
                var pos=new ChunkPos(centerX>>4,z>>4);var level=helper.getLevel();
                var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(-128,704),level.registryAccess().registryOrThrow(Registries.BIOME),null);
                generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),chunk).join();
                var cursor=new BlockPos.MutableBlockPos();
                for(int cz=pos.getMinBlockZ();cz<=pos.getMaxBlockZ();cz++)for(int x=pos.getMinBlockX();x<=pos.getMaxBlockX();x++) {
                    var column=terrain.column(x,cz);
                    for(int y=column.surfaceY()-2;y<=column.topY()+1;y++) {
                        cursor.set(x,y,cz);helper.assertTrue(chunk.getBlockState(cursor).equals(column.state(y)),"沢・岸・雪と実チャンクが一致: "+cursor);
                    }
                }
            }
        }
        helper.assertTrue(lastWidth>firstWidth*6 && tributaries>80 && wet>1000 && snow>0,"下流への成長・支沢・谷頭の雪: "+lastWidth+" tributaries="+tributaries+" wet="+wet+" snow="+snow);
        com.mojang.logging.LogUtils.getLogger().info("HEADWATERS wet={} tributaries={} snow={} first_half_width={} last_half_width={}",wet,tributaries,snow,firstWidth,lastWidth);
        helper.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=12000)
    public static void exportHeadwaterBlocks(GameTestHelper helper) throws Exception {
        String phase=System.getenv("ASHEN_HEADWATERS_PREVIEW");
        if(phase==null){helper.succeed();return;}
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();
        var output=Path.of("../../../../../tools/fixed-map/preview/headwaters",phase).normalize();
        Files.createDirectories(output);
        int[][] areas={{-4000,-12100},{-4200,-11600},{-4450,-10800},{-4900,-9500}};
        for(var point:areas) {
            int cx=terrain.map().river(point[0],point[1]).x(),cz=point[1],size=385;
            var image=new BufferedImage(1200,900,BufferedImage.TYPE_INT_RGB);
            var g=image.createGraphics();g.setColor(new Color(0xc3e0fa));g.fillRect(0,0,1200,900);
            int[][] heights=new int[size][size];
            for(int z=0;z<size;z++)for(int x=0;x<size;x++)heights[z][x]=terrain.column(cx+x-size/2,cz+z-size/2).topY();
            int reference=heights[size/2][size/2];
            for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
                var column=terrain.column(cx+x-size/2,cz+z-size/2);int top=heights[z][x];
                int px=600+(x-z),py=300+(x+z)/2-(top-reference)*2;
                int south=z+1<size?heights[z+1][x]:top,east=x+1<size?heights[z][x+1]:top;
                for(int y=Math.min(south,east)+1;y<=top;y++) {
                    var state=column.state(y);if(state.isAir())continue;
                    int sy=py+(top-y)*2;Color color=new Color(color(state));
                    if(y>south){g.setColor(color.darker());g.fillPolygon(new int[]{px-1,px,px,px-1},new int[]{sy,sy+1,sy+3,sy+2},4);}
                    if(y>east){g.setColor(color.darker().darker());g.fillPolygon(new int[]{px,px+1,px+1,px},new int[]{sy+1,sy,sy+2,sy+3},4);}
                }
                var state=column.state(top);g.setColor(new Color(color(state)));
                g.fillPolygon(new int[]{px,px+1,px,px-1},new int[]{py-1,py,py+1,py},4);
            }
            g.setColor(Color.BLACK);g.drawString(phase+" X="+cx+" Z="+cz+" / 385 x 385 blocks",20,25);g.dispose();
            ImageIO.write(image,"png",output.resolve(cz+"-blocks.png").toFile());
            var section=new BufferedImage(769,480,BufferedImage.TYPE_INT_RGB);
            for(int dx=-384;dx<=384;dx++) {
                var column=terrain.column(cx+dx,cz);
                for(int y=0;y<240;y++)section.setRGB(dx+384,479-y*2,color(column.state(y)));
                for(int y=0;y<240;y++)section.setRGB(dx+384,478-y*2,color(column.state(y)));
            }
            ImageIO.write(section,"png",output.resolve(cz+"-section.png").toFile());
        }
        var csv=new StringBuilder("z,x,station,width,depth,water,bed,bank\n");
        int centerX=-4000;
        for(int z=-12100;z<=-9000;z+=25) {
            centerX=centerX(terrain.map(),centerX,z);var river=terrain.map().river(centerX,z);
            var column=terrain.column(centerX,z);
            csv.append(z).append(',').append(centerX).append(',').append(river.station()).append(',').append(river.width()).append(',')
                    .append(river.depth()).append(',').append(river.surface()).append(',').append(column.surfaceY()).append(',')
                    .append(terrain.surfaceY(river.x()+(int)Math.ceil(river.width())+2,z)).append('\n');
        }
        Files.writeString(output.resolve("profile.csv"),csv);
        helper.succeed();
    }

    private static int centerX(FixedMapData map,int previous,int z) {
        int result=previous;double distance=Double.POSITIVE_INFINITY;
        for(int x=previous-128;x<=previous+128;x++) {
            var sample=map.river(x,z);
            if(!sample.tributary() && sample.distance()<distance){distance=sample.distance();result=x;}
        }
        return result;
    }

    private static int color(BlockState state) {
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if(state.isAir())return 0xc3e0fa;
        if(!state.getFluidState().isEmpty())return 0x307dad;
        if(id.contains("snow"))return 0xf4f8ff;
        if(id.contains("ice"))return 0x96c6db;
        if(id.contains("gravel"))return 0x948d80;
        if(id.contains("soil") || id.contains("dirt"))return 0x75634a;
        return 0x777c83;
    }
}
