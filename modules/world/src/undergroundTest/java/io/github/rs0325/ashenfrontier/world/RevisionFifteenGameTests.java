package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RevisionFifteenGameTests {
    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=2400)
    public static void islandMassifsHaveFixedPeaksGulliesAndExposedStrata(GameTestHelper helper) throws Exception {
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();
        var rows=new StringBuilder("island,x,z,height,coast,material,cover,layers\n");
        var section=new StringBuilder("island,x,z,y,material\n");
        var rocks=new HashSet<String>();int peaks=0,talus=0,thinSnow=0;
        try(var stream=RevisionFifteenGameTests.class.getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/index.json")) {
            var index=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            for(var value:index.getAsJsonArray("mountain_islands")) {
                var island=value.getAsJsonObject();String id=island.get("id").getAsString();
                int x0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,z0=Integer.MAX_VALUE,z1=Integer.MIN_VALUE;
                for(var row:island.getAsJsonArray("pixel_spans_y_x0_x1ex")) {
                    var span=row.getAsJsonArray();x0=Math.min(x0,span.get(1).getAsInt()*16-16384);x1=Math.max(x1,span.get(2).getAsInt()*16-16384);
                    z0=Math.min(z0,span.get(0).getAsInt()*16-16384);z1=Math.max(z1,span.get(0).getAsInt()*16-16384);
                }
                for(int z=z0-24;z<=z1+24;z+=2)for(int x=x0-24;x<=x1+24;x+=2) {
                    var c=terrain.column(x,z);int h=c.surfaceY();var material=c.state(h);var cap=c.state(h+1);
                    int layers=cap.hasProperty(BlockStateProperties.LAYERS)?cap.getValue(BlockStateProperties.LAYERS):0;
                    if(terrain.map().coastDistance(x,z)>0 && terrain.map().mountainIsland(x,z)!=null) {
                        helper.assertTrue(h<=205,"島の高度をr14の上限内に保つ");
                        String block=BuiltInRegistries.BLOCK.getKey(material.getBlock()).getPath();
                        if(block.equals("talus"))talus++;
                        if(block.equals("granite")||block.equals("gneiss")||block.equals("quartzite"))rocks.add(block);
                        if(cap.is(Blocks.SNOW)){helper.assertTrue(layers<=4,"島の積雪は薄くする");thinSnow++;}
                    }
                    rows.append(id).append(',').append(x).append(',').append(z).append(',').append(h).append(',')
                            .append(terrain.map().coastDistance(x,z)).append(',').append(BuiltInRegistries.BLOCK.getKey(material.getBlock())).append(',')
                            .append(BuiltInRegistries.BLOCK.getKey(cap.getBlock())).append(',').append(layers).append('\n');
                }
                for(var definition:island.getAsJsonArray("summits")) {
                    var peak=definition.getAsJsonObject();var xz=peak.getAsJsonArray("xz");int x=xz.get(0).getAsInt(),z=xz.get(1).getAsInt();
                    int h=terrain.surfaceY(x,z);
                    helper.assertTrue(h>=peak.get("peak_y").getAsInt()-1,"固定した小峰の高さ");
                    for(int dx:new int[]{-8,0,8})for(int dz:new int[]{-8,0,8})
                        helper.assertTrue(terrain.surfaceY(x+dx,z+dz)<=h+1,"小峰が局所的な頂となる");
                    peaks++;
                }
                int z=island.getAsJsonArray("summit_xz").get(1).getAsInt();
                for(int x=x0-24;x<=x1+24;x++) {
                    var c=terrain.column(x,z);
                    for(int y=45;y<=210;y++)section.append(id).append(',').append(x).append(',').append(z).append(',').append(y).append(',')
                            .append(BuiltInRegistries.BLOCK.getKey(c.state(y).getBlock())).append('\n');
                }
            }
        }
        helper.assertTrue(peaks>=12 && rocks.size()==3 && talus>20 && thinSnow>20,"複数小峰・3岩相の地層・崖錐・薄雪の実生成: "+peaks+" / "+rocks+" / "+talus+" / "+thinSnow);
        var output=ReliefRevisionGameTests.output();
        if(output!=null){Files.writeString(output.resolve("review15-islands.csv"),rows);Files.writeString(output.resolve("review15-sections.csv"),section);}
        com.mojang.logging.LogUtils.getLogger().info("REVISION15 peaks={} rocks={} talus={} thin_snow={}",peaks,rocks,talus,thinSnow);
        helper.succeed();
    }
}
