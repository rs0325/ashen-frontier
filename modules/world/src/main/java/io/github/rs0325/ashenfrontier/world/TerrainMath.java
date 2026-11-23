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

    /** 地面ブロック底面からの天面を1/8単位で返す。0は散布なし。 */
    static int sedimentCover(double height, int ground, double coverage, double scatter, double slope, double hollow) {
        int layers = depositLayers(coverage, coverage >= .92 ? 0 : scatter);
        if (layers == 0) return 0;
        double inside = smooth((coverage - .92) / .08);
        double depth = .125 + .375 * smooth((.24 - slope) / .18) * smooth((hollow + .4) / .8);
        double edge = 1 + Math.min(2, layers) / 8.0;
        double top = Math.clamp(height - ground, -.5, .5) + 1 + depth;
        return (int) Math.round((edge + (top - edge) * inside) * 8);
    }

    static double islandPeak(double dx, double dz, int peak, double radius, double cos, double sin, double detail) {
        double along=(dx*cos+dz*sin)/radius, across=(-dx*sin+dz*cos)/(radius*.65);
        double shoulder=smooth((Math.abs(along)+Math.abs(across))/.3);
        across-=shoulder*(.16*Math.sin(along*5)+detail*.3*smooth((Math.abs(along)+Math.abs(across))/.4));
        double ridge=Math.max(0,1-.56*Math.abs(along)-.72*Math.abs(across)
                -.22*Math.abs(across+along*.65));
        double gully=Math.max(0,detail)*22*shoulder*ridge;
        return 63+(peak-63)*ridge-gully;
    }

    static double rockRelief(long seed, double x, double z) {
        double joint=noise(seed,"rock_joints",.8*x+.6*z,-.6*x+.8*z,24)*2-1;
        double spur=1-Math.abs(joint);
        double cleft=noise(seed,"rock_clefts",x+joint*12,z,9)*2-1;
        double ledge=noise(seed,"rock_ledge",x,z,5)*2-1;
        return (spur-.5)*24+cleft*8+ledge*2;
    }

    static double massifRelief(long seed, double x, double z) {
        double bend=(noise(seed,"gully_bend",x,z,160)-.5)*48;
        double channel=noise(seed,"massif_gully",x+bend,z*.28,48)*2-1;
        double incision=smooth((.4-Math.abs(channel))/.4);
        double joint=noise(seed,"massif_shoulder",.8*x+.6*z,-.6*x+.8*z,40)*2-1;
        double blocks=noise(seed,"massif_blocks",x+joint*8,z,16)*2-1;
        return (1-joint*joint-.65)*30-incision*36+blocks*9;
    }
    static double valleyHead(long seed,int x,int z,RiverCourse.Sample source,double ground) {
        double north=source.z()-z,cross=x-source.x();
        double axis=.15*north+34*(noise(seed,"head_gully_axis",source.x(),z,56)
                -noise(seed,"head_gully_axis",source.x(),source.z(),56))*smooth(north/24);
        double shoulder=fbm(seed,"brook_shoulder",x,z,36),cut=ground;
        for(int branch=0;branch<3;branch++) {
            double join=branch==0?0:branch==1?65:110;
            if(north<join)continue;
            double t=north-join,offset=0,strength=1;
            if(branch>0) {
                double turn=1-Math.cos(t/(branch==1?55:43));
                offset=(branch==1?-1:1)*(t*.55+turn*12)*smooth(t/24);
                strength=smooth(t/28)*(branch==1?.85:.65);
            }
            double d=Math.max(0,Math.abs(cross-axis-offset)-source.width());
            if(d>=48)continue;
            double floor=source.surface()+1.05*north+.001*north*north+(branch==0?0:t*.35);
            double target=floor+.35+d*.35+Math.pow(Math.max(0,d-.8),1.25)*(1.8+shoulder*.35);
            double rocks=d>3?(massifRelief(seed,x,z)+12)*.9*smooth((d-3)/10):0;
            target=Math.max(floor+.35+d*.35,target+rocks);
            double influence=1-smooth((d-8)/40);
            cut=Math.min(cut,ground-Math.max(0,ground-target)*influence*strength);
        }
        return cut;
    }

    /** 静的な寒冷水面の判定。kindは湖1・川2・海3、水深はブロック数。 */
    static boolean freezes(double latitude, int water, int depth, int kind, double cold) {
        double score = Math.abs(latitude) + (water - 63) / 32.0 + cold * 12;
        return depth > 0
                && score > (kind == 3 ? 72 : kind == 2 ? 64 : 60);
    }
}
