package io.github.rs0325.ashenfrontier.core.water;

import io.github.rs0325.ashenfrontier.core.recipe.WaterTank;

/** BlockEntityが実装する内部の槽の所属契約。外部ケイパビリティではない。 */
public interface WaterTankOwner {
    WaterTank waterTank();
}
