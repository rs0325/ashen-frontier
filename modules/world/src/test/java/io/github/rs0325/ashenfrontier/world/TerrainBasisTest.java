package io.github.rs0325.ashenfrontier.world;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainBasisTest {
    static MapVersion version() throws Exception {
        try (var s = TerrainBasisTest.class.getResourceAsStream("/data/ashen_frontier/fixed_map/earth_002/manifest.json")) {
            return new MapVersion("earth_002",1,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.readAllBytes())));
        }
    }
    @Test void newBasisLoadsAndKeepsTheApprovedControlHeights() throws Exception {
        var map = FixedMapData.require(version());
        var layout = BundledMaps.landform(version());
        for (var unit : layout.units().values()) if (!unit.kind().equals("detail"))
            for (var p : unit.controls()) {
                int actual = FixedTerrain.surfaceY(map,p.x(),p.z());
                System.out.println("BASIS_CONTROL " + p.id() + " target=" + p.controlY() + " actual=" + actual);
                assertTrue(Math.abs(actual-p.controlY())<=1,p.id()+": "+actual);
            }
        assertEquals(0,map.waterInventoryId(-4000,-12100));
        assertFalse(map.isProtected(-8500,-8192));
    }

    @Test void interpolationDoesNotStepAtTileOrClassificationEdges() throws Exception {
        var map=FixedMapData.require(version());int checked=0;double maximum=0;int worstX=0,worstZ=0;boolean worstEast=false;
        for(int z=-14000;z<-2000;z+=32) for(int x=-13800;x<-1500;x+=32) {
            if(map.landformId(x,z)==0 || map.coastDistance(x,z)<256)continue;
            for(boolean east:new boolean[]{false,true}) {
                double dx=east?1:0,dz=east?0:1;
                double left=map.height(x-dx*.01,z-dz*.01),right=map.height(x+dx*.01,z+dz*.01);
                if(Math.abs(left-right)>maximum) {maximum=Math.abs(left-right);worstX=x;worstZ=z;worstEast=east;}
                checked++;
            }
        }
        assertTrue(checked>100000);
        double dx=worstEast?1:0,dz=worstEast?0:1;
        double half=Math.abs(map.height(worstX-dx*.005,worstZ-dz*.005)-map.height(worstX+dx*.005,worstZ+dz*.005));
        System.out.println("BASIS_EDGE max="+maximum+" half="+half+" at="+worstX+","+worstZ);
        assertTrue(maximum<.15,"格子・区分の境界で過大な段差を作らない: "+maximum);
        assertEquals(maximum,half*2,1e-4,"間隔を半分にすると高低差も半分になる連続した斜面");
    }

    @Test void smallReliefBreaksSmoothSlopesAndLongTerraces() throws Exception {
        var basis=FixedMapData.require(version()).basis();
        int[][] points={{-9635,-11309},{-8334,-6111},{-6520,-4000},{-9947,-2675},{-4600,-8112}};
        for(int i=0;i<points.length;i++) {
            int size=257;double[][] old=new double[size][size],actual=new double[size][size];
            for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
                int px=points[i][0]+x-size/2,pz=points[i][1]+z-size/2;
                old[z][x]=basis.baseHeight(px,pz);actual[z][x]=basis.height(px,pz);
            }
            double before=curvatureSpread(old),after=curvatureSpread(actual);
            int oldRun=longestStep(old),newRun=longestStep(actual);
            System.out.println("BASIS_FINE point="+i+" before_curvature="+before+" after_curvature="+after+" old_step="+oldRun+" new_step="+newRun);
            assertTrue(after>before*2,"16B補間で失われていた小起伏を実列へ戻す: "+i);
            assertTrue(after>(i<2?.10:i<4?.025:.008),"地形別の小尺度曲率: "+i);
            if(i==2 || i==3) {
                assertTrue(newRun<oldRun,"緩斜面で長い同高の段を短くする: "+i);
                assertTrue(newRun<=30,"緩斜面の同高の段を数十Bで分断する: "+newRun);
            }
            if(i==2 || i==3) {
                double squares=0;int count=0;
                for(int z=4;z<size-4;z++)for(int x=4;x<size-4;x++) {
                    double dx=old[z][x+4]-old[z][x-4],dz=old[z+4][x]-old[z-4][x],norm=Math.hypot(dx,dz);
                    if(norm<.1)continue;
                    double ax=actual[z][x+4]-actual[z][x-4]-dx,az=actual[z+4][x]-actual[z-4][x]-dz;
                    double cross=(ax*dz-az*dx)/(norm*8);
                    squares+=cross*cross;count++;
                }
                double transverse=Math.sqrt(squares/count);
                System.out.println("BASIS_CROSS point="+i+" rms="+transverse);
                assertTrue(transverse>.10,"等高段に沿うだけでなく横切る起伏を保つ: "+i);
            }
            int needles=0;double maximum=0;
            for(int z=1;z<size-1;z++)for(int x=1;x<size-1;x++) {
                double h=actual[z][x];
                double high=Math.max(Math.max(actual[z-1][x],actual[z+1][x]),Math.max(actual[z][x-1],actual[z][x+1]));
                double low=Math.min(Math.min(actual[z-1][x],actual[z+1][x]),Math.min(actual[z][x-1],actual[z][x+1]));
                if(h-high>=2 || low-h>=2)needles++;
                maximum=Math.max(maximum,Math.abs(h-actual[z][x+1]));
            }
            System.out.println("BASIS_FINE_SAFETY point="+i+" needles="+needles+" max_neighbor="+maximum);
            assertEquals(0,needles,"独立した細い柱・穴にしない");
        }
    }

    @Test void ridgeDetailsDoNotRepeatParallelCombs() throws Exception {
        var basis=FixedMapData.require(version()).basis();double maximum=0;
        for(int z0=-11816;z0<-11432;z0+=64)for(int x0=-10220;x0<-9644;x0+=64) {
            double[][] residual=new double[64][64];
            for(int z=0;z<64;z++)for(int x=0;x<64;x++)
                residual[z][x]=basis.height(x0+x,z0+z)-basis.baseHeight(x0+x,z0+z);
            double xx=0,zz=0,xz=0;
            for(int z=4;z<60;z++)for(int x=4;x<60;x++) {
                double dx=residual[z][x+4]-residual[z][x-4],dz=residual[z+4][x]-residual[z-4][x];
                xx+=dx*dx;zz+=dz*dz;xz+=dx*dz;
            }
            double concentration=Math.hypot(xx-zz,2*xz)/(xx+zz);
            maximum=Math.max(maximum,concentration);
        }
        System.out.println("BASIS_COMB max_orientation_concentration="+maximum);
        assertTrue(maximum<.8,"北斜面の細部を一方向の櫛に集中させない: "+maximum);
    }

    private static double curvatureSpread(double[][] height) {
        double sum=0,squares=0;int count=0;
        for(int z=2;z<height.length-2;z++)for(int x=2;x<height[0].length-2;x++) {
            double value=height[z-2][x]+height[z+2][x]+height[z][x-2]+height[z][x+2]-4*height[z][x];
            sum+=value;squares+=value*value;count++;
        }
        return Math.sqrt(squares/count-Math.pow(sum/count,2));
    }

    private static int longestStep(double[][] height) {
        int maximum=0,size=height.length;
        for(int axis=0;axis<2;axis++)for(int row=0;row<size-1;row++) {
            int run=0,previous=Integer.MIN_VALUE;
            for(int i=0;i<size;i++) {
                int a=(int)Math.round(axis==0?height[row][i]:height[i][row]);
                int b=(int)Math.round(axis==0?height[row+1][i]:height[i][row+1]);
                run=Math.abs(a-b)==1?(a==previous?run+1:1):0;previous=a;maximum=Math.max(maximum,run);
            }
        }
        return maximum;
    }
}
