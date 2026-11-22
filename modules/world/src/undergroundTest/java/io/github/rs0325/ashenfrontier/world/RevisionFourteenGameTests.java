package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RevisionFourteenGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 2400)
    public static void transitionLayersAndDeepColdSea(GameTestHelper helper) throws Exception {
        var terrain = TerrainPreviewGameTests.generator(helper).terrain();
        var rows = new StringBuilder("area,x,z,height,material,cover,layers,powder\n");
        int thin = 0, thick = 0, powder = 0, frozen = 0;
        for (var area : new int[][]{{0,-3800,-13584,192,2},{1,-3728,-14624,192,2},
                {2,-4550,-10600,192,2},{3,-7920,-11848,160,2},
                {4,-3004,-15012,64,1},{5,-3676,-13548,64,1},{6,-10876,-7236,64,1}}) {
            for (int z=area[2]-area[3];z<=area[2]+area[3];z+=area[4])
                for (int x=area[1]-area[3];x<=area[1]+area[3];x+=area[4]) {
                    var c=terrain.column(x,z);int h=c.surfaceY();var material=c.state(h);var cap=c.state(h+1);
                    int layers=cap.hasProperty(BlockStateProperties.LAYERS)?cap.getValue(BlockStateProperties.LAYERS):0;
                    if(layers>0 && layers<=2)thin++;
                    if(layers>=6)thick++;
                    if(material.is(Blocks.POWDER_SNOW)) {
                        powder++;
                        helper.assertTrue(!terrain.map().hazardProtected(x,z),"粉雪を必須経路・入口・施設の保護域に置かない");
                    }
                    rows.append(area[0]).append(',').append(x).append(',').append(z).append(',').append(h).append(',')
                            .append(BuiltInRegistries.BLOCK.getKey(material.getBlock())).append(',')
                            .append(BuiltInRegistries.BLOCK.getKey(cap.getBlock())).append(',').append(layers).append(',')
                            .append(material.is(Blocks.POWDER_SNOW)?1:0).append('\n');
                }
        }
        for(int z=-15100;z<=-14900;z+=4) for(int x=-8900;x<=-8300;x+=8) {
            var c=terrain.column(x,z);
            if(c.surfaceY()<-70) {
                helper.assertTrue(c.state(63).is(NatureContent.block("sea_ice")),"寒冷地の海溝上にも海氷を置く");
                helper.assertTrue(c.state(62).is(NatureContent.block("polluted_seawater")),"海氷の下の水質を保つ");
                frozen++;
            }
        }
        helper.assertTrue(thin>100 && thick>100 && powder>0 && frozen>10,"薄い境界片・厚い内側・粉雪・深い海の実地形を検査");
        var output=ReliefRevisionGameTests.output();
        if(output!=null)Files.writeString(output.resolve("review14-details.csv"),rows);
        com.mojang.logging.LogUtils.getLogger().info("REVISION14 thin={} thick={} powder={} frozen_trench={}",thin,thick,powder,frozen);
        helper.succeed();
    }
}
