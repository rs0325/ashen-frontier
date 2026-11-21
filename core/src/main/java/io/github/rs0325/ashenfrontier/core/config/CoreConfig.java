package io.github.rs0325.ashenfrontier.core.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class CoreConfig {
    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.DoubleValue LIFE_SUPPORT_LOOT_MULTIPLIER = SERVER_BUILDER
            .translation("config.ashen_frontier_core.loot.lifeSupportMultiplier")
            .comment("建物の生命維持消耗品loot倍率（未開封容器の初回開封時に適用）")
            .defineInRange("loot.lifeSupportMultiplier", 1.0, 0.0, 4.0);
    private static final ModConfigSpec.ConfigValue<List<? extends String>> SERVER_ALLOWED_MODS =
            SERVER_BUILDER.translation("config.ashen_frontier_core.compatibility.allowedMods")
                    .comment("互換性チェックで許可する追加Mod ID")
                    .defineListAllowEmpty("compatibility.allowedMods", List.of(), value -> value instanceof String);
    public static final ModConfigSpec SERVER = SERVER_BUILDER.build();

    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue USE_KELVIN = CLIENT_BUILDER
            .translation("config.ashen_frontier_core.units.temperatureKelvin")
            .comment("温度をケルビンで表示する")
            .define("units.temperatureKelvin", false);
    private static final ModConfigSpec.BooleanValue USE_PASCAL = CLIENT_BUILDER
            .translation("config.ashen_frontier_core.units.pressurePascal")
            .comment("圧力をパスカルで表示する")
            .define("units.pressurePascal", false);
    private static final ModConfigSpec.IntValue PARTICLE_QUALITY = CLIENT_BUILDER
            .translation("config.ashen_frontier_core.visual.particleQuality")
            .comment("霧・砂塵の装飾粒子の描画品質（0: 無効、1: 少ない、2: 標準）")
            .defineInRange("visual.particleQuality", 2, 0, 2);
    private static final ModConfigSpec.ConfigValue<List<? extends String>> CLIENT_ALLOWED_MODS = CLIENT_BUILDER
            .translation("config.ashen_frontier_core.compatibility.allowedMods")
            .comment("互換性チェックで許可する追加Mod ID")
            .defineListAllowEmpty("compatibility.allowedMods", List.of(), value -> value instanceof String);
    public static final ModConfigSpec CLIENT = CLIENT_BUILDER.build();

    private CoreConfig() {}

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER);
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT);
    }

    public static boolean useKelvin() { return USE_KELVIN.get(); }
    public static boolean usePascal() { return USE_PASCAL.get(); }
    public static int particleQuality() { return PARTICLE_QUALITY.get(); }
    public static double lifeSupportLootMultiplier() { return LIFE_SUPPORT_LOOT_MULTIPLIER.get(); }

    public static List<String> serverAllowedMods() {
        return SERVER_ALLOWED_MODS.get().stream().map(String.class::cast).toList();
    }

    public static List<String> clientAllowedMods() {
        return CLIENT_ALLOWED_MODS.get().stream().map(String.class::cast).toList();
    }
}
