package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MountainGameTests {
    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void islandSummitsMatchGeneratedColumns(GameTestHelper helper) throws Exception {
        var generator = TerrainPreviewGameTests.generator(helper);
        var level = helper.getLevel();
        int count = 0;
        var positions = new ArrayList<ChunkPos>();
        try (var stream = MountainGameTests.class.getResourceAsStream("/data/ashen_frontier/fixed_map/"+generator.mapVersion().mapVersion()+"/index.json")) {
            var index = JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            for (var value : index.getAsJsonArray("mountain_islands")) {
                var island = value.getAsJsonObject();
                for (var row : island.getAsJsonArray("pixel_spans_y_x0_x1ex")) {
                    var span = row.getAsJsonArray();
                    for (int px = span.get(1).getAsInt(); px < span.get(2).getAsInt(); px++) {
                        positions.add(new ChunkPos(px - 1024, span.get(0).getAsInt() - 1024));
                    }
                }
                var point = island.getAsJsonArray("summit_xz");
                int x = point.get(0).getAsInt(), z = point.get(1).getAsInt();
                var column = generator.terrain().column(x,z);
                helper.assertTrue(column.surfaceY() <= 213 && column.surfaceY() >= island.get("peak_y").getAsInt()-2,"北へ低くなる実際の島の峰");
                helper.assertTrue(generator.terrain().map().geologyProfile(x,z)==3,"島にも山脈の母岩を適用する");
                var pos = new ChunkPos(x>>4,z>>4);
                var chunk = new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(-128,704),level.registryAccess().registryOrThrow(Registries.BIOME),null);
                generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),chunk).join();
                for (int sx=pos.getMinBlockX();sx<=pos.getMaxBlockX();sx++) for (int sz=pos.getMinBlockZ();sz<=pos.getMaxBlockZ();sz++) {
                    var sample = generator.terrain().column(sx,sz);
                    var base = generator.getBaseColumn(sx,sz,LevelHeightAccessor.create(-128,704),null);
                    for (int y=-128;y<448;y++) {
                        helper.assertTrue(chunk.getBlockState(new BlockPos(sx,y,sz)).equals(sample.state(y)),"島の実チャンクと列の一致");
                        helper.assertTrue(base.getBlock(y).equals(sample.state(y)),"島のbase columnと列の一致");
                        count++;
                    }
                    helper.assertTrue(generator.getBaseHeight(sx,sz,Heightmap.Types.WORLD_SURFACE_WG,LevelHeightAccessor.create(-128,704),null)==sample.topY()+1,"島の雪を含むbase heightの一致");
                }
                LogUtils.getLogger().info("MOUNTAIN_ISLAND_GENERATED id={} xyz={},{},{}",island.get("id"),x,column.surfaceY(),z);
            }
        }
        LogUtils.getLogger().info("MOUNTAIN_ISLAND_MATCH blocks={}",count);
        long[] times = new long[1000];
        for (int i = -100; i < times.length; i++) {
            var position = positions.get(Math.floorMod(i, positions.size()));
            long start = System.nanoTime();
            var chunk = new ProtoChunk(position,UpgradeData.EMPTY,LevelHeightAccessor.create(-128,704),level.registryAccess().registryOrThrow(Registries.BIOME),null);
            generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),chunk).join();
            if (i >= 0) times[i] = System.nanoTime() - start;
            helper.assertTrue(chunk.getBlockState(new BlockPos(position.getMinBlockX(),-128,position.getMinBlockZ())).is(Blocks.BEDROCK),"島の実チャンクを計測する");
        }
        Arrays.sort(times);
        LogUtils.getLogger().info("MOUNTAIN_ISLAND_BENCHMARK count=1000 unique_chunks={} warmup=100 parallelism=1 p50_ms={} p95_ms={} max_ms={}",
                positions.size(),times[499]/1e6,times[949]/1e6,times[999]/1e6);
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void miningAndNorthernPassesRemainWalkable(GameTestHelper helper) throws Exception {
        var generator=TerrainPreviewGameTests.generator(helper);
        var terrain=generator.terrain();
        int count=0;
        try(var stream=MountainGameTests.class.getResourceAsStream("/data/ashen_frontier/fixed_map/"+generator.mapVersion().mapVersion()+"/index.json")) {
            var index=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            for(var value:index.getAsJsonArray("walkways")) {
                var route=value.getAsJsonObject();var points=route.getAsJsonArray("points_x_z_y");
                double previous=Double.NaN;
                for(int i=1;i<points.size();i++) {
                    var a=points.get(i-1).getAsJsonArray();var b=points.get(i).getAsJsonArray();
                    double ax=a.get(0).getAsDouble(),az=a.get(1).getAsDouble(),dx=b.get(0).getAsDouble()-ax,dz=b.get(1).getAsDouble()-az;
                    int steps=Math.max(1,(int)Math.ceil(Math.hypot(dx,dz)));
                    for(int j=0;j<=steps;j++) {
                        int x=(int)Math.round(ax+dx*j/steps),z=(int)Math.round(az+dz*j/steps);
                        var column=terrain.column(x,z);int ground=column.surfaceY();var floor=column.state(ground);var cover=column.state(ground+1);
                        helper.assertTrue(!floor.isAir() && floor.getFluidState().isEmpty() && !floor.is(Blocks.POWDER_SNOW),"徒歩経路の固体の床: "+route.get("id")+" "+x+","+z);
                        helper.assertTrue(column.state(ground+2).isAir() && column.state(ground+3).isAir(),"峠と歩行路の頭上の余裕");
                        double top=ground+1+(cover.is(Blocks.SNOW)?cover.getValue(SnowLayerBlock.LAYERS)/8.0:0);
                        helper.assertTrue(Double.isNaN(previous) || Math.abs(top-previous)<=1.25,"雪を含む徒歩の段差: "+x+","+z+" "+previous+" -> "+top);
                        previous=top;count++;
                    }
                }
            }
        }
        helper.assertTrue(count>8000,"鉱業地帯と寒冷拠点を結ぶ全徒歩経路");
        LogUtils.getLogger().info("MOUNTAIN_WALKABLE columns={}",count);
        helper.succeed();
    }
}
