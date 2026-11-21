package io.github.rs0325.ashenfrontier.world;

/** 配置図の北西角サンプルを補間する純粋計算。 */
final class TerrainMath {
    private TerrainMath() {}

    static int pixel(int block) { return Math.floorDiv(block - MapCoordinates.MIN, 16); }

    static int nearestPixel(int block) { return Math.min(2047, pixel(block + 8)); }

    static double fraction(int block) { return Math.floorMod(block - MapCoordinates.MIN, 16) / 16.0; }

    static double bilinear(double nw, double ne, double sw, double se, double x, double z) {
        return (nw + (ne - nw) * x) * (1 - z) + (sw + (se - sw) * x) * z;
    }

    static double unit(long seed, String salt, int x, int z) {
        long n = seed ^ ((long) salt.hashCode() << 32) ^ x * 0x9e3779b97f4a7c15L ^ z * 0xc2b2ae3d27d4eb4fL;
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
        return ((n ^ (n >>> 31)) >>> 11) * 0x1.0p-53;
    }

    static double noise(long seed, String salt, int x, int z, int scale) {
        int gx = Math.floorDiv(x, scale), gz = Math.floorDiv(z, scale);
        double fx = Math.floorMod(x, scale) / (double) scale, fz = Math.floorMod(z, scale) / (double) scale;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        return bilinear(unit(seed, salt, gx, gz), unit(seed, salt, gx + 1, gz),
                unit(seed, salt, gx, gz + 1), unit(seed, salt, gx + 1, gz + 1), fx, fz);
    }
}
