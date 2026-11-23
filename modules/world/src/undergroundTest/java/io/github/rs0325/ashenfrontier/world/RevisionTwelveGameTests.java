package io.github.rs0325.ashenfrontier.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;

/** r12の撮影地点・地下の固定高さを比較する配布対象外の検査。 */
@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RevisionTwelveGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void glacierDoesNotLeakOutsideTheSnowline(GameTestHelper helper) {
        var terrain = TerrainPreviewGameTests.generator(helper).terrain();
        for (int z : new int[]{-13584, -13576}) {
            var column = terrain.column(-3800, z);
            var ground = column.state(column.surfaceY());
            helper.assertTrue(!ground.is(Blocks.PACKED_ICE) && !ground.is(Blocks.BLUE_ICE), "雪線外に角ばった氷河表層を露出させない");
        }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=2400)
    public static void exportReviewAndFixedUnderground(GameTestHelper helper) throws Exception {
        var output=ReliefRevisionGameTests.output();
        if(output==null){helper.succeed();return;}
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();var map=terrain.map();
        var fixed=new StringBuilder("kind,id,ax,az,ay,bx,bz,by\n");
        for(var body:terrain.underground().bodies())fixed.append("body,").append(body.data().id()).append(',').append(body.data().x()).append(',')
                .append(body.data().z()).append(',').append(body.y()).append(",0,0,0\n");
        for(var path:terrain.underground().passages())fixed.append("path,").append(path.id()).append(',').append(path.ax()).append(',').append(path.az())
                .append(',').append(path.ay()).append(',').append(path.bx()).append(',').append(path.bz()).append(',').append(path.by()).append('\n');
        Files.writeString(output.resolve("fixed-underground.csv"),fixed);
        if(output.getFileName().toString().endsWith("after")) {
            var before=output.resolveSibling("review12-before").resolve("fixed-underground.csv");
            helper.assertTrue(Files.readString(before).equals(fixed.toString()),"地下の固定中心・入口・経路端の絶対YとIDを維持");
        }
        var rows=new StringBuilder("area,x,z,height,water,coast,region,biome,material,cover,layers\n");
        for(var area:new int[][]{{0,-11400,-12000,256,4},{1,-3200,-13400,768,8},{2,-4800,-14768,256,4},
                {3,-4960,-15104,256,4},{4,-7920,-11848,160,2},{5,-3480,-14600,512,4},
                {6,-7867,-10645,320,4},{7,-5000,-8200,192,2},{8,-4550,-10600,192,2},{9,-8750,-15000,384,2},
                {10,-7400,-14750,1600,8}}) {
            for(int z=area[2]-area[3];z<=area[2]+area[3];z+=area[4])for(int x=area[1]-area[3];x<=area[1]+area[3];x+=area[4]) {
                var c=terrain.column(x,z);int h=c.surfaceY();var cap=c.state(h+1);
                rows.append(area[0]).append(',').append(x).append(',').append(z).append(',').append(h).append(',')
                        .append(map.waterSurface(x,z)).append(',').append(map.coastDistance(x,z)).append(',').append(map.region(x,z)).append(',')
                        .append(FixedTerrain.biomeIndex(map,x,z,h)).append(',').append(BuiltInRegistries.BLOCK.getKey(c.state(h).getBlock())).append(',')
                        .append(BuiltInRegistries.BLOCK.getKey(cap.getBlock())).append(',')
                        .append(cap.hasProperty(BlockStateProperties.LAYERS)?cap.getValue(BlockStateProperties.LAYERS).intValue():0).append('\n');
            }
        }
        Files.writeString(output.resolve("review12.csv"),rows);
        rows.setLength(0);rows.append("line,x,z,y,material\n");
        for(var line:new int[][]{{0,-11400,-12000,256,100,525},{1,-4800,-14768,256,40,380},
                {2,-4960,-15104,256,40,330},{3,-7920,-11848,160,90,270},{4,-5000,-8200,192,40,130},
                {5,-4550,-10600,192,70,140},{6,-8600,-15000,256,-127,65}}) {
            for(int dx=-line[3];dx<=line[3];dx++) {
                int x=line[1]+(line[0]==6?0:dx),z=line[2]+(line[0]==6?dx:0);var c=terrain.column(x,z);
                for(int y=line[4];y<=line[5];y++)rows.append(line[0]).append(',').append(x).append(',').append(z).append(',').append(y).append(',')
                        .append(BuiltInRegistries.BLOCK.getKey(c.state(y).getBlock())).append('\n');
            }
        }
        Files.writeString(output.resolve("review12-sections.csv"),rows);
        rows.setLength(0);rows.append("x,z,water,depth,kind,quality,freeze\n");
        int candidates=0;
        for(int z=-16000;z<-3000;z+=64)for(int x=-15000;x<3000;x+=64) {
            int water=map.waterSurface(x,z);if(water==Integer.MIN_VALUE)continue;
            int mask=map.waterMask(x,z),kind=(mask&8)!=0?3:(mask&2)!=0?2:1;
            int depth=water-terrain.surfaceY(x,z);
            double cold=Math.abs(MapCoordinates.latitude(z))+(water-63)/32.0+map.boundary(x,z).cold()*12;
            double threshold=kind==3?72:kind==2?64:60;
            boolean freeze=cold>threshold && depth>0;
            if(freeze)candidates++;
            rows.append(x).append(',').append(z).append(',').append(water).append(',').append(depth).append(',')
                    .append(kind).append(',').append(map.waterQuality(x,z)).append(',').append(freeze?1:0).append('\n');
        }
        helper.assertTrue(candidates>100,"寒冷な浅い湾・湖・川の凍結候補を制作側で分類");
        Files.writeString(output.resolve("freeze-candidates.csv"),rows);
        helper.succeed();
    }
}
