package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonArray;

/** r20の細地形の岸合成・静的な雪氷。水量や気候tickは更新しない。 */
final class TerrainDetails {
    record Cover(int ice,int snow,int layers,boolean powder) {}
    private final long seed;
    private final int headX,headZ,headY;
    private final double[][] snowline,glacier,axis,moraines,outwash;
    private final double windward,leeward,north,south;
    private final int scale;
    private final int canyonX,canyonZ,canyonWidth,canyonDepth;
    private final float[] canyon;

    TerrainDetails(MapVersion version,long seed) {
        this.seed=seed;
        var head=BundledMaps.hydrology(version).reaches().get("R05U").profile().getFirst();
        headX=head.x();headZ=head.z();headY=head.waterY();
        var doc=BundledMaps.landform(version).document();
        snowline=rows(doc.getAsJsonArray("snowline_latitude_y"));
        glacier=rows(doc.getAsJsonObject("glacier").getAsJsonArray("points"));
        moraines=rows(doc.getAsJsonObject("glacier").getAsJsonArray("moraine"));
        outwash=rows(doc.getAsJsonObject("glacier").getAsJsonArray("outwash"));
        var unit=BundledMaps.landform(version).units().get(1);
        axis=new double[unit.controls().size()][2];
        for(int i=0;i<axis.length;i++){axis[i][0]=unit.controls().get(i).x();axis[i][1]=unit.controls().get(i).z();}
        var offsets=doc.getAsJsonObject("snowline_offsets_y");
        windward=offsets.get("windward").getAsDouble();leeward=offsets.get("leeward").getAsDouble();
        north=offsets.get("north_facing").getAsDouble();south=offsets.get("south_facing").getAsDouble();
        scale=doc.get("snow_continuity_scale_blocks").getAsInt();
        var boundary=BundledMaps.landform(version).units().get(37).boundary();
        canyonX=Math.floorDiv(boundary.stream().mapToInt(p -> p.x()).min().orElseThrow()-96,16)*16;
        canyonZ=Math.floorDiv(boundary.stream().mapToInt(p -> p.z()).min().orElseThrow()-96,16)*16;
        canyonWidth=(boundary.stream().mapToInt(p -> p.x()).max().orElseThrow()+96-canyonX)/16+2;
        canyonDepth=(boundary.stream().mapToInt(p -> p.z()).max().orElseThrow()+96-canyonZ)/16+2;
        canyon=new float[canyonWidth*canyonDepth];
        for(int z=0;z<canyonDepth;z++)for(int x=0;x<canyonWidth;x++) {
            double px=canyonX+x*16,pz=canyonZ+z*16,nearest=Double.POSITIVE_INFINITY;boolean inside=false;
            for(int i=0;i<boundary.size();i++) {
                var a=boundary.get(i);var b=boundary.get((i+1)%boundary.size());
                double dx=b.x()-a.x(),dz=b.z()-a.z();
                double t=Math.clamp(((px-a.x())*dx+(pz-a.z())*dz)/(dx*dx+dz*dz),0,1);
                nearest=Math.min(nearest,Math.hypot(px-a.x()-t*dx,pz-a.z()-t*dz));
                if((a.z()>pz)!=(b.z()>pz) && px<(b.x()-a.x())*(pz-a.z())/(b.z()-a.z())+a.x())inside=!inside;
            }
            canyon[z*canyonWidth+x]=(float)TerrainMath.smooth(((inside?nearest:-nearest)+64)/128);
        }
    }

    private static double[][] rows(JsonArray input) {
        var result=new double[input.size()][];
        for(int i=0;i<result.length;i++){
            var row=input.get(i).getAsJsonArray();result[i]=new double[row.size()];
            for(int j=0;j<row.size();j++)result[i][j]=row.get(j).getAsDouble();
        }
        return result;
    }

    double line(int x,int z,double sx,double sz) {
        double latitude=Math.abs(MapCoordinates.latitude(z)),height=550;
        if(latitude>=snowline[0][0])height=snowline[0][1];
        for(int i=0;i<snowline.length-1;i++) {
            var a=snowline[i];var b=snowline[i+1];
            if(latitude<=a[0] && latitude>=b[0])height=b[1]+(a[1]-b[1])*(latitude-b[0])/(a[0]-b[0]);
        }
        double ridge=axis[z<axis[0][1]?0:axis.length-1][0];
        for(int i=0;i<axis.length-1;i++)if(z>=axis[i][1] && z<=axis[i+1][1]) {
            var a=axis[i];var b=axis[i+1];ridge=a[0]+(b[0]-a[0])*(z-a[1])/(b[1]-a[1]);break;
        }
        double dry=TerrainMath.smooth((x-ridge+160)/320);
        height+=windward+(leeward-windward)*dry;
        double aspect=sz/Math.max(.15,Math.hypot(sx,sz));
        height+=aspect>0?north*aspect:-south*aspect;
        height+=(TerrainMath.noise(seed,"d3_snowline",.8*x+.6*z,-.6*x+.8*z,scale)-.5)*24;
        return height;
    }

    int ice(int x,int z) {
        if(x< -10520 || x> -9940 || z< -13340 || z> -12610)return 0;
        double best=Double.POSITIVE_INFINITY,width=0,depth=0,across=0,past=0,px=0,pz=0;
        for(int i=0;i<glacier.length-1;i++) {
            var a=glacier[i];var b=glacier[i+1];
            double dx=b[0]-a[0],dz=b[1]-a[1],length=Math.hypot(dx,dz);
            double raw=((x-a[0])*dx+(z-a[1])*dz)/(length*length),t=Math.clamp(raw,0,1);
            double ax=a[0]+t*dx,az=a[1]+t*dz,distance=Math.hypot(x-ax,z-az);
            if(distance<best) {
                best=distance;width=(a[3]+t*(b[3]-a[3]))*.5;depth=a[4]+t*(b[4]-a[4]);
                across=(-(x-ax)*dz+(z-az)*dx)/length;
                past=raw<0?-raw*length:raw>1?(raw-1)*length:0;px=ax;pz=az;
            }
        }
        across+=(TerrainMath.noise(seed,"d3_ice_bank",x,z,32)-TerrainMath.noise(seed,"d3_ice_bank",px,pz,32))*16;
        double mask=(1-TerrainMath.smooth((Math.abs(across)-width*.62)/(width*.38)))
                *(1-TerrainMath.smooth(past/34));
        return mask<=.12?0:Math.clamp((int)Math.floor(depth*mask+.001),1,8);
    }

    double glacierWeight(int x,int z) {
        if(x< -10640 || x> -9880 || z< -13460 || z> -12480)return 0;
        double edge=glacierEdge(x,z)-12;
        double bend=(TerrainMath.noise(seed,"d3_glacier_flank",x,z,79)-.5)*34
                +(TerrainMath.noise(seed,"d3_glacier_spur",x,z,31)-.5)*16;
        return 1-TerrainMath.smooth((edge+bend*TerrainMath.smooth(edge/24))/128);
    }

    private double glacierEdge(int x,int z) {
        double best=Double.POSITIVE_INFINITY,width=0;
        for(int i=1;i<glacier.length;i++) {
            var a=glacier[i-1];var b=glacier[i];
            double dx=b[0]-a[0],dz=b[1]-a[1];
            double t=Math.clamp(((x-a[0])*dx+(z-a[1])*dz)/(dx*dx+dz*dz),0,1);
            double distance=Math.hypot(x-a[0]-t*dx,z-a[1]-t*dz);
            if(distance<best){best=distance;width=(a[3]+t*(b[3]-a[3]))*.5;}
        }
        return best-width;
    }

    double headHeight(int x,int z,double current,HydrologyTerrain.Bank bank) {
        double dx=x-headX,dz=z-headZ,along=.4*dx-.916*dz,across=.916*dx+.4*dz;
        if(along< -8 || along>220 || Math.abs(across)>150)return current;
        double protect=TerrainMath.smooth((bank.distance()-bank.width()-12)/24)
                *TerrainMath.smooth((glacierEdge(x,z)-14)/20);
        if(protect==0)return current;
        across-=(TerrainMath.noise(seed,"d3_snow_gully",along,0,73)
                -TerrainMath.noise(seed,"d3_snow_gully",0,0,73))*18*TerrainMath.smooth(along/48);
        double width=12+14*TerrainMath.smooth(along/150);
        double distance=Math.abs(across);
        double branch=Math.abs(across-Math.max(0,along-45)*.7);
        double grade=headY+.45*Math.max(0,along);
        double target=grade+.1*distance+.018*distance*distance;
        double fork=grade+7+.15*branch+.025*branch*branch;
        double branchWeight=1-TerrainMath.smooth((along-115)/45);
        target=Math.min(target,current+(Math.min(current,fork)-current)*branchWeight);
        double weight=TerrainMath.smooth((along+8)/24)*(1-TerrainMath.smooth((along-140)/80));
        weight*=1-TerrainMath.smooth((Math.min(distance,branch)-width)/64);
        return current+(Math.min(current,target)-current)*weight*protect;
    }

    double canyonWeight(int x,int z) {
        double px=(x-canyonX)/16.,pz=(z-canyonZ)/16.;
        int ix=(int)Math.floor(px),iz=(int)Math.floor(pz);
        if(ix<0 || iz<0 || ix>=canyonWidth-1 || iz>=canyonDepth-1)return 0;
        int at=iz*canyonWidth+ix;
        if(canyon[at]==canyon[at+1] && canyon[at]==canyon[at+canyonWidth] && canyon[at]==canyon[at+canyonWidth+1]
                && (canyon[at]==0 || canyon[at]==1))return canyon[at];
        double tx=px-ix;
        return Math.clamp(TerrainMath.cubic(canyonRow(ix,iz-1,tx),canyonRow(ix,iz,tx),
                canyonRow(ix,iz+1,tx),canyonRow(ix,iz+2,tx),pz-iz),0,1);
    }

    private double canyonRow(int x,int z,double t) {
        int row=Math.clamp(z,0,canyonDepth-1)*canyonWidth;
        return TerrainMath.cubic(canyon[row+Math.max(0,x-1)],canyon[row+x],
                canyon[row+Math.min(canyonWidth-1,x+1)],canyon[row+Math.min(canyonWidth-1,x+2)],t);
    }

    double canyonHeight(int x,int z,double natural,double current,HydrologyTerrain.Bank bank) {
        double weight=canyonWeight(x,z);
        if(weight==0)return current;
        double d=Math.max(0,bank.distance()-bank.width());
        double shelf=1.5+1.5*TerrainMath.noise(seed,"d3_canyon_floor",x,z,48);
        double t=Math.max(0,d-shelf);
        double span=88+(TerrainMath.noise(seed,"d3_canyon_side",x,z,73)-.5)*32;
        double rise=.65*t+Math.max(0,natural-bank.grade()-1)*TerrainMath.smooth(t/span);
        double along=.3*x+.954*z,across=.954*x-.3*z;
        double bend=TerrainMath.noise(seed,"d3_canyon_bend",along,across,64);
        along+=(bend-.5)*32;
        double ravine=1-TerrainMath.smooth(Math.abs(2*TerrainMath.noise(seed,"d3_canyon_ravine",along+across*.5,across*.08,43)-1)/.45);
        double cut=1-TerrainMath.smooth(Math.abs(2*TerrainMath.noise(seed,"d3_canyon_gully",along+across*.65,across*.25,23)-1)/.5);
        double rill=1-TerrainMath.smooth(Math.abs(2*TerrainMath.noise(seed,"d3_canyon_rill",along+across*.3,across*.7,9)-1)/.6);
        double erosion=(Math.min(22,rise*.5)*ravine+Math.min(7,rise*.25)*cut)*TerrainMath.smooth((d-6)/14)
                +Math.min(2.5,rise*.2)*rill;
        rise-=erosion*(1-TerrainMath.smooth((d-span*.9)/span));
        double target=Math.min(natural,bank.grade()+1+rise);
        return current+(target-current)*weight;
    }

    boolean moraine(int x,int z) {
        if(x< -10500 || x> -9800 || z< -13400 || z> -13000)return false;
        for(int i=0;i<moraines.length;i++) {
            double dx=x-moraines[i][0],dz=z-moraines[i][1];
            double c=i==1?.53:.768,s=i==1?-.848:-.64;
            double along=dx*c+dz*s,across=-dx*s+dz*c;
            if(Math.abs(along)>110 || Math.abs(across)>45)continue;
            double bend=(TerrainMath.noise(seed,"d3_moraine",x,z,32)-.5)*16;
            double edge=TerrainMath.noise(seed,"d3_till_edge",x,z,48);
            if(Math.abs(along+bend)<55+edge*45 && Math.abs(across+bend)<16+edge*12)return true;
        }
        return false;
    }

    boolean outwash(int x,int z) {
        if(x< -10200 || x> -9920 || z< -13560 || z> -13160)return false;
        var a=glacier[glacier.length-1];
        for(var b:outwash) {
            double dx=b[0]-a[0],dz=b[1]-a[1];
            double t=Math.clamp(((x-a[0])*dx+(z-a[1])*dz)/(dx*dx+dz*dz),0,1);
            double distance=Math.hypot(x-a[0]-t*dx,z-a[1]-t*dz);
            if(distance<24+20*TerrainMath.noise(seed,"d3_outwash_margin",x,z,48)
                    +10*TerrainMath.noise(seed,"d3_outwash_lobe",x,z,20))return true;
            a=b;
        }
        return false;
    }

    Cover cover(FixedMapData map,int x,int z,int ground,boolean wet,double coast) {
        if(wet)return new Cover(0,0,0,false);
        int ice=ice(x,z);
        // 雪線より十分低い平野は勾配・水際の追加評価を行わない。
        if(ice==0 && ground<line(x,z,0,0)-36)return new Cover(0,0,0,false);
        var basis=map.basis();
        double east=basis.baseHeight(x+16,z),west=basis.baseHeight(x-16,z);
        double northHeight=basis.baseHeight(x,z-16),southHeight=basis.baseHeight(x,z+16);
        double sx=(east-west)/32,sz=(southHeight-northHeight)/32,slope=Math.hypot(sx,sz);
        double above=ground-line(x,z,sx,sz);
        int layers=TerrainMath.snowLayers(ground,ground-above,slope);
        var bank=map.hydrology().waterBank(x,z);
        if(bank.distance()<4 && ground<=bank.waterY()+2)layers=0;
        double hollow=(east+west+northHeight+southHeight)*.25-basis.baseHeight(x,z);
        int snow=ice==0 && layers==8 && bank.distance()>=28
                ?(int)Math.floor(TerrainMath.snowPack(above,coast,slope,hollow,-sz)):0;
        boolean powder=snow>=3 && slope<.15 && bank.distance()>=28
                && TerrainMath.noise(seed,"d3_powder",x,z,48)>.78;
        if(ice>0){snow=0;powder=false;}
        return new Cover(ice,snow,layers,powder);
    }
}
