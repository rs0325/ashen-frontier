package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 二つの制作schemaとmanifestで共有する、整数・実数の型検査。 */
final class LayoutJson {
    private LayoutJson() {}

    static int integer(JsonObject object, String key) { return integer(object.get(key)); }

    static int integer(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("-?[0-9]+"))
            throw new IllegalStateException("整数値が必要です");
        return value.getAsBigDecimal().intValueExact();
    }

    static double number(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()))
            throw new IllegalStateException("有限の数値が必要です");
        return value.getAsDouble();
    }
}
