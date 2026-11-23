package io.github.rs0325.ashenfrontier.world;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HeadwaterContinuityGameTests {
    private record Edge(int x,int z,int dx,int dz,int a,int b,boolean branchA,boolean branchB) {}

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void gorgeWaterlineDoesNotHaveAlternatingSnow(GameTestHelper helper) {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();var scene=new HeadwaterRevisionGameTests.Scene(terrain);
        int samples=0,snow=0,pairs=0,changes=0,longest=0,wet=0;
        for(int side:new int[]{-1,1}) {
            boolean previous=false;int previousZ=0,run=0;
            for(int z=-12110;z<=-11760;z++) {
                int edge=side<0?Integer.MAX_VALUE:Integer.MIN_VALUE;
                for(int x=-4200;x<=-3950;x++) {
                    var river=terrain.map().river(x,z);
                    if(river.tributary() || river.distance()>=river.width())continue;
                    edge=side<0?Math.min(edge,x):Math.max(edge,x);
                    var column=scene.column(x,z);
                    for(int y=column.surfaceY()+1;y<=river.surface();y++) {
                        helper.assertTrue(!scene.getFluidState(new BlockPos(x,y,z)).isEmpty(),"積雪で沢の水を遮らない");
                        wet++;
                    }
                }
                if(edge==Integer.MAX_VALUE || edge==Integer.MIN_VALUE)continue;
                int x=edge+side;var column=scene.column(x,z);var river=terrain.map().river(x,z);
                if(column.surfaceY()>river.surface()+2 || river.distance()-river.width()>=4)continue;
                boolean covered=scene.getBlockState(new BlockPos(x,column.surfaceY()+1,z)).is(Blocks.SNOW);
                samples++;if(covered)snow++;
                if(previousZ==z-1){pairs++;if(covered!=previous){changes++;run++;}else run=0;}
                else run=0;
                longest=Math.max(longest,run);previous=covered;previousZ=z;
            }
        }
        com.mojang.logging.LogUtils.getLogger().info("GORGE_WATERLINE_SNOW samples={} snow={} pairs={} changes={} longest={} wet={}",samples,snow,pairs,changes,longest,wet);
        helper.assertTrue(samples>500 && wet>1000,"撮影した源流と渓谷内の両岸・水中を十分に走査する");
        helper.assertTrue(changes<=pairs*.35 && longest<=5,"水際に交互の薄雪を規則的に並べない");
        helper.assertTrue(snow==0,"水面に近い低い岸は融雪させ、周期的な雪の並びを残さない");helper.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void snowfieldHasNoHighVerticalSeams(GameTestHelper helper) {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();var scene=new HeadwaterRevisionGameTests.Scene(terrain);
        int maximum=0,samples=0;
        for(int z=-12350;z<=-11000;z++)for(int x=-4500;x<=-3750;x++) {
            if(Math.abs(terrain.map().height(x,z)-130)>=4)continue;
            for(var offset:new int[][]{{1,0},{0,1}}) {
                int bx=x+offset[0],bz=z+offset[1];if(Math.abs(terrain.map().height(bx,bz)-130)>=4)continue;
                maximum=Math.max(maximum,Math.abs(scene.column(x,z).topY()-scene.column(bx,bz).topY()));samples++;
            }
        }
        helper.assertTrue(samples>1000 && maximum<=5,"雪面の実ブロックに十数ブロックの直壁を残さない: "+maximum+" / "+samples);
        com.mojang.logging.LogUtils.getLogger().info("HEAD_SNOW_CONTINUITY samples={} max_step={}",samples,maximum);helper.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void narrowBrooksDoNotHaveLongWalledTrenches(GameTestHelper helper) {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();var scene=new HeadwaterRevisionGameTests.Scene(terrain);
        int longest=0,run=0,rows=0;
        for(int z=-12112;z<=-11700;z++) {
            int left=Integer.MAX_VALUE,right=Integer.MIN_VALUE;double water=-1000;
            for(int x=-4200;x<=-3950;x++) {
                var sample=terrain.map().river(x,z);if(sample.tributary() || sample.distance()>=sample.width())continue;
                left=Math.min(left,x);right=Math.max(right,x);water=Math.max(water,top(scene,x,z));
            }
            boolean wall=left!=Integer.MAX_VALUE && top(scene,left-1,z)-water>1 && top(scene,right+1,z)-water>1;
            run=wall?run+1:0;longest=Math.max(longest,run);if(left!=Integer.MAX_VALUE)rows++;
        }
        run=0;
        for(int x=-4250;x<=-4000;x++) {
            int north=Integer.MAX_VALUE,south=Integer.MIN_VALUE;double water=-1000;
            for(int z=-11400;z<=-11000;z++) {
                var sample=terrain.map().river(x,z);if(!sample.tributary() || sample.distance()>=sample.width())continue;
                north=Math.min(north,z);south=Math.max(south,z);water=Math.max(water,top(scene,x,z));
            }
            boolean wall=north!=Integer.MAX_VALUE && top(scene,x,north-1)-water>1 && top(scene,x,south+1)-water>1;
            run=wall?run+1:0;longest=Math.max(longest,run);if(north!=Integer.MAX_VALUE)rows++;
        }
        helper.assertTrue(rows>400 && longest<=24,"源流と支沢で両岸が直立する細溝を長く連続させない: "+longest+" / "+rows);
        com.mojang.logging.LogUtils.getLogger().info("BROOK_WALLED_TRENCH rows={} longest={}",rows,longest);helper.succeed();
    }

    private static double top(HeadwaterRevisionGameTests.Scene scene,int x,int z) {
        var column=scene.column(x,z);var pos=new BlockPos(x,column.topY(),z);var state=scene.getBlockState(pos);var fluid=state.getFluidState();
        return pos.getY()+(fluid.isEmpty()?state.getShape(scene,pos).max(Direction.Axis.Y):fluid.getHeight(scene,pos));
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void gorgeIsNarrowAndTheRidgeRemainsConnected(GameTestHelper helper) {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();var map=terrain.map();
        int center=-4006,maxShoulder=0,sections=0;double minFloor=100,maxFloor=0;
        for(int z=-12108;z<=-11880;z+=4) {
            double nearest=1000;
            int start=center-8,end=center+8;
            for(int x=start;x<=end;x++) {
                var river=map.river(x,z);
                if(!river.tributary() && river.distance()<nearest){nearest=river.distance();center=x;}
            }
            var upstream=map.river(center,z-8);var downstream=map.river(center,z+8);
            double nx=downstream.z()-upstream.z(),nz=upstream.x()-downstream.x(),norm=Math.hypot(nx,nz),floor=0;
            int shoulder=1;
            for(int side:new int[]{-1,1}) {
                double s=.25;
                for(;s<=12;s+=.25) {
                    int bx=(int)Math.round(center+side*s*nx/norm),bz=(int)Math.round(z+side*s*nz/norm);
                    if(terrain.surfaceY(bx,bz)>map.river(bx,bz).surface()+1)break;
                }
                floor+=s-.125;
                int dx=1;
                while(dx<=160 && FixedTerrain.landHeight(map,center+side*dx,z)-terrain.surfaceY(center+side*dx,z)>2){shoulder++;dx++;}
            }
            minFloor=Math.min(minFloor,floor);maxFloor=Math.max(maxFloor,floor);maxShoulder=Math.max(maxShoulder,shoulder);sections++;
        }
        int ridge=1000;
        for(int x=-4200;x<=-3800;x++)ridge=Math.min(ridge,terrain.surfaceY(x,-12480));
        com.mojang.logging.LogUtils.getLogger().info("HEAD_GORGE sections={} floor={}..{} shoulder_max={} ridge_min={}",sections,minFloor,maxFloor,maxShoulder,ridge);
        helper.assertTrue(minFloor>=2.5 && maxFloor<=5.5,"岸と落水周囲の1段を含む垂直断面の谷底を約3〜5に収める（ブロック境界の半幅を許容）");
        helper.assertTrue(maxShoulder<=110,"山の肩まで数百ブロックの通路として彫らない");
        helper.assertTrue(ridge>=190,"源流の北で主稜線を連続させ、低い雪原で切り開かない");helper.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void gorgeCanBeFollowedOnFoot(GameTestHelper helper) {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();var scene=new HeadwaterRevisionGameTests.Scene(terrain);
        var queue=new ArrayDeque<BlockPos>();var seen=new HashSet<Long>();
        for(int x=-4012;x<=-4000;x++)queue.add(new BlockPos(x,0,-12108));
        int farthest=-12108;
        while(!queue.isEmpty()) {
            var pos=queue.removeFirst();int x=pos.getX(),z=pos.getZ();long key=((long)x<<32)^(z&0xffffffffL);
            if(!seen.add(key))continue;
            var river=terrain.map().river(x,z);if(river.distance()>12)continue;
            double feet=walkHeight(scene,x,z);if(!Double.isFinite(feet))continue;
            farthest=Math.max(farthest,z);if(z>=-11200)break;
            for(var direction:Direction.Plane.HORIZONTAL) {
                int bx=x+direction.getStepX(),bz=z+direction.getStepZ();
                if(bx< -4300 || bx> -3900 || bz< -12108 || bz> -11200)continue;
                double nextFeet=walkHeight(scene,bx,bz);
                if(!Double.isFinite(nextFeet) || Math.abs(nextFeet-feet)>1)continue;
                queue.addLast(new BlockPos(bx,0,bz));
            }
        }
        com.mojang.logging.LogUtils.getLogger().info("HEAD_GORGE_WALK farthest_z={} visited={}",farthest,seen.size());
        helper.assertTrue(farthest>=-11200,"高さ差1以内、浅い水と岸を使い、渓谷から開ける区間まで歩いて辿れる");helper.succeed();
    }

    private static double walkHeight(HeadwaterRevisionGameTests.Scene scene,int x,int z) {
        var column=scene.column(x,z);double floor=column.surfaceY()+1,water=floor;
        for(int y=column.surfaceY()+1;y<=column.topY();y++) {
            var pos=new BlockPos(x,y,z);var state=scene.getBlockState(pos);var fluid=state.getFluidState();
            if(!fluid.isEmpty())water=Math.max(water,y+fluid.getHeight(scene,pos));
            else {
                var shape=state.getCollisionShape(scene,pos);
                if(!shape.isEmpty())floor=Math.max(floor,y+shape.max(Direction.Axis.Y));
            }
        }
        return water-floor>1?Double.NaN:floor;
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=12000)
    public static void exportValleyContinuity(GameTestHelper helper) throws Exception {
        String phase=System.getenv("ASHEN_HEADWATERS_PREVIEW");
        if(phase==null || !phase.startsWith("r3-")){helper.succeed();return;}
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();
        var out=Path.of("../../../../../tools/fixed-map/preview/headwaters",phase).normalize();Files.createDirectories(out);
        int x0=-4340,z0=-12600,w=641,d=801;int[][] heights=new int[d][w];
        for(int z=0;z<d;z++)for(int x=0;x<w;x++)heights[z][x]=terrain.surfaceY(x0+x,z0+z);
        var edges=new ArrayList<Edge>();var image=new BufferedImage(w,d,BufferedImage.TYPE_INT_RGB);
        int maximum=0,switches=0,transition=0;
        for(int z=0;z<d-1;z++)for(int x=0;x<w-1;x++) {
            int h=heights[z][x],difference=Math.max(Math.abs(h-heights[z][x+1]),Math.abs(h-heights[z+1][x]));
            int gray=Math.clamp((h-80)*2,0,255);image.setRGB(x,z,difference>4?0xff3030:gray*0x010101);
            maximum=Math.max(maximum,difference);
            if(Math.abs(terrain.map().height(x0+x,z0+z)-130)<4)transition=Math.max(transition,difference);
            for(var offset:new int[][]{{1,0},{0,1}}) {
                int next=heights[z+offset[1]][x+offset[0]];
                if(Math.abs(h-next)<=4)continue;
                boolean a=terrain.map().river(x0+x,z0+z).tributary(),b=terrain.map().river(x0+x+offset[0],z0+z+offset[1]).tributary();
                edges.add(new Edge(x0+x,z0+z,offset[0],offset[1],h,next,a,b));if(a!=b)switches++;
            }
        }
        edges.sort(Comparator.comparingInt((Edge edge)->Math.abs(edge.a-edge.b)).reversed());
        var csv=new StringBuilder("x,z,dx,dz,height,next,branch,next_branch\n");
        for(var edge:edges.stream().limit(128).toList())csv.append(edge.x).append(',').append(edge.z).append(',').append(edge.dx).append(',').append(edge.dz)
                .append(',').append(edge.a).append(',').append(edge.b).append(',').append(edge.branchA).append(',').append(edge.branchB).append('\n');
        Files.writeString(out.resolve("continuity.csv"),csv);ImageIO.write(image,"png",out.resolve("head-height-jumps.png").toFile());
        com.mojang.logging.LogUtils.getLogger().info("HEAD_CONTINUITY max_step={} transition_step={} above_four={} branch_switches={}",maximum,transition,edges.size(),switches);
        helper.succeed();
    }
}
