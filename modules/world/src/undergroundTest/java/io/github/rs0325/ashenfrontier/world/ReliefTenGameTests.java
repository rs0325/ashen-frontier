package io.github.rs0325.ashenfrontier.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;

/** r10の見た目を同じ地点で比較する、配布対象外の列データ。 */
@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReliefTenGameTests {
    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void gentleConvexSnowAndWeatheredRockRemainCoherent(GameTestHelper helper) {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();var map=terrain.map();
        var rocks=new java.util.HashSet<String>();int snow=0;
        for(int z=-12400;z<=-11500;z+=8)for(int x=-12200;x<=-10500;x+=8) {
            var c=terrain.column(x,z);int y=c.surfaceY();
            rocks.add(BuiltInRegistries.BLOCK.getKey(c.rockState(y).getBlock()).toString());
            double e=terrain.surfaceY(x+8,z),w=terrain.surfaceY(x-8,z),n=terrain.surfaceY(x,z-8),s=terrain.surfaceY(x,z+8);
            double slope=Math.hypot(e-w,s-n)/16,hollow=(e+w+n+s)/4-y;
            if(y>240 && slope<.2 && hollow<-.3 && !map.isProtected(x,z)) {
                helper.assertTrue(c.state(y+1).is(Blocks.SNOW) || c.state(y).is(Blocks.SNOW_BLOCK)
                        || c.state(y).is(Blocks.POWDER_SNOW),"緩い凸地を曲率だけで無積雪にしない");
                snow++;
            }
        }
        helper.assertTrue(snow>5 && rocks.size()>=5,"凸地の雪と複数の岩肌材料");
        com.mojang.logging.LogUtils.getLogger().info("RELIEF10_SNOW convex_columns={} rock_materials={}",snow,rocks);
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=2400)
    public static void exportSurfaceAndProfiles(GameTestHelper helper) throws Exception {
        var output=ReliefRevisionGameTests.output();
        if(output==null){helper.succeed();return;}
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();var map=terrain.map();
        var rows=new StringBuilder("area,x,z,height,coast,water,material,cover,layers,snow,slope,river,width\n");
        for(var area:new int[][]{{0,-11400,-12000,256,4},{1,-3200,-13400,256,4},{2,-4800,-14768,320,4},
                {3,-11216,-6880,256,4},{4,-5000,-8200,320,4},{5,-8800,-15000,320,4},{6,1000,0,1600,16},
                {7,-3480,-14600,192,4},{8,-12400,-5300,256,4}}) {
            for(int z=area[2]-area[3];z<=area[2]+area[3];z+=area[4])for(int x=area[1]-area[3];x<=area[1]+area[3];x+=area[4]) {
                var c=terrain.column(x,z);int h=c.surfaceY();var state=c.state(h);var cap=c.state(h+1);int snow=0;
                while(c.state(h-snow).is(Blocks.SNOW_BLOCK))snow++;
                double slope=Math.hypot(terrain.surfaceY(x+4,z)-terrain.surfaceY(x-4,z),terrain.surfaceY(x,z+4)-terrain.surfaceY(x,z-4))/8;
                var river=map.river(x,z);
                rows.append(area[0]).append(',').append(x).append(',').append(z).append(',').append(h).append(',').append(map.coastDistance(x,z)).append(',')
                        .append(map.waterSurface(x,z)).append(',').append(BuiltInRegistries.BLOCK.getKey(state.getBlock())).append(',')
                        .append(BuiltInRegistries.BLOCK.getKey(cap.getBlock())).append(',')
                        .append(cap.hasProperty(BlockStateProperties.LAYERS)?cap.getValue(BlockStateProperties.LAYERS).intValue():0).append(',')
                        .append(snow).append(',').append(slope).append(',').append(river.distance()).append(',').append(river.width()).append('\n');
            }
        }
        Files.writeString(output.resolve("review10.csv"),rows);
        rows.setLength(0);rows.append("line,x,z,height,water\n");
        for(var line:new int[][]{{0,-8800,-14700,-8800,-15300},{1,-600,0,2600,0},
                {2,-4900,-10600,-4100,-10600},{3,-5400,-8200,-4600,-8200},{4,-5100,-7200,-4300,-7200}}) {
            int steps=Math.max(Math.abs(line[3]-line[1]),Math.abs(line[4]-line[2]));
            for(int i=0;i<=steps;i++) {
                int x=(int)Math.round(line[1]+(line[3]-line[1])*i/(double)steps),z=(int)Math.round(line[2]+(line[4]-line[2])*i/(double)steps);
                rows.append(line[0]).append(',').append(x).append(',').append(z).append(',').append(terrain.surfaceY(x,z)).append(',').append(map.waterSurface(x,z)).append('\n');
            }
        }
        Files.writeString(output.resolve("review10-profiles.csv"),rows);
        rows.setLength(0);rows.append("x,y,z,material\n");
        for(int x=-11600;x<=-11200;x+=2) {
            var c=terrain.column(x,-12000);
            for(int y=100;y<=400;y++)rows.append(x).append(',').append(y).append(',').append(-12000).append(',')
                    .append(BuiltInRegistries.BLOCK.getKey(c.state(y).getBlock())).append('\n');
        }
        Files.writeString(output.resolve("review10-rock-section.csv"),rows);
        helper.succeed();
    }
}
