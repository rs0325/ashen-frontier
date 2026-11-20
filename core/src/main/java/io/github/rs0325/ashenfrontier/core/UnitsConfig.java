package io.github.rs0325.ashenfrontier.core;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class UnitsConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    static final ModConfigSpec.BooleanValue USE_KELVIN = BUILDER
            .comment("温度をケルビンで表示する")
            .define("units.temperatureKelvin", false);
    static final ModConfigSpec.BooleanValue USE_PASCAL = BUILDER
            .comment("圧力をパスカルで表示する")
            .define("units.pressurePascal", false);
    private static final ModConfigSpec SPEC = BUILDER.build();

    private UnitsConfig() {}

    static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
    }
}
