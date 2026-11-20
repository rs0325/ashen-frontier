package io.github.rs0325.ashenfrontier.core;

import io.github.rs0325.ashenfrontier.core.compat.ModCompatibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModCompatibilityTest {
    @Test
    void allowsBuiltInConfiguredAndProjectMods() {
        assertEquals(Set.of("other"), ModCompatibility.unsupported(List.of(
                "minecraft", "neoforge", "jei", "ashen_frontier_core", "custom", "other"),
                List.of("custom")));
    }
}
