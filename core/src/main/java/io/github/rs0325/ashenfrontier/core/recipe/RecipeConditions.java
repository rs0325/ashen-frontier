package io.github.rs0325.ashenfrontier.core.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rs0325.ashenfrontier.core.UnitDisplay;
import io.github.rs0325.ashenfrontier.core.Units;
import io.github.rs0325.ashenfrontier.core.research.ResearchDefinitionsUpdate;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.research.ResearchProgressUpdate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
        return displayLines(TimeBasis.REAL);
    }

    public List<Component> displayLines(TimeBasis timeBasis) { return displayLines(timeBasis, Optional.empty()); }

    /** 専用サーバーではクライアント設定を参照せずSI単位で条件を案内する。 */
    public List<Component> serverDisplayLines(TimeBasis timeBasis, ServerPlayer player) { return displayLines(timeBasis, Optional.of(player)); }

    private List<Component> displayLines(TimeBasis timeBasis, Optional<ServerPlayer> player) {
        boolean clientUnits = player.isEmpty();
        List<Component> lines = new ArrayList<>();
        temperatureK.ifPresent(range -> lines.add(Component.translatable("recipe.ashen_frontier.temperature",
                clientUnits ? UnitDisplay.temperature(range.min()) : Units.temperature(range.min(), true),
                clientUnits ? UnitDisplay.temperature(range.max()) : Units.temperature(range.max(), true))));
        pressurePa.ifPresent(range -> lines.add(Component.translatable("recipe.ashen_frontier.pressure",
                clientUnits ? UnitDisplay.pressure(range.min()) : Units.pressure(range.min(), true),
                clientUnits ? UnitDisplay.pressure(range.max()) : Units.pressure(range.max(), true))));
        durationSeconds.ifPresent(value -> lines.add(Component.translatable("recipe.ashen_frontier.duration_" + timeBasis.name().toLowerCase(Locale.ROOT),
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
            Component name = player.isPresent()
                    ? Component.translatable(ResearchManager.tree().definitions().get(id.toString()).name())
                    : ResearchDefinitionsUpdate.clientName(id);
            boolean unlocked = player.map(p -> ResearchManager.isUnlocked(p, id))
                    .orElseGet(() -> ResearchProgressUpdate.isClientUnlocked(id));
            lines.add(Component.translatable(unlocked ? "recipe.ashen_frontier.research"
                    : "recipe.ashen_frontier.research_locked", name));
        });
        return List.copyOf(lines);
    }

}
