package io.github.rs0325.ashenfrontier.world;

import com.mojang.logging.LogUtils;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
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
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** D1の実地形を使う検査。通常のワールドプリセットは切り替えない。 */
@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerrainBasisGameTests {
    private static FixedMapChunkGenerator generator(GameTestHelper helper) throws Exception {
        var original = TerrainPreviewGameTests.generator(helper);
        try (var stream = TerrainBasisGameTests.class.getResourceAsStream("/data/ashen_frontier/fixed_map/earth_002/manifest.json")) {
            var version = new MapVersion("earth_002", 1, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
            return new FixedMapChunkGenerator(((FixedMapBiomeSource)original.getBiomeSource()).withMapVersion(version), version);
        }
    }

    private static ProtoChunk chunk(GameTestHelper helper, FixedMapChunkGenerator generator, ChunkPos pos, RandomState random) {
        var level = helper.getLevel();
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(-128,704), level.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
        return chunk;
    }

    private static long hash(ProtoChunk chunk) {
        long hash = 0xcbf29ce484222325L;
        var pos = new BlockPos.MutableBlockPos();
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) for (int y=-128;y<576;y++)
            hash = (hash ^ Block.getId(chunk.getBlockState(pos.set(chunk.getPos().getMinBlockX()+x,y,chunk.getPos().getMinBlockZ()+z)))) * 0x100000001b3L;
        return hash;
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=1200)
    public static void basisActualChunksAndOrder(GameTestHelper helper) throws Exception {
        var generator=generator(helper); var level=helper.getLevel();
        var settings=level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var first=RandomState.create(settings,level.registryAccess().lookupOrThrow(Registries.NOISE),1L);
        var second=RandomState.create(settings,level.registryAccess().lookupOrThrow(Registries.NOISE),-987654321L);
        int[][] positions={{-10600,-13200},{-4200,-12100},{-4624,-8112},{-6800,-4000}};
        long[] expected=new long[positions.length];
        for (int i=0;i<positions.length;i++) {
            var p=positions[i];var pos=new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16));
            var generated=chunk(helper,generator,pos,first);expected[i]=hash(generated);
            for (int dx=0;dx<16;dx++) for (int dz=0;dz<16;dz++) {
                int x=pos.getMinBlockX()+dx,z=pos.getMinBlockZ()+dz;
                var column=generator.terrain().column(x,z);
                helper.assertTrue(column.state(column.surfaceY()).is(Blocks.STONE),"D1は雪・鉱床・施設を先取りしない母岩");
                for(int y=-128;y<576;y++) helper.assertTrue(generated.getBlockState(new BlockPos(x,y,z)).equals(column.state(y)),"実チャンクと全Yの列の一致");
            }
        }
        for(int i=positions.length-1;i>=0;i--) {
            var p=positions[i];
            helper.assertTrue(hash(chunk(helper,generator,new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16)),second))==expected[i],"seed・生成順から独立");
        }
        var parallel=IntStream.range(0,positions.length).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            var p=positions[i];
            return hash(chunk(helper,generator,new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16)),second))==expected[i];
        })).toList();
        for(var result:parallel)helper.assertTrue(result.join(),"並列順からも独立");
        var old=TerrainPreviewGameTests.generator(helper);
        for(var p:new int[][]{{8192,8192},{-15000,-8192},{-768,-3840}}) {
            var pos=new ChunkPos(Math.floorDiv(p[0],16),Math.floorDiv(p[1],16));
            helper.assertTrue(hash(chunk(helper,generator,pos,first))==hash(chunk(helper,old,pos,first)),"他大陸・海底・島を変更しない");
        }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace=AshenFrontierWorld.MOD_ID, timeoutTicks=12000)
    public static void basisGenerationBenchmark(GameTestHelper helper) throws Exception {
        var generator=generator(helper); var random=helper.getLevel().getChunkSource().randomState();
        String[] names={"range","massif","lowland"};
        int[][] origins={{-650,-800},{-330,-780},{-300,-520}};
        for(int group=0;group<origins.length;group++) {
            long[] times=new long[1000];var origin=origins[group];
            for(int i=-100;i<1000;i++) {
                int index=Math.max(0,i);var pos=new ChunkPos(origin[0]+index%25,origin[1]+index/25);
                helper.assertTrue(generator.terrain().map().landformId(pos.getMinBlockX(),pos.getMinBlockZ())!=0,"計測対象はearth_002北西大陸");
                long start=System.nanoTime();var generated=chunk(helper,generator,pos,random);
                if(i>=0)times[i]=System.nanoTime()-start;
                helper.assertTrue(generated.getBlockState(new BlockPos(pos.getMinBlockX(),-128,pos.getMinBlockZ())).is(Blocks.BEDROCK),"実チャンクを計測");
            }
            Arrays.sort(times);double p95=times[949]/1e6;
            LogUtils.getLogger().info("BASIS_BENCHMARK group={} count=1000 warmup=100 parallelism=1 p50_ms={} p95_ms={} max_ms={}",names[group],times[499]/1e6,p95,times[999]/1e6);
            helper.assertTrue(p95<=25,"earth_002の各地形でp95<=25ms: "+names[group]+" "+p95);
        }
        helper.succeed();
    }
}
