package io.github.rs0325.ashenfrontier.world;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RevisionNineteenTest {
    @Test
    void mainFlanksHaveBroadProjectionsAndDeepReentrants() throws Exception {
        var map=MountainReliefTest.map();
        for(int side:new int[]{-1,1}) {
            var outline=new ArrayList<Integer>();
            for(int x=-12400;x<=2400;x+=64) {
                int axis=axisZ(x),edge=0;
                for(int d=160;d<=1120;d+=16) {
                    int sum=0;
                    for(int dx:new int[]{-32,0,32})sum+=FixedTerrain.surfaceY(map,x+dx,axis+side*d);
                    if(sum/3>=180)edge=d;
                }
                outline.add(edge);
            }
            int min=outline.stream().mapToInt(Integer::intValue).min().orElseThrow();
            int max=outline.stream().mapToInt(Integer::intValue).max().orElseThrow();
            int projections=0,last=-100;
            for(int i=5;i<outline.size()-5;i++) {
                int edge=outline.get(i);
                int left=outline.subList(i-5,i).stream().mapToInt(Integer::intValue).min().orElseThrow();
                int right=outline.subList(i+1,i+6).stream().mapToInt(Integer::intValue).min().orElseThrow();
                if(edge-Math.max(left,right)>=128 && i-last>=5){projections++;last=i;}
            }
            System.out.println("MAP93_OUTLINE side="+side+" min="+min+" max="+max+" projections="+projections);
            assertTrue(max-min>=400,"細部のノイズより大きい外形の出入り: "+(max-min));
            assertTrue(projections>=8,"両側に複数の支稜と深く入る谷: "+projections);
        }
    }

    @Test
    void northAndSouthFoothillsReturnToTheirDesignedDistribution() throws Exception {
        var map=MountainReliefTest.map();
        for(int side:new int[]{-1,1}) {
            var heights=new ArrayList<Integer>();int designed=0;
            for(int x=-12400;x<=2400;x+=64)for(int d=900;d<=1300;d+=100) {
                int z=axisZ(x)+side*d;
                if(map.coastDistance(x,z)<200 || map.detailWeight(x,z)<.99
                        || (x>-5100 && x<-3400) || (x>-4500 && side<0))continue;
                int y=FixedTerrain.surfaceY(map,x,z);
                heights.add(y);if(y>=90 && y<=130)designed++;
            }
            heights.sort(Integer::compareTo);
            int median=heights.get(heights.size()/2),p90=heights.get(heights.size()*9/10);
            System.out.println("MAP93_FOOTHILLS side="+side+" n="+heights.size()+" median="+median+" p90="+p90+" designed="+designed);
            assertTrue(heights.size()>400,"南北それぞれの山すそを広く標本化する");
            assertTrue(median>=90 && median<=130 && designed>=heights.size()*.70,"山すその主分布をY90〜130に戻す");
            assertTrue(p90<=160,"山脈と平行なY150〜250の帯を残さない: "+p90);
        }
    }

    @Test
    void lowFoothillContoursDoNotFormLongStraightCuts() throws Exception {
        var map=MountainReliefTest.map();
        for(int mountain=0;mountain<2;mountain++)for(int side:new int[]{-1,1}) {
            var outline=new ArrayList<double[]>();
            double maximumToeSlope=0;int toeSamples=0;
            double length=Math.hypot(1432,2972),dx=1432/length,dz=-2972/length;
            int start=mountain==0?-12400:1000,end=mountain==0?2400:3000;
            for(int t=start;t<=end;t+=32) {
                if(mountain==0 && t>-5100 && t<-2400){outline.add(null);continue;}
                double x=mountain==0?t:-3800+dx*t,z=mountain==0?axisZ(t):-12020+dz*t;
                double nx=mountain==0?0:-dz,nz=mountain==0?1:dx;
                int radius=mountain==0?1100:700,level=mountain==0?120:116;
                double[] point=null;
                for(int d=radius+160;d>=0;d-=4) {
                    double px=x+side*nx*d,pz=z+side*nz*d;
                    if(map.height(px,pz)<level)continue;
                    if(d<radius+160)point=new double[]{px,pz};
                    break;
                }
                if(point!=null && mountain==0 && side==1 && t>=-10304 && t<=-9504) {
                    double slope=Math.abs(map.height(point[0],point[1]+16)-map.height(point[0],point[1]-16))/32;
                    maximumToeSlope=Math.max(maximumToeSlope,slope);toeSamples++;
                }
                outline.add(point);
            }
            if(mountain==0 && side==1) {
                System.out.println("MAP93_TOE_SLOPE samples="+toeSamples+" maximum="+maximumToeSlope);
                assertTrue(toeSamples>=24 && maximumToeSlope<=.70,"撮影範囲の低い裾に急な落差を作らない: "+maximumToeSlope);
            }
            int checked=0;double minimum=Double.POSITIVE_INFINITY;
            for(int i=0;i+16<outline.size();i++) {
                boolean complete=true;
                for(int k=i;k<=i+16;k++)complete&=outline.get(k)!=null;
                if(!complete)continue;
                var a=outline.get(i);var b=outline.get(i+16);
                double vx=b[0]-a[0],vz=b[1]-a[1],chord=Math.hypot(vx,vz);
                if(chord<384)continue;
                double deviation=0;
                for(int k=i+1;k<i+16;k++) {
                    var q=outline.get(k);
                    deviation=Math.max(deviation,Math.abs(vx*(q[1]-a[1])-vz*(q[0]-a[0]))/chord);
                }
                minimum=Math.min(minimum,deviation);checked++;
            }
            System.out.println("MAP93_LOW_CONTOUR mountain="+mountain+" side="+side+" windows="+checked+" minimum_deviation="+minimum);
            assertTrue(checked>(mountain==0?300:20),"主脈・分岐の両側を連続した区間で検査する");
            assertTrue(minimum>12,"低い山すその512ブロック区間を斜めの直線でも切らない: "+minimum);
        }
    }

    @Test
    void rockReliefFadesIntoTheFoothillsWithoutAHeightSeam() throws Exception {
        var map=MountainReliefTest.map();
        for(int[] area:new int[][]{{-10640,-11520,-10320,-11200},{-9344,-11160,-9152,-10800}}) {
            int checked=0,badX=0,badZ=0;double maximum=0;
            for(int z=area[1];z<=area[3];z+=4)for(int x=area[0];x<=area[2];x+=4) {
                if(Math.abs(map.height(x,z)-130)>12)continue;
                double near=FixedTerrain.landHeight(map,x+1,z)-FixedTerrain.landHeight(map,x-1,z);
                double wide=(FixedTerrain.landHeight(map,x+8,z)-FixedTerrain.landHeight(map,x-8,z))/8;
                double nearZ=FixedTerrain.landHeight(map,x,z+1)-FixedTerrain.landHeight(map,x,z-1);
                double wideZ=(FixedTerrain.landHeight(map,x,z+8)-FixedTerrain.landHeight(map,x,z-8))/8;
                double fold=Math.max(Math.abs(near-wide),Math.abs(nearZ-wideZ));checked++;
                if(fold>maximum){maximum=fold;badX=x;badZ=z;}
            }
            System.out.println("MAP93_RELIEF_SEAM checked="+checked+" maximum="+maximum+" at="+badX+","+badZ);
            assertTrue(checked>20,"継ぎ目周辺の低い斜面を検査する");
            assertTrue(maximum<1.5,"山地起伏の切替えで標高を跳ばさない: "+maximum+" at "+badX+","+badZ);
        }
    }

    private static int axisZ(int x) {
        int[][] points={{-14000,-11776},{-10000,-11900},{-6000,-11800},{-2000,-12200},{3000,-11776}};
        for(int i=1;i<points.length;i++)if(x<=points[i][0]) {
            int[] a=points[i-1],b=points[i];
            return (int)Math.round(a[1]+(b[1]-a[1])*(x-a[0])/(double)(b[0]-a[0]));
        }
        throw new IllegalArgumentException("主脈の範囲外");
    }
}
