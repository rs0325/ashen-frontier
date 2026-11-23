package io.github.rs0325.ashenfrontier.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 実際の列から堆積環境と確認地点を採取する、配布対象外の検査。 */
@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SedimentGameTests {
    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=2400)
    public static void sedimentsOccurInTheirFixedEnvironments(GameTestHelper helper) throws Exception {
        var generator = TerrainPreviewGameTests.generator(helper);
        var terrain = generator.terrain();
        var wanted = Set.of("talus", "river_gravel", "glacial_till", "silt", "loess", "black_sand",
                "talus_layer", "silt_layer", "loess_layer", "tidal_mud", "organic_mud", "peat", "cracked_mud");
        Map<String,int[]> points = new LinkedHashMap<>();
        Map<String,Integer> counts = new LinkedHashMap<>();
        for (int z=-15300;z<=-2800;z+=24) for (int x=-14500;x<=-3000;x+=24) {
            if (terrain.map().coastDistance(x,z)<-600) continue;
            var c=terrain.column(x,z); int y=c.surfaceY();
            for (int sample=y+1;sample>=y-4;sample--) {
                var state=c.state(sample);
                var id=BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if (!id.getNamespace().equals("ashen_frontier") || !wanted.contains(id.getPath())) continue;
                if (id.getPath().equals("silt") || id.getPath().equals("silt_layer")) {
                    helper.assertTrue(terrain.map().river(x,z).distance()<150,"河道範囲外の海岸をシルトで埋めない");
                }
                counts.merge(id.getPath(),1,Integer::sum);
                if (sample>=y && !c.state(sample).isAir()) points.putIfAbsent(id.getPath(),new int[]{x,sample,z});
                if (state.hasProperty(BlockStateProperties.LAYERS)) {
                    helper.assertTrue(sample==c.topY() && c.state(sample-1).isCollisionShapeFullBlock(helper.getLevel(),BlockPos.ZERO),
                            "堆積層は地面の上で支持される");
                    helper.assertTrue(c.topY()==sample,"堆積層を含む列の高さ");
                }
            }
        }
        LogUtils.getLogger().info("SEDIMENT_COUNTS {}",counts);
        for (var id:wanted) helper.assertTrue(points.containsKey(id),"地形に堆積物が露出する: "+id);
        var rows=new StringBuilder("id,x,y,z\n");
        var sections=new StringBuilder("id,offset,y,material\n");
        for (var entry:points.entrySet()) {
            int[] p=entry.getValue();
            var level=helper.getLevel();
            var height=LevelHeightAccessor.create(-128,704);
            var chunk=new ProtoChunk(new ChunkPos(p[0]>>4,p[2]>>4),UpgradeData.EMPTY,height,
                    level.registryAccess().registryOrThrow(Registries.BIOME),null);
            generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),chunk).join();
            var base=generator.getBaseColumn(p[0],p[2],height,null);
            var column=terrain.column(p[0],p[2]);
            for (int y=-128;y<=575;y++) helper.assertTrue(chunk.getBlockState(new BlockPos(p[0],y,p[2])).equals(column.state(y))
                    && base.getBlock(y).equals(column.state(y)),"堆積物の実チャンク・base columnの一致: "+entry.getKey());
            helper.assertTrue(generator.getBaseHeight(p[0],p[2],Heightmap.Types.WORLD_SURFACE_WG,height,null)==column.topY()+1,
                    "堆積層を含むbase heightの一致");
            rows.append(entry.getKey()).append(',').append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append('\n');
            LogUtils.getLogger().info("SEDIMENT_VIEW id={} xyz={},{},{}",entry.getKey(),p[0],p[1],p[2]);
            for (int dx=-32;dx<=32;dx++) {
                var c=terrain.column(p[0]+dx,p[2]);
                for (int y=p[1]-18;y<=p[1]+18;y++) {
                    sections.append(entry.getKey()).append(',').append(dx).append(',').append(y).append(',')
                            .append(BuiltInRegistries.BLOCK.getKey(c.state(y).getBlock())).append('\n');
                }
            }
        }
        if (ReliefRevisionGameTests.output()!=null) {
            Files.writeString(ReliefRevisionGameTests.output().resolve("sediments.csv"),rows);
            Files.writeString(ReliefRevisionGameTests.output().resolve("sediment-sections.csv"),sections);
            var plans=new StringBuilder("id,x,z,height,water,material,cover\n");
            for(var id:Set.of("tidal_mud","organic_mud","peat","cracked_mud")) {
                int[] p=points.get(id);
                for(int z=p[2]-96;z<=p[2]+96;z+=2)for(int x=p[0]-96;x<=p[0]+96;x+=2) {
                    var c=terrain.column(x,z);int h=c.surfaceY();
                    plans.append(id).append(',').append(x).append(',').append(z).append(',').append(h).append(',')
                            .append(terrain.map().waterSurface(x,z)).append(',')
                            .append(BuiltInRegistries.BLOCK.getKey(c.state(h).getBlock())).append(',')
                            .append(BuiltInRegistries.BLOCK.getKey(c.state(h+1).getBlock())).append('\n');
                }
            }
            Files.writeString(ReliefRevisionGameTests.output().resolve("mud-plans.csv"),plans);
        }
        helper.succeed();
    }
}
