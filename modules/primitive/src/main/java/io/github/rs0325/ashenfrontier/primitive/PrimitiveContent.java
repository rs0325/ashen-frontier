package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
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
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
    }

    static boolean owns(Item item) { return ENTRIES.values().stream().anyMatch(holder -> holder.get() == item); }

    public static Item item(String id) { return ENTRIES.get(id).get(); }
}
