package io.github.rs0325.ashenfrontier.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RevisionEighteenGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void narrowStraitsMatchColumnsAndKeepTheirWaterSurface(GameTestHelper helper) {
        var generator=TerrainPreviewGameTests.generator(helper);var terrain=generator.terrain();var level=helper.getLevel();
        for(var point:new int[][]{{-4858,-14826},{-4848,-14860},{-4650,-14704}}) {
            var pos=new ChunkPos(point[0]>>4,point[1]>>4);
            var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(-128,704),level.registryAccess().registryOrThrow(Registries.BIOME),null);
            generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),chunk).join();
            var cursor=new BlockPos.MutableBlockPos();
            for(int z=pos.getMinBlockZ();z<=pos.getMaxBlockZ();z++)for(int x=pos.getMinBlockX();x<=pos.getMaxBlockX();x++) {
                var column=terrain.column(x,z);int h=column.surfaceY();
                for(int y=h-8;y<=Math.max(64,h+1);y++) {
                    cursor.set(x,y,z);
                    helper.assertTrue(chunk.getBlockState(cursor).equals(column.state(y)),"海峡の壁・岩岸・水面と実生成が一致: "+cursor);
                }
                if(terrain.map().coastDistance(x,z)<0)helper.assertTrue(h<63 && !column.state(63).isAir(),"海峡は陸地を足さず水面を維持");
            }
        }
        helper.succeed();
    }
}
