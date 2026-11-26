package io.github.rs0325.ashenfrontier.core.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.ItemPropertyMath;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** CommonRecipeと同じレシピ内に保存・同期する限定的な加工指定。入力番号はIngredient順で、スロット順ではない。 */
public record ProcessingData(List<Input> inputs, Optional<Water> water, double lossKg, double toolWear,
                             List<Inheritance> inheritance, boolean requiresEnvironment) {
    public static final ProcessingData EMPTY = new ProcessingData(List.of(), Optional.empty(), 0, 0, List.of(), false);
    public static final Codec<ProcessingData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Input.CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(ProcessingData::inputs),
            Water.CODEC.optionalFieldOf("water").forGetter(ProcessingData::water),
            Codec.doubleRange(0, Double.MAX_VALUE).optionalFieldOf("loss_kg", 0.0).forGetter(ProcessingData::lossKg),
            Codec.doubleRange(0, Double.MAX_VALUE).optionalFieldOf("tool_wear", 0.0).forGetter(ProcessingData::toolWear),
            Inheritance.CODEC.listOf().optionalFieldOf("inheritance", List.of()).forGetter(ProcessingData::inheritance),
            Codec.BOOL.optionalFieldOf("requires_environment", false).forGetter(ProcessingData::requiresEnvironment)
    ).apply(i, ProcessingData::new));

    public ProcessingData {
        inputs = List.copyOf(inputs);
        inheritance = List.copyOf(inheritance);
        if (!Double.isFinite(lossKg) || lossKg < 0 || !Double.isFinite(toolWear) || toolWear < 0) {
            throw new IllegalArgumentException("損失量と摩耗は有限の非負値が必要です");
        }
        toolWear = ItemPropertyMath.roundUp(toolWear, ItemProperties.WEAR_STEP);
    }

    public record Input(int ingredient, int count, boolean retained) {
        static final Codec<Input> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("ingredient").forGetter(Input::ingredient),
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(Input::count),
                Codec.BOOL.optionalFieldOf("retained", false).forGetter(Input::retained)
        ).apply(i, Input::new));
    }

    public record Water(ResourceLocation quality, double massKg) {
        static final Codec<Water> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.fieldOf("quality").forGetter(Water::quality),
                WaterMass.DEFINITION_KG.fieldOf("mass_kg").forGetter(Water::massKg)
        ).apply(i, Water::new));

        public Water {
            massKg = WaterMass.definitionKg(massKg);
            if (massKg == 0) throw new IllegalArgumentException("加工水量は1 mg以上が必要です");
        }
    }

    /** 指定原料の属性を指定出力へ保持する。追加自由水はこの指定とは独立に全消費原料から全製品へ質量配分する。 */
    public record Inheritance(int ingredient, int output) {
        static final Codec<Inheritance> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("ingredient").forGetter(Inheritance::ingredient),
                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("output").forGetter(Inheritance::output)
        ).apply(i, Inheritance::new));
    }

    public Input input(int ingredient) {
        return inputs.stream().filter(v -> v.ingredient() == ingredient).findFirst().orElse(new Input(ingredient, 1, false));
    }

    void validate(int inputCount, int outputCount) {
        var indices = new HashSet<Integer>();
        for (Input input : inputs) {
            if (input.ingredient() < 0 || input.ingredient() >= inputCount || input.count() < 1
                    || !indices.add(input.ingredient())) throw new IllegalArgumentException("加工入力の参照または個数が不正です");
        }
        indices.clear();
        for (Inheritance entry : inheritance) {
            if (entry.ingredient() < 0 || entry.ingredient() >= inputCount || entry.output() < 0
                    || entry.output() >= outputCount || !indices.add(entry.output())) {
                throw new IllegalArgumentException("属性継承の参照が不正または出力に重複しています");
            }
        }
        water.ifPresent(v -> {
            if (!Double.isFinite(v.massKg()) || v.massKg() <= 0 || !WaterTank.isWater(v.quality())) {
                throw new IllegalArgumentException("水消費量または水質参照が不正です");
            }
        });
    }

    public List<Component> displayLines() {
        var lines = new ArrayList<Component>();
        for (Input input : inputs) lines.add(Component.translatable("recipe.ashen_frontier.input_use",
                input.ingredient() + 1, input.count(), Component.translatable(input.retained()
                        ? "recipe.ashen_frontier.retained" : "recipe.ashen_frontier.consumed")));
        water.ifPresent(v -> lines.add(Component.translatable("recipe.ashen_frontier.water_use",
                Component.translatable("fluid_type." + v.quality().getNamespace() + "." + v.quality().getPath()), v.massKg())));
        if (lossKg > 0) lines.add(Component.translatable("recipe.ashen_frontier.mass_loss", lossKg));
        if (toolWear > 0) lines.add(Component.translatable("recipe.ashen_frontier.tool_wear", toolWear));
        if (requiresEnvironment) lines.add(Component.translatable("recipe.ashen_frontier.environment_required"));
        return List.copyOf(lines);
    }
}
