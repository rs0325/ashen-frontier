package io.github.rs0325.ashenfrontier.primitive;

import com.mojang.serialization.MapCodec;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import net.minecraft.world.level.Level;
import io.github.rs0325.ashenfrontier.core.recipe.ProcessingStation;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 標準の組立処理を使い、結果スロットへ渡す前に原料の属性を保持する。 */
public final class AssemblyRecipe extends ShapedRecipe {
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, AshenFrontierCore.NAMESPACE);
    private static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<AssemblyRecipe>> HAFTING =
            SERIALIZERS.register("hafting", () -> new RecipeSerializer<>() {
                @Override
                public MapCodec<AssemblyRecipe> codec() {
                    return ShapedRecipe.Serializer.CODEC.xmap(AssemblyRecipe::new, recipe -> recipe);
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, AssemblyRecipe> streamCodec() {
                    return ShapedRecipe.Serializer.STREAM_CODEC.map(AssemblyRecipe::new, recipe -> recipe);
                }
            });
    private static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<Hammer>> HAMMER =
            SERIALIZERS.register("hammer_assembly", () -> new RecipeSerializer<>() {
                @Override
                public MapCodec<Hammer> codec() {
                    return new ShapelessRecipe.Serializer().codec().xmap(Hammer::new, recipe -> recipe);
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, Hammer> streamCodec() {
                    return ShapelessRecipe.Serializer.STREAM_CODEC.map(Hammer::new, recipe -> recipe);
                }
            });

    private static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<Filter>> FILTER =
            SERIALIZERS.register("filter_assembly", () -> new RecipeSerializer<>() {
                @Override
                public MapCodec<Filter> codec() {
                    return new ShapelessRecipe.Serializer().codec().xmap(Filter::new, recipe -> recipe);
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, Filter> streamCodec() {
                    return ShapelessRecipe.Serializer.STREAM_CODEC.map(Filter::new, recipe -> recipe);
                }
            });

    private AssemblyRecipe(ShapedRecipe recipe) {
        super(recipe.getGroup(), recipe.category(), recipe.pattern,
                recipe.getResultItem(RegistryAccess.EMPTY), recipe.showNotification());
    }

    public static void register(IEventBus bus) {
        SERIALIZERS.register(bus);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return HAFTING.get();
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return inherit(input, super.assemble(input, registries), true);
    }

    private static ItemStack inherit(CraftingInput input, ItemStack output, boolean hafted) {
        for (var source : input.items()) {
            if (source.is(hafted ? PrimitiveContent.item("biface") : NatureContent.item("pebble"))) {
                ProcessingStation.inherit(source, output);
                if (hafted) output.set(ItemProperties.WEAR, source.get(ItemProperties.WEAR));
                break;
            }
        }
        return output;
    }

    private static final class Hammer extends ShapelessRecipe {
        private Hammer(ShapelessRecipe recipe) {
            super(recipe.getGroup(), recipe.category(), recipe.getResultItem(RegistryAccess.EMPTY), recipe.getIngredients());
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return HAMMER.get();
        }

        @Override
        public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
            return inherit(input, super.assemble(input, registries), false);
        }
    }
    private static final class Filter extends ShapelessRecipe {
        private Filter(ShapelessRecipe recipe) {
            super(recipe.getGroup(), recipe.category(), recipe.getResultItem(RegistryAccess.EMPTY), recipe.getIngredients());
        }

        @Override
        public RecipeSerializer<?> getSerializer() { return FILTER.get(); }

        @Override
        public boolean matches(CraftingInput input, Level level) {
            return super.matches(input, level)
                    && input.items().stream().allMatch(stack -> stack.getOrDefault(NatureProperties.MOISTURE.get(), 0.0) == 0);
        }
    }
}
