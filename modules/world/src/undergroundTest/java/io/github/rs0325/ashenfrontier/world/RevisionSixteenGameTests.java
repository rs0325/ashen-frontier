package io.github.rs0325.ashenfrontier.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RevisionSixteenGameTests {
    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID)
    public static void undergroundIdsAndCoordinatesStayFixed(GameTestHelper helper) throws Exception {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();
        var fixed=new StringBuilder("kind,id,ax,az,ay,bx,bz,by\n");
        for(var body:terrain.underground().bodies())fixed.append("body,").append(body.data().id()).append(',').append(body.data().x()).append(',')
                .append(body.data().z()).append(',').append(body.y()).append(",0,0,0\n");
        for(var path:terrain.underground().passages())fixed.append("path,").append(path.id()).append(',').append(path.ax()).append(',').append(path.az())
                .append(',').append(path.ay()).append(',').append(path.bx()).append(',').append(path.bz()).append(',').append(path.by()).append('\n');
        try(var stream=RevisionSixteenGameTests.class.getResourceAsStream("/revision16-underground.csv")) {
            String expected=new String(stream.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");
            helper.assertTrue(expected.equals(fixed.toString()),"地下のID・中心・入口・経路端のX/Zと絶対Yをr15から動かさない");
        }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=2400)
    public static void islandSlopesHaveReliefBetweenTheFixedPeaks(GameTestHelper helper) {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();int samples=0,broken=0;double curvature=0;
        for(int z=-15180;z<-14600;z+=3)for(int x=-5250;x<-4430;x+=3) {
            if(terrain.map().mountainIsland(x,z)==null || terrain.map().coastDistance(x,z)<32)continue;
            int h=terrain.surfaceY(x,z);if(h<95 || h>180)continue;
            int second=Math.abs(terrain.surfaceY(x-4,z)-2*h+terrain.surfaceY(x+4,z));
            curvature+=second;if(second>1)broken++;samples++;
        }
        helper.assertTrue(samples>1000 && curvature/samples>1.2 && broken>samples*.25,"峰の間の岩斜面にも数ブロックの起伏: "+samples+" / "+curvature/Math.max(1,samples)+" / "+broken);
        com.mojang.logging.LogUtils.getLogger().info("REVISION16_ROCK_RELIEF samples={} mean_second_difference={} broken={}",samples,curvature/Math.max(1,samples),broken);
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=12000)
    public static void exportBlockFaces(GameTestHelper helper) throws Exception {
        var output=ReliefRevisionGameTests.output();
        if(output!=null) {
            var terrain=TerrainPreviewGameTests.generator(helper).terrain();
            int[][] areas={{-5392,-15344,1136,1056},{-14784,-12512,1600,1376}};
            for(int a=0;a<areas.length;a++) {
                var area=areas[a];int x0=area[0],z0=area[1],w=area[2],d=area[3];
                int[] heights=new int[w*d];
                for(int z=0;z<d;z++)for(int x=0;x<w;x++)heights[z*w+x]=terrain.surfaceY(x0+x,z0+z);
                var palette=new ArrayList<String>();
                try(var stream=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output.resolve("review16-blocks-"+a+".bin"))))) {
                    stream.writeInt(x0);stream.writeInt(z0);stream.writeInt(w);stream.writeInt(d);
                    for(int z=0;z<d;z++)for(int x=0;x<w;x++) {
                        int h=heights[z*w+x],low=h;
                        if(x>0)low=Math.min(low,heights[z*w+x-1]);if(x<w-1)low=Math.min(low,heights[z*w+x+1]);
                        if(z>0)low=Math.min(low,heights[(z-1)*w+x]);if(z<d-1)low=Math.min(low,heights[(z+1)*w+x]);
                        var column=terrain.column(x0+x,z0+z);
                        stream.writeShort(h);stream.writeShort(low);stream.writeShort(Math.max(h+1,63));
                        for(int y=low;y<=Math.max(h+1,63);y++) {
                            String id=BuiltInRegistries.BLOCK.getKey(column.state(y).getBlock()).toString();
                            int index=palette.indexOf(id);
                            if(index<0){index=palette.size();palette.add(id);}
                            stream.writeShort(index);
                        }
                    }
                }
                Files.write(output.resolve("review16-palette-"+a+".txt"),palette);
            }
        }
        helper.succeed();
    }
}
