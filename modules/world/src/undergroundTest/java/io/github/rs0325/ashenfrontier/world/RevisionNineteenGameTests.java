package io.github.rs0325.ashenfrontier.world;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RevisionNineteenGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void snowAndMountainOutlinesDoNotFormLongGridEdges(GameTestHelper helper) {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();
        // 前2区間は矩形の再発、後3区間は広い斜面で長大な直線が残らないことを検査する。
        int[][] areas={{-10600,-11600,-9300,-10600,160,192},{-5000,-13300,-3300,-10500,160,192},
                {-4500,-13600,-2400,-12200,224,288},{-7600,-13000,-6400,-10800,224,288},
                {-1500,-13100,100,-10800,224,288}};
        var failures=new StringBuilder();
        for(var area:areas) {
            int width=(area[2]-area[0])/4,height=(area[3]-area[1])/4;
            int[][] snow=new int[height+1][width+1],mountain=new int[height+1][width+1];
            for(int z=0;z<height;z++)for(int x=0;x<width;x++) {
                var column=terrain.column(area[0]+x*4,area[1]+z*4);
                var surface=column.state(column.surfaceY());var cap=column.state(column.topY());
                boolean covered=surface.is(Blocks.SNOW_BLOCK) || surface.is(Blocks.POWDER_SNOW)
                        || surface.is(Blocks.PACKED_ICE) || surface.is(Blocks.BLUE_ICE) || cap.is(Blocks.SNOW);
                snow[z+1][x+1]=(covered?1:0)+snow[z][x+1]+snow[z+1][x]-snow[z][x];
                mountain[z+1][x+1]=(column.surfaceY()>=180?1:0)+mountain[z][x+1]+mountain[z+1][x]-mountain[z][x];
            }
            var snowRun=longestAxisEdge(snow,terrain.map(),area);
            var mountainRun=longestAxisEdge(mountain,terrain.map(),area);
            LogUtils.getLogger().info("MAP93_GRID_EDGE x={} z={} snow={} mountain={}",area[0],area[1],Arrays.toString(snowRun),Arrays.toString(mountainRun));
            if(snowRun[0]>area[4] || mountainRun[0]>area[5])failures.append(" x=").append(area[0]).append(" z=").append(area[1])
                    .append(" snow=").append(Arrays.toString(snowRun)).append(" mountain=").append(Arrays.toString(mountainRun));
        }
        helper.assertTrue(failures.isEmpty(),"雪原・山塊の境界を座標軸に長く固定しない:"+failures);
        helper.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void snowFormsPatchesRatherThanSingleBlockSpeckles(GameTestHelper helper) {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();
        int width=740,height=280,covered=0,isolated=0,edges=0;
        var snow=new boolean[height][width];
        for(int z=0;z<height;z++)for(int x=0;x<width;x++) {
            var column=terrain.column(-10240+x,-11380+z);
            var surface=column.state(column.surfaceY());var cap=column.state(column.topY());
            snow[z][x]=surface.is(Blocks.SNOW_BLOCK) || surface.is(Blocks.POWDER_SNOW) || cap.is(Blocks.SNOW);
            if(snow[z][x])covered++;
        }
        for(int z=1;z<height-1;z++)for(int x=1;x<width-1;x++) {
            if(snow[z][x] && !snow[z-1][x] && !snow[z+1][x] && !snow[z][x-1] && !snow[z][x+1])isolated++;
            if(snow[z][x]!=snow[z][x+1])edges++;
            if(snow[z][x]!=snow[z+1][x])edges++;
        }
        double transitions=edges/(double)((width-2)*(height-2)*2);
        LogUtils.getLogger().info("MAP93_SNOW_PATCH covered={} isolated={} transitions={}",covered,isolated,transitions);
        helper.assertTrue(covered>20000,"雪を消して点描を回避しない");
        helper.assertTrue(isolated<covered*.003 && transitions<.12,"1ブロック単位の点描ではなく連続した雪の斑にする");
        helper.succeed();
    }

    private static int[] longestAxisEdge(int[][] integral,FixedMapData map,int[] area) {
        int height=integral.length-9,width=integral[0].length-9;
        boolean[][] mask=new boolean[height][width];
        for(int z=0;z<height;z++)for(int x=0;x<width;x++)
            mask[z][x]=integral[z+9][x+9]-integral[z][x+9]-integral[z+9][x]+integral[z][x]>=41;
        int[] maximum=new int[4];
        for(int axis=0;axis<2;axis++) {
            int along=axis==0?height:width,across=axis==0?width:height;
            int[][] runs=new int[across-1][5];
            for(int i=24;i<along-24;i++)for(int j=24;j<across-24;j++) {
                int[] next=new int[5];
                for(int k=0;k<5;k++) {
                    int offset=j+k-2;
                    boolean edge=axis==0?mask[i][offset]!=mask[i][offset+1]:mask[offset][i]!=mask[offset+1][i];
                    int x=area[0]+((axis==0?offset:i)+4)*4,z=area[1]+((axis==0?i:offset)+4)*4;
                    if(!edge || map.river(x,z).distance()<96)continue;
                    // 同じ帯の別の雪筋へ飛び移らず、隣接する境界だけをつなぐ。
                    int previous=runs[j][k];
                    if(k>0)previous=Math.max(previous,runs[j][k-1]);
                    if(k<4)previous=Math.max(previous,runs[j][k+1]);
                    next[k]=previous+1;
                    if(next[k]*4>maximum[0]) {maximum[0]=next[k]*4;maximum[1]=x;maximum[2]=z;maximum[3]=axis;}
                }
                runs[j]=next;
            }
        }
        return maximum;
    }
}
