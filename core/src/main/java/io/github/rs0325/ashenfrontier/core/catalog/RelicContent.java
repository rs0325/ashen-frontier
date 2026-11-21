package io.github.rs0325.ashenfrontier.core.catalog;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.LinkedHashMap;
import java.util.Map;

/** 遺物のID所有者。機能Modなしで登録・配置・回収できる。 */
public final class RelicContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, AshenFrontierCore.NAMESPACE);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, AshenFrontierCore.NAMESPACE);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AshenFrontierCore.NAMESPACE);
    private static final Map<String, DeferredHolder<Block, ? extends Block>> BLOCK_ENTRIES = new LinkedHashMap<>();
    private static final Map<String, DeferredHolder<Item, ? extends Item>> ITEM_ENTRIES = new LinkedHashMap<>();
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RelicEquipmentEntity>> EQUIPMENT = ENTITIES.register(
            "relic_equipment", () -> BlockEntityType.Builder.of(RelicEquipmentEntity::new,
                    RelicCatalog.entries().stream().filter(e -> e.kind().equals("equipment"))
                            .map(e -> block(e.id())).toArray(Block[]::new)).build(null));

    private RelicContent() {}

    public static void register(IEventBus bus) {
        for (var entry : RelicCatalog.entries()) {
            if (entry.block()) {
                var block = BLOCKS.register(entry.id(), () -> createBlock(entry));
                BLOCK_ENTRIES.put(entry.id(), block);
                ITEM_ENTRIES.put(entry.id(), ITEMS.register(entry.id(), () -> new BlockItem(block.get(), new Item.Properties())));
            } else {
                ITEM_ENTRIES.put(entry.id(), ITEMS.register(entry.id(), () -> {
                    var properties = new Item.Properties();
                    properties.component(NatureProperties.QUALITY.get(), AshenFrontierCore.id("salvaged"));
                    if (entry.id().equals("archive_document")) properties.component(RelicProperties.DOCUMENT.get(), AshenFrontierCore.id("field_manual"));
                    if (entry.behavior().equals("sealed_container")) {
                        String quality = switch (entry.id()) {
                            case "sealed_water_container" -> "clean_water";
                            case "sealed_oil_container" -> "light_low_sulfur_crude_oil";
                            default -> "archived_reagent";
                        };
                        properties.component(NatureProperties.QUALITY.get(), AshenFrontierCore.id(quality));
                        properties.component(RelicProperties.CONTENT_MASS.get(), 1.0);
                        if (entry.id().equals("sealed_reagent_container")) properties.component(RelicProperties.REAGENT.get(), AshenFrontierCore.id(quality));
                    }
                    if (entry.behavior().equals("food")) properties.food(new FoodProperties.Builder().nutrition(6).saturationModifier(.6f).build());
                    if (entry.behavior().equals("simple_tool")) properties.stacksTo(1);
                    return entry.behavior().equals("document") ? new ArchiveDocumentItem(properties) : new Item(properties);
                }));
            }
        }
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
    }

    private static Block createBlock(NatureCatalog.Entry entry) {
        var properties = BlockBehaviour.Properties.ofFullCopy(Blocks.STONE).strength(entry.hardness(), entry.hardness() * 3);
        String id = entry.id();
        if (entry.kind().equals("equipment")) return new RelicEquipmentBlock(BlockBehaviour.Properties.of()
                .strength(entry.hardness(), entry.hardness() * 3));
        if (id.endsWith("_stairs")) return new StairBlock(Blocks.STONE.defaultBlockState(), properties);
        if (id.endsWith("_slab")) return new SlabBlock(properties);
        if (id.endsWith("_fence")) return new FenceBlock(properties);
        if (id.endsWith("_door")) return new DoorBlock(BlockSetType.OAK, properties.noOcclusion());
        if (id.endsWith("_window")) return new IronBarsBlock(properties.noOcclusion());
        if (id.endsWith("_casing")) return new RelicCasingBlock(properties.noOcclusion());
        return new Block(properties);
    }

    /** 登録完了後に呼ぶ。IDはRelicCatalog.requireで制作時に照合する。 */
    public static Block block(String id) { return BLOCK_ENTRIES.get(id).get(); }
    public static Item item(String id) { return ITEM_ENTRIES.get(id).get(); }
}
