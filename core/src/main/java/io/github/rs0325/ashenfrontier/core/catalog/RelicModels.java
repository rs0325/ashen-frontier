package io.github.rs0325.ashenfrontier.core.catalog;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.data.PackOutput;
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
            else if (block instanceof SlabBlock slab) slabBlock(slab, modLoc("block/" + id.replace("_slab", "_floor")), texture);
            else if (block instanceof FenceBlock fence) {
                fenceBlock(fence, texture);
                itemModels().fenceInventory(id, texture);
                continue;
            } else if (block instanceof DoorBlock door) {
                doorBlockWithRenderType(door, texture, texture, "cutout");
                itemModels().singleTexture(id, mcLoc("item/generated"), "layer0", texture);
                continue;
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
