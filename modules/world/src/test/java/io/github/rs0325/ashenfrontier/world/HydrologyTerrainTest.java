package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Arrays;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyTerrainTest {
    @Test void unexcavatedCoastsKeepTheD1Floor() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());var map=terrain.map();
        int checked=0,oldChanges=0,maximum=0;int[] bounds={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(int offset:new int[]{0,4})for(int z=-15936+offset;z< -464;z+=8)for(int x=-15392+offset;x<6304;x+=8) {
            if(!map.basis().contains(x,z) || map.coastDistance(x,z)<0 || map.hydrology().water(x,z)!=Integer.MIN_VALUE)continue;
            double natural=map.basis().height(x,z);
            if(natural>=62.5 || map.hydrology().height(x,z,map.basis())<natural)continue;
            int d1=(int)Math.round(Math.clamp(natural,63,520));
            int old=(int)Math.round(Math.clamp(natural,-127,520));
            if(old!=d1) {
                oldChanges++;maximum=Math.max(maximum,d1-old);
                bounds[0]=Math.min(bounds[0],x);bounds[1]=Math.min(bounds[1],z);
                bounds[2]=Math.max(bounds[2],x);bounds[3]=Math.max(bounds[3],z);
            }
            assertEquals(d1,terrain.column(x,z).surfaceY(),"水系外の海岸はD1と同じ: "+x+","+z);
            checked++;
        }
        for(int[] point:new int[][]{{-4088,-14992},{-12741,-8721}}) {
            var column=terrain.column(point[0],point[1]);
            assertEquals(63,column.surfaceY());
            for(int y=-128;y<576;y++)
                assertEquals(y==-128?Blocks.BEDROCK.defaultBlockState():
                        y<=63?Blocks.STONE.defaultBlockState():
                        Blocks.AIR.defaultBlockState(),column.state(y),"D1海岸の実列: "+point[0]+","+y+","+point[1]);
        }
        assertTrue(checked>2500,"沿岸全域の低い自然地表を含む");
        System.out.println("D2_COAST_D1 checked="+checked+" restored="+oldChanges+" maximum="+maximum+" bounds="+Arrays.toString(bounds));
    }

    @Test void coursesKeepTheirLevelsConnectionsAndExcavatedBeds() throws Exception {
        var version=TerrainBasisTest.version();var map=FixedMapData.require(version);var layout=BundledMaps.hydrology(version);
        int checked=0;
        var elevations=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(var reach:layout.reaches().values()) {
            int previous=Integer.MAX_VALUE;
            for(var p:reach.profile()) {
                int water=map.waterSurface(p.x(),p.z());
                assertNotEquals(Integer.MIN_VALUE,water,"河道が欠ける: "+reach.id()+" "+p);
                assertTrue(water<=previous,"逆勾配: "+reach.id()+" "+p+" previous="+previous+" actual="+water);
                previous=water;
                assertEquals(map.coastDistance(p.x(),p.z())<0?1:reach.inventoryId(),map.waterInventoryId(p.x(),p.z()),"流域と海の境界: "+p);
                assertEquals(map.coastDistance(p.x(),p.z())<0?8:4,map.waterQuality(p.x(),p.z()));
                checked++;
                int bed=FixedTerrain.surfaceY(map,p.x(),p.z());
                assertTrue(bed<water,"床が水面以上");
                elevations.add(()->assertTrue(bed<=Math.max(63,map.basis().height(p.x(),p.z()))+.5,"盛土が必要な河床: "+p+" bed="+bed+" natural="+map.basis().height(p.x(),p.z())));
            }
            var start=layout.nodes().get(reach.from());var end=layout.nodes().get(reach.to());
            assertEquals(start.waterY(),map.waterSurface(start.x(),start.z()),start.id());
            assertEquals(end.waterY(),map.waterSurface(end.x(),end.z()),end.id());
        }
        assertTrue(checked>20000);
        assertEquals(143,FixedTerrain.surfaceY(map,-6510,-10300));
        assertEquals(145,map.waterSurface(-7000,-10400));
        assertEquals(120,minimumLakeBed(map));
        System.out.println("HYDROLOGY_COURSE checked="+checked);
        assertAll(elevations);
    }

    private static int minimumLakeBed(FixedMapData map) {
        int minimum=145;
        for(int z=-10750;z<=-9980;z+=3)for(int x=-7450;x<=-6490;x+=3)
            if(map.hydrology().owner(x,z)==9)minimum=Math.min(minimum,map.hydrology().bed(x,z));
        return minimum;
    }

    @Test void eachInitialColumnHasOneOwnerAndOneVolume() throws Exception {
        var version=TerrainBasisTest.version();var map=FixedMapData.require(version);long[] sums=new long[5];int columns=0;
        try(var stream=getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_002/index.json")) {
            var index=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            for(var pair:index.getAsJsonObject("hydrology_tiles").getAsJsonArray("tiles")) {
                int x0=pair.getAsJsonArray().get(0).getAsInt()*128,z0=pair.getAsJsonArray().get(1).getAsInt()*128;
                for(int z=z0;z<z0+128;z++)for(int x=x0;x<x0+128;x++) {
                    if(map.hydrology().owner(x,z)==0)continue;
                    int id=map.waterInventoryId(x,z),water=map.waterSurface(x,z),bed=FixedTerrain.surfaceY(map,x,z);
                    assertTrue(id>=2 && id<=4,"所有不一致 "+x+","+z+" id="+id);assertTrue(water>bed,"水面以下の床 "+x+","+z+" water="+water+" bed="+bed);sums[id]+=water-bed;columns++;
                }
            }
        }
        for(int id=2;id<=4;id++) {assertEquals(sums[id],map.initialWaterVolume(id));assertTrue(sums[id]>0);}
        assertThrows(IllegalArgumentException.class,()->map.initialWaterVolume(1));
        assertEquals(1,map.waterInventoryId(-14000,-15000));
        assertEquals(0,map.waterInventoryId(-4000,-12100));
        System.out.println("HYDROLOGY_VOLUME columns="+columns+" nw05="+sums[2]+" nw06="+sums[3]+" nw16="+sums[4]);
    }

    @Test void sourceHasNarrowFloorAndSameBankPassage() throws Exception {
        var map=FixedMapData.require(TerrainBasisTest.version());var points=BundledMaps.hydrology(TerrainBasisTest.version()).reaches().get("R05U").profile();
        double station=0;int checked=0;
        for(int i=1;i<points.size()-1;i++) {
            var a=points.get(i-1);var p=points.get(i);var b=points.get(i+1);
            station+=Math.hypot(p.x()-a.x(),p.z()-a.z());if(station>256)break;if(i>=61 && i%12!=0)continue;
            double dx=b.x()-a.x(),dz=b.z()-a.z(),length=Math.hypot(dx,dz);int water=map.waterSurface(p.x(),p.z());
            var floor=new HashSet<Long>();var wet=new HashSet<Long>();
            for(int d=-12;d<=12;d++) {
                int x=(int)Math.round(p.x()-dz/length*d),z=(int)Math.round(p.z()+dx/length*d);long key=((long)x<<32)^(z&0xffffffffL);
                if(map.waterInventoryId(x,z)==2) {wet.add(key);assertEquals(1,water-FixedTerrain.surfaceY(map,x,z));}
                if(FixedTerrain.surfaceY(map,x,z)<=water+2)floor.add(key);
                if(d> -12) {
                    int px=(int)Math.round(p.x()-dz/length*(d-1)),pz=(int)Math.round(p.z()+dx/length*(d-1));
                    int h=FixedTerrain.surfaceY(map,x,z),previous=FixedTerrain.surfaceY(map,px,pz);
                    if(map.waterInventoryId(x,z)==0 && map.waterInventoryId(px,pz)==0 && map.basis().height(x,z)-h>2 && map.basis().height(px,pz)-previous>2)
                        assertTrue(Math.abs(h-previous)<=3,"源頭の掘削壁を急な箱形にしない: "+station+" d="+d+" rise="+Math.abs(h-previous));
                }
            }
            // 谷底は水面+1までの床。斜面に上がる最初の段は含めない。
            floor.clear();
            for(int side:new int[]{-1,1})for(int d=0;d<=12;d++) {
                int x=(int)Math.round(p.x()-dz/length*d*side),z=(int)Math.round(p.z()+dx/length*d*side);
                if(FixedTerrain.surfaceY(map,x,z)>water+1)break;
                floor.add(((long)x<<32)^(z&0xffffffffL));
            }
            System.out.println("HYDROLOGY_HEAD station="+station+" wet="+wet.size()+" connected_floor="+floor.size());
            assertTrue(wet.size()>=1 && wet.size()<=2,"水幅1～2: "+wet.size());
            if(floor.size()<3 || floor.size()>5)for(int d=-6;d<=6;d++) {
                int x=(int)Math.round(p.x()-dz/length*d),z=(int)Math.round(p.z()+dx/length*d);
                System.out.println("HEAD_SECTION d="+d+" x="+x+" z="+z+" water="+water+" natural="+map.basis().height(x,z)+" raw="+map.hydrology().height(x,z,map.basis().height(x,z))+" ground="+FixedTerrain.surfaceY(map,x,z));
            }
            assertTrue(floor.size()>=3 && floor.size()<=5,"谷底幅3～5: "+floor.size());
            for(int d:new int[]{2,3}) {
                int x=(int)Math.round(p.x()-dz/length*d),z=(int)Math.round(p.z()+dx/length*d);
                assertEquals(0,map.waterInventoryId(x,z),"同じ側の乾いた岸");
                assertTrue(FixedTerrain.surfaceY(map,x,z)>=water,"水際を塞がない岸");
            }
            checked++;
        }
        assertTrue(checked>=12);
        int longest=0;
        for(int x=-10248;x< -10192;x++) {
            int run=0,previous=-999,next=-999;
            for(int z=-12808;z< -12600;z++) {
                int a=FixedTerrain.surfaceY(map,x,z),b=FixedTerrain.surfaceY(map,x+1,z);
                boolean edge=a>302 && Math.abs(a-b)==1;
                run=edge?(a==previous && b==next?run+1:1):0;
                previous=a;next=b;longest=Math.max(longest,run);
            }
        }
        System.out.println("SOURCE_PARALLEL_STEP maximum="+longest);
        assertTrue(longest<=10,"源頭の棚が流路と平行に長く続く: "+longest);
        int width=38,height=60,rectangular=0;
        int[][] ground=new int[height][width];boolean[][] dry=new boolean[height][width];
        for(int z=0;z<height;z++)for(int x=0;x<width;x++) {
            ground[z][x]=FixedTerrain.surfaceY(map,-10248+x,-12800+z);
            dry[z][x]=map.waterInventoryId(-10248+x,-12800+z)==0;
        }
        for(int axis=0;axis<2;axis++)for(int span=1;span<=2;span++) {
            int rows=axis==0?height:width,columns=axis==0?width:height;
            for(int x=1;x<columns-span;x++) {
                int run=0,previousFloor=-999,previousBank=-999;
                for(int z=0;z<rows;z++) {
                    int floor=axis==0?ground[z][x]:ground[x][z];
                    int bank=axis==0?ground[z][x-1]:ground[x-1][z];
                    boolean valid=bank>floor && bank==(axis==0?ground[z][x+span]:ground[x+span][z]);
                    for(int i=-1;i<=span;i++) {
                        valid&=axis==0?dry[z][x+i]:dry[x+i][z];
                        if(i>=0 && i<span)valid&=floor==(axis==0?ground[z][x+i]:ground[x+i][z]);
                    }
                    run=valid?(floor==previousFloor && bank==previousBank?run+1:1):0;
                    rectangular=Math.max(rectangular,run);previousFloor=floor;previousBank=bank;
                }
            }
        }
        System.out.println("SOURCE_RECTANGULAR_HOLLOW maximum="+rectangular);
        assertTrue(rectangular<=3,"源頭の同じ幅の矩形切れ込み: "+rectangular);

    }

    @Test void sourceBankIsWalkableWithOneInEightLongitudinalGrade() throws Exception {
        var version=TerrainBasisTest.version();var map=FixedMapData.require(version);var points=BundledMaps.hydrology(version).reaches().get("R05U").profile();
        var path=new java.util.ArrayList<double[]>();double station=0;
        for(int i=1;i<points.size()-1;i++) {
            var a=points.get(i-1);var p=points.get(i);var b=points.get(i+1);station+=Math.hypot(p.x()-a.x(),p.z()-a.z());if(station>256)break;
            double dx=b.x()-a.x(),dz=b.z()-a.z(),norm=Math.hypot(dx,dz);
            int x=(int)Math.round(p.x()-dz/norm*2),z=(int)Math.round(p.z()+dx/norm*2);
            path.add(new double[]{x,z,FixedTerrain.surfaceY(map,x,z)});
        }
        double[] distance=new double[path.size()];
        for(int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);distance[i]=distance[i-1]+Math.hypot(b[0]-a[0],b[1]-a[1]);
            assertTrue(Math.abs(a[2]-b[2])<=1,"通行帯の一歩の段差");
        }
        for(int i=0,j=0;i<path.size();i++) {
            while(j+1<path.size() && distance[j]-distance[i]<32)j++;
            if(distance[j]-distance[i]>=32)assertTrue(Math.abs(path.get(j)[2]-path.get(i)[2])<=Math.ceil((distance[j]-distance[i])/8),"32B窓の通行帯勾配1/8");
        }
    }

    @Test void canyonWallsHaveLocalReliefWithoutLongParallelSteps() throws Exception {
        var map=FixedMapData.require(TerrainBasisTest.version());
        for(int[] point:new int[][]{{-8775,-11006},{-12587,-8770}}) {
            int size=257;int[][] h=new int[size][size];boolean[][] bank=new boolean[size][size],dry=new boolean[size][size];
            for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
                int px=point[0]+x-128,pz=point[1]+z-128;h[z][x]=FixedTerrain.surfaceY(map,px,pz);
                dry[z][x]=map.waterInventoryId(px,pz)==0;
                bank[z][x]=dry[z][x] && map.basis().height(px,pz)-h[z][x]>4;
            }
            int maximum=0,steep=0,count=0,wallMaximum=0;double sum=0,squares=0;
            for(int axis=0;axis<2;axis++)for(int row=0;row<size-1;row++) {
                int run=0,wallRun=0,previous=-999;
                for(int j=0;j<size-1;j++) {
                    int x=axis==0?j:row,z=axis==0?row:j,nx=x+(axis==1?1:0),nz=z+(axis==0?1:0);
                    int a=h[z][x],b=h[nz][nx];boolean carved=bank[z][x]&&bank[nz][nx];
                    run=carved&&Math.abs(a-b)==1?(a==previous?run+1:1):0;previous=a;maximum=Math.max(maximum,run);
                    wallRun=carved&&Math.abs(a-b)>=3?wallRun+1:0;wallMaximum=Math.max(wallMaximum,wallRun);
                    if(carved) {double slope=Math.abs(a-b);sum+=slope;squares+=slope*slope;count++;if(slope>=16)steep++;}
                }
            }
            System.out.println("HYDROLOGY_WALL point="+point[0]+","+point[1]+" step_run="+maximum+" steep="+steep+" wall_run="+wallMaximum+" tips="+thinTips(h,bank,dry)+" n="+count);
            assertTrue(count>1000);assertTrue(maximum<=32,"長い平行な段: "+maximum);
            assertTrue(Math.sqrt(squares/count-Math.pow(sum/count,2))>.2,"均一な直壁にしない");
            assertEquals(0,steep,"1B間隔で16B以上切り替わる直壁を作らない");
            assertTrue(wallMaximum<=32,"3B以上立ち上がる壁を長く連ねない: "+wallMaximum);
            assertEquals(0,thinTips(h,bank,dry),"掘削面に1～2列だけの突起を作らない");
        }
    }
    @Test void lakeBankCutoffAndContoursVaryAlongTheShore() throws Exception {
        var version=TerrainBasisTest.version();var map=FixedMapData.require(version);
        var shore=BundledMaps.hydrology(version).lakes().get("L10").outline();
        for(int region=0;region<2;region++) {
            var widths=new java.util.ArrayList<Double>();var gaps=new java.util.ArrayList<Double>();
            var nearGaps=new java.util.ArrayList<Double>();double kink=0;
            for(int i=0;i<shore.size();i+=4) {
                var p=shore.get(i);
                if(region==0?!(p.x()< -7300 && p.z()> -10370):!(p.x()> -7000 && p.x()< -6750 && p.z()< -10500))continue;
                var a=shore.get((i+shore.size()-2)%shore.size());var b=shore.get((i+2)%shore.size());
                double dx=b.x()-a.x(),dz=b.z()-a.z(),norm=Math.hypot(dx,dz);
                double nx=dz/norm,nz=-dx/norm;
                if(map.hydrology().owner((int)Math.round(p.x()+nx*6),(int)Math.round(p.z()+nz*6))==9) {nx=-nx;nz=-nz;}
                double low=0,high=0,nearLow=0,nearHigh=0,width=0;int lastX=0,lastZ=0;
                boolean crossed=false;
                for(int d=3;d<=260;d++) {
                    int x=(int)Math.round(p.x()+nx*d),z=(int)Math.round(p.z()+nz*d);
                    if(map.hydrology().owner(x,z)>0) {crossed=true;break;}
                    double h=map.hydrology().height(x,z,250);
                    if(nearLow==0 && h>=151)nearLow=shoreDistance(shore,x,z);
                    if(nearHigh==0 && h>=159)nearHigh=shoreDistance(shore,x,z);
                    if(low==0 && h>=165)low=shoreDistance(shore,x,z);
                    if(high==0 && h>=195)high=shoreDistance(shore,x,z);
                    if(h<248) {width=d;lastX=x;lastZ=z;}
                }
                if(crossed || low==0 || high==0 || width==0)continue;
                widths.add(shoreDistance(shore,lastX,lastZ));gaps.add(high-low);nearGaps.add(nearHigh-nearLow);
                int px=(int)Math.round(p.x()+nx*64),pz=(int)Math.round(p.z()+nz*64);
                double lo=140,hi=400;
                for(int n=0;n<40;n++) {
                    double middle=(lo+hi)/2;
                    if(map.hydrology().height(px,pz,middle)<middle-1e-9)hi=middle;else lo=middle;
                }
                double n=(lo+hi)/2,epsilon=.001;
                double left=map.hydrology().height(px,pz,n-epsilon),center=map.hydrology().height(px,pz,n),right=map.hydrology().height(px,pz,n+epsilon);
                kink=Math.max(kink,Math.abs(right-2*center+left)/epsilon);
            }
            assertTrue(widths.size()>=12,"湖岸の有効横断数");
            double mean=widths.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double gap=gaps.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double sd=Math.sqrt(widths.stream().mapToDouble(v->(v-mean)*(v-mean)).average().orElseThrow());
            double gapSd=Math.sqrt(gaps.stream().mapToDouble(v->(v-gap)*(v-gap)).average().orElseThrow());
            double nearMean=nearGaps.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double nearCv=Math.sqrt(nearGaps.stream().mapToDouble(v->(v-nearMean)*(v-nearMean)).average().orElseThrow())/nearMean;
            System.out.println("LAKE_NEAR_CONTOURS region="+region+" gap_cv="+nearCv);
            assertTrue(nearCv>.35,"水際に近い曲がった等高線も平行にそろえない: "+nearCv);
            System.out.println("LAKE_BANK region="+region+" count="+widths.size()+" cutoff_sd="+sd+" contour_gap_sd="+gapSd+" join_curvature="+kink);
            assertTrue(sd>8,"岸に沿って一定幅で切り取る斜面: "+sd);

            assertTrue(kink<.02,"元の地表との交線で片側微分を連続にする: "+kink);
            int size=257,x0=region==0?-7616:-7040,z0=region==0?-10496:-10800;
            int[][] h=new int[size][size];boolean[][] dry=new boolean[size][size];
            for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
                h[z][x]=FixedTerrain.surfaceY(map,x0+x,z0+z);dry[z][x]=map.waterInventoryId(x0+x,z0+z)==0;
            }
            boolean[][] carved=new boolean[size][size];
            for(int z=0;z<size;z++)for(int x=0;x<size;x++)carved[z][x]=dry[z][x] && map.basis().height(x0+x,z0+z)-h[z][x]>.5;
            assertEquals(0,thinTips(h,carved,dry),"湖岸に1～2列だけの突起を作らない");
            int isolated=0,maxJump=0,jumpX=0,jumpZ=0;double maxSlope=0;
            for(int z=1;z<size-1;z++)for(int x=1;x<size-1;x++) {
                if(!dry[z][x])continue;
                int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;boolean allDry=true;
                for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
                    if(dx==0 && dz==0)continue;
                    allDry&=dry[z+dz][x+dx];low=Math.min(low,h[z+dz][x+dx]);high=Math.max(high,h[z+dz][x+dx]);
                    if(dry[z+dz][x+dx])maxSlope=Math.max(maxSlope,Math.abs(h[z][x]-h[z+dz][x+dx])/Math.hypot(dx,dz));
                    if(dry[z+dz][x+dx] && Math.abs(h[z][x]-h[z+dz][x+dx])>maxJump) {
                        maxJump=Math.abs(h[z][x]-h[z+dz][x+dx]);jumpX=x0+x;jumpZ=z0+z;
                    }
                }
                if(allDry && (h[z][x]+3<=low || h[z][x]-3>=high))isolated++;
            }
            System.out.println("LAKE_LOCAL region="+region+" isolated="+isolated+" max_jump="+maxJump+" at="+jumpX+","+jumpZ+" slope="+maxSlope);
            assertEquals(0,isolated,"1Bの柱・穴を作らない");
            assertTrue(maxSlope<16,"水平距離1Bあたり16B以上の直壁を作らない");
            double longest=0;
            for(int[] direction:new int[][]{{1,0},{0,1},{1,1},{-1,1}}) {
                int dx=direction[0],dz=direction[1],nx=dz==0?0:1,nz=dz==0?1:0;
                int[][] runs=new int[size][size];
                for(int z=2;z<size-2;z++)for(int i=2;i<size-2;i++) {
                    int x=dx<0?size-1-i:i;
                    if(!dry[z][x] || !dry[z+nz][x+nx] || h[z][x]<=150 || h[z][x]>=245
                            || Math.abs(h[z+nz][x+nx]-h[z][x])!=1)continue;
                    int px=x-dx,pz=z-dz;
                    boolean same=h[z][x]==h[pz][px] && h[z+nz][x+nx]==h[pz+nz][px+nx];
                    runs[z][x]=same?runs[pz][px]+1:1;longest=Math.max(longest,runs[z][x]*Math.hypot(dx,dz));
                }
            }
            System.out.println("LAKE_STEPS region="+region+" longest="+longest);
            assertTrue(longest<=32,"湖岸斜面の長い平行な段: "+longest);
        }
    }

    @Test void dryBanksDoNotLeaveThinBladesOnSlopes() throws Exception {
        var map=FixedMapData.require(TerrainBasisTest.version());
        double curvature=0;
        for(int z=-10275;z< -10255;z++)for(int x=-6179;x< -6171;x++) {
            double left=map.hydrology().height(x-1,z,map.basis().height(x-1,z));
            double center=map.hydrology().height(x,z,map.basis().height(x,z));
            double right=map.hydrology().height(x+1,z,map.basis().height(x+1,z));
            curvature=Math.max(curvature,Math.abs(left-2*center+right));
        }
        System.out.println("JOIN_SEGMENT_CURVATURE maximum="+curvature);
        assertTrue(curvature<.75,"最近区間の切替えで掘削面を跳ねさせない: "+curvature);
        for(int[] center:new int[][]{{-6200,-10300},{-5280,-13920}}) {
            int size=259,x0=center[0]-129,z0=center[1]-129,count=0;
            int[][] h=new int[size][size];boolean[][] dry=new boolean[size][size];
            for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
                h[z][x]=FixedTerrain.surfaceY(map,x0+x,z0+z);dry[z][x]=map.waterInventoryId(x0+x,z0+z)==0;
            }
            for(int z=2;z<size-2;z++)for(int x=2;x<size-2;x++) {
                if(!dry[z][x] || map.basis().height(x0+x,z0+z)-h[z][x]<.5)continue;
                for(int[] d:new int[][]{{1,0},{0,1},{1,1},{1,-1}}) {
                    int dx=d[0],dz=d[1];
                    boolean blade=true;
                    int length=dx!=0 && dz!=0?1:0;
                    for(int k=-length;k<=length;k++) {
                        int px=x-dz*k,pz=z+dx*k;
                        blade&=dry[pz+dz][px+dx] && dry[pz-dz][px-dx]
                                && h[pz][px]-Math.max(h[pz+dz][px+dx],h[pz-dz][px-dx])>=2;
                    }
                    if(blade) {
                        count++;System.out.println("THIN_BANK_COLUMN "+(x0+x)+","+(z0+z)+" axis="+dx+","+dz+" ground="+h[z][x]);
                        for(int zz=-1;zz<=1;zz++)for(int xx=-1;xx<=1;xx++) {
                            int px=x0+x+xx,pz=z0+z+zz;
                            System.out.println("THIN_BANK_NEIGHBOR "+px+","+pz+" natural="+map.basis().height(px,pz)+" raw="+map.hydrology().height(px,pz,map.basis().height(px,pz))+" final="+h[z+zz][x+xx]);
                        }
                    }
                }
            }
            System.out.println("THIN_BANK_BLADES point="+center[0]+","+center[1]+" count="+count);
            assertEquals(0,count,"斜面の上でも1B幅の板・とげを残さない");
        }
    }

    @Test void transitionBandsDoNotCutNewCliffsAtLakeAndRiverMargins() throws Exception {
        var map=FixedMapData.require(TerrainBasisTest.version());
        var tiles=new java.util.TreeSet<Long>();var wetTiles=new java.util.ArrayList<int[]>();
        try(var stream=getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_002/index.json")) {
            var index=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            for(var pair:index.getAsJsonObject("hydrology_tiles").getAsJsonArray("tiles")) {
                int tx=pair.getAsJsonArray().get(0).getAsInt(),tz=pair.getAsJsonArray().get(1).getAsInt();
                wetTiles.add(new int[]{tx,tz});
                for(int dz=-3;dz<=3;dz++)for(int dx=-3;dx<=3;dx++)
                    tiles.add(((long)(tx+dx)<<32)^((tz+dz)&0xffffffffL));
            }
        }
        int shoreMaximum=0,shoreCount=0,transitionCount=0,finalMaximum=0;double excess=0;String excessPoint="";
        var steep=new java.util.ArrayList<int[]>();
        for(var tile:wetTiles)for(int z=tile[1]*128;z<(tile[1]+1)*128;z++)for(int x=tile[0]*128;x<(tile[0]+1)*128;x++) {
            if(map.hydrology().owner(x,z)==0)continue;
            int level=map.waterSurface(x,z);
            for(int[] d:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
                int nx=x+d[0],nz=z+d[1];
                if(map.hydrology().owner(nx,nz)>0 || !map.basis().contains(nx,nz) || map.coastDistance(nx,nz)<0)continue;
                int rise=FixedTerrain.surfaceY(map,nx,nz)-level;
                shoreMaximum=Math.max(shoreMaximum,rise);shoreCount++;
            }
        }
        for(long tile:tiles) {
            int x0=(int)(tile>>32)*128,z0=(int)tile*128;
            for(int z=z0;z<z0+128;z++)for(int x=x0;x<x0+128;x++) {
                if((x&7)!=0 && (z&7)!=0)continue;
                if(!map.basis().contains(x,z) || map.coastDistance(x,z)<0 || map.hydrology().owner(x,z)>0)continue;
                double natural=map.basis().height(x,z),height=map.hydrology().height(x,z,natural);
                for(int[] d:new int[][]{{1,0},{0,1}}) {
                    int nx=x+d[0],nz=z+d[1];
                    if(map.hydrology().owner(nx,nz)>0)continue;
                    double nextNatural=map.basis().height(nx,nz),next=map.hydrology().height(nx,nz,nextNatural);
                    if(natural-height<.5 && nextNatural-next<.5)continue;
                    double added=Math.abs(height-next)-Math.abs(natural-nextNatural);
                    if(added>=4) {
                        int jump=Math.abs(FixedTerrain.surfaceY(map,x,z)-FixedTerrain.surfaceY(map,nx,nz));
                        int inherited=(int)Math.abs(Math.round(natural)-Math.round(nextNatural));
                        finalMaximum=Math.max(finalMaximum,jump-inherited);
                        if(jump-inherited>=8)steep.add(new int[]{x,z,d[0],d[1]});
                    }
                    if(added>excess) {excess=added;excessPoint=x+","+z+" -> "+nx+","+nz+" raw="+height+","+next+" natural="+natural+","+nextNatural;}
                    transitionCount++;
                }
            }
        }
        double longest=0;String longestPoint="";
        for(var point:steep) {
            int x=point[0],z=point[1],dx=point[2],dz=point[3];
            int sign=Integer.signum(FixedTerrain.surfaceY(map,x,z)-FixedTerrain.surfaceY(map,x+dx,z+dz));
            for(int[] tangent:new int[][]{{1,0},{0,1},{1,1},{1,-1}}) {
                int count=1;
                for(int direction:new int[]{-1,1})for(int step=1;step<=16;step++) {
                    int px=x+tangent[0]*step*direction,pz=z+tangent[1]*step*direction,nx=px+dx,nz=pz+dz;
                    if(!map.basis().contains(px,pz) || !map.basis().contains(nx,nz)
                            || map.coastDistance(px,pz)<0 || map.coastDistance(nx,nz)<0
                            || map.hydrology().owner(px,pz)>0 || map.hydrology().owner(nx,nz)>0)break;
                    int jump=FixedTerrain.surfaceY(map,px,pz)-FixedTerrain.surfaceY(map,nx,nz);
                    int inherited=(int)Math.abs(Math.round(map.basis().height(px,pz))-Math.round(map.basis().height(nx,nz)));
                    if(jump*sign<=0 || Math.abs(jump)-inherited<8)break;
                    count++;
                }
                double run=count*Math.hypot(tangent[0],tangent[1]);
                if(run>longest){longest=run;longestPoint=x+","+z+" direction="+dx+","+dz+" tangent="+tangent[0]+","+tangent[1];}
            }
        }
        System.out.println("HYDROLOGY_TRANSITIONS tiles="+tiles.size()+" shore_pairs="+shoreCount+" shore_max="+shoreMaximum+" dry_pairs="+transitionCount+" excess="+excess+" final_max="+finalMaximum+" cliff_run="+longest+" cliff_at="+longestPoint+" at="+excessPoint);
        assertTrue(shoreCount>10000 && transitionCount>10000);
        assertTrue(shoreMaximum<=8,"水際で斜面が切断される: "+shoreMaximum);
        assertTrue(longest<=16,"D1にない8B以上の急な面を16B超連ねる移行帯: "+longest);
    }

    private static int thinTips(int[][] h,boolean[][] bank,boolean[][] dry) {
        int size=h.length,count=0;boolean[][] seen=new boolean[size][size];
        for(int z=1;z<size-1;z++)for(int x=1;x<size-1;x++) {
            if(seen[z][x])continue;
            int level=h[z][x];var cells=new java.util.ArrayList<Integer>();cells.add(z*size+x);seen[z][x]=true;
            boolean isolated=true,carved=false;
            for(int i=0;i<cells.size();i++) {
                int a=cells.get(i),px=a%size,pz=a/size;carved|=bank[pz][px];
                for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
                    int nx=px+dx,nz=pz+dz;
                    if(nx<0 || nz<0 || nx>=size || nz>=size) {isolated=false;continue;}
                    if(!dry[nz][nx] || h[nz][nx]>level)isolated=false;
                    if(h[nz][nx]==level && !seen[nz][nx]) {seen[nz][nx]=true;cells.add(nz*size+nx);}
                }
            }
            if(isolated && carved && cells.size()<=2) {count++;System.out.println("THIN_TIP level="+level+" cells="+cells);}
        }
        return count;
    }

    @Test void sourceShortStepsDoNotFormParallelStacks() throws Exception {
        var map=FixedMapData.require(TerrainBasisTest.version());
        int[][] h=new int[32][14];
        for(int z=0;z<32;z++)for(int x=0;x<14;x++)h[z][x]=FixedTerrain.surfaceY(map,-10218+x,-12792+z);
        int[] counts=new int[30];int maximum=0,crowded=0;
        for(int z=1;z<31;z++) {
            int[] edges=new int[13];int count=0;
            for(int x=0;x<13;x++) {
                if(h[z][x]==h[z][x+1])continue;
                boolean stable=true;
                for(int dx=0;dx<=1;dx++)stable&=h[z-1][x+dx]==h[z][x+dx] && h[z+1][x+dx]==h[z][x+dx];
                if(stable)edges[count++]=x;
            }
            maximum=Math.max(maximum,count);counts[z-1]=count;
            if(count>=4) {
                int[] gaps=new int[count-1];
                for(int i=0;i<gaps.length;i++)gaps[i]=edges[i+1]-edges[i];
                Arrays.sort(gaps);
                double median=(gaps[(gaps.length-1)/2]+gaps[gaps.length/2])*.5;
                if(median<=2)crowded++;
            }
        }
        Arrays.sort(counts);double q90=counts[26]+(counts[27]-counts[26])*.1;
        System.out.println("SOURCE_STEP_STACK maximum="+maximum+" q90="+q90+" crowded_rows="+crowded);
        assertTrue(maximum<=5 && crowded<=3,"短い段を水路と平行に重ねない: "+maximum+" / "+crowded);
    }

    @Test void adjacentChunksKeepColumnsWhenTheCacheAdvances() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());int checked=0;
        int[][] orders={{0,1,2,5,4,3,6,7,8},{8,7,6,3,4,5,2,1,0},{0,3,6,7,4,1,2,5,8},{4,0,8,2,6,1,7,3,5}};
        for(int[] origin:new int[][]{{-640,-800},{-388,-643},{-412,-648},{-331,-871}}) {
            long[] expected=new long[2304];
            for(int pass=0;pass<orders.length;pass++) {
                terrain.column(-8192,-8192);
                for(int chunk:orders[pass]) {
                    for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                        int x=(origin[0]+chunk%3)*16+dx,z=(origin[1]+chunk/3)*16+dz,at=chunk*256+dz*16+dx;
                        var column=terrain.column(x,z);
                        long value=((long)column.surfaceY()<<32)^(column.topY()&0xffffffffL);
                        if(pass==0)expected[at]=value;
                        else {assertEquals(expected[at],value,"隣接チャンクの生成順: "+x+","+z);checked++;}
                    }
                }
            }
        }
        System.out.println("D2_CHUNK_CACHE checked="+checked);
    }

    @Test void ridgeColumnsDoNotContainAirBelowTheirSurface() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());int checked=0;
        for(int[] point:new int[][]{{-6338,-10420},{-6328,-10410},{-6327,-10408},
                {-6575,-10540},{-6581,-10550},{-6589,-10554}}) {
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
                int x=point[0]+dx,z=point[1]+dz;
                var column=terrain.column(x,z);
                for(int y=-127;y<=column.surfaceY();y++)assertFalse(column.state(y).isAir(),"稜線の内部の空気: "+x+","+y+","+z);
                checked++;
            }
        }
        System.out.println("RIDGE_SOLID_COLUMNS checked="+checked);
    }

    private static double shoreDistance(java.util.List<HydrologyLayout.Point> shore,int x,int z) {
        double best=Double.POSITIVE_INFINITY;
        for(int i=0;i<shore.size();i++) {
            var a=shore.get(i);var b=shore.get((i+1)%shore.size());
            double dx=b.x()-a.x(),dz=b.z()-a.z(),t=Math.clamp(((x-a.x())*dx+(z-a.z())*dz)/(dx*dx+dz*dz),0,1);
            best=Math.min(best,Math.hypot(x-a.x()-t*dx,z-a.z()-t*dz));
        }
        return best;
    }

}
