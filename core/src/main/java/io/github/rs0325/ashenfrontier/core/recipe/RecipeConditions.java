package io.github.rs0325.ashenfrontier.core.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rs0325.ashenfrontier.core.UnitDisplay;
import io.github.rs0325.ashenfrontier.core.Units;
import io.github.rs0325.ashenfrontier.core.research.ResearchDefinitionsUpdate;
import io.github.rs0325.ashenfrontier.core.research.ResearchProgressUpdate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

/** 全加工レシピで共有するSI単位の条件。 */
public record RecipeConditions(Optional<Range> temperatureK, Optional<Range> pressurePa,
                               Optional<Double> durationSeconds, Optional<Double> energyJoules,
                               Optional<ItemStack> catalyst, Optional<ResourceLocation> facility,
                               Optional<ResourceLocation> research) {
    public static final Codec<RecipeConditions> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Range.CODEC.optionalFieldOf("temperature_k").forGetter(RecipeConditions::temperatureK),
            Range.CODEC.optionalFieldOf("pressure_pa").forGetter(RecipeConditions::pressurePa),
            Codec.DOUBLE.optionalFieldOf("duration_seconds").forGetter(RecipeConditions::durationSeconds),
            Codec.DOUBLE.optionalFieldOf("energy_joules").forGetter(RecipeConditions::energyJoules),
            ItemStack.CODEC.optionalFieldOf("catalyst").forGetter(RecipeConditions::catalyst),
            ResourceLocation.CODEC.optionalFieldOf("facility").forGetter(RecipeConditions::facility),
            ResourceLocation.CODEC.optionalFieldOf("research").forGetter(RecipeConditions::research)
    ).apply(instance, RecipeConditions::new));

    public record Range(double min, double max) {
        public static final Codec<Range> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("min").forGetter(Range::min),
                Codec.DOUBLE.fieldOf("max").forGetter(Range::max)
        ).apply(instance, Range::new));

        public boolean contains(double value) {
            return value >= min && value <= max;
        }
    }

    public boolean matches(double actualTemperatureK, double actualPressurePa, double actualDurationSeconds,
                           double energyJoules, boolean hasCatalyst, boolean hasFacility,
                           boolean researchUnlocked) {
        return temperatureK.map(range -> range.contains(actualTemperatureK)).orElse(true)
                && pressurePa.map(range -> range.contains(actualPressurePa)).orElse(true)
                && durationSeconds.map(value -> actualDurationSeconds >= value).orElse(true)
                && this.energyJoules.map(value -> energyJoules >= value).orElse(true)
                && (catalyst.isEmpty() || hasCatalyst)
                && (facility.isEmpty() || hasFacility)
                && (research.isEmpty() || researchUnlocked);
    }

    /** JEI・EMI向けに、クライアントの研究状態と表示単位を反映した条件文を返す。 */
    public List<Component> displayLines() {
        List<Component> lines = new ArrayList<>();
        temperatureK.ifPresent(range -> lines.add(Component.translatable("recipe.ashen_frontier.temperature",
                UnitDisplay.temperature(range.min()), UnitDisplay.temperature(range.max()))));
        pressurePa.ifPresent(range -> lines.add(Component.translatable("recipe.ashen_frontier.pressure",
                UnitDisplay.pressure(range.min()), UnitDisplay.pressure(range.max()))));
        durationSeconds.ifPresent(value -> lines.add(Component.translatable("recipe.ashen_frontier.duration",
                Units.format(value, "s"))));
        energyJoules.ifPresent(value -> lines.add(Component.translatable("recipe.ashen_frontier.energy",
                Units.format(value, "J"))));
        catalyst.ifPresent(stack -> lines.add(Component.translatable("recipe.ashen_frontier.catalyst",
                stack.getHoverName())));
        facility.ifPresent(id -> {
            var block = BuiltInRegistries.BLOCK.getOptional(id);
            Component name = block.<Component>map(value -> value.getName())
                    .orElseGet(() -> BuiltInRegistries.ITEM.getOptional(id)
                            .map(value -> value.getName(value.getDefaultInstance()))
                            .orElseGet(() -> Component.literal(id.toString())));
            lines.add(Component.translatable("recipe.ashen_frontier.facility", name));
        });
        research.ifPresent(id -> {
            Component name = ResearchDefinitionsUpdate.clientName(id);
            boolean unlocked = ResearchProgressUpdate.isClientUnlocked(id);
            lines.add(Component.translatable(unlocked ? "recipe.ashen_frontier.research"
                    : "recipe.ashen_frontier.research_locked", name));
        });
        return List.copyOf(lines);
    }

}
