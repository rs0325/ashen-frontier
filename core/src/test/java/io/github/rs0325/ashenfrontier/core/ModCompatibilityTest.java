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
                "minecraft", "neoforge", "jei", "mezz_config", "ashen_frontier_core", "custom", "other"),
                List.of("custom")));
    }

    @Test
    void summarizesUnsupportedModsWithOmittedCount() {
        Set<String> unsupported = Set.of("g", "f", "e", "d", "c", "b", "a");
        assertEquals("a, b, c, d, e", ModCompatibility.displayedMods(unsupported));
        assertEquals(2, ModCompatibility.omittedCount(unsupported));
        assertEquals(0, ModCompatibility.omittedCount(Set.of("a", "b", "c")));
    }
}
