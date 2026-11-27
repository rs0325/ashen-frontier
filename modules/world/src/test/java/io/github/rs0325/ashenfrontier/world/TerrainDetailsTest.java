package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainDetailsTest {
    @Test void measuredDetailControlsAndGroundedGlacier() throws Exception {
        var version=TerrainBasisTest.version();
        var terrain=new FixedTerrain(version);
        var details=new TerrainDetails(version,terrain.map().seed());
        var doc=BundledMaps.landform(version).document();
        var index=JsonParser.parseString(new String(getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_002/index.json").readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(var item:index.getAsJsonArray("detail_controls")) {
            var p=item.getAsJsonArray();int x=p.get(0).getAsInt(),z=p.get(1).getAsInt(),expected=p.get(2).getAsInt();
            int actual=terrain.column(x,z).surfaceY();
            System.out.println("DETAIL_CONTROL "+x+","+z+" expected="+expected+" actual="+actual);
            checks.add(()->assertTrue(Math.abs(actual-expected)<=1,"細地形の制御高 "+x+","+z+": "+actual+" / "+expected));
        }
        assertAll(checks);
        for(var item:doc.getAsJsonObject("glacier").getAsJsonArray("moraine")) {
            var p=item.getAsJsonArray();var column=terrain.column(p.get(0).getAsInt(),p.get(1).getAsInt());
            assertEquals("ashen_frontier:glacial_till",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(column.state(column.surfaceY()).getBlock()).toString(),"モレーンの表層");
        }
        for(var item:doc.getAsJsonObject("glacier").getAsJsonArray("outwash")) {
            var p=item.getAsJsonArray();var column=terrain.column(p.get(0).getAsInt(),p.get(1).getAsInt());
            assertEquals("ashen_frontier:river_gravel",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(column.state(column.surfaceY()).getBlock()).toString(),"氷河末端の乾いた砂礫");
        }
        for(var item:doc.getAsJsonObject("glacier").getAsJsonArray("points")) {
            var p=item.getAsJsonArray();int x=p.get(0).getAsInt(),z=p.get(1).getAsInt();
            int depth=p.get(4).getAsInt();var column=terrain.column(x,z);int surface=column.surfaceY();
            assertEquals(depth,details.ice(x,z),"G01の中心厚");
            for(int y=surface-depth+1;y<=surface;y++)
                assertTrue(column.state(y).is(Blocks.PACKED_ICE)||column.state(y).is(Blocks.BLUE_ICE),"G01氷体");
            assertFalse(column.state(surface-depth).isAir(),"氷河の接地");
            assertFalse(column.state(surface-depth).getFluidState().isSource(),"氷の下は固定地盤");
        }
        var canyon=terrain.column(-5200,-11300);
        assertEquals(162,canyon.surfaceY());
        assertTrue(!canyon.state(164).getFluidState().isEmpty());
        assertEquals(152,terrain.column(-6600,-10500).surfaceY());
    }
    @Test void glacierIsInAValleyRatherThanOnARaisedRibbon() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());
        var points=BundledMaps.landform(TerrainBasisTest.version()).document().getAsJsonObject("glacier").getAsJsonArray("points");
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(int i=0;i<points.size()-1;i++) {
            var p=points.get(i).getAsJsonArray();
            var a=points.get(Math.max(0,i-1)).getAsJsonArray();var b=points.get(i+1).getAsJsonArray();
            double dx=b.get(0).getAsDouble()-a.get(0).getAsDouble(),dz=b.get(1).getAsDouble()-a.get(1).getAsDouble();
            double length=Math.hypot(dx,dz);int surface=p.get(2).getAsInt();
            int radius=p.get(3).getAsInt()/2+16;
            for(int side:new int[]{-1,1}) {
                int x=(int)Math.round(p.get(0).getAsInt()-dz/length*radius*side);
                int z=(int)Math.round(p.get(1).getAsInt()+dx/length*radius*side);
                int bank=terrain.column(x,z).surfaceY();
                System.out.println("D3_GLACIER_CROSS point="+i+" side="+side+" bank="+bank+" ice="+surface);
                checks.add(()->assertTrue(bank>=surface+8,"G01の両岸が氷面より8B以上高く、氷体を尾根状に盛り上げない: "+x+","+z+"/"+bank));
            }
        }
        assertAll(checks);
    }

    @Test void glacierShoulderDoesNotFormAFlatRoof() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());
        double sum=0,squares=0;int count=0,minimum=Integer.MAX_VALUE,maximum=Integer.MIN_VALUE;
        for(int z=-12864;z<=-12624;z+=16) {
            int height=terrain.column(-10496,z).surfaceY();
            sum+=height;squares+=height*height;count++;
            minimum=Math.min(minimum,height);maximum=Math.max(maximum,height);
        }
        double sd=Math.sqrt(squares/count-Math.pow(sum/count,2));
        System.out.println("D3_GLACIER_SHOULDER min="+minimum+" max="+maximum+" sd="+sd);
        assertTrue(sd>4 && maximum-minimum>=16,"P1から見えるG01の肩を長い平坦な頂部にしない");
    }

    @Test void detailUpperLimitsKeepTheApprovedEmbeddedRidge() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());var map=terrain.map();
        assertEquals(32,map.landformId(-8500,-10100));
        assertEquals(330,terrain.column(-8500,-10100).surfaceY(),"32の包絡内でも22-03の稜線を残す");
        assertEquals(200,terrain.column(-8500,-10400).surfaceY(),"溶岩面32-01");
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(int id:new int[]{31,33,34,35,36,38,40}) {
            var unit=BundledMaps.landform(TerrainBasisTest.version()).units().get(id);
            int x0=unit.boundary().stream().mapToInt(p->p.x()).min().orElseThrow()-128;
            int x1=unit.boundary().stream().mapToInt(p->p.x()).max().orElseThrow()+128;
            int z0=unit.boundary().stream().mapToInt(p->p.z()).min().orElseThrow()-128;
            int z1=unit.boundary().stream().mapToInt(p->p.z()).max().orElseThrow()+128;
            int maximum=Integer.MIN_VALUE,atX=0,atZ=0,count=0;
            for(int z=z0;z<=z1;z+=4)for(int x=x0;x<=x1;x+=4)if(map.landformId(x,z)==id) {
                int height=terrain.column(x,z).surfaceY();count++;
                if(height>maximum){maximum=height;atX=x;atZ=z;}
            }
            int limit=unit.peakY(),actual=maximum;
            System.out.println("D3_UPPER_LIMIT id="+id+" max="+maximum+" at="+atX+","+atZ+" count="+count);
            assertTrue(count>1000);
            checks.add(()->assertTrue(actual<=limit,"細地形"+id+"の最終上限 "+actual+" / "+limit));
        }
        assertAll(checks);
    }

    @Test void snowHeadHasDryTributariesInsteadOfAClosedBowl() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());var map=terrain.map();
        for(int[] point:new int[][]{{-10186,-12863},{-10145,-12888}}) {
            int x=point[0],z=point[1];
            int center=terrain.column(x,z).surfaceY();
            int left=terrain.column(x-17,z-27).surfaceY(),right=terrain.column(x+17,z+27).surfaceY();
            double incision=(left+right)*.5-center;
            System.out.println("D3_HEAD_GULLY x="+x+" z="+z+" center="+center+" banks="+left+","+right+" incision="+incision);
            assertEquals(0,map.waterInventoryId(x,z),"源頭より上は新しい常時水面を置かない");
            assertEquals(0,map.details().ice(x,z),"支谷は氷体の外へ通す");
            assertTrue(incision>=8,"雪斜面を下る支谷が源頭の丸い面を横切る: "+incision);
        }
    }

    @Test void snowHasCoherentPatchesAndMeltsAtLowBanks() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());var map=terrain.map();
        int size=257,snow=0,isolated=0,changes=0,edges=0;
        boolean[][] cover=new boolean[size][size];
        for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
            var column=terrain.column(-10628+x,-13028+z);
            var top=column.state(column.topY());
            cover[z][x]=top.is(Blocks.SNOW)||top.is(Blocks.SNOW_BLOCK)||top.is(Blocks.POWDER_SNOW);
            if(cover[z][x])snow++;
        }
        for(int z=1;z<size-1;z++)for(int x=1;x<size-1;x++) {
            if(cover[z][x] && !cover[z-1][x] && !cover[z+1][x] && !cover[z][x-1] && !cover[z][x+1])isolated++;
            if(cover[z][x]!=cover[z][x+1])changes++;
            if(cover[z][x]!=cover[z+1][x])changes++;
            edges+=2;
        }
        System.out.println("D3_SNOW count="+snow+" isolated="+isolated+" transition="+(double)changes/edges);
        assertTrue(snow>1000,"晩夏の涵養域にまとまった雪がある");
        assertTrue(isolated/(double)snow<.003,"孤立した雪の点を作らない");
        assertTrue(changes/(double)edges<.12,"1Bごとの市松模様にしない");
        var points=BundledMaps.hydrology(TerrainBasisTest.version()).reaches().get("R05U").profile();
        double station=0;int checked=0;
        for(int i=1;i<points.size();i++) {
            var a=points.get(i-1);var p=points.get(i);
            station+=Math.hypot(p.x()-a.x(),p.z()-a.z());
            if(station>256)break;
            if(i%8!=0)continue;
            for(int dz=-3;dz<=3;dz++)for(int dx=-3;dx<=3;dx++) {
                if(Math.hypot(dx,dz)>=4)continue;
                var c=terrain.column(p.x()+dx,p.z()+dz);
                if(map.waterInventoryId(p.x()+dx,p.z()+dz)>0 || c.surfaceY()>p.waterY()+2)continue;
                assertFalse(c.state(c.topY()).is(Blocks.SNOW),"水際の低い岸を融雪: "+(p.x()+dx)+","+(p.z()+dz)+" ground="+c.surfaceY()+" water="+p.waterY());
                assertFalse(c.state(c.surfaceY()).is(Blocks.SNOW_BLOCK),"通行帯に厚雪を置かない");
                assertFalse(c.state(c.surfaceY()).is(Blocks.POWDER_SNOW),"通行帯に粉雪を置かない");
                checked++;
            }
        }
        assertTrue(checked>100,"源頭の水際と通行帯を調べる");
    }

    @Test void canyonKeepsApprovedFloorWidthAndShoulders() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());
        var points=BundledMaps.hydrology(TerrainBasisTest.version()).reaches().get("R15").profile();
        int at=0;
        for(int i=0;i<points.size();i++)if(points.get(i).x()==-5200 && points.get(i).z()==-11300){at=i;break;}
        assertTrue(at>0);
        var before=points.get(at-1);var after=points.get(at+1);
        double dx=after.x()-before.x(),dz=after.z()-before.z(),length=Math.hypot(dx,dz);
        var floor=new java.util.HashSet<Long>();int[] shoulders=new int[2];
        for(int side:new int[]{-1,1}) {
            for(int d=0;d<40;d++) {
                int x=(int)Math.round(-5200-dz/length*d*side),z=(int)Math.round(-11300+dx/length*d*side);
                int y=terrain.column(x,z).surfaceY();
                if(y>165)break;
                floor.add(((long)x<<32)^(z&0xffffffffL));
            }
        }
        for(int z=-11520;z<=-11120;z+=16)for(int x=-5480;x<=-4880;x+=16) {
            if(terrain.map().details().canyonWeight(x,z)<.999)continue;
            double best=Double.POSITIVE_INFINITY;int nearest=0;
            for(int i=0;i<points.size();i++) {
                var p=points.get(i);double distance=Math.hypot(x-p.x(),z-p.z());
                if(distance<best){best=distance;nearest=i;}
            }
            if(best<180 || best>280 || nearest<4 || nearest>=points.size()-4)continue;
            var a=points.get(nearest-4);var b=points.get(nearest+4);var p=points.get(nearest);
            int side=(b.x()-a.x())*(z-p.z())-(b.z()-a.z())*(x-p.x())<0?0:1;
            shoulders[side]=Math.max(shoulders[side],terrain.column(x,z).surfaceY());
        }
        System.out.println("D3_CANYON floor="+floor.size()+" shoulders="+java.util.Arrays.toString(shoulders));
        for(int d=-24;d<=24;d++) {
            int x=(int)Math.round(-5200-dz/length*d),z=(int)Math.round(-11300+dx/length*d);
            System.out.println("D3_CANYON_SECTION "+d+" x="+x+" z="+z+" ground="+terrain.surfaceY(x,z)+" natural="+terrain.map().basis().height(x,z));
        }
        assertTrue(floor.size()>=12 && floor.size()<=20,"37の谷底全幅12〜20B");
        for(int height:shoulders)assertTrue(height>=275 && height<=350,"37の肩275〜350: "+height);
    }

    @Test void nearbyCanyonBranchesKeepContinuousSideSlopes() throws Exception {
        var map=FixedMapData.require(TerrainBasisTest.version());
        double maximum=0;
        for(int[] center:new int[][]{{-5231,-11392},{-5192,-11261}})for(int t=-20;t<=20;t++) {
            int x=center[0]+t,z=center[1]-t;
            double natural=map.basis().height(x,z),nextNatural=map.basis().height(x,z+1);
            double first=map.hydrology().height(x,z,natural),second=map.hydrology().height(x,z+1,nextNatural);
            double added=Math.abs(first-second)-Math.abs(natural-nextNatural);
            maximum=Math.max(maximum,added);
            if(added>8)System.out.println("D3_CANYON_BRANCH x="+x+" z="+z+" natural="+natural+","+nextNatural+" carved="+first+","+second+" weight="+map.details().canyonWeight(x,z)+","+map.details().canyonWeight(x,z+1));
        }
        System.out.println("D3_CANYON_BRANCH maximum="+maximum);
        assertTrue(maximum<8,"近い河道区間の切替えに8B以上の切断を作らない: "+maximum);
    }

    @Test void rockSlopesKeepSmallGulliesInsteadOfParallelTerraces() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());var basis=terrain.map().basis();
        for(int[] point:new int[][]{{-5050,-11420},{-9900,-10700},{-10430,-13120},{-7700,-5800}}) {
            int size=129;double[][] old=new double[size][size],actual=new double[size][size];
            int needles=0;
            for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
                old[z][x]=basis.baseHeight(point[0]+x-64,point[1]+z-64);
                actual[z][x]=terrain.column(point[0]+x-64,point[1]+z-64).surfaceY();
            }
            double before=TerrainBasisTest.curvatureSpread(old),after=TerrainBasisTest.curvatureSpread(actual);
            for(int z=1;z<size-1;z++)for(int x=1;x<size-1;x++) {
                double high=Math.max(Math.max(actual[z-1][x],actual[z+1][x]),Math.max(actual[z][x-1],actual[z][x+1]));
                if(actual[z][x]-high>=2)needles++;
            }
            System.out.println("D3_ROCK_RELIEF "+point[0]+","+point[1]+" base="+before+" actual="+after+" needles="+needles);
            assertTrue(after>.15 && after>before*1.5,"D3の岩壁にも小さなガリー・尾根を残す");
            assertEquals(0,needles,"1Bの孤立した突起にしない");
        }
    }

    @Test void canyonContoursDoNotShareOneSlopeDirection() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());
        for(int[] point:new int[][]{{-5240,-11200},{-5050,-11420}})
            assertTrue(slopeSpread(terrain,point[0],point[1],16)>.15,"同じ向きの段が広い側斜面を覆わない");
    }

    @Test void dryBasinOutletBreaksParallelShallowSteps() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());
        assertTrue(slopeSpread(terrain,-7700,-5800,4)>.35,"盆地の乾いた出口を平行な段で囲まない");
    }

    private static double slopeSpread(FixedTerrain terrain,int x0,int z0,double minimum) {
        int size=81;int[][] h=new int[size][size];
        for(int z=0;z<size;z++)for(int x=0;x<size;x++)
            h[z][x]=terrain.column(x0+x-40,z0+z-40).surfaceY();
        double cosine=0,sine=0;int count=0;
        for(int z=8;z<size-8;z++)for(int x=8;x<size-8;x++) {
            double dx=h[z][x+8]-h[z][x-8],dz=h[z+8][x]-h[z-8][x],square=dx*dx+dz*dz;
            if(square<=minimum)continue;
            cosine+=(dx*dx-dz*dz)/square;sine+=2*dx*dz/square;count++;
        }
        double spread=1-Math.hypot(cosine/count,sine/count);
        System.out.println("D3_CONTOUR_DIRECTION "+x0+","+z0+" count="+count+" spread="+spread);
        assertTrue(count>500,"緩い床ではなく実際の斜面を測る");
        return spread;
    }

    @Test void basinBottomStaysWithinApprovedRange() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        for(int z=-5780;z<=-5700;z++)for(int x=-7800;x<=-7720;x++) {
            assertEquals(38,terrain.map().landformId(x,z));
            int height=terrain.column(x,z).surfaceY();low=Math.min(low,height);high=Math.max(high,height);
        }
        System.out.println("D3_BASIN_BOTTOM minimum="+low+" maximum="+high);
        assertTrue(low>=139 && high<=155,"盆地底の範囲139〜155: "+low+"〜"+high);
        assertTrue(high-low>=3,"盆地底にも緩い起伏を残す");
    }

    @Test void actualColumnsKeepDryOutletsOpen() throws Exception {
        var terrain=new FixedTerrain(TerrainBasisTest.version());
        int[][][] routes={
                {{-8000,-5600},{-7600,-5900},{-6900,-6700}},
                {{-10000,-10800},{-10200,-10500},{-10400,-10200},{-10200,-9900}},
                {{-10100,-13250},{-10080,-13320},{-10020,-13480}}};
        int[] starts={145,350,210};
        for(int i=0;i<routes.length;i++) {
            int spill=spill(terrain,routes[i]);
            System.out.println("D3_ACTUAL_DRAIN route="+i+" spill="+spill);
            assertTrue(spill<=starts[i]+1,"1B実列で乾いた排水口を塞がない: "+i+" "+spill);
        }
    }

    private record FloodCell(int at,int height) {}

    private static int spill(FixedTerrain terrain,int[][] points) {
        int x0=Integer.MAX_VALUE,z0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,z1=Integer.MIN_VALUE;
        for(var p:points) {
            x0=Math.min(x0,p[0]-112);z0=Math.min(z0,p[1]-112);
            x1=Math.max(x1,p[0]+112);z1=Math.max(z1,p[1]+112);
        }
        int width=x1-x0+1,depth=z1-z0+1;
        short[] heights=new short[width*depth];java.util.Arrays.fill(heights,Short.MIN_VALUE);
        int[] best=new int[heights.length];java.util.Arrays.fill(best,Integer.MAX_VALUE);
        int start=(points[0][1]-z0)*width+points[0][0]-x0;
        var end=points[points.length-1];int goal=(end[1]-z0)*width+end[0]-x0;
        var queue=new java.util.PriorityQueue<FloodCell>(java.util.Comparator.comparingInt(FloodCell::height));
        best[start]=terrain.column(points[0][0],points[0][1]).surfaceY();queue.add(new FloodCell(start,best[start]));
        int[][] directions={{-1,0},{1,0},{0,-1},{0,1}};
        while(!queue.isEmpty()) {
            var cell=queue.remove();int at=cell.at();
            if(cell.height()!=best[at])continue;
            if(at==goal)return cell.height();
            int x=at%width,z=at/width;
            for(int[] d:directions) {
                int nx=x+d[0],nz=z+d[1];
                if(nx<0 || nz<0 || nx>=width || nz>=depth)continue;
                int next=nz*width+nx;
                if(heights[next]==Short.MIN_VALUE) {
                    int px=x0+nx,pz=z0+nz;double distance=Double.POSITIVE_INFINITY;
                    for(int i=1;i<points.length;i++) {
                        var a=points[i-1];var b=points[i];
                        double dx=b[0]-a[0],dz=b[1]-a[1],t=Math.clamp(((px-a[0])*dx+(pz-a[1])*dz)/(dx*dx+dz*dz),0,1);
                        distance=Math.min(distance,Math.hypot(px-a[0]-t*dx,pz-a[1]-t*dz));
                    }
                    heights[next]=distance>96?Short.MAX_VALUE:(short)terrain.column(px,pz).surfaceY();
                }
                if(heights[next]==Short.MAX_VALUE)continue;
                int cost=Math.max(cell.height(),heights[next]);
                if(cost<best[next]) {best[next]=cost;queue.add(new FloodCell(next,cost));}
            }
        }
        return Integer.MAX_VALUE;
    }

}
