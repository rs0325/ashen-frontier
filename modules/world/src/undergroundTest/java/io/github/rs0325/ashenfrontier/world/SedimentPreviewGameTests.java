package io.github.rs0325.ashenfrontier.world;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

/** 実チャンクのブロックと選択形状から、薄層の前後比較用データを任意出力する。 */
@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SedimentPreviewGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 2400)
    public static void exportSedimentBlockShapes(GameTestHelper helper) throws Exception {
        String directory = System.getenv("ASHEN_SEDIMENT_PREVIEW");
        if (directory == null) { helper.succeed(); return; }
        var output = Path.of(directory);
        Files.createDirectories(output);
        var generator = TerrainPreviewGameTests.generator(helper);
        var terrain = generator.terrain();
        int coastX = -12400, coastZ = -5400;
        double best = -Double.MAX_VALUE;
        for (int z = -7800; z <= -4200; z += 64) for (int x = -15000; x <= -9000; x += 64) {
            double coast = terrain.map().coastDistance(x,z);
            if (terrain.map().region(x,z) != 4 || coast < 24 || coast > 120) continue;
            var column = terrain.column(x,z);
            if (!BuiltInRegistries.BLOCK.getKey(column.state(column.surfaceY()).getBlock()).getPath().equals("loess")) continue;
            int loess = 0;
            for (int dz = -32; dz <= 32; dz += 16) for (int dx = -32; dx <= 32; dx += 16) {
                var c = terrain.column(x+dx,z+dz);
                if (BuiltInRegistries.BLOCK.getKey(c.state(c.surfaceY()).getBlock()).getPath().equals("loess")) loess++;
            }
            double score = loess * 10 - Math.abs(coast-60) - Math.abs(column.surfaceY()-80);
            if (score > best) { best=score; coastX=x; coastZ=z; }
        }
        for (var area : new int[][]{{coastX,coastZ},{-12400,-5400}}) {
            int x0=(area[0]>>4)*16-192, z0=(area[1]>>4)*16-192, width=384, depth=384;
            var chunks = new HashMap<ChunkPos,ProtoChunk>();
            for (int z=z0;z<z0+depth;z+=16) for(int x=x0;x<x0+width;x+=16) {
                var position=new ChunkPos(x>>4,z>>4);
                var chunk=new ProtoChunk(position, UpgradeData.EMPTY, LevelHeightAccessor.create(-128,704),
                        helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
                generator.fillFromNoise(Blender.empty(),helper.getLevel().getChunkSource().randomState(),helper.getLevel().structureManager(),chunk).join();
                chunks.put(position,chunk);
            }
            var palette=new ArrayList<String>();
            var pixels=new JsonArray();
            var pos=new BlockPos.MutableBlockPos();
            int[] layerCounts=new int[9];
            for(int z=z0;z<z0+depth;z++) for(int x=x0;x<x0+width;x++) {
                var chunk=chunks.get(new ChunkPos(x>>4,z>>4));
                int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,x&15,z&15)+1;
                while(chunk.getBlockState(pos.set(x,top,z)).isAir())top--;
                if(BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).getPath().equals("dead_grass"))top--;
                var cell=new JsonArray(); cell.add(top);
                for(int y=top;y>=top-2;y--) {
                    var state=chunk.getBlockState(pos.set(x,y,z));
                    String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    int index=palette.indexOf(id);if(index<0){index=palette.size();palette.add(id);}
                    var shape=state.getShape(helper.getLevel(),pos);
                    double height=!state.getFluidState().isEmpty()?1:shape.isEmpty()?0:shape.bounds().maxY;
                    cell.add(index);cell.add(height);
                    if(y==top && id.equals("ashen_frontier:loess_layer")) layerCounts[state.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS)]++;
                }
                pixels.add(cell);
            }
            var result=new JsonObject();result.addProperty("x0",x0);result.addProperty("z0",z0);
            result.addProperty("width",width);result.addProperty("depth",depth);
            result.add("palette",new Gson().toJsonTree(palette));result.add("cells",pixels);
            double nx=terrain.map().coastDistance(area[0]+8,area[1])-terrain.map().coastDistance(area[0]-8,area[1]);
            double nz=terrain.map().coastDistance(area[0],area[1]+8)-terrain.map().coastDistance(area[0],area[1]-8);
            result.addProperty("inland_x",nx);result.addProperty("inland_z",nz);
            Files.writeString(output.resolve("sediment-"+area[0]+"_"+area[1]+".json"),new Gson().toJson(result));
            LogUtils.getLogger().info("SEDIMENT_PREVIEW center={},{} loess_layers={}",area[0],area[1],java.util.Arrays.toString(layerCounts));
        }
        helper.succeed();
    }
}
