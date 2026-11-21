package io.github.rs0325.ashenfrontier.core;

import io.github.rs0325.ashenfrontier.core.loot.LifeSupportQuantity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LifeSupportLootTest {
    @Test
    void roundsHalfUpAndHonorsMultiplierBounds() {
        assertEquals(0, LifeSupportQuantity.scaledCount(3, 0));
        assertEquals(3, LifeSupportQuantity.scaledCount(3, 1));
        assertEquals(2, LifeSupportQuantity.scaledCount(3, .5));
        assertEquals(12, LifeSupportQuantity.scaledCount(3, 4));
    }
}
