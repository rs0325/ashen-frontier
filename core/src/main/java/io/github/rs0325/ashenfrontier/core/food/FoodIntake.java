package io.github.rs0325.ashenfrontier.core.food;

import io.github.rs0325.ashenfrontier.core.ItemProperties;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** M3へ渡す1個分の摂取情報。消費・生理更新・安全判定は行わない。腐敗/放射能の時刻も維持する。 */
public record FoodIntake(NutritionData nutrition, Optional<ItemProperties.Rot> rot,
                         Optional<ItemProperties.Contamination> contamination,
                         Optional<ItemProperties.Radioactivity> radioactivity) {
    public static Optional<FoodIntake> from(ItemStack stack) {
        return NutritionData.get(stack).map(n -> new FoodIntake(n,
                Optional.ofNullable(stack.get(ItemProperties.ROT)),
                Optional.ofNullable(stack.get(ItemProperties.CONTAMINATION)),
                Optional.ofNullable(stack.get(ItemProperties.RADIOACTIVITY))));
    }
}
