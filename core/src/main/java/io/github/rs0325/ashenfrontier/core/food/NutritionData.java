package io.github.rs0325.ashenfrontier.core.food;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.datamaps.DataMapType;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 1個分の栄養。エネルギーはJ、質量はkg。ビタミン区分は定性的な分類で、摂取量ではない。 */
public record NutritionData(double energyJoules, double proteinKg, double fatKg, double waterKg,
                            Set<String> vitaminGroups) {
    public static final Codec<NutritionData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.doubleRange(0, Double.MAX_VALUE).fieldOf("energy_joules").forGetter(NutritionData::energyJoules),
            Codec.doubleRange(0, Double.MAX_VALUE).fieldOf("protein_kg").forGetter(NutritionData::proteinKg),
            Codec.doubleRange(0, Double.MAX_VALUE).fieldOf("fat_kg").forGetter(NutritionData::fatKg),
            Codec.doubleRange(0, Double.MAX_VALUE).fieldOf("water_kg").forGetter(NutritionData::waterKg),
            Codec.STRING.listOf().xmap(Set::copyOf, values -> values.stream().sorted().toList()).fieldOf("vitamin_groups").forGetter(NutritionData::vitaminGroups)
    ).apply(i, NutritionData::new));
    public static final DataMapType<Item, NutritionData> TYPE = DataMapType.builder(
            AshenFrontierCore.id("nutrition"), Registries.ITEM, CODEC).synced(CODEC, false).build();

    public NutritionData {
        if (!Double.isFinite(energyJoules) || !Double.isFinite(proteinKg) || !Double.isFinite(fatKg)
                || !Double.isFinite(waterKg) || energyJoules < 0 || proteinKg < 0 || fatKg < 0 || waterKg < 0) {
            throw new IllegalArgumentException("栄養値は有限の非負値が必要です");
        }
        vitaminGroups = Set.copyOf(vitaminGroups);
    }

    /** 個別成分を優先し、なければ固定データを返す。未定義食品に栄養や安全性を補わない。 */
    public static Optional<NutritionData> get(ItemStack stack) {
        var value = stack.get(FoodProperties.NUTRITION);
        return Optional.ofNullable(value != null ? value : stack.getItem().builtInRegistryHolder().getData(TYPE));
    }
}
