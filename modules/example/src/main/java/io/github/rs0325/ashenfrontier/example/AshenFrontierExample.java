package io.github.rs0325.ashenfrontier.example;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.neoforged.fml.common.Mod;

@Mod(AshenFrontierExample.MOD_ID)
public final class AshenFrontierExample {
    public static final String MOD_ID = "ashen_frontier_example";

    public AshenFrontierExample() {
        AshenFrontierCore.LOGGER.info("Loaded {}", AshenFrontierCore.id("example"));
    }
}
