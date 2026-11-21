package io.github.rs0325.ashenfrontier.core.catalog;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

/** core所有の登録。容器と道具適性は別Issueで追加し、ここでは外部ケイパビリティを公開しない。 */
public final class NatureContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, AshenFrontierCore.NAMESPACE);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, AshenFrontierCore.NAMESPACE);
    private static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, AshenFrontierCore.NAMESPACE);
    private static final DeferredRegister<FluidType> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES,
            AshenFrontierCore.NAMESPACE);
    private static final Map<String, DeferredHolder<Block, ? extends Block>> BLOCK_ENTRIES = new LinkedHashMap<>();
    private static final Map<String, DeferredHolder<Item, ? extends Item>> ITEM_ENTRIES = new LinkedHashMap<>();
    private static final Map<String, FluidEntry> FLUID_ENTRIES = new LinkedHashMap<>();

    private NatureContent() {}

    public static void register(IEventBus bus) {
        for (var entry : NatureCatalog.entries()) {
            String id = entry.id();
            if (entry.fluid()) {
                DeferredHolder<FluidType, FluidType> type = TYPES.register(id, () -> new FluidType(FluidType.Properties.create()
                        .descriptionId("fluid_type.ashen_frontier." + id).canConvertToSource(false)) {});
                DeferredHolder<Fluid, BaseFlowingFluid.Source> source = FLUIDS.register(id, () -> new NatureFluid.Source(properties(id)));
                DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing = FLUIDS.register("flowing_" + id, () -> new NatureFluid.Flowing(properties(id)));
                DeferredHolder<Block, LiquidBlock> block = BLOCKS.register(id, () -> new NatureLiquidBlock(source.get(),
                        BlockBehaviour.Properties.ofFullCopy(Blocks.WATER).noLootTable()));
                BLOCK_ENTRIES.put(id, block);
                FLUID_ENTRIES.put(id, new FluidEntry(type, source, flowing, block));
            } else if (entry.block()) {
                var block = BLOCKS.register(id, () -> {
                    var properties = BlockBehaviour.Properties.ofFullCopy(
                        switch (entry.kind()) {
                            case "soil" -> Blocks.DIRT;
                            case "natural" -> entry.material().equals("wood") ? Blocks.OAK_PLANKS : Blocks.DEAD_BUSH;
                            default -> Blocks.STONE;
                        }).strength(entry.hardness(), entry.hardness() * 3);
                    return entry.loose() ? new LooseNaturalBlock(properties) : new Block(properties);
                });
                BLOCK_ENTRIES.put(id, block);
                ITEM_ENTRIES.put(id, ITEMS.register(id, () -> new BlockItem(block.get(), itemProperties(entry))));
            } else {
                ITEM_ENTRIES.put(id, ITEMS.register(id, () -> new Item(itemProperties(entry))));
            }
        }
        TYPES.register(bus);
        FLUIDS.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }

    private static BaseFlowingFluid.Properties properties(String id) {
        var entry = FLUID_ENTRIES.get(id);
        return new BaseFlowingFluid.Properties(entry.type(), entry.source(), entry.flowing())
                .block(() -> (LiquidBlock) entry.block().get())
                .tickRate(NatureCatalog.require(id).kind().equals("oil") ? 20 : 5);
    }

    private static Item.Properties itemProperties(NatureCatalog.Entry entry) {
        var properties = new Item.Properties();
        if (entry.fuel() != null || entry.kind().equals("soil")) {
            properties.component(NatureProperties.QUALITY.get(), AshenFrontierCore.id(
                    entry.kind().equals("soil") ? entry.id() : entry.fuel()));
        }
        return properties;
    }

    /** 登録完了後に呼ぶ。未知IDはエラーとし、バニラIDへの暗黙置換をしない。 */
    public static Block block(String id) {
        return BLOCK_ENTRIES.get(id).get();
    }

    public static Item item(String id) {
        return ITEM_ENTRIES.get(id).get();
    }

    /** キーは品質ID。sourceとflowingは同じFluidType・液体ブロックへ対応する。 */
    public static Map<String, FluidEntry> fluids() {
        return Map.copyOf(FLUID_ENTRIES);
    }

    public record FluidEntry(DeferredHolder<FluidType, FluidType> type, DeferredHolder<Fluid, BaseFlowingFluid.Source> source,
                             DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing, DeferredHolder<Block, LiquidBlock> block) {}
}
