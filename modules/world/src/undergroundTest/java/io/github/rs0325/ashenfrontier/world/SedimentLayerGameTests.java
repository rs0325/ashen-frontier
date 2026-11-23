package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.SoilContamination;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.HashSet;
import java.util.ArrayList;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SedimentLayerGameTests {
    private static ProtoChunk chunk(GameTestHelper helper, FixedMapChunkGenerator generator, int x, int z) {
        var result=new ProtoChunk(new ChunkPos(x>>4,z>>4), UpgradeData.EMPTY, LevelHeightAccessor.create(-128,704),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
        generator.fillFromNoise(Blender.empty(),helper.getLevel().getChunkSource().randomState(),helper.getLevel().structureManager(),result).join();
        return result;
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void generatedSedimentsHaveFractionalSlopes(GameTestHelper helper) {
        var generator=TerrainPreviewGameTests.generator(helper);
        var distribution=new FixedContamination(generator.terrain().map());
        int[][] sites={{-12400,-5400},{-4708,-10668},{-4924,-15132}};
        String[] ids={"loess_layer","silt_layer","talus_layer"};
        for(int site=0;site<sites.length;site++) {
            var thicknesses=new HashSet<Integer>();int[] counts=new int[9];
            int[][] tops=new int[64][64];
            int[][] ground=new int[64][64];
            int x0=(sites[site][0]>>4)*16-32,z0=(sites[site][1]>>4)*16-32;
            for(int z=z0;z<z0+64;z+=16) for(int x=x0;x<x0+64;x+=16) {
                var chunk=chunk(helper,generator,x,z);
                for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                    var column=generator.terrain().column(x+dx,z+dz);
                    var pos=new BlockPos(x+dx,column.topY(),z+dz);
                    var state=chunk.getBlockState(pos);
                    if(!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals(ids[site]))continue;
                    int layers=state.getValue(SnowLayerBlock.LAYERS);
                    thicknesses.add(layers);counts[layers]++;
                    tops[z+dz-z0][x+dx-x0]=pos.getY()*8+layers;
                    ground[z+dz-z0][x+dx-x0]=column.surfaceY()*8;
                    helper.assertTrue(state.equals(column.state(pos.getY())),"実チャンクと列の薄層状態が一致");
                    helper.assertTrue(state.getShape(helper.getLevel(),pos).bounds().maxY==layers/8.0,"描画形状も1/8ブロック単位");
                    helper.assertTrue(!chunk.getBlockState(pos.below()).isAir(),"薄層が地面で支持される");
                    var map=generator.terrain().map();
                    helper.assertTrue(column.contamination(pos.getY()).equals(distribution.surface(x+dx,z+dz,
                            map.coastDistance(x+dx,z+dz),map.river(x+dx,z+dz))),"薄層の実際の天面でも従来の地表汚染値を保持");
                }
            }
            helper.assertTrue(thicknesses.stream().anyMatch(n->n<8),"斜面に薄い層が存在: "+ids[site]+" "+thicknesses);
            if(site==0)helper.assertTrue(thicknesses.containsAll(java.util.Set.of(1,2,3,4,5,6,7,8)),"黄土内陸に全8厚さを生成");
            LogUtils.getLogger().info("SEDIMENT_LAYER_DISTRIBUTION id={} counts={}",ids[site],java.util.Arrays.toString(counts));
            var differences=new ArrayList<Integer>();
            for(int z=0;z<63;z++)for(int x=0;x<63;x++)if(tops[z][x]!=0) {
                if(tops[z][x+1]!=0) {
                    differences.add(Math.abs(tops[z][x]-tops[z][x+1]));
                    if(site==2)helper.assertTrue(Math.abs(tops[z][x]-ground[z][x]-tops[z][x+1]+ground[z][x+1])<=1,
                            "岩場の地形段差は保ち、崖錐の散布層の差だけを1層以内にする");
                }
                if(tops[z+1][x]!=0) {
                    differences.add(Math.abs(tops[z][x]-tops[z+1][x]));
                    if(site==2)helper.assertTrue(Math.abs(tops[z][x]-ground[z][x]-tops[z+1][x]+ground[z+1][x])<=1,
                            "崖錐の散布層の差は1層以内");
                }
            }
            differences.sort(Integer::compareTo);
            helper.assertTrue(differences.size()>20,"隣接する実薄層を比較できる");
            int p95=differences.get(differences.size()*95/100);
            if(site<2)helper.assertTrue(p95<=3,"緩斜面の隣接天面差の95%以上が3層以内: "+ids[site]+" "+p95);
            else helper.assertTrue(thicknesses.stream().allMatch(n->n<=2),"海岸の岩場に散る崖錐は全て1〜2層");
            LogUtils.getLogger().info("SEDIMENT_ADJACENT id={} pairs={} p95_eighths={} max_eighths={}",
                    ids[site],differences.size(),p95,differences.getLast());
        }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void coastalScatterHasAtMostTwoLayers(GameTestHelper helper) {
        var generator=TerrainPreviewGameTests.generator(helper);
        var map=generator.terrain().map();int checked=0;
        for(int z=-6016;z<-5920;z+=16)for(int x=-14112;x<-14016;x+=16) {
            var chunk=chunk(helper,generator,x,z);
            for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                var column=generator.terrain().column(x+dx,z+dz);
                var pos=new BlockPos(x+dx,column.topY(),z+dz);
                var state=chunk.getBlockState(pos);
                if(!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals("loess_layer"))continue;
                double margin=TerrainMath.smooth((map.coastDistance(x+dx,z+dz)
                        -OceanRelief.shore(map,x+dx,z+dz).beachWidth()+6)/32);
                if(margin>.92)continue;
                helper.assertTrue(state.getValue(SnowLayerBlock.LAYERS)<=2,"海浜の縁に散る黄土は1〜2層");
                helper.assertTrue(chunk.getBlockState(pos.below()).is(Blocks.SAND),
                        "縁の薄層の下にフルブロックの黄土を残さない");
                checked++;
            }
        }
        helper.assertTrue(checked>20,"海浜の散らしを実ブロックで検査");
        LogUtils.getLogger().info("SEDIMENT_COASTAL_SCATTER checked={} max_layers=2",checked);
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void generatedReserveKeepsLowNonzeroTraces(GameTestHelper helper) {
        var generator=TerrainPreviewGameTests.generator(helper);
        int light=0,total=0;
        for(int z=-8320;z<-8064;z+=64)for(int x=-5728;x<-5472;x+=64) {
            var chunk=chunk(helper,generator,x,z);
            for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                var column=generator.terrain().column(x+dx,z+dz);
                var pos=new BlockPos(x+dx,column.surfaceY(),z+dz);
                helper.assertTrue(BlockContamination.isTarget(chunk.getBlockState(pos)),"予備地の実ブロックは土・堆積物");
                var value=column.contamination(pos.getY());boolean trace=false;
                for(var kind:SoilContamination.Kind.values()) {
                    helper.assertTrue(value.value(kind)>0 && value.stage(kind)<=1,"全種類非ゼロで清浄〜軽度");
                    trace|=value.stage(kind)==1;
                }
                if(trace)light++;total++;
            }
        }
        helper.assertTrue(light>0 && light<total/3,"比較的きれいだが軽度の痕跡が残る");
        LogUtils.getLogger().info("RESERVE_TRACES sampled={} light={}",total,light);
        helper.succeed();
    }
}
