package io.github.rs0325.ashenfrontier.core;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ContaminationTools {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AshenFrontierCore.NAMESPACE);
    public static final DeferredItem<Item> METER = ITEMS.registerSimpleItem("contamination_meter", new Item.Properties().stacksTo(1));

    private ContaminationTools() {}

    public static void register(IEventBus bus) { ITEMS.register(bus); }
}
