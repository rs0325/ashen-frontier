package io.github.rs0325.ashenfrontier.core.catalog;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

import java.util.Map;
import java.util.Optional;

/** 固定燃料特性。質量比は0〜1、発熱量はJ/kg、API度は表示指標。未調査項目はempty。 */
public record FuelData(ResourceLocation profile, Optional<Double> heatingValueJKg, Optional<Double> volatileFraction,
                       Optional<Double> ashFraction, Optional<Double> sulfurFraction, Optional<Double> apiGravity) {
    public static final Codec<FuelData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("profile").forGetter(FuelData::profile),
            Codec.DOUBLE.optionalFieldOf("heating_value_j_kg").forGetter(FuelData::heatingValueJKg),
            Codec.DOUBLE.optionalFieldOf("volatile_fraction").forGetter(FuelData::volatileFraction),
            Codec.DOUBLE.optionalFieldOf("ash_fraction").forGetter(FuelData::ashFraction),
            Codec.DOUBLE.optionalFieldOf("sulfur_fraction").forGetter(FuelData::sulfurFraction),
            Codec.DOUBLE.optionalFieldOf("api_gravity").forGetter(FuelData::apiGravity)
    ).apply(instance, FuelData::new));
    public static final DataMapType<Item, FuelData> ITEM = DataMapType.builder(
            AshenFrontierCore.id("fuel"), Registries.ITEM, CODEC).synced(CODEC, false).build();
    public static final DataMapType<Fluid, FuelData> FLUID = DataMapType.builder(
            AshenFrontierCore.id("fuel"), Registries.FLUID, CODEC).synced(CODEC, false).build();

    public static void register(IEventBus bus) {
        bus.addListener((RegisterDataMapTypesEvent event) -> {
            event.register(ITEM);
            event.register(FLUID);
        });
    }

    public static FuelData from(NatureCatalog.Entry entry) {
        Map<String, Double> values = NatureCatalog.requireFuel(entry.fuel()).values();
        return new FuelData(AshenFrontierCore.id(entry.fuel()),
                Optional.ofNullable(values.get("heating_value_j_kg")), Optional.ofNullable(values.get("volatile_fraction")),
                Optional.ofNullable(values.get("ash_fraction")), Optional.ofNullable(values.get("sulfur_fraction")),
                Optional.ofNullable(values.get("api_gravity")));
    }
}
