package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.core.SoilContamination.Kind;
import java.util.List;

/** earth_001の静的な汚染分布。地形の列で一度評価し、改変のない位置は保存しない。 */
final class FixedContamination {
    private record Source(Kind kind, int x, int z, int toxic, int high, int moderate, int light) {}
    private static final List<Source> SOURCES = List.of(
            new Source(Kind.METALS, -10050, -10800, 64, 160, 480, 1000),
            new Source(Kind.METALS, -6100, -5200, 48, 128, 320, 800),
            new Source(Kind.METALS, -4100, -3900, 32, 96, 240, 640),
            new Source(Kind.ORGANICS, -6100, -5200, 64, 160, 480, 1000),
            new Source(Kind.ORGANICS, -4100, -3900, 48, 128, 320, 800),
            new Source(Kind.SALTS, -12400, -5400, 64, 160, 480, 1000),
            new Source(Kind.ACIDITY, -10050, -10800, 48, 128, 384, 768),
            new Source(Kind.RADIOACTIVE, -7000, -6200, 48, 128, 320, 640),
            new Source(Kind.BIOLOGICAL, -7800, -7900, 32, 96, 256, 512),
            new Source(Kind.BIOLOGICAL, -6100, -5200, 32, 96, 256, 640));
    private final FixedMapData map;
    private final double confluence;

    FixedContamination(FixedMapData map) {
        this.map = map;
        var projected = map.river(-4500, -6400);
        confluence = map.river(projected.x(), projected.z()).station();
    }

    static boolean reserve(int x, int z) {
        return x >= -5728 && x < -5472 && z >= -8320 && z < -8064;
    }

    SoilContamination surface(int x, int z, double coast, RiverCourse.Sample river) {
        int region = map.region(x, z);
        if (region < 1 || region > 7 || coast < 0 || reserve(x, z)) return SoilContamination.CLEAN;
        double[] values = {1e-5, 0, .05, 6.5, 20, 0};
        if (region == 5 || region == 6) {
            values[0] = 5e-5;
            values[1] = 1e-6;
        }
        for (var source : SOURCES) {
            double dx = x - source.x, dz = z - source.z;
            if (Math.abs(dx) > source.light + 64 || Math.abs(dz) > source.light + 64) continue;
            double distance = Math.hypot(dx, dz);
            if (distance > source.light + 64) continue;
            if (distance > source.light) {
                double edge = source.kind.boundary(1), background = SoilContamination.CLEAN.value(source.kind);
                if (source.kind == Kind.ACIDITY) { edge = activity(edge); background = activity(background); }
                double value = edge + (background - edge) * ((distance - source.light) / 64);
                merge(values, source.kind, source.kind == Kind.ACIDITY ? -Math.log10(value) : value);
                continue;
            }
            int[] radii = {0, source.toxic, source.high, source.moderate, source.light};
            double center = source.kind == Kind.ACIDITY ? activity(2.9) : source.kind.boundary(4) * 2;
            double previous = center;
            for (int ring = 1; ring <= 4; ring++) {
                double boundary = source.kind.boundary(5 - ring);
                if (source.kind == Kind.ACIDITY) boundary = activity(boundary);
                if (distance <= radii[ring]) {
                    double t = (distance - radii[ring - 1]) / (radii[ring] - radii[ring - 1]);
                    double value = t == 1 ? boundary : previous + (boundary - previous) * t;
                    if (source.kind == Kind.ACIDITY) value = -Math.log10(value);
                    merge(values, source.kind, value);
                    break;
                }
                previous = boundary;
            }
        }
        if (coast <= 192) merge(values, Kind.SALTS,
                coast <= 64 ? Math.min(Math.nextDown(.8), .4 + .4 * (1 - coast / 64))
                        : Math.min(Math.nextDown(.4), .2 + .2 * (192 - coast) / 128));
        double downstream = river.station() - confluence;
        if (downstream >= 0 && downstream <= 1500 && river.distance() <= 96) {
            double factor = (1 - downstream / 1500) * (1 - river.distance() / 96);
            merge(values, Kind.ORGANICS, 2e-5 * factor);
            merge(values, Kind.METALS, 1e-5 + 9e-5 * factor);
            merge(values, Kind.BIOLOGICAL, 2e6 * factor);
        }
        double east = x + 7000, north = -(z + 6200);
        double along = (east + north) / Math.sqrt(2), across = Math.abs(east - north) / Math.sqrt(2);
        if (along >= 0 && along < 1200 && across < 96 * (1 - along / 1200))
            merge(values, Kind.RADIOACTIVE, 100 + 299 * (1 - along / 1200)
                    * (1 - across / (96 * (1 - along / 1200))));
        var result = new SoilContamination(values[0], values[1], values[2], values[3], values[4], values[5]);
        if (result.severe() && map.contaminationProtected(x, z))
            return new SoilContamination(Math.min(values[0], Math.nextDown(Kind.METALS.boundary(3))),
                    Math.min(values[1], Math.nextDown(Kind.ORGANICS.boundary(3))),
                    Math.min(values[2], Math.nextDown(Kind.SALTS.boundary(3))),
                    Math.max(values[3], Kind.ACIDITY.boundary(3)),
                    Math.min(values[4], Math.nextDown(Kind.RADIOACTIVE.boundary(3))),
                    Math.min(values[5], Math.nextDown(Kind.BIOLOGICAL.boundary(3))));
        return result;
    }

    private static double activity(double ph) { return Math.pow(10, -ph); }

    private static void merge(double[] values, Kind kind, double value) {
        int index = kind.ordinal();
        values[index] = kind == Kind.ACIDITY ? Math.min(values[index], value) : Math.max(values[index], value);
    }
}
