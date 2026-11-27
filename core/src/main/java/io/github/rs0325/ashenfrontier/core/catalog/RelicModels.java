package io.github.rs0325.ashenfrontier.core.catalog;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.data.PackOutput;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** Vanillaの形状・状態モデル生成を使い、向きや接続の組合せを手書きしない。 */
public final class RelicModels extends BlockStateProvider {
    private RelicModels(PackOutput output, ExistingFileHelper files) { super(output, AshenFrontierCore.NAMESPACE, files); }

    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeClient(), new RelicModels(event.getGenerator().getPackOutput(), event.getExistingFileHelper()));
    }

    private ModelFile ruinModel(String name, String id, RuinGeometry geometry, int[][] boxes) {
        var texture = modLoc("block/" + RelicCatalog.textureId(id));
        var model = models().getBuilder(name).texture("all", texture).texture("particle", texture);
        if (id.endsWith("_glass_window")) model.renderType("cutout");
        if (geometry.rebarFrom < boxes.length) model.texture("rebar",
                modLoc("block/" + (geometry == RuinGeometry.SEVERE_RC ? "corroded_exposed_rebar" : "exposed_rebar")));
        for (int i = 0; i < boxes.length; i++) {
            int[] b = boxes[i];
            String faceTexture = i >= geometry.rebarFrom ? "#rebar" : "#all";
            boolean window = id.endsWith("_glass_window");
            var frontAxis = boxes[0][3] - boxes[0][0] == 2 ? Direction.Axis.X : Direction.Axis.Z;
            model.element().from(b[0], b[1], b[2]).to(b[3], b[4], b[5])
                    .allFaces((direction, face) -> {
                        face.texture(faceTexture);
                        if (window && direction.getAxis() != frontAxis) face.uvs(0, 0, 1, 1);
                    }).end();
        }
        return model;
    }

    @Override protected void registerStatesAndModels() {
        for (var entry : RelicCatalog.entries()) {
            String id = entry.id();
            var texture = modLoc((entry.block() ? "block/" : "item/") + id);
            if (!entry.block()) {
                itemModels().singleTexture(id, mcLoc("item/generated"), "layer0", texture);
                continue;
            }
            var block = RelicContent.block(id);
            if (block instanceof StairBlock stairs) stairsBlock(stairs, texture);
            else if (block instanceof SlabBlock slab) slabBlock(slab,
                    modLoc("block/" + id.replace("_slab", entry.shape() == null ? "_floor" : "")), texture);
            else if (block instanceof FenceBlock fence) {
                fenceBlock(fence, texture);
                itemModels().fenceInventory(id, texture);
                continue;
            } else if (block instanceof DoorBlock door) {
                doorBlockWithRenderType(door, texture, texture, "cutout");
                itemModels().singleTexture(id, mcLoc("item/generated"), "layer0", texture);
                continue;
            } else if (block instanceof RuinBlock ruin) {
                getVariantBuilder(block).forAllStates(state -> {
                    var direction = state.getValue(HorizontalDirectionalBlock.FACING);
                    String name = id + (direction == Direction.NORTH ? "" : "_" + direction.getName());
                    return ConfiguredModel.builder().modelFile(ruinModel(name, id, ruin.geometry,
                            ruin.geometry.oriented(direction))).build();
                });
            } else if (block instanceof RuinBeamBlock beam) {
                getVariantBuilder(block).forAllStates(state -> {
                    var axis = state.getValue(RotatedPillarBlock.AXIS);
                    String name = id + (axis == Direction.Axis.Y ? "" : "_" + axis.getName());
                    return ConfiguredModel.builder().modelFile(ruinModel(name, id, beam.geometry,
                            beam.geometry.along(axis))).build();
                });
            } else if (block instanceof IronBarsBlock pane) {
                paneBlockWithRenderType(pane, texture, texture, "cutout");
                itemModels().singleTexture(id, mcLoc("item/generated"), "layer0", texture);
                continue;
            } else if (block instanceof RelicCasingBlock) {
                var model = models().getBuilder(id).texture("all", texture).texture("particle", texture);
                for (int[] box : new int[][]{{0,0,0,3,16,16}, {13,0,0,16,16,16}, {3,0,0,13,3,16}, {3,13,0,13,16,16}}) {
                    model.element().from(box[0],box[1],box[2]).to(box[3],box[4],box[5]).allFaces((direction, face) -> face.texture("#all")).end();
                }
                simpleBlock(block, model);
            } else if (block instanceof RelicEquipmentBlock) {
                horizontalBlock(block, models().orientable(id, modLoc("block/" + id + "_side"), texture,
                        modLoc("block/" + id + "_top")));
            } else simpleBlock(block);
            itemModels().withExistingParent(id, modLoc("block/" + id));
        }
    }
}
