package io.github.rs0325.ashenfrontier.world;

public final class AtmosphereMath {
    public static final double EDGE_WIDTH = 1024.0;

    private AtmosphereMath() {}

    public static double edgeDensity(double distanceToEdge) {
        return clamp(1.0 - distanceToEdge / EDGE_WIDTH);
    }

    /** 元の開始距離 < 終了距離を前提に両方を縮め、外縁より手前で不透明にする。 */
    public static FogRange fogRange(double near, double far, double biomeDensity, double distanceToEdge) {
        double scale = 1.0 - clamp(biomeDensity) * 0.68;
        scale *= 1.0 - edgeDensity(distanceToEdge) * 0.92;
        double end = Math.min(far * scale, Math.max(0, distanceToEdge) * 0.9);
        double start = Math.min(near * scale, end - Math.max(1, (far - near) * scale));
        return new FogRange(start, end);
    }

    public static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record FogRange(double start, double end) {}
}
