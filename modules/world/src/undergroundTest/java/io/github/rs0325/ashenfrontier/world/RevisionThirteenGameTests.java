package io.github.rs0325.ashenfrontier.world;

import com.mojang.logging.LogUtils;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RevisionThirteenGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 2400)
    public static void waterIceKeepsItsQualityAndGeologicalContactsCrossValleys(GameTestHelper helper) throws Exception {
        var terrain = TerrainPreviewGameTests.generator(helper).terrain();
        String[] ice = {"raw_water_ice", "turbid_water_ice", "contaminated_water_ice", "toxic_water_ice", "sea_ice"};
        String[] waterIds = {"raw_water", "turbid_water", "contaminated_water", "toxic_water", "polluted_seawater"};
        var fixtures = new StringBuilder("quality,y,material\n");
        for (int quality = 4; quality <= 8; quality++) {
            var frozen = terrain.frozenWater(quality, -4000, -15000, 63, 8, 3, 1);
            helper.assertTrue(frozen.is(NatureContent.block(ice[quality - 4])), "元の水質に対応する氷");
            helper.assertTrue(terrain.frozenWater(quality, -4000, -5000, 63, 8, 3, 0) == null, "温暖な水面を凍らせない");
            var pos = helper.absolutePos(new BlockPos(quality - 4, 3, 0));
            var source = NatureContent.block(waterIds[quality - 4]).defaultBlockState();
            helper.getLevel().setBlock(pos.below(), source, Block.UPDATE_CLIENTS);
            helper.getLevel().setBlock(pos, frozen, Block.UPDATE_CLIENTS);
            helper.assertTrue(helper.getLevel().getBlockState(pos) == frozen
                    && helper.getLevel().getBlockState(pos.below()) == source, "5水質の検証用水面に氷と元の水を配置");
            fixtures.append(quality).append(",63,").append(BuiltInRegistries.BLOCK.getKey(frozen.getBlock())).append('\n');
            fixtures.append(quality).append(",62,").append(BuiltInRegistries.BLOCK.getKey(source.getBlock())).append('\n');
        }
        for (int quality = 1; quality <= 3; quality++)
            helper.assertTrue(terrain.frozenWater(quality, -4000, -15000, 63, 8, 3, 1) == null, "未承認の清浄水・純水・超純水の氷は置かない");
        int sea = 0;
        var rows = new StringBuilder("x,z,water,depth,quality,material\n");
        for (int z = -16000; z <= -13000; z += 32) for (int x = -14000; x <= 3000; x += 32) {
            int water = terrain.map().waterSurface(x, z);
            if (water == Integer.MIN_VALUE) continue;
            var column = terrain.column(x, z);
            var material = column.state(water);
            if (material.is(NatureContent.block("sea_ice"))) {
                helper.assertTrue(terrain.map().waterQuality(x, z) == 8, "海氷は海水の水面に置く");
                if (water - column.surfaceY() > 1)
                    helper.assertTrue(column.state(water - 1).is(NatureContent.block("polluted_seawater")), "海氷の下も同じ水質");
                sea++;
            }
            rows.append(x).append(',').append(z).append(',').append(water).append(',').append(water - column.surfaceY()).append(',')
                    .append(terrain.map().waterQuality(x,z)).append(',').append(BuiltInRegistries.BLOCK.getKey(material.getBlock())).append('\n');
        }
        helper.assertTrue(sea > 100, "寒冷な浅い湾の実地形に海氷が生成される");
        var contacts = new StringBuilder("x,z,surface,quartzite_bottom\n");
        int eroded = 0, correlated = 0, previousHeight = 0, previousContact = 0;
        for (int x = -11656; x <= -11144; x++) {
            var column = terrain.column(x, -12000);
            int contact = -999;
            for (int y = 100; y <= column.surfaceY() - 4; y++)
                if (column.rockState(y).is(NatureContent.block("quartzite"))) { contact = y; break; }
            contacts.append(x).append(",-12000,").append(column.surfaceY()).append(',').append(contact).append('\n');
            if (!column.rockState(column.surfaceY() - 4).is(Blocks.GRANITE)) eroded++;
            if (contact != -999 && previousContact != -999 && Math.abs(column.surfaceY()-previousHeight) >= 2 && Math.abs(contact-previousContact) <= 1) correlated++;
            previousContact = contact; previousHeight = column.surfaceY();
        }
        helper.assertTrue(eroded > 0 && correlated > 3, "谷で地層が切られ、傾く同じ層理面が急な地表と独立して続く: " + eroded + "/" + correlated);
        LogUtils.getLogger().info("REVISION13_ICE sea={} strata_eroded={} independent_contacts={}", sea, eroded, correlated);
        var output = ReliefRevisionGameTests.output();
        if (output != null) {
            Files.writeString(output.resolve("review13-ice.csv"), rows);
            Files.writeString(output.resolve("review13-contacts.csv"), contacts);
            Files.writeString(output.resolve("review13-fixtures.csv"), fixtures);
        }
        helper.succeed();
    }
}
