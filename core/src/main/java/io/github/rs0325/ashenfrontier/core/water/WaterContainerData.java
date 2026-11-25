package io.github.rs0325.ashenfrontier.core.water;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.recipe.WaterMass;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/** 補給を許可する単一容器の最大kg。密封容器の初期内容量とは別のデータ。 */
public final class WaterContainerData {
    private static final Codec<Double> CAPACITY = Codec.DOUBLE.validate(kg -> {
        try {
            WaterMass.definitionKg(kg);
            if (WaterMass.fromKg(kg) == 0) return DataResult.error(() -> "容器容量は正値が必要です");
            return DataResult.success(kg);
        } catch (IllegalArgumentException e) {
            return DataResult.error(e::getMessage);
        }
    });
    public static final DataMapType<Item, Double> TYPE = DataMapType.builder(
            AshenFrontierCore.id("water_container"), Registries.ITEM, CAPACITY).synced(CAPACITY, false).build();

    private WaterContainerData() {}
    public static void register(IEventBus bus) {
        bus.addListener((RegisterDataMapTypesEvent e) -> e.register(TYPE));
    }
    static Double capacity(ItemStack stack) { return stack.getItem().builtInRegistryHolder().getData(TYPE); }
    static boolean canEmpty(ItemStack stack) {
        return capacity(stack) != null || stack.is(RelicContent.item("sealed_water_container"));
    }
}
