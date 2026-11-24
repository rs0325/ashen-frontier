package io.github.rs0325.ashenfrontier.core.recipe.emi;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe;
import io.github.rs0325.ashenfrontier.core.recipe.RecipeTypeRegistry;
import io.github.rs0325.ashenfrontier.core.vanilla.RemovedItems;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;

@EmiEntrypoint
public final class AshenFrontierEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.removeEmiStacks(stack -> RemovedItems.isRemoved(stack.getItemStack()));
        RecipeTypeRegistry.registeredTypes().forEach((id, type) -> {
            EmiRecipeCategory category = new EmiRecipeCategory(id, EmiStack.of(Items.FURNACE)) {
                @Override
                public Component getName() {
                    return Component.translatable("recipe_type." + id.getNamespace() + "." + id.getPath());
                }
            };
            registry.addCategory(category);
            for (RecipeHolder<CommonRecipe> holder : registry.getRecipeManager().getAllRecipesFor(type.get())) {
                registry.addRecipe(new CommonEmiRecipe(category, holder));
            }
        });
    }

    private record CommonEmiRecipe(EmiRecipeCategory category, RecipeHolder<CommonRecipe> holder)
            implements EmiRecipe {
        @Override
        public EmiRecipeCategory getCategory() {
            return category;
        }

        @Override
        public ResourceLocation getId() {
            return holder.id();
        }

        @Override
        public List<EmiIngredient> getInputs() {
            return holder.value().inputs().stream().map(EmiIngredient::of).toList();
        }

        @Override
        public List<EmiStack> getOutputs() {
            return holder.value().outputs().stream().map(EmiStack::of).toList();
        }

        @Override
        public int getDisplayWidth() {
            return 180;
        }

        @Override
        public int getDisplayHeight() {
            return Math.max(120, 48 + displayLines().size() * 10);
        }

        private List<FormattedCharSequence> displayLines() {
            var font = Minecraft.getInstance().font;
            return holder.value().displayLines().stream()
                    .flatMap(line -> font.split(line, 180).stream())
                    .toList();
        }

        @Override
        public void addWidgets(WidgetHolder widgets) {
            for (int i = 0; i < holder.value().inputs().size(); i++) {
                widgets.addSlot(EmiIngredient.of(holder.value().inputs().get(i)), i % 5 * 20, i / 5 * 20);
            }
            for (int i = 0; i < holder.value().outputs().size(); i++) {
                widgets.addSlot(EmiStack.of(holder.value().outputs().get(i)), 112 + i % 3 * 20, i / 3 * 20)
                        .recipeContext(this);
            }
            List<FormattedCharSequence> lines = displayLines();
            for (int i = 0; i < lines.size(); i++) {
                widgets.addText(lines.get(i), 0, 44 + i * 10, 0x404040, false);
            }
        }

        @Override
        public RecipeHolder<?> getBackingRecipe() {
            return holder;
        }
    }
}
