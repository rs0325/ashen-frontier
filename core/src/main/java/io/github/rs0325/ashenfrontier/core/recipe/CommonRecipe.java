package io.github.rs0325.ashenfrontier.core.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

/** 入力・複数出力・共通条件を持つ加工レシピ。 */
public record CommonRecipe(ResourceLocation typeId, List<Ingredient> inputs, List<ItemStack> outputs,
                           RecipeConditions conditions, ProcessingData processing) implements Recipe<RecipeInput> {
    public CommonRecipe {
        inputs = List.copyOf(inputs);
        outputs = outputs.stream().map(ItemStack::copy).toList();
        for (ItemStack output : outputs) {
            var mass = output.get(RelicProperties.CONTENT_MASS);
            var quality = output.get(NatureProperties.QUALITY);
            if (mass != null && quality != null && WaterTank.isWater(quality)) {
                output.set(RelicProperties.CONTENT_MASS, WaterMass.definitionKg(mass));
            }
        }
        processing.validate(inputs.size(), outputs.size());
    }

    public CommonRecipe(ResourceLocation typeId, List<Ingredient> inputs, List<ItemStack> outputs, RecipeConditions conditions) {
        this(typeId, inputs, outputs, conditions, ProcessingData.EMPTY);
    }

    static RecipeSerializer<CommonRecipe> serializer(ResourceLocation typeId) {
        MapCodec<CommonRecipe> codec = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Ingredient.CODEC.listOf().fieldOf("inputs").forGetter(CommonRecipe::inputs),
                ItemStack.CODEC.listOf().fieldOf("outputs").forGetter(CommonRecipe::outputs),
                RecipeConditions.CODEC.fieldOf("conditions").forGetter(CommonRecipe::conditions),
                ProcessingData.CODEC.optionalFieldOf("processing", ProcessingData.EMPTY).forGetter(CommonRecipe::processing)
        ).apply(instance, (inputs, outputs, conditions, processing) ->
                new CommonRecipe(typeId, inputs, outputs, conditions, processing)));
        StreamCodec<RegistryFriendlyByteBuf, CommonRecipe> streamCodec =
                ByteBufCodecs.fromCodecWithRegistries(codec.codec());
        return new RecipeSerializer<>() {
            @Override
            public MapCodec<CommonRecipe> codec() {
                return codec;
            }

            @Override
            public StreamCodec<RegistryFriendlyByteBuf, CommonRecipe> streamCodec() {
                return streamCodec;
            }
        };
    }

    @Override
    public boolean matches(RecipeInput input, Level level) {
        return ingredientSlots(input).isPresent();
    }

    /**
     * Ingredient番号から渡された入力の実スロットへの対応。空きは照合対象から除くが番号は詰めない。
     * 数量も検査するが、研究/設備/水/時間条件の強制や消費はしない。
     */
    public Optional<int[]> ingredientSlots(RecipeInput input) {
        int occupied = 0;
        for (int slot = 0; slot < input.size(); slot++) if (!input.getItem(slot).isEmpty()) occupied++;
        if (occupied != inputs.size()) return Optional.empty();
        int[] matchedSlots = new int[inputs.size()];
        Arrays.fill(matchedSlots, -1);
        for (int slot = 0; slot < input.size(); slot++) {
            if (input.getItem(slot).isEmpty()) continue;
            if (!matchSlot(slot, input, matchedSlots, new boolean[inputs.size()])) return Optional.empty();
        }
        return Optional.of(matchedSlots);
    }

    private boolean matchSlot(int slot, RecipeInput input, int[] matchedSlots, boolean[] visited) {
        for (int ingredient = 0; ingredient < inputs.size(); ingredient++) {
            if (visited[ingredient] || !inputs.get(ingredient).test(input.getItem(slot))
                    || input.getItem(slot).getCount() < processing.input(ingredient).count()) continue;
            visited[ingredient] = true;
            if (matchedSlots[ingredient] == -1
                    || matchSlot(matchedSlots[ingredient], input, matchedSlots, visited)) {
                matchedSlots[ingredient] = slot;
                return true;
            }
        }
        return false;
    }

    /** バニラRecipe契約の先頭出力プレビュー。加工確定にはProcessingStationを使う。 */
    @Override
    public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
        return outputs.isEmpty() ? ItemStack.EMPTY : outputs.getFirst().copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= inputs.size();
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return outputs.isEmpty() ? ItemStack.EMPTY : outputs.getFirst().copy();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return NonNullList.copyOf(inputs);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return RecipeTypeRegistry.serializer(typeId);
    }

    @Override
    public RecipeType<?> getType() {
        return RecipeTypeRegistry.type(typeId);
    }

    /** 端末・設備・JEI/EMIが共有する条件表示。実秒/ゲーム秒と容器成分を省略しない。 */
    public List<Component> displayLines() {
        var lines = new ArrayList<>(conditions.displayLines(RecipeTypeRegistry.timeBasis(typeId)));
        lines.addAll(processing.displayLines());
        for (int i = 0; i < outputs.size(); i++) {
            var output = outputs.get(i);
            var mass = output.get(RelicProperties.CONTENT_MASS);
            var quality = output.get(NatureProperties.QUALITY);
            if (mass != null) lines.add(Component.translatable("recipe.ashen_frontier.output_mass", i + 1, mass));
            if (quality != null) lines.add(Component.translatable("recipe.ashen_frontier.output_quality", i + 1,
                    Component.translatable("fluid_type." + quality.getNamespace() + "." + quality.getPath())));
        }
        return List.copyOf(lines);
    }
}
