package io.github.rs0325.ashenfrontier.world;

import java.util.Arrays;
import java.util.Locale;

/** 固定水系の雑音セルを正確な4隅の値で再利用する。スレッド専用で容量は固定。 */
final class HydrologyNoise {
    enum Salt {
        CANYON_BEND,
        CANYON_EXTENT,
        CANYON_GULLY,
        CANYON_JOINT,
        CANYON_SLOPE,
        CANYON_SMALL_GULLY,
        CANYON_TRANSITION,
        LAKE_BANK_BRANCH,
        LAKE_BANK_EXTENT,
        LAKE_BANK_JOINT,
        LAKE_BANK_TRANSITION,
        LAKE_BANK_WIDTH,
        LAKE_GULLY_BEND,
        LAKE_SMALL_GULLY,
        SOURCE_BANK_OFFSET,
        SOURCE_BANK_TOE,
        SOURCE_BRANCH,
        SOURCE_BRANCH_BEND,
        SOURCE_BRANCH_DEPTH,
        SOURCE_CROSS_BRANCH,
        SOURCE_CROSS_SURFACE,
        SOURCE_OBLIQUE_EXTENT,
        SOURCE_OBLIQUE_TRANSITION,
        SOURCE_SPUR,
        SOURCE_SPUR_BEND,
        SOURCE_TOE_LIFT,
        SOURCE_TOE_WIDTH,
        VALLEY_CROSS_RILL,
        VALLEY_RILL_BRANCH,
        VALLEY_RILL_SHIFT;

        final String id=name().toLowerCase(Locale.ROOT);
    }

    private static final int SIZE=16384;
    private static final class Cells {
        final long[] keys=new long[SIZE];
        final int[] tags=new int[SIZE];
        final double[] corners=new double[SIZE*4];

        Cells() {Arrays.fill(tags,-1);}

        void fill(long seed,String salt,int tag,long key,int slot,int gx,int gz) {
            tags[slot]=tag;keys[slot]=key;int at=slot*4;
            corners[at]=TerrainMath.unit(seed,salt,gx,gz);
            corners[at+1]=TerrainMath.unit(seed,salt,gx+1,gz);
            corners[at+2]=TerrainMath.unit(seed,salt,gx,gz+1);
            corners[at+3]=TerrainMath.unit(seed,salt,gx+1,gz+1);
        }
    }

    private final long seed;
    private final Cells cells=new Cells();

    HydrologyNoise(long seed) {this.seed=seed;}

    double noise(Salt salt,double x,double z,int scale) {
        return noise(cells,salt,0,x,z,scale);
    }

    double fbm(Salt salt,double x,double z,int scale) {
        var cache=cells;
        double sum=0,weight=1,total=0;
        for(int octave=0;octave<4;octave++) {
            sum+=(noise(cache,salt,octave,x,z,scale)*2-1)*weight;
            total+=weight;
            double nx=.8*x-.6*z+137;
            z=.6*x+.8*z-271;
            x=nx;scale/=2;weight*=.5;
        }
        return sum/total;
    }

    private double noise(Cells cache,Salt salt,int octave,double x,double z,int scale) {
        int gx=(int)Math.floor(x/scale),gz=(int)Math.floor(z/scale);
        double fx=x/scale-gx,fz=z/scale-gz;
        fx=fx*fx*(3-2*fx);fz=fz*fz*(3-2*fz);
        int tag=salt.ordinal()*4+octave;
        int hash=gx*0x1f1f1f1f ^ gz*0x5f356495 ^ tag*0x9e3779b9;
        int slot=(hash^(hash>>>16))&(SIZE-1),at=slot*4;
        long key=((long)gx<<32)^(gz&0xffffffffL);
        if(cache.tags[slot]!=tag || cache.keys[slot]!=key) {
            cache.fill(seed+octave,salt.id,tag,key,slot,gx,gz);
        }
        return TerrainMath.bilinear(cache.corners[at],cache.corners[at+1],
                cache.corners[at+2],cache.corners[at+3],fx,fz);
    }
}
