package io.github.rs0325.ashenfrontier.world;

import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RevisionSeventeenGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void westernBranchesMatchGeneratedBlocksWithoutAGraniteCap(GameTestHelper helper) {
        var generator=TerrainPreviewGameTests.generator(helper);
        var terrain=generator.terrain();var level=helper.getLevel();
        for(var point:new int[][]{{-13500,-11712},{-13740,-11760},{-13920,-11770},{-13600,-11970}}) {
            var pos=new ChunkPos(point[0]>>4,point[1]>>4);
            var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(-128,704),level.registryAccess().registryOrThrow(Registries.BIOME),null);
            generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),chunk).join();
            var cursor=new BlockPos.MutableBlockPos();
            for(int z=pos.getMinBlockZ();z<=pos.getMaxBlockZ();z++)for(int x=pos.getMinBlockX();x<=pos.getMaxBlockX();x++) {
                var column=terrain.column(x,z);int top=column.surfaceY();
                for(int y=top-40;y<=top+1;y++) {
                    cursor.set(x,y,z);
                    helper.assertTrue(chunk.getBlockState(cursor).equals(column.state(y)),"西端の支尾根・谷と実生成が一致: "+cursor);
                }
            }
        }
        var rocks=new HashSet<Block>();int granite=0,total=0;
        for(int z=-11872;z<=-11552;z+=4)for(int x=-13660;x<=-13340;x+=4) {
            var column=terrain.column(x,z);int y=column.surfaceY();if(y<400)continue;
            var state=column.rockState(y);rocks.add(state.getBlock());
            if(state.is(Blocks.GRANITE))granite++;total++;
        }
        helper.assertTrue(total>100 && rocks.size()>=3 && granite<total*.4,"頂部を一様な花崗岩の帽子で覆わない: "+granite+"/"+total+" rocks="+rocks.size());
        com.mojang.logging.LogUtils.getLogger().info("REVISION17_WEST_CAP columns={} granite={} materials={}",total,granite,rocks.size());
        helper.succeed();
    }
}
