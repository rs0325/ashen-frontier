package io.github.rs0325.ashenfrontier.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;

/** r9の山の島と源流を、配布対象外で検査・画像化する。 */
@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RevisionNineGameTests {
    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void islandWallsHaveNoBeachAndTheSourceKeepsItsWater(GameTestHelper helper) throws Exception {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();var map=terrain.map();
        int coastColumns=0,directRise=0;
        var rows=new StringBuilder("island,x,z,ground,coast,material,top\n");
        for(int z=-15360;z<=-14300;z+=4)for(int x=-5350;x<=-4300;x+=4) {
            var island=map.mountainIsland(x,z);
            if(island==null)continue;
            double coast=map.coastDistance(x,z);var c=terrain.column(x,z);
            var material=c.state(c.surfaceY());
            if(coast>=0 && coast<32) {
                helper.assertTrue(!material.is(Blocks.SAND) && !material.is(Blocks.GRAVEL),"山の島には砂浜・礫浜を作らず、露岩と崖錐を使う");
                if(coast<8)helper.assertTrue(material.equals(c.rockState(c.surfaceY()))
                        && !c.state(c.surfaceY()+1).is(Blocks.SNOW),"水際直近は雪・堆積物を介さず母岩で接続");
                coastColumns++;
                if(coast>=8 && c.surfaceY()>=68)directRise++;
            }
            rows.append(island.peakY()).append(',').append(x).append(',').append(z).append(',').append(c.surfaceY()).append(',')
                    .append(coast).append(',').append(BuiltInRegistries.BLOCK.getKey(material.getBlock())).append(',').append(c.topY()).append('\n');
        }
        helper.assertTrue(coastColumns>500 && directRise>200,"海から直接立ち上がる山の島の確認数");
        int x=-4000,z=-12100,water=map.waterSurface(x,z);var source=terrain.column(x,z);
        helper.assertTrue(water>=63 && water<=110 && water-source.surfaceY()>=2,"源流の谷頭と水深");
        helper.assertTrue(!source.state(water).getFluidState().isEmpty(),"源流が山で埋まらず水を持つ");
        LogUtils.getLogger().info("REVISION9_ISLAND coastal_columns={} direct_rise={} source_bed={} source_water={}",coastColumns,directRise,source.surfaceY(),water);
        if(ReliefRevisionGameTests.output()!=null)Files.writeString(ReliefRevisionGameTests.output().resolve("island-walls.csv"),rows);
        helper.succeed();
    }
}
