package io.github.rs0325.ashenfrontier.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import io.github.rs0325.ashenfrontier.core.research.ResearchTree;
import java.util.List;
import java.util.Map;

class AshenFrontierCoreTest {
    @Test
    void usesSharedResourceNamespace() {
        assertEquals("ashen_frontier", AshenFrontierCore.NAMESPACE);
    }

    @Test
    void resolvesPrerequisitesInOrder() {
        var first = "test:first";
        var second = "test:second";
        var definitions = Map.of(
                second, new ResearchTree.Definition(second, "second", "second", List.of(first), List.of(), List.of()),
                first, new ResearchTree.Definition(first, "first", "first", List.of(), List.of(), List.of()));
        assertEquals(List.of(first, second), ResearchTree.resolve(definitions).order());
    }

    @Test
    void rejectsCyclesAndMissingPrerequisites() {
        var first = "test:first";
        var second = "test:second";
        var missing = "test:missing";
        var definitions = Map.of(
                first, new ResearchTree.Definition(first, "first", "first", List.of(second), List.of(), List.of()),
                second, new ResearchTree.Definition(second, "second", "second",
                        List.of(first, missing), List.of(), List.of()));
        var resolved = ResearchTree.resolve(definitions);
        assertTrue(resolved.invalid().containsAll(List.of(first, second)));
        assertFalse(resolved.definitions().containsKey(first));
    }
}
