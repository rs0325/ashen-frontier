package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 固定徒歩経路の高さと、その周囲の狭い移行斜面。 */
final class TerrainWalkways {
    private record Segment(double ax, double az, double ay, double bx, double bz, double by) {}
    record Sample(double distance, double height) {}
    private final Map<Long, List<Segment>> cells;
    private final long seed;

    TerrainWalkways(JsonArray walkways, long seed) {
        this.seed = seed;
        var cells = new HashMap<Long, List<Segment>>();
        for (var value : walkways) {
            var points = value.getAsJsonObject().getAsJsonArray("points_x_z_y");
            for (int i = 1; i < points.size(); i++) {
                var a = points.get(i - 1).getAsJsonArray(); var b = points.get(i).getAsJsonArray();
                var segment = new Segment(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(),
                        b.get(0).getAsDouble(), b.get(1).getAsDouble(), b.get(2).getAsDouble());
                for (int x = (int) Math.floor((Math.min(segment.ax, segment.bx) - 64) / 128); x <= (int) Math.floor((Math.max(segment.ax, segment.bx) + 64) / 128); x++) {
                    for (int z = (int) Math.floor((Math.min(segment.az, segment.bz) - 64) / 128); z <= (int) Math.floor((Math.max(segment.az, segment.bz) + 64) / 128); z++) {
                        cells.computeIfAbsent(key(x, z), ignored -> new ArrayList<>()).add(segment);
                    }
                }
            }
        }
        cells.replaceAll((key, value) -> List.copyOf(value));
        this.cells = Map.copyOf(cells);
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    Sample sample(int x, int z) {
        double nearest = 64 * 64, height = 0;
        for (var segment : cells.getOrDefault(key(x >> 7, z >> 7), List.of())) {
            double dx = segment.bx - segment.ax, dz = segment.bz - segment.az, squared = dx * dx + dz * dz;
            double t = squared == 0 ? 0 : Math.clamp(((x - segment.ax) * dx + (z - segment.az) * dz) / squared, 0, 1);
            double distance = Math.pow(x - segment.ax - t * dx, 2) + Math.pow(z - segment.az - t * dz, 2);
            if (distance < nearest) { nearest = distance; height = segment.ay + t * (segment.by - segment.ay); }
        }
        return new Sample(Math.sqrt(nearest), height);
    }

    double height(int x, int z, double ground) {
        var sample = sample(x, z);
        double width = 20 + 40 * TerrainMath.noise(seed, "walkway_erosion", x, z, 72);
        double blend = 1 - TerrainMath.smooth((sample.distance - 1) / width);
        return ground + (sample.height - ground) * blend;
    }
}
