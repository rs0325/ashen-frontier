package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.Map;

/** 自製する手作業品の登録。自然素材・初期工具はcoreの既存IDを使う。 */
public final class PrimitiveContent {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, AshenFrontierCore.NAMESPACE);
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, AshenFrontierCore.NAMESPACE);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AshenFrontierCore.NAMESPACE);
    private static final Map<String, DeferredHolder<Item, ? extends Item>> ENTRIES = new LinkedHashMap<>();
    public static final DeferredHolder<Block, KnappingSurfaceBlock> SURFACE = BLOCKS.register("knapping_surface",
            () -> new KnappingSurfaceBlock(BlockBehaviour.Properties.of().strength(0.5f).noOcclusion()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<KnappingSurfaceEntity>> SURFACE_ENTITY =
            ENTITIES.register("knapping_surface", () -> BlockEntityType.Builder.of(KnappingSurfaceEntity::new, SURFACE.get()).build(null));

    public static final DeferredHolder<Block, ThermalStationBlock> HEARTH = BLOCKS.register("hearth",
            () -> new ThermalStationBlock(BlockBehaviour.Properties.of().strength(1).noOcclusion()
                    .lightLevel(state -> state.getValue(BlockStateProperties.LIT) ? 12 : 0)
                    .pushReaction(PushReaction.BLOCK)));
    public static final DeferredHolder<Block, ThermalStationBlock> DRYING_RACK = BLOCKS.register("drying_rack",
            () -> new ThermalStationBlock(BlockBehaviour.Properties.of().strength(0.5f).noOcclusion()
                    .pushReaction(PushReaction.BLOCK)));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ThermalStationEntity>> THERMAL_ENTITY =
            ENTITIES.register("primitive_processing", () -> BlockEntityType.Builder.of(ThermalStationEntity::new, HEARTH.get(), DRYING_RACK.get()).build(null));

    public static final DeferredHolder<Block, WaterFilterBlock> FILTER = BLOCKS.register("water_filter",
            () -> new WaterFilterBlock(BlockBehaviour.Properties.of().strength(0.5f).noOcclusion()
                    .pushReaction(PushReaction.BLOCK)));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WaterFilterEntity>> FILTER_ENTITY =
            ENTITIES.register("water_filter", () -> BlockEntityType.Builder.of(WaterFilterEntity::new, FILTER.get()).build(null));

    private PrimitiveContent() {}

    public static void register(IEventBus bus) {
        for (String id : new String[]{"chopper", "biface", "stone_flake", "cordage", "fiber_cloth", "tool_handle",
                "hafted_axe", "stone_adze", "digging_stick"}) {
            ENTRIES.put(id, ITEMS.register(id, () -> {
                var properties = new Item.Properties();
                if (id.equals("chopper") || id.equals("biface")) {
                    properties.stacksTo(1).component(ItemProperties.WEAR.get(), ItemProperties.Wear.of(0, 64));
                } else if (id.equals("stone_flake")) {
                    properties.component(ItemProperties.WEAR.get(), ItemProperties.Wear.of(0, 16));
                } else if (id.equals("hafted_axe") || id.equals("stone_adze") || id.equals("digging_stick")) {
                    return new PrimitiveToolItem(properties.stacksTo(1)
                            .component(ItemProperties.WEAR.get(), ItemProperties.Wear.of(0, 64)));
                }
                return new Item(properties);
            }));
        }
        ENTRIES.put("knapping_surface", ITEMS.register("knapping_surface", () -> new BlockItem(SURFACE.get(), new Item.Properties())));
        for (String id : new String[]{"fire_drill", "tinder", "green_pot", "dried_pot", "ceramic_pot", "ceramic_lid", "ceramic_tube"}) {
            ENTRIES.put(id, ITEMS.register(id, () -> {
                var properties = new Item.Properties();
                if (id.equals("fire_drill")) properties.stacksTo(1).component(ItemProperties.WEAR.get(), ItemProperties.Wear.of(0, 64));
                if (id.equals("green_pot") || id.equals("dried_pot")) properties.stacksTo(1).component(PotteryProperties.FORM.get(), "pot");
                if (id.equals("ceramic_pot")) properties.stacksTo(1).component(RelicProperties.CONTENT_MASS.get(), 0.0);
                return new Item(properties);
            }));
        }
        ENTRIES.put("hearth", ITEMS.register("hearth", () -> new BlockItem(HEARTH.get(), new Item.Properties().stacksTo(1))));
        ENTRIES.put("drying_rack", ITEMS.register("drying_rack", () -> new BlockItem(DRYING_RACK.get(), new Item.Properties().stacksTo(1))));
        ENTRIES.put("water_filter", ITEMS.register("water_filter", () -> new BlockItem(FILTER.get(), new Item.Properties().stacksTo(1))));
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
    }

    static boolean owns(Item item) { return ENTRIES.values().stream().anyMatch(holder -> holder.get() == item); }

    public static Item item(String id) { return ENTRIES.get(id).get(); }
}
