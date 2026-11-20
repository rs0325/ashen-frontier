package io.github.rs0325.ashenfrontier.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AshenFrontierCoreTest {
    @Test
    void usesSharedResourceNamespace() {
        assertEquals("ashen_frontier", AshenFrontierCore.NAMESPACE);
    }
}
