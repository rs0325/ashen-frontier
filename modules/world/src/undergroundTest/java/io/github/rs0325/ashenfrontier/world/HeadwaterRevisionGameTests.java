package io.github.rs0325.ashenfrontier.world;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FlowingFluid;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HeadwaterRevisionGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=2400)
    public static void generatedFallsCoverTheBedAcrossReaches(GameTestHelper helper) {
        var generator=TerrainPreviewGameTests.generator(helper);var terrain=generator.terrain();var scene=new Scene(terrain);var level=helper.getLevel();
        int falls=0;
        for(var point:findDrops(terrain.map())) {
            int x=point[0],z=point[1],low=terrain.map().waterSurface(x,z),high=terrain.map().waterSurface(x+point[2],z+point[3]);
            var pos=new BlockPos(x,high,z);var falling=scene.getFluidState(pos);var lower=scene.getFluidState(new BlockPos(x,low,z));
            helper.assertTrue(!falling.isEmpty() && !falling.isSource() && falling.getValue(FlowingFluid.FALLING),"段差の下流側に落水を生成: "+pos);
            helper.assertTrue(falling.getFlow(scene,pos).lengthSqr()>0,"落水は静止した水源の表示にしない");
            helper.assertTrue(lower.getHeight(scene,new BlockPos(x,low,z))==1,"低い側を満水にし、河床の上に1/9の隙間を残さない");
            helper.assertTrue(terrain.column(x,z).topY()>=high,"落水の上端を高さ取得に含める");
            for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL) {
                int bx=x+direction.getStepX(),bz=z+direction.getStepZ();
                if(terrain.map().waterSurface(bx,bz)==Integer.MIN_VALUE)helper.assertTrue(terrain.surfaceY(bx,bz)>=high,"落水を岸より高く浮かせない");
            }
            var chunkPos=new ChunkPos(x>>4,z>>4);
            var chunk=new ProtoChunk(chunkPos,UpgradeData.EMPTY,LevelHeightAccessor.create(-128,704),level.registryAccess().registryOrThrow(Registries.BIOME),null);
            generator.fillFromNoise(Blender.empty(),level.getChunkSource().randomState(),level.structureManager(),chunk).join();
            helper.assertTrue(chunk.getBlockState(pos).equals(scene.getBlockState(pos)),"落水を実チャンクにも生成する");
            helper.assertTrue(!falling.getFluidType().canConvertToSource(falling,level,helper.absolutePos(BlockPos.ZERO)),"落水から新しい水源を生成しない");
            falls++;
        }
        helper.assertTrue(falls>20,"源流から河口まで既存の段差を走査: "+falls);
        com.mojang.logging.LogUtils.getLogger().info("RIVER_FALLS checked={} source_to_mouth=true",falls);helper.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=100)
    public static void tickingTheFallKeepsFlowWithoutCreatingSources(GameTestHelper helper) {
        var level=helper.getLevel();var origin=helper.absolutePos(new BlockPos(0,1,0));
        var water=NatureContent.block("raw_water").defaultBlockState();var flow=water.setValue(LiquidBlock.LEVEL,8);
        for(int x=0;x<3;x++)for(int z=0;z<3;z++) {
            level.setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),2);
            if(z!=1)for(int y=1;y<=3;y++)level.setBlock(origin.offset(x,y,z),Blocks.STONE.defaultBlockState(),2);
        }
        level.setBlock(origin.offset(0,1,1),Blocks.STONE.defaultBlockState(),2);
        var upper=origin.offset(0,2,1);var lower=origin.offset(1,1,1);var end=origin.offset(2,1,1);var fall=origin.offset(1,2,1);
        level.setBlock(upper,water,2);level.setBlock(lower,water,2);level.setBlock(end,water,2);level.setBlock(fall,flow,2);
        level.scheduleTick(fall,flow.getFluidState().getType(),1);
        helper.runAfterDelay(40,()->{
            var current=level.getFluidState(fall);
            helper.assertTrue(!current.isEmpty() && !current.isSource() && current.getFlow(level,fall).lengthSqr()>0,"tick後も水源にならず流れる");
            helper.assertTrue(level.getFluidState(lower).getHeight(level,lower)==1,"tick後も下の河床に隙間を作らない");
            for(int x=0;x<3;x++)for(int y=1;y<=3;y++) {
                var pos=origin.offset(x,y,1);
                if(!pos.equals(upper) && !pos.equals(lower) && !pos.equals(end))helper.assertTrue(!level.getFluidState(pos).isSource(),"新しい水源を作らない: "+pos);
            }
            helper.succeed();
        });
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierWorld.MOD_ID,timeoutTicks=12000)
    public static void exportHeadwaterRevision(GameTestHelper helper) throws Exception {
        String phase=System.getenv("ASHEN_HEADWATERS_PREVIEW");
        if(phase==null || !(phase.startsWith("r2-") || phase.startsWith("r3-"))){helper.succeed();return;}
        var terrain=TerrainPreviewGameTests.generator(helper).terrain();
        var output=Path.of("../../../../../tools/fixed-map/preview/headwaters",phase).normalize();Files.createDirectories(output);
        var areas=new java.util.ArrayList<int[]>(java.util.List.of(new int[]{-4000,-12100},new int[]{-4468,-10800}));
        if(phase.startsWith("r3-"))areas.add(new int[]{-4075,-11250});
        for(var point:areas) {
            int size=641;var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
            for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
                int bx=point[0]+x-size/2,bz=point[1]+z-size/2;
                var column=terrain.column(bx,bz);var state=column.state(column.topY());
                double sx=terrain.surfaceY(bx+1,bz)-terrain.surfaceY(bx-1,bz);
                double sz=terrain.surfaceY(bx,bz+1)-terrain.surfaceY(bx,bz-1);
                var color=new Color(color(state));double shade=Math.clamp(.8+(sx+sz)*.045,.35,1.15);
                image.setRGB(x,z,new Color((int)Math.min(255,color.getRed()*shade),(int)Math.min(255,color.getGreen()*shade),(int)Math.min(255,color.getBlue()*shade)).getRGB());
            }
            ImageIO.write(image,"png",output.resolve(point[1]+"-plan.png").toFile());
        }
        int[][] drops={{-4048,-11520,1,0},{-4192,-11184,1,0},{-4640,-10064,-1,0}};
        var csv=new StringBuilder("reach,x,z,dx,dz,low,high,falling,source,lower_height\n");
        for(int i=0;i<drops.length;i++) {
            var drop=drops[i];var scene=new Scene(terrain);int x=drop[0],z=drop[1],dx=drop[2],dz=drop[3];
            int low=terrain.map().waterSurface(x,z),high=terrain.map().waterSurface(x+dx,z+dz);
            var state=scene.getFluidState(new BlockPos(x,high,z));var lower=scene.getFluidState(new BlockPos(x,low,z));
            csv.append(i).append(',').append(x).append(',').append(z).append(',').append(dx).append(',').append(dz).append(',')
                    .append(low).append(',').append(high).append(',').append(!state.isEmpty() && state.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING)).append(',')
                    .append(state.isSource()).append(',').append(lower.getHeight(scene,new BlockPos(x,low,z))).append('\n');
            var image=new BufferedImage(1200,720,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(new Color(0xc3e0fa));g.fillRect(0,0,1200,720);
            for(int v=-1;v<=1;v++)for(int u=0;u<=0;u++) {
                int bx=x+dz*u-dx*v,bz=z-dx*u-dz*v;var column=scene.column(bx,bz);
                if(scene.getFluidState(new BlockPos(bx,terrain.map().waterSurface(bx,bz),bz)).isEmpty())continue;
                for(int y=low-3;y<=Math.min(column.topY(),low+3);y++) {
                    var block=column.state(y);if(block.isAir())continue;
                    var fluid=block.getFluidState();double h=fluid.isEmpty()?1:fluid.getHeight(scene,new BlockPos(bx,y,bz));
                    double base=350+v*24-(y-low)*120;
                    int px=450+u*180+v*100,py=(int)Math.round(base-h*120),bottom=(int)Math.round(base);
                    Color color=new Color(color(block));
                    g.setColor(color.darker());g.fillPolygon(new int[]{px+100,px+280,px+280,px+100},new int[]{py+24,py+24,bottom+24,bottom+24},4);
                    g.setColor(color.darker().darker());g.fillPolygon(new int[]{px+180,px+280,px+280,px+180},new int[]{py,py+24,bottom+24,bottom},4);
                    g.setColor(color);g.fillPolygon(new int[]{px,px+180,px+280,px+100},new int[]{py,py,py+24,py+24},4);
                }
            }
            g.setColor(Color.BLACK);g.drawString(phase+" / X="+x+" Z="+z+" / water="+low+" -> "+high+" / one-block-wide cutaway / actual fluid height",20,25);g.dispose();
            ImageIO.write(image,"png",output.resolve("drop-"+i+"-low.png").toFile());
        }
        Files.writeString(output.resolve("drops.csv"),csv);helper.succeed();
    }

    static int[][] findDrops(FixedMapData map) {
        var found=new java.util.ArrayList<int[]>();int x=-4000;
        for(int z=-12112;z<=-5000;z++) {
            double best=Double.POSITIVE_INFINITY;int center=x;
            for(int bx=x-12;bx<=x+12;bx++) {
                var sample=map.river(bx,z);
                if(!sample.tributary() && sample.distance()<best){best=sample.distance();center=bx;}
            }
            x=center;
            for(var offset:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
                int low=map.waterSurface(x,z),high=map.waterSurface(x+offset[0],z+offset[1]);
                if(low>=63 && high==low+1)found.add(new int[]{x,z,offset[0],offset[1]});
            }
        }
        if(found.isEmpty())throw new IllegalStateException("河川の段差が見つかりません");
        return found.toArray(int[][]::new);
    }

    static final class Scene implements BlockGetter {
        private final FixedTerrain terrain;
        private final HashMap<Long,FixedTerrain.Column> columns=new HashMap<>();
        Scene(FixedTerrain terrain){this.terrain=terrain;}
        FixedTerrain.Column column(int x,int z){return columns.computeIfAbsent(((long)x<<32)^(z&0xffffffffL),key->terrain.column(x,z));}
        @Override public BlockState getBlockState(BlockPos pos){return column(pos.getX(),pos.getZ()).state(pos.getY());}
        @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public BlockEntity getBlockEntity(BlockPos pos){return null;}
        @Override public int getHeight(){return 704;}
        @Override public int getMinBuildHeight(){return -128;}
    }

    private static int color(BlockState state) {
        var fluid=state.getFluidState();
        if(!fluid.isEmpty())return !fluid.isSource()?0x49aecb:0x387fa5;
        String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if(id.contains("snow"))return 0xf3f8ff;
        if(id.contains("soil") || id.contains("dirt"))return 0x817050;
        if(id.contains("sand") || id.contains("gravel"))return 0xb2a479;
        return 0x80858b;
    }
}
