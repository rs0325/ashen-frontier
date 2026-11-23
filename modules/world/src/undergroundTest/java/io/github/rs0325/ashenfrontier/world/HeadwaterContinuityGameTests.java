package io.github.rs0325.ashenfrontier.world;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HeadwaterContinuityGameTests {
    private record Edge(int x,int z,int dx,int dz,int a,int b,boolean branchA,boolean branchB) {}

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void snowfieldHasNoHighVerticalSeams(GameTestHelper helper) {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();var scene=new HeadwaterRevisionGameTests.Scene(terrain);
        int maximum=0,samples=0;
        for(int z=-12350;z<=-12130;z++)for(int x=-4180;x<=-3900;x++) {
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
