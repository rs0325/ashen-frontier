package io.github.rs0325.ashenfrontier.world;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

import java.util.Locale;

final class MapClientConfig {
    enum Corner implements TranslatableEnum {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

        @Override public Component getTranslatedName() {
            return Component.translatable("map.ashen_frontier.corner." + name().toLowerCase(Locale.ROOT));
        }
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    static final ModConfigSpec.BooleanValue ENABLED = BUILDER.translation("map.ashen_frontier.enabled")
            .define("minimap.enabled", true);
    static final ModConfigSpec.IntValue SIZE = BUILDER.translation("map.ashen_frontier.size")
            .defineInRange("minimap.size", 128, 64, 320);
    static final ModConfigSpec.DoubleValue ZOOM = BUILDER.translation("map.ashen_frontier.zoom")
            .defineInRange("minimap.zoom", 1.0, 0.125, 4.0);
    static final ModConfigSpec.EnumValue<Corner> CORNER = BUILDER.translation("map.ashen_frontier.corner")
            .defineEnum("minimap.corner", Corner.TOP_RIGHT);
    static final ModConfigSpec.IntValue MARGIN = BUILDER.translation("map.ashen_frontier.margin")
            .defineInRange("minimap.margin", 8, 0, 100);
    static final ModConfigSpec SPEC = BUILDER.build();

    private MapClientConfig() {}
}
