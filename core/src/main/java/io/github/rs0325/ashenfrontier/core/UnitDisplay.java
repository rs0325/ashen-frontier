package io.github.rs0325.ashenfrontier.core;

import net.minecraft.network.chat.Component;

/** クライアント設定を適用し、翻訳済み単位記号で値を表示する。 */
public final class UnitDisplay {
    private UnitDisplay() {}

    public static String temperature(double kelvin) {
        boolean useKelvin = UnitsConfig.USE_KELVIN.get();
        String symbol = Component.translatable(useKelvin ? "unit.ashen_frontier.kelvin" : "unit.ashen_frontier.celsius").getString();
        return Units.temperature(kelvin, useKelvin, symbol);
    }

    public static String pressure(double pascals) {
        boolean usePascal = UnitsConfig.USE_PASCAL.get();
        String symbol = Component.translatable(usePascal ? "unit.ashen_frontier.pascal" : "unit.ashen_frontier.bar").getString();
        return Units.pressure(pascals, usePascal, symbol);
    }
}
