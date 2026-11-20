package io.github.rs0325.ashenfrontier.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

import java.util.Optional;

public record MaterialData(double massKg, Optional<Double> hardnessPa, Optional<Double> densityKgM3,
                           Optional<Double> specificHeatJKgK, Optional<Double> meltingPointK,
                           Optional<Double> thermalConductivityWMK, Optional<Double> mohsHardness) {
    public static final Codec<MaterialData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("mass_kg").forGetter(MaterialData::massKg),
            Codec.DOUBLE.optionalFieldOf("hardness_pa").forGetter(MaterialData::hardnessPa),
            Codec.DOUBLE.optionalFieldOf("density_kg_m3").forGetter(MaterialData::densityKgM3),
            Codec.DOUBLE.optionalFieldOf("specific_heat_j_kg_k").forGetter(MaterialData::specificHeatJKgK),
            Codec.DOUBLE.optionalFieldOf("melting_point_k").forGetter(MaterialData::meltingPointK),
            Codec.DOUBLE.optionalFieldOf("thermal_conductivity_w_m_k").forGetter(MaterialData::thermalConductivityWMK),
            Codec.DOUBLE.optionalFieldOf("mohs_hardness").forGetter(MaterialData::mohsHardness)
    ).apply(instance, MaterialData::new));

    public static final DataMapType<Item, MaterialData> TYPE = DataMapType.builder(
            AshenFrontierCore.id("material"), Registries.ITEM, CODEC).synced(CODEC, false).build();

    static void register(net.neoforged.bus.api.IEventBus modEventBus) {
        modEventBus.addListener(MaterialData::registerTypes);
    }

    private static void registerTypes(RegisterDataMapTypesEvent event) {
        event.register(TYPE);
    }

    /** アイテムの材料データを返す。データが割り当てられていない場合はempty。 */
    public static Optional<MaterialData> get(Item item) {
        return Optional.ofNullable(item.builtInRegistryHolder().getData(TYPE));
    }
}
