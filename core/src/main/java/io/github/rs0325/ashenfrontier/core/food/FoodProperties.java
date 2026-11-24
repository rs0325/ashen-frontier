package io.github.rs0325.ashenfrontier.core.food;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/** 調理後の個別栄養と、既存の保存種子・保存菌類の種別を保持する。 */
public final class FoodProperties {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(AshenFrontierCore.NAMESPACE);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<NutritionData>> NUTRITION = COMPONENTS.registerComponentType(
            "nutrition", b -> b.persistent(NutritionData.CODEC).networkSynchronized(ByteBufCodecs.fromCodecWithRegistries(NutritionData.CODEC)));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> CROP_SPECIES = COMPONENTS.registerComponentType(
            "crop_species", b -> b.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC));

    private FoodProperties() {}

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
        bus.addListener((RegisterDataMapTypesEvent event) -> event.register(NutritionData.TYPE));
    }
}
