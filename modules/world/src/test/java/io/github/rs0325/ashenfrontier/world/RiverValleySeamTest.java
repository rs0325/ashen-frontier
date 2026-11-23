package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverValleySeamTest {
    @Test
    void valleySlopesDoNotBreakAcrossLongEastWestLines() throws Exception {
        var map=MountainReliefTest.map();
        int x0=-4160,x1=-3840;double largest=0,natural=0,base=0;int at=0;
        for(int z=-11924;z<=-11876;z++) {
            double sum=0,land=0,raster=0;
            for(int x=x0;x<=x1;x++) {
                sum+=FixedTerrain.surfaceY(map,x,z+4)-2*FixedTerrain.surfaceY(map,x,z)+FixedTerrain.surfaceY(map,x,z-4);
                land+=FixedTerrain.landHeight(map,x,z+4)-2*FixedTerrain.landHeight(map,x,z)+FixedTerrain.landHeight(map,x,z-4);
                raster+=map.height(x,z+4)-2*map.height(x,z)+map.height(x,z-4);
            }
            double score=Math.abs(sum)/(4*(x1-x0+1));
            if(score>largest){largest=score;at=z;natural=Math.abs(land)/(4*(x1-x0+1));base=Math.abs(raster)/(4*(x1-x0+1));}
        }
        System.out.println("VALLEY_LINE x="+x0+".."+x1+" z="+at+" actual="+largest+" natural="+natural+" base="+base);
        assertTrue(largest<.85,"横断方向に揃う長い勾配の折れを残さない");
    }

    @Test
    void theOutletTributaryDoesNotCutAStraightSideValley() throws Exception {
        var map=MountainReliefTest.map();var points=new java.util.HashSet<Long>();
        for(int z=-11250;z<=-11080;z++)for(int x=-4210;x<=-3970;x++) {
            var r=map.river(x,z);
            if(r.tributary() && r.station()>700 && r.station()<1100 && r.distance()<r.width())
                points.add(((long)r.x()<<32)^(r.z()&0xffffffffL));
        }
        double sx=0,sz=0,xx=0,zz=0,xz=0,n=points.size();
        for(long point:points){double x=(int)(point>>32),z=(int)point;sx+=x;sz+=z;xx+=x*x;zz+=z*z;xz+=x*z;}
        double vx=xx-sx*sx/n,vz=zz-sz*sz/n,cov=xz-sx*sz/n;
        double minor=(vx+vz-Math.sqrt((vx-vz)*(vx-vz)+4*cov*cov))/2;
        double rms=Math.sqrt(Math.max(0,minor/n));
        System.out.println("OUTLET_TRIBUTARY samples="+points.size()+" curve_rms="+rms);
        assertTrue(points.size()>100 && rms>5,"出口の支沢を細長い直線の壁にしない");
    }

    @Test
    void aWaterStepDoesNotExtendIntoTheDistantBank() throws Exception {
        var map=MountainReliefTest.map();double[][] points=new double[1201][3];
        for(int i=0;i<points.length;i++)points[i]=new double[]{i*4,0,110-i/16.0};
        var river=new RiverCourse(points,map.seed());double curvature=0;
        for(int x=1396;x<=1420;x++) {
            double before=FixedTerrain.riverBankHeight(map,x-1,20,200,river.sample(x-1,20));
            double center=FixedTerrain.riverBankHeight(map,x,20,200,river.sample(x,20));
            double after=FixedTerrain.riverBankHeight(map,x+1,20,200,river.sample(x+1,20));
            curvature=Math.max(curvature,Math.abs(after-2*center+before));
        }
        System.out.println("BANK_WATER_STEP curvature="+curvature);
        assertTrue(curvature<.45,"水位1段を岸の奥の横断方向へ伝播させない");
    }

    @Test
    void tributariesJoinOneWindingHeadAtDifferentPositions() throws Exception {
        var map=MountainReliefTest.map();var head=map.riverSource();
        int rows=0,basins=0;double sx=0,sy=0,xx=0,yy=0,xy=0;
        for(int north=24;north<=140;north+=2) {
            int z=head.z()-north,minimum=1000;
            for(int x=head.x()-110;x<=head.x()+110;x++) {
                int h=FixedTerrain.surfaceY(map,x,z);
                if(FixedTerrain.landHeight(map,x,z)-h>12)minimum=Math.min(minimum,h);
            }
            if(minimum==1000)continue;
            int count=0,n=0;double sum=0;boolean low=false;
            for(int x=head.x()-110;x<=head.x()+110;x++) {
                int h=FixedTerrain.surfaceY(map,x,z);
                if(h<=minimum+1 && FixedTerrain.landHeight(map,x,z)-h>12){sum+=x;n++;}
                boolean next=h<=minimum+10 && FixedTerrain.landHeight(map,x,z)-h>12;
                if(next && !low)count++;low=next;
            }
            if(north<=48)basins=Math.max(basins,count);
            double center=sum/n;
            rows++;sx+=north;sy+=center;xx+=north*north;yy+=center*center;xy+=north*center;
        }
        double vx=xx-sx*sx/rows,vy=yy-sy*sy/rows,cov=xy-sx*sy/rows;
        double rms=Math.sqrt(Math.max(0,(vy-cov*cov/vx)/rows));
        System.out.println("HEAD_GULLIES rows="+rows+" near_source_basins="+basins+" curve_rms="+rms);
        assertTrue(rows>=40 && basins==1,"源流直前に複数の深い溝を一点へ集めない");
        assertTrue(rms>2,"谷頭の主谷を直線のくさびにしない");
    }
}
