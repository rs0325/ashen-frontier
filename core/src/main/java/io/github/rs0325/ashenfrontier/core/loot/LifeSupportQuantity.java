package io.github.rs0325.ashenfrontier.core.loot;

public final class LifeSupportQuantity {
    private LifeSupportQuantity() {}

    public static int scaledCount(int count, double multiplier) {
        return (int) Math.floor(count * multiplier + 0.5);
    }
}
