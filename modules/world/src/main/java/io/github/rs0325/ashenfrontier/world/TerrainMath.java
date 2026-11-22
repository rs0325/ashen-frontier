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

    static double smooth(double t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    static double cubic(double a, double b, double c, double d, double t) {
        return b + 0.5 * t * (c - a + t * (2 * a - 5 * b + 4 * c - d
                + t * (3 * (b - c) + d - a)));
    }

    static double unit(long seed, String salt, int x, int z) {
        long n = seed ^ ((long) salt.hashCode() << 32) ^ x * 0x9e3779b97f4a7c15L ^ z * 0xc2b2ae3d27d4eb4fL;
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
        return ((n ^ (n >>> 31)) >>> 11) * 0x1.0p-53;
    }

    static double noise(long seed, String salt, int x, int z, int scale) {
        return noise(seed, salt, (double) x, (double) z, scale);
    }

    static double noise(long seed, String salt, double x, double z, int scale) {
        int gx = (int) Math.floor(x / scale), gz = (int) Math.floor(z / scale);
        double fx = x / scale - gx, fz = z / scale - gz;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        return bilinear(unit(seed, salt, gx, gz), unit(seed, salt, gx + 1, gz),
                unit(seed, salt, gx, gz + 1), unit(seed, salt, gx + 1, gz + 1), fx, fz);
    }

    static double fbm(long seed, String salt, double x, double z, int scale) {
        double sum = 0, weight = 1, total = 0;
        for (int octave = 0; octave < 4; octave++) {
            sum += (noise(seed + octave, salt, x, z, scale) * 2 - 1) * weight;
            total += weight;
            double nx = 0.8 * x - 0.6 * z + 137;
            z = 0.6 * x + 0.8 * z - 271;
            x = nx;
            scale /= 2;
            weight *= 0.5;
        }
        return sum / total;
    }

    static double snowline(long seed, int x, int z, double latitude, double slopeX, double slopeZ, boolean cold) {
        double climate = cold ? 45 : 230 - Math.max(0, latitude - 45) * 3;
        return climate + fbm(seed, "snowline", x, z, 384) * 18 - slopeZ * 10
                + Math.max(0, Math.hypot(slopeX, slopeZ) - 0.4) * 18;
    }

    static double layerThickness(long seed, String salt, int x, int z, int min, int max, double regionalFold) {
        double variation = 0.1 * fbm(seed, salt, x, z, 256)
                + 0.6 * (noise(seed + 73, salt, x, z, 48) * 2 - 1)
                + 0.25 * (noise(seed + 149, salt, x, z, 20) * 2 - 1) + 0.05 * regionalFold;
        return min + (0.5 + 0.5 * Math.tanh(variation * 2.5)) * (max - min);
    }

    static int snowLayers(double height, double snowline, double slope) {
        double cover=Math.clamp((height-snowline+12)/3,0,8);
        return (int)Math.floor(cover*smooth((.9-slope)/.4));
    }

    static double valleyHeight(double ground, double water, double distance, double width, double slope) {
        double bank = water + 1 + Math.pow(Math.max(0, distance - width), 1.25) * slope;
        double blend = smooth((distance - width - 48) / 72);
        double cut = Math.min(ground, bank);
        return cut + (ground - cut) * blend;
    }

    static double snowPack(double aboveLine, double coast, double slope, double hollow, double aspect) {
        double drift = Math.clamp(.35 + hollow*.18 + aspect*.3,0,1);
        return (2+2*drift)*smooth((aboveLine-12)/32)*smooth((coast-24)/48)*smooth((.65-slope)/.35);
    }

    static int snowCover(double continuousHeight, int ground, int transition) {
        return transition < 8 ? transition : Math.clamp((int)Math.round((continuousHeight-ground+.5)*8),1,8);
    }

    static int depositLayers(double coverage, double scatter) {
        if (scatter >= coverage) return 0;
        return coverage < .92 ? 1 + (scatter < coverage * .5 ? 1 : 0)
                : Math.clamp((int) Math.ceil((coverage - .92) / .08 * 8), 2, 8);
    }

    static double islandPeak(double dx, double dz, int peak, double radius, double cos, double sin, double detail) {
        double along=(dx*cos+dz*sin)/radius, across=(-dx*sin+dz*cos)/(radius*.65);
        double r=Math.hypot(along,across);
        double angle=Math.atan2(across,along);
        r*=1+(.22*Math.sin(angle*3)+detail*.35)*smooth(r/.3);
        double profile=Math.pow(1-smooth(r),.7);
        double gully=Math.pow(Math.max(0,Math.sin(angle*4+detail*3)),6)*18*profile*(1-profile);
        return 63+(peak-63)*profile-gully;
    }
    /** 静的な寒冷水面の判定。kindは湖1・川2・海3、水深はブロック数。 */
    static boolean freezes(double latitude, int water, int depth, int kind, double cold) {
        double score = Math.abs(latitude) + (water - 63) / 32.0 + cold * 12;
        return depth > 0
                && score > (kind == 3 ? 72 : kind == 2 ? 64 : 60);
    }
}
