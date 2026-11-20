package io.github.rs0325.ashenfrontier.core.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.List;

/** 入力・複数出力・共通条件を持つ加工レシピ。 */
public record CommonRecipe(ResourceLocation typeId, List<Ingredient> inputs, List<ItemStack> outputs,
                           RecipeConditions conditions) implements Recipe<RecipeInput> {
    public CommonRecipe {
        inputs = List.copyOf(inputs);
        outputs = outputs.stream().map(ItemStack::copy).toList();
    }

    static RecipeSerializer<CommonRecipe> serializer(ResourceLocation typeId) {
        MapCodec<CommonRecipe> codec = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Ingredient.CODEC.listOf().fieldOf("inputs").forGetter(CommonRecipe::inputs),
                ItemStack.CODEC.listOf().fieldOf("outputs").forGetter(CommonRecipe::outputs),
                RecipeConditions.CODEC.fieldOf("conditions").forGetter(CommonRecipe::conditions)
        ).apply(instance, (inputs, outputs, conditions) ->
                new CommonRecipe(typeId, inputs, outputs, conditions)));
        StreamCodec<RegistryFriendlyByteBuf, CommonRecipe> streamCodec =
                net.minecraft.network.codec.ByteBufCodecs.fromCodecWithRegistries(codec.codec());
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
        if (input.size() != inputs.size()) return false;
        boolean[] matched = new boolean[inputs.size()];
        for (int slot = 0; slot < input.size(); slot++) {
            boolean found = false;
            for (int index = 0; index < inputs.size(); index++) {
                if (!matched[index] && inputs.get(index).test(input.getItem(slot))) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

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
}
