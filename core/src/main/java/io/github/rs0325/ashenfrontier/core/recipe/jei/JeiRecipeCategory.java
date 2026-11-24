package io.github.rs0325.ashenfrontier.core.recipe.jei;

import io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

final class JeiRecipeCategory implements IRecipeCategory<CommonRecipe> {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 280;
    private static final int TEXT_WIDTH = 180;
    private final RecipeType<CommonRecipe> type;
    private final ResourceLocation id;
    private final IDrawable icon;

    JeiRecipeCategory(ResourceLocation id, IGuiHelper gui) {
        this.id = id;
        this.type = new RecipeType<>(id, CommonRecipe.class);
        this.icon = gui.createDrawableItemLike(Items.FURNACE);
    }

    @Override
    public RecipeType<CommonRecipe> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("recipe_type." + id.getNamespace() + "." + id.getPath());
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, CommonRecipe recipe, IFocusGroup focuses) {
        for (int i = 0; i < recipe.inputs().size(); i++) {
            builder.addSlot(RecipeIngredientRole.INPUT, i % 5 * 20, i / 5 * 20)
                    .addIngredients(recipe.inputs().get(i));
        }
        for (int i = 0; i < recipe.outputs().size(); i++) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, 112 + i % 3 * 20, i / 3 * 20)
                    .addItemStack(recipe.outputs().get(i));
        }
    }

    @Override
    public void draw(CommonRecipe recipe, IRecipeSlotsView slots, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        var lines = recipe.displayLines().stream()
                .flatMap(line -> font.split(line, TEXT_WIDTH).stream())
                .toList();
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), 0, 44 + i * 10, 0x404040, false);
        }
    }
}
