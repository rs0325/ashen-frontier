package io.github.rs0325.ashenfrontier.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 承認済み中心線から水平な淵と短い早瀬を持つ距離場を復元する。 */
final class RiverCourse {
    record Sample(double distance, double surface, int x, int z, double riffle, double width, double bank, double depth, double grade) {}
    private record Segment(double ax, double az, double ay, double bx, double bz, double by, double station, double bend) {}
    private record Drop(double station) {}
    private final Map<Long, List<Segment>> cells;
    private final Map<Long, List<Drop>> drops;
    private final long seed;

    RiverCourse(double[][] points, long seed) {
        this.seed = seed;
        var controls = new ArrayList<double[]>();
        for (int i = 0; i < points.length; i += 8) controls.add(points[i]);
        controls.add(points[points.length - 1]);
        var cells = new HashMap<Long, List<Segment>>();
        var drops = new HashMap<Long, List<Drop>>();
        double station = 0;
        for (int i = 0; i < controls.size() - 1; i++) {
            double[] a = controls.get(Math.max(0, i - 1)), b = controls.get(i),
                    c = controls.get(i + 1), d = controls.get(Math.min(controls.size() - 1, i + 2));
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(c[0] - b[0], c[1] - b[1]) / 4));
            double px = b[0], pz = b[1], py = b[2];
            for (int step = 1; step <= steps; step++) {
                double t = step / (double) steps;
                double x = TerrainMath.cubic(a[0], b[0], c[0], d[0], t);
                double z = TerrainMath.cubic(a[1], b[1], c[1], d[1], t);
                double y = b[2] + t * (c[2] - b[2]);
                double length = Math.hypot(x - px, z - pz);
                double ux=c[0]-b[0],uz=c[1]-b[1],vx=b[0]-a[0],vz=b[1]-a[1],wx=d[0]-c[0],wz=d[1]-c[1];
                double turnA=(vx*uz-vz*ux)/Math.max(1,Math.hypot(ux,uz)*Math.hypot(vx,vz));
                double turnB=(ux*wz-uz*wx)/Math.max(1,Math.hypot(ux,uz)*Math.hypot(wx,wz));
                double bend=Math.clamp((turnA+(turnB-turnA)*t)*4,-1,1);
                var segment = new Segment(px, pz, py, x, z, y, station, bend);
                Drop drop = null;
                if (Math.ceil(py - 1e-8) > Math.ceil(y - 1e-8)) {
                    double crossing = (py - Math.ceil(y - 1e-8)) / (py - y);
                    drop = new Drop(station + length * Math.clamp(crossing, 0, 1));
                }
                for (int cx = (int) Math.floor((Math.min(px, x) - 256) / 128); cx <= (int) Math.floor((Math.max(px, x) + 256) / 128); cx++) {
                    for (int cz = (int) Math.floor((Math.min(pz, z) - 256) / 128); cz <= (int) Math.floor((Math.max(pz, z) + 256) / 128); cz++) {
                        cells.computeIfAbsent(key(cx, cz), ignored -> new ArrayList<>()).add(segment);
                        if (drop != null) drops.computeIfAbsent(key(cx, cz), ignored -> new ArrayList<>()).add(drop);
                    }
                }
                px = x; pz = z; py = y;
                station += length;
            }
        }
        cells.replaceAll((key, value) -> List.copyOf(value));
        this.cells = Map.copyOf(cells);
        drops.replaceAll((key, value) -> List.copyOf(value));
        this.drops = Map.copyOf(drops);
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    Sample sample(int x, int z) {
        double distance = 256 * 256, surface = 63, nearestX = x, nearestZ = z, station = 0, bank = 0, grade=63;
        for (var segment : cells.getOrDefault(key(x >> 7, z >> 7), List.of())) {
            double dx = segment.bx - segment.ax, dz = segment.bz - segment.az;
            double length = dx * dx + dz * dz;
            double t = length == 0 ? 0 : Math.clamp(((x - segment.ax) * dx + (z - segment.az) * dz) / length, 0, 1);
            double sx = segment.ax + t * dx, sz = segment.az + t * dz;
            double squared = (x - sx) * (x - sx) + (z - sz) * (z - sz);
            if (squared < distance) {
                distance = squared;
                grade=segment.ay+t*(segment.by-segment.ay);
                surface = Math.ceil(grade - 1e-8);
                station = segment.station + t * Math.sqrt(length);
                nearestX = sx; nearestZ = sz;
                bank=-Math.signum(dx*(z-sz)-dz*(x-sx))*segment.bend;
            }
        }
        if(distance==256*256)return new Sample(256,63,x,z,0,32,0,7,63);
        double toDrop = 8;
        for (var drop : drops.getOrDefault(key(x >> 7, z >> 7), List.of())) {
            toDrop = Math.min(toDrop, Math.abs(station - drop.station));
        }
        double reach=TerrainMath.noise(seed,"river_reach",nearestX,nearestZ,320);
        return new Sample(Math.sqrt(distance), surface, (int) Math.round(nearestX), (int) Math.round(nearestZ),
                1 - TerrainMath.smooth(toDrop / 8), 22+reach*26+TerrainMath.noise(seed,"river_width",nearestX,nearestZ,96)*8,
                bank,5+reach*4,grade);
    }
}
