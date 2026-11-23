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
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.HashSet;

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
        int[][] sites={{-12400,-5400},{-4684,-10452},{-4924,-15132}};
        String[] ids={"loess_layer","silt_layer","talus_layer"};
        for(int site=0;site<sites.length;site++) {
            var thicknesses=new HashSet<Integer>();int[] counts=new int[9];
            int x0=(sites[site][0]>>4)*16-32,z0=(sites[site][1]>>4)*16-32;
            for(int z=z0;z<z0+64;z+=16) for(int x=x0;x<x0+64;x+=16) {
                var chunk=chunk(helper,generator,x,z);
                for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                    var column=generator.terrain().column(x+dx,z+dz);
                    var pos=new BlockPos(x+dx,column.surfaceY()+1,z+dz);
                    var state=chunk.getBlockState(pos);
                    if(!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals(ids[site]))continue;
                    int layers=state.getValue(SnowLayerBlock.LAYERS);
                    thicknesses.add(layers);counts[layers]++;
                    helper.assertTrue(state.equals(column.state(pos.getY())),"実チャンクと列の薄層状態が一致");
                    helper.assertTrue(state.getShape(helper.getLevel(),pos).bounds().maxY==layers/8.0,"描画形状も1/8ブロック単位");
                    helper.assertTrue(!chunk.getBlockState(pos.below()).isAir(),"薄層が地面で支持される");
                }
            }
            helper.assertTrue(thicknesses.stream().anyMatch(n->n<8),"斜面に薄い層が存在: "+ids[site]+" "+thicknesses);
            if(site==0)helper.assertTrue(thicknesses.containsAll(java.util.Set.of(1,2,3,4,5,6,7,8)),"黄土内陸に全8厚さを生成");
            LogUtils.getLogger().info("SEDIMENT_LAYER_DISTRIBUTION id={} counts={}",ids[site],java.util.Arrays.toString(counts));
        }
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
