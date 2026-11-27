package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** 制作済みの初期水列と、その近傍だけに作用する河谷・湖岸。残水量は保持しない。 */
final class HydrologyTerrain {
    private record Gully(double station,double angle,double bend,double width,double strength) {}
    private final Gully[][] tributaries=new Gully[2][18];
    private final Gully[][] sourceGullies=new Gully[2][10];
    private static final int[][] NEIGHBORS={{-1,0},{1,0},{0,-1},{0,1}};
    private record Tile(byte[] owner, short[] water, short[] bed) {}
    private record Shore(HydrologyLayout.Point a,HydrologyLayout.Point b,double station) {}
    private static final class Samples {
        int chunkX=Integer.MIN_VALUE,chunkZ=Integer.MIN_VALUE,x0,z0;
        final HydrologyNoise noise;
        Samples(long seed) {noise=new HydrologyNoise(seed);}

        long tileKey=Long.MIN_VALUE;
        Tile tile;
        final double[] neighbors=new double[9];
        final double[] natural=new double[400],carved=new double[400],minimum=new double[400];

        void use(int x,int z) {
            if(chunkX==(x>>4) && chunkZ==(z>>4))return;
            int dx=(x>>4)-chunkX,dz=(z>>4)-chunkZ;
            if((Math.abs(dx)==1 && dz==0) || (dx==0 && Math.abs(dz)==1)) {
                advance(natural,dx,dz);advance(carved,dx,dz);advance(minimum,dx,dz);
            } else {
                Arrays.fill(natural,Double.NaN);Arrays.fill(carved,Double.NaN);Arrays.fill(minimum,Double.NaN);
            }
            chunkX=x>>4;chunkZ=z>>4;x0=chunkX*16-2;z0=chunkZ*16-2;
        }

        private static void advance(double[] values,int dx,int dz) {
            if(dz!=0) {
                System.arraycopy(values,dz>0?320:0,values,dz>0?0:320,80);
                Arrays.fill(values,dz>0?80:0,dz>0?400:320,Double.NaN);
            } else for(int row=0;row<20;row++) {
                int start=row*20;
                System.arraycopy(values,start+(dx>0?16:0),values,start+(dx>0?0:16),4);
                Arrays.fill(values,start+(dx>0?4:0),start+(dx>0?20:16),Double.NaN);
            }
        }

        double natural(TerrainBasis basis,int x,int z) {
            int at=(z-z0)*20+x-x0;
            if(Double.isNaN(natural[at]))natural[at]=basis.height(x,z);
            return natural[at];
        }

        double minimum(HydrologyTerrain terrain,TerrainBasis basis,int x,int z) {
            int at=(z-z0)*20+x-x0;
            if(Double.isNaN(minimum[at])) {
                double low=Double.POSITIVE_INFINITY;
                for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)
                    low=Math.min(low,height(terrain,basis,x+dx,z+dz));
                minimum[at]=low;
            }
            return minimum[at];
        }

        double height(HydrologyTerrain terrain,TerrainBasis basis,int x,int z) {
            int at=(z-z0)*20+x-x0;
            if(Double.isNaN(carved[at]))carved[at]=terrain.height(x,z,natural(basis,x,z));
            return carved[at];
        }
    }
    private final ThreadLocal<Samples> samples;

    private record Segment(double ax,double az,double ay,double aw,double ad,
                           double bx,double bz,double by,double bw,double bd,double station,boolean source,boolean reshape,boolean oblique,int reach,double dx,double dz,double length2,double length,double span) {
        double distance(double x,double z) {
            double t=length2==0?0:Math.clamp(((x-ax)*dx+(z-az)*dz)/length2,0,1);
            return Math.hypot(x-ax-t*dx,z-az-t*dz);
        }
    }
    record Bank(double distance,double grade,double width,double station,boolean source,double side) {}
    private final Map<Long,Tile> tiles=new HashMap<>();
    private final Map<Long,List<Segment>> cells=new HashMap<>();
    private final Map<Long,List<Shore>> shores=new HashMap<>();
    private final int[] inventory;
    private final long[] volume=new long[5];
    private final long seed;
    private final int lakeY;
    private final int sourceOwner;
    private final int sourceZ;
    private final TerrainDetails details;
    private final int canyonReach;

    HydrologyTerrain(MapVersion version,FixedMapData background,TerrainDetails details) {
        this.details=details;
        seed=background.seed();samples=ThreadLocal.withInitial(()->new Samples(seed));
        for(int side=0;side<2;side++) {
            double station=-24;
            for(int i=0;i<18;i++) {
                station+=12+10*TerrainMath.noise(seed,"source_oblique_station",i,side,1);
                tributaries[side][i]=new Gully(station,
                    -1.8+1.5*TerrainMath.noise(seed,"source_oblique_angle",i,side,1),
                    -.25+.5*TerrainMath.noise(seed,"source_oblique_bend",i,side,1),
                    8+7*TerrainMath.noise(seed,"source_oblique_width",i,side,1),
                    .75+.25*TerrainMath.noise(seed,"source_oblique_strength",i,side,1));
            }
        }
        for(int side=0;side<2;side++) {
            double station=-32;
            for(int i=0;i<10;i++) {
                station+=18+16*TerrainMath.noise(seed,"source_gully_station",i,side,1);
                sourceGullies[side][i]=new Gully(station,
                        -.9+1.8*TerrainMath.noise(seed,"source_gully_angle",i,side,1),
                        -.5+TerrainMath.noise(seed,"source_gully_bend",i,side,1),
                        9+6*TerrainMath.noise(seed,"source_gully_width",i,side,1),
                        .65+.35*TerrainMath.noise(seed,"source_gully_strength",i,side,1));
            }
        }
        var layout=BundledMaps.hydrology(version);
        sourceZ=layout.reaches().get("R05U").profile().getFirst().z();
        lakeY=layout.lakes().get("L10").surfaceY();
        String root="/data/ashen_frontier/fixed_map/"+version.mapVersion()+"/";
        try(var stream=HydrologyTerrain.class.getResourceAsStream(root+"index.json")) {
            var index=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            var definition=index.getAsJsonObject("hydrology_tiles");
            if(definition==null || LayoutJson.integer(definition,"schema_version")!=1 || LayoutJson.integer(definition,"tile_size")!=128)
                throw new IllegalStateException("D2の水列タイルが不足・不正です");
            int source=0;var names=definition.getAsJsonArray("owners");
            for(int n=0;n<names.size();n++)if(names.get(n).getAsString().equals("R05U"))source=n+1;
            sourceOwner=source;
            var ids=definition.getAsJsonArray("inventory_ids");inventory=new int[ids.size()+1];
            for(int i=0;i<ids.size();i++)inventory[i+1]=ids.get(i).getAsInt();
            try(var owners=HydrologyTerrain.class.getResourceAsStream(root+"hydrology_owner.png");
                var levels=HydrologyTerrain.class.getResourceAsStream(root+"hydrology_surface.png");
                var beds=HydrologyTerrain.class.getResourceAsStream(root+"hydrology_bed.png")) {
                var oi=ImageIO.read(owners);var wi=ImageIO.read(levels);var bi=ImageIO.read(beds);
                int columns=definition.get("atlas_columns").getAsInt();
                if(oi.getRaster().getNumBands()!=1 || wi.getRaster().getNumBands()!=1 || bi.getRaster().getNumBands()!=1
                        || oi.getSampleModel().getSampleSize(0)!=8 || wi.getSampleModel().getSampleSize(0)!=16 || bi.getSampleModel().getSampleSize(0)!=16
                        || oi.getWidth()!=columns*128 || wi.getWidth()!=oi.getWidth() || bi.getWidth()!=oi.getWidth()
                        || wi.getHeight()!=oi.getHeight() || bi.getHeight()!=oi.getHeight())throw new IllegalStateException("水列タイルの寸法不一致");
                int n=0;
                for(var value:definition.getAsJsonArray("tiles")) {
                    var pair=value.getAsJsonArray();int tx=pair.get(0).getAsInt(),tz=pair.get(1).getAsInt();
                    byte[] owner=new byte[16384];short[] water=new short[16384],bed=new short[16384];
                    for(int z=0;z<128;z++)for(int x=0;x<128;x++) {
                        int at=z*128+x,px=n%columns*128+x,pz=n/columns*128+z;
                        int id=oi.getRaster().getSample(px,pz,0);if(id==0)continue;
                        if(id>=inventory.length)throw new IllegalStateException("不明な水列所有者");
                        int y=wi.getRaster().getSample(px,pz,0)-64,b=bi.getRaster().getSample(px,pz,0)-64;
                        if(b>=y || y<63 || y>520 || b< -127)throw new IllegalStateException("不正な水列の水面・床");
                        water[at]=(short)y;bed[at]=(short)b;
                        // 海との境界は旧版と共有する実際の海岸で一回だけ確定する。
                        if(background.coastDistance(tx*128+x,tz*128+z)>=0) {
                            owner[at]=(byte)id;volume[inventory[id]]+=y-b;
                        }
                    }
                    if(tiles.put(key(tx,tz),new Tile(owner,water,bed))!=null)throw new IllegalStateException("水列タイルの重複");n++;
                }
                oi.flush();wi.flush();bi.flush();
            }
        } catch(IOException|RuntimeException e) {throw new IllegalStateException("固定マップ版 "+version.mapVersion()+": 水系資産を読めません",e);}
        int reachId=0;
        var ordered=layout.reaches().values().stream().sorted(java.util.Comparator.comparing(HydrologyLayout.Reach::id)).toList();
        canyonReach=ordered.stream().map(HydrologyLayout.Reach::id).toList().indexOf("R15");
        for(var reach:ordered) {
            int id=reachId++;
            var points=reach.profile();double station=0;
            for(int i=0;i<points.size()-1;) {
                int end=Math.min(points.size()-1,i+4);var a=points.get(i);var b=points.get(end);
                double dx=b.x()-a.x(),dz=b.z()-a.z(),length2=dx*dx+dz*dz;
                // 源頭の小さな曲がりで、下流全域の侵食位相をずらさない。
                double span=reach.id().equals("R05U") && end<=60?Math.abs(dz):Math.hypot(dx,dz);
                var s=new Segment(a.x(),a.z(),a.waterY(),a.halfWidth(),a.depth(),b.x(),b.z(),b.waterY(),b.halfWidth(),b.depth(),station,reach.id().equals("R05U"),!reach.id().equals("R06"),!reach.id().equals("R16"),id,dx,dz,length2,Math.sqrt(length2),span);
                for(int x=(int)Math.floor((Math.min(s.ax,s.bx)-320)/32);x<=(int)Math.floor((Math.max(s.ax,s.bx)+320)/32);x++)
                    for(int z=(int)Math.floor((Math.min(s.az,s.bz)-320)/32);z<=(int)Math.floor((Math.max(s.az,s.bz)+320)/32);z++)
                        cells.computeIfAbsent(key(x,z),ignored->new ArrayList<>()).add(s);
                station+=s.span;i=end;
            }
        }
        cells.replaceAll(this::localCandidates);
        cells.entrySet().removeIf(entry->entry.getValue().isEmpty());
        for(var lake:layout.lakes().values()) {
            double station=0;
            for(int i=0;i<lake.outline().size();i+=12) {
            var a=lake.outline().get(i);var b=lake.outline().get(Math.min(lake.outline().size(),i+12)%lake.outline().size());
            var pair=new Shore(a,b,station);station+=Math.hypot(b.x()-a.x(),b.z()-a.z());
            for(int x=Math.floorDiv(Math.min(a.x(),b.x())-320,128);x<=Math.floorDiv(Math.max(a.x(),b.x())+320,128);x++)
                for(int z=Math.floorDiv(Math.min(a.z(),b.z())-320,128);z<=Math.floorDiv(Math.max(a.z(),b.z())+320,128);z++)
                    shores.computeIfAbsent(key(x,z),ignored->new ArrayList<>()).add(pair);
            }
        }
    }

    private List<Segment> localCandidates(long cell,List<Segment> segments) {
        double x=(int)(cell>>32)*32+15.5,z=(int)cell*32+15.5;
        var local=new ArrayList<Segment>();
        for(int i=0;i<segments.size();) {
            int start=i,reach=segments.get(i).reach;double nearest=Double.POSITIVE_INFINITY,width=0;
            while(i<segments.size() && segments.get(i).reach==reach) {
                var segment=segments.get(i++);
                nearest=Math.min(nearest,segment.distance(x,z));
                width=Math.max(width,Math.max(segment.aw,segment.bw));
            }
            if(nearest>width+294)continue;
            // セル半径の往復46Bと、支流32B・峡谷の連続化48Bを確保する。
            double limit=nearest+(segments.get(start).reach==canyonReach?102:78);
            for(int n=start;n<i;n++) {
                var segment=segments.get(n);
                if(segment.distance(x,z)<=limit)local.add(segment);
            }
        }
        return local;
    }

    private static long key(int x,int z) {return ((long)x<<32)^(z&0xffffffffL);}
    private Tile tile(int x,int z) {
        var cache=samples.get();long tileKey=key(x>>7,z>>7);
        if(cache.tileKey!=tileKey) {cache.tileKey=tileKey;cache.tile=tiles.get(tileKey);}
        return cache.tile;
    }
    int owner(int x,int z) {var t=tile(x,z);return t==null?0:Byte.toUnsignedInt(t.owner[(z&127)*128+(x&127)]);}
    int inventory(int x,int z) {return inventory[owner(x,z)];}
    int water(int x,int z) {var t=tile(x,z);int at=(z&127)*128+(x&127);return t==null || t.water[at]==0?Integer.MIN_VALUE:t.water[at];}
    int bed(int x,int z) {var t=tile(x,z);return t==null?Integer.MIN_VALUE:t.bed[(z&127)*128+(x&127)];}
    long initialVolume(int id) {
        if(id<2 || id>4)throw new IllegalArgumentException("有限の初期幾何体積はinventory 2～4です");
        return volume[id];
    }

    double height(int x,int z,double natural) {
        if(water(x,z)!=Integer.MIN_VALUE)return bed(x,z);
        double result=natural,bankEdge=Double.POSITIVE_INFINITY;
        Bank canyon=null,sourceBank=null;
        double glacier=details.glacierWeight(x,z);
        var nearby=cells.getOrDefault(key(x>>5,z>>5),List.of());
        for(int i=0;i<nearby.size();) {
            int start=i,reach=nearby.get(i).reach;double best=Double.POSITIVE_INFINITY,maxWidth=0;Segment closest=null;double fraction=0;
            while(i<nearby.size() && nearby.get(i).reach==reach) {
                var a=nearby.get(i++);double dx=a.dx,dz=a.dz,length=a.length2;
                maxWidth=Math.max(maxWidth,Math.max(a.aw,a.bw));
                double t=length==0?0:Math.clamp(((x-a.ax)*dx+(z-a.az)*dz)/length,0,1);
                double px=a.ax+t*dx,pz=a.az+t*dz,distance=(x-px)*(x-px)+(z-pz)*(z-pz);
                if(distance<best) {
                    best=distance;closest=a;fraction=t;
                }
            }
            if(closest!=null && Math.sqrt(best)-maxWidth<270) {
                var a=closest;double t=fraction;
                double station=a.station+t*a.span;
                boolean coherent=a.reshape && a.oblique;
                double head=a.source?1-TerrainMath.smooth((station-256)/256):0;
                double coherence=a.source?head*TerrainMath.smooth((Math.sqrt(best)-a.aw-t*(a.bw-a.aw)-8)/16):1;
                if(coherent && coherence>0) {
                    double band=a.source?16:4;
                    double weighted=0,total=0,span=2*band*Math.sqrt(best)+band*band;
                    for(int k=start;k<i;k++) {
                        var segment=nearby.get(k);double dx=segment.dx,dz=segment.dz,l=segment.length2;
                        double along=l==0?0:((x-segment.ax)*dx+(z-segment.az)*dz)/l;
                        double u=Math.clamp(along,0,1);
                        double px=x-segment.ax-u*dx,pz=z-segment.az-u*dz;
                        double weight=1-TerrainMath.smooth((px*px+pz*pz-best)/span);
                        if(weight==0)continue;
                        weighted+=weight*(segment.station+along*segment.span);total+=weight;
                    }
                    station+=(weighted/total-station)*coherence;
                }
                double cross=(a.bx-a.ax)*(z-a.az-t*(a.bz-a.az))-(a.bz-a.az)*(x-a.ax-t*(a.bx-a.ax));
                double side=Math.signum(cross);
                if(a.source)side+=(Math.clamp(cross/(a.length*8),-1,1)-side)*coherence;
                var nearest=new Bank(Math.sqrt(best),a.ay+t*(a.by-a.ay),a.aw+t*(a.bw-a.aw),station,a.source,side);
                if(reach==canyonReach && details.canyonWeight(x,z)>0) {
                    double grade=0,width=0,total=0,margin=48;
                    for(int k=start;k<i;k++) {
                        var segment=nearby.get(k);
                        double u=segment.length2==0?0:Math.clamp(((x-segment.ax)*segment.dx+(z-segment.az)*segment.dz)/segment.length2,0,1);
                        double distance=segment.distance(x,z);
                        double weight=1-TerrainMath.smooth((distance-nearest.distance)/margin);
                        grade+=weight*(segment.ay+u*(segment.by-segment.ay));
                        width+=weight*(segment.aw+u*(segment.bw-segment.aw));total+=weight;
                    }
                    canyon=new Bank(nearest.distance,grade/total,width/total,station,false,side);
                }
                if(nearest.source())sourceBank=nearest;
                double carved=bankHeight(natural,nearest.distance,nearest.grade,nearest.width,nearest.station,nearest.source,nearest.side);
                for(int k=start;k<i;k++) {
                    var other=nearby.get(k);
                    double separation=Math.abs(other.station-nearest.station);
                    double blend=separation<24?0:1;
                    if(coherent)blend+=(TerrainMath.smooth((separation-16)/16)-blend)*coherence;
                    if(blend==0)continue;
                    double ox=other.dx,oz=other.dz,l=other.length2;
                    double u=l==0?0:Math.clamp(((x-other.ax)*ox+(z-other.az)*oz)/l,0,1);
                    double px=x-other.ax-u*ox,pz=z-other.az-u*oz,limit=nearest.distance+32;
                    if(Math.abs(px)>=limit || Math.abs(pz)>=limit)continue;
                    double distance=Math.hypot(px,pz);
                    if(distance>=limit)continue;
                    double grade=other.ay+u*(other.by-other.ay);
                    double fade=TerrainMath.smooth((nearest.distance+32-distance)/16);
                    if(coherent && !a.source && natural-Math.max(0,natural-grade)*blend*fade>=carved+2)continue;
                    double signed=ox*(z-other.az-u*oz)-oz*(x-other.ax-u*ox),branchSide=Math.signum(signed);
                    if(other.source)branchSide+=(Math.clamp(signed/(other.length*8),-1,1)-branchSide)*coherence;
                    double cut=bankHeight(natural,distance,grade,other.aw+u*(other.bw-other.aw),
                            other.station+u*other.span,other.source,branchSide);
                    double otherCut=natural-(natural-cut)*blend*fade;
                    carved=nearest.source && nearest.station<256?Math.min(carved,otherCut):join(natural,carved,otherCut);
                }
                if(a.reshape)carved=rillHeight(natural,carved,nearest,a.oblique,cross/Math.max(1,a.length));
                if(a.source && nearest.station<192) {
                    double weight=(1-TerrainMath.smooth((nearest.station-96)/96));
                    double passage=.1+2.15*TerrainMath.smooth((cross/Math.max(1,a.length)+2)/4);
                    weight*=TerrainMath.smooth(nearest.distance-nearest.width-passage);
                    carved+=(obliqueHead(z,natural,nearest,cross/Math.max(1,a.length))-carved)*weight;
                }
                if(coherent && !a.source)bankEdge=Math.min(bankEdge,Math.max(0,nearest.distance-nearest.width));
                result=join(natural,result,carved);
            }
        }
        double shore=Double.POSITIVE_INFINITY,lake=natural;
        for(var edge:shores.getOrDefault(key(x>>7,z>>7),List.of())) {
            var a=edge.a;var b=edge.b;double dx=b.x()-a.x(),dz=b.z()-a.z();
            double t=Math.clamp(((x-a.x())*dx+(z-a.z())*dz)/(dx*dx+dz*dz),0,1);
            double distance=Math.hypot(x-a.x()-t*dx,z-a.z()-t*dz);
            shore=Math.min(shore,distance);
        }
        if(shore<320) {
            for(var edge:shores.getOrDefault(key(x>>7,z>>7),List.of())) {
                var a=edge.a;var b=edge.b;double dx=b.x()-a.x(),dz=b.z()-a.z();
                double t=Math.clamp(((x-a.x())*dx+(z-a.z())*dz)/(dx*dx+dz*dz),0,1);
                double distance=Math.hypot(x-a.x()-t*dx,z-a.z()-t*dz);
                if(distance>=shore+32)continue;
                double cut=lakeBankHeight(natural,distance,edge.station+t*Math.hypot(dx,dz));
                cut=natural-(natural-cut)*TerrainMath.smooth((shore+32-distance)/16);
                lake=join(natural,lake,cut,lakeY+1);
            }
            result=join(natural,result,lake,lakeY+1);
        }
        if(bankEdge<144) {
            double outer=Math.max(0,bankEdge-4);
            double slope=.08+.27*TerrainMath.smooth((bankEdge-48)/48);
            double rise=outer*slope*TerrainMath.smooth(outer/8);
            rise*=1-TerrainMath.smooth((bankEdge-64)/80);
            rise*=TerrainMath.smooth((natural-result-4)/8);
            result=Math.min(lake,Math.min(natural,result+rise));
        }
        if(canyon!=null)result=details.canyonHeight(x,z,natural,result,canyon);
        if(glacier>0) {
            double support=natural;
            if(sourceBank!=null) {
                double d=Math.max(0,sourceBank.distance()-sourceBank.width());
                double t=Math.max(0,d-4);
                double spur=(14*TerrainMath.noise(seed,"d3_source_spur",x,z,47)
                        +8*TerrainMath.noise(seed,"d3_source_cross",.73*x+.5*z,-.5*x+.7*z,19))
                        *TerrainMath.smooth((d-12)/24);
                support=Math.min(natural,sourceBank.grade()+1+2.7*t+.008*t*t+spur);
                glacier*=TerrainMath.smooth((d-12)/24);
            }
            result+=Math.max(0,support-result)*glacier;
        }
        if(sourceBank!=null)result=details.headHeight(x,z,result,sourceBank);
        return result;
    }

    private double obliqueHead(int z,double natural,Bank bank,double perpendicular) {
        double d=Math.max(0,bank.distance-bank.width);
        if(d>=170)return natural;
        var noise=samples.get().noise;
        double path=.21+1.54*TerrainMath.smooth((perpendicular+2)/4);
        double side=Math.max(0,d-path);
        double g;
        if(Math.abs(perpendicular)>=2)g=gullyInfluence(tributaries[perpendicular>0?1:0],bank.station,side);
        else {
            double a=gullyInfluence(tributaries[0],bank.station,side),b=gullyInfluence(tributaries[1],bank.station,side);
            g=a+(b-a)*TerrainMath.smooth((perpendicular+2)/4);
        }
        double grade=bank.grade;
        double rise=1.3+.3*TerrainMath.smooth((z-sourceZ-48)/16)+side*.025+.8*(1-g)*TerrainMath.smooth((side-5)/8)+side*side*.0002;
        double depth=Math.max(0,natural-grade-1-rise);
        depth*=TerrainMath.smooth(depth);
        double reach=100+70*noise.noise(HydrologyNoise.Salt.SOURCE_OBLIQUE_EXTENT,bank.station,side*.3,73);
        double start=24+12*noise.noise(HydrologyNoise.Salt.SOURCE_OBLIQUE_TRANSITION,bank.station,side*.25,47);
        double tributary=4*g*TerrainMath.smooth((d-4)/6)*(1-TerrainMath.smooth((d-48)/48));
        tributary*=TerrainMath.smooth((natural-bank.grade+6)/12);
        return natural-(depth+tributary)*(1-TerrainMath.smooth((d-start)/(reach-start)));
    }

    private double rillHeight(double natural,double profile,Bank bank,boolean oblique,double perpendicular) {
        double d=Math.max(0,bank.distance-bank.width);
        if(d>=144)return profile;
        var noise=samples.get().noise;
        double head=bank.source?1-TerrainMath.smooth((bank.station-256)/256):0;
        double path=1.75*head*(bank.side>0?1:.12);
        double edge=2-1.5*head;
        double gate=TerrainMath.smooth((d-path-edge)/(3-1.5*head))*(1-TerrainMath.smooth((d-64)/80));
        gate*=1-TerrainMath.smooth((profile-bank.grade-24)/16);
        gate*=1-TerrainMath.smooth((natural-profile-96)/32);
        double cut=TerrainMath.smooth((natural-profile)/4);
        gate*=cut+(1-cut)*(1-TerrainMath.smooth((d-16)/16));
        double crossing=head*Math.max(0,bank.side)*TerrainMath.smooth((d-6)/6)*(1-TerrainMath.smooth((d-64)/80));
        if(gate==0 && crossing==0)return profile;
        double shift=(noise.noise(HydrologyNoise.Salt.VALLEY_RILL_SHIFT,bank.station,bank.side*37,32)-.5)*12;
        double result=profile;
        if(gate>0) {
            double blend=head*(1-TerrainMath.smooth((bank.station-80)/80));
            if(oblique && !bank.source) {
                double rill=noise.noise(HydrologyNoise.Salt.VALLEY_CROSS_RILL,
                        bank.station+d*(.02+shift*.015)+shift+bank.side*73,d*.02-bank.station*.1,8);
                double branch=noise.noise(HydrologyNoise.Salt.VALLEY_RILL_BRANCH,
                        bank.station*.6+d*.02+shift+(rill-.5)*14,d*.02+bank.station*.1,21);
                double side=Math.max(0,d-2);
                double target=bank.grade+1.4+side*(.02+.24*rill+.04*branch)+side*side*.0002;
                result=profile+(target-profile)*gate;
            } else {
                if(blend<1) {
                    double u=oblique?.8*bank.station+.6*d:bank.station;
                    double v=oblique?.6*bank.station-.8*d:d*.35;
                    u+=(bank.station+d*(.9+shift*.05)-u)*blend;
                    v+=(d*.15-bank.station*.1-v)*blend;
                    double rill=1-Math.abs(2*noise.noise(HydrologyNoise.Salt.VALLEY_CROSS_RILL,u+shift+bank.side*73,v,oblique?8:6)-1);
                    double bu=bank.station*(oblique?.5:.6)+d*(oblique?.8:.7)+shift;
                    double bv=d*(oblique?.6:.8)-bank.station*(oblique?.5:.25);
                    bu+=(bank.station*.6-d+shift+(rill-.5)*14-bu)*blend;
                    bv+=(d*.24+bank.station*.12-bv)*blend;
                    double branch=1-Math.abs(2*noise.noise(HydrologyNoise.Salt.VALLEY_RILL_BRANCH,bu,bv,oblique?19:13)-1);
                    double erosion=.65*Math.pow(TerrainMath.smooth(rill),2)+.35*Math.pow(TerrainMath.smooth(branch),2);
                    erosion+=head*(.25+.75*(.65*TerrainMath.smooth(rill)+.35*TerrainMath.smooth(branch))-erosion);
                    erosion+=(.75*Math.pow(TerrainMath.smooth(rill),2)+.25*Math.pow(TerrainMath.smooth(branch),2)-erosion)*blend;
                    double available=Math.max(0,profile-bank.grade-(1.25+head*.35));
                    double detail=.5*head*(1-TerrainMath.smooth((bank.station-80)/80));
                    double amount=available*(.8-.3*detail)*TerrainMath.smooth(available/2);
                    double amplitude=Math.min(6-3*detail,amount);
                    if(blend>0)amplitude+=(6*amount/(6+amount)-amplitude)*blend;
                    result-=gate*amplitude*erosion;
                }
                if(blend>0) {
                    double side=Math.max(0,d-head*(.21+1.54*TerrainMath.smooth((perpendicular+2)/4)));
                    double influence=gullyInfluence(bank.station,side,perpendicular);
                    double variation=noise.noise(HydrologyNoise.Salt.SOURCE_CROSS_SURFACE,bank.station+side*.9,side*.35,7);
                    double target=bank.grade+2+(side*.3+side*side*.004)*(1-.85*influence)
                            +2*(variation-.5)*TerrainMath.smooth(side/3);
                    result+=(profile+(target-profile)*gate-result)*blend;
                }
            }
        }
        if(crossing>0) {
            double cross=1-Math.abs(2*noise.noise(HydrologyNoise.Salt.SOURCE_CROSS_BRANCH,
                    bank.station*.8+d*.6+shift,d*.15-bank.station*.1,6)-1);
            double detail=.5*(1-TerrainMath.smooth((bank.station-80)/80));
            result-=crossing*Math.min(3-1.5*detail,Math.max(0,result-bank.grade-1.6)*(.4-.15*detail))*Math.pow(TerrainMath.smooth(cross),2);
        }
        return result;
    }

    private double gullyInfluence(double station,double side,double perpendicular) {
        if(Math.abs(perpendicular)>=2)return gullyInfluence(sourceGullies[perpendicular>0?1:0],station,side);
        double a=gullyInfluence(sourceGullies[0],station,side),b=gullyInfluence(sourceGullies[1],station,side);
        return a+(b-a)*TerrainMath.smooth((perpendicular+2)/4);
    }

    private static double gullyInfluence(Gully[] gullies,double station,double side) {
        double radius=side*2.1+24;
        int low=0,high=gullies.length;
        while(low<high) {
            int mid=(low+high)>>>1;
            if(gullies[mid].station<station-radius)low=mid+1;
            else high=mid;
        }
        double remaining=1;
        for(int i=low;i<gullies.length && gullies[i].station<=station+radius;i++) {
            var g=gullies[i];
            double center=g.station+g.angle*side+g.bend*side*side/(side+24);
            double width=g.width*(1.15-.35*side/(side+48));
            double q=Math.abs(station-center)/width;
            if(q>=1)continue;
            double influence=1-TerrainMath.smooth(q);
            remaining*=1-influence*influence*g.strength;
        }
        return 1-remaining;
    }

    private double bankHeight(double natural,double distance,double grade,double halfWidth,double station,boolean source,double bankSide) {
        double d=Math.max(0,distance-halfWidth);
        if(d>=270)return natural;
        var noise=samples.get().noise;
        double reach=170+100*noise.noise(HydrologyNoise.Salt.CANYON_EXTENT,station,bankSide*37,112);
        if(d>=reach)return natural;
        double head=source?1-TerrainMath.smooth((station-256)/256):0;
        double level=Math.floor(grade+1e-7);
        level+=(grade-level)*TerrainMath.smooth((d-2)/10);
        double correction=head*(1-TerrainMath.smooth((station-80)/80));
        double shift=correction>0?.75*noise.noise(HydrologyNoise.Salt.SOURCE_BANK_OFFSET,station,d*.4,3):0;
        double path=bankSide>0?1.75*head:.21*head+correction*shift;
        double side=Math.max(0,d-path);
        double bend=noise.fbm(HydrologyNoise.Salt.CANYON_BEND,station,d*.6,96)*28;
        double joint=noise.fbm(HydrologyNoise.Salt.CANYON_JOINT,station+bend,d*.65,40);
        double gully=1-Math.abs(2*noise.noise(HydrologyNoise.Salt.CANYON_GULLY,station+bend,d*.4,32)-1);
        double slope=.28+.4*head+.13*noise.noise(HydrologyNoise.Salt.CANYON_SLOPE,station,bankSide*51,72);
        double rise=side*slope+Math.pow(side,1.45)*.022+head*2*TerrainMath.smooth(side/1.25);
        if(correction>0 && bankSide<0) {
            double width=5+3*noise.noise(HydrologyNoise.Salt.SOURCE_TOE_WIDTH,station,d*.5,23);
            double lift=.8+.6*noise.noise(HydrologyNoise.Salt.SOURCE_TOE_LIFT,station,d*.4,9);
            rise+=correction*lift*TerrainMath.smooth(d/1.5)*(1-TerrainMath.smooth(d/width));
        }
        if(head>0 && bankSide<0) {
            double toe=noise.noise(HydrologyNoise.Salt.SOURCE_BANK_TOE,station,d*.25,4);
            rise+=(head-correction)*1.8*TerrainMath.smooth((toe-.25)/.5)*(1-TerrainMath.smooth(side/2));
        }
        if(head>0) {
            double warp=(noise.noise(HydrologyNoise.Salt.SOURCE_SPUR_BEND,station,bankSide*43,37)-.5)*19;
            double spur=noise.noise(HydrologyNoise.Salt.SOURCE_SPUR,station+warp+side*.6,side*.45+bankSide*71,8);
            rise+=Math.min(rise,8)*head*(spur*.7-.35)*TerrainMath.smooth(bankSide<0?side/1.25:(side-.75)/2.5);
        }
        double cut=level+1+rise+Math.min(11,rise*.28)*(joint-Math.pow(gully,3)*.8);
        double small=1-Math.abs(2*noise.noise(HydrologyNoise.Salt.CANYON_SMALL_GULLY,station+bend+joint*7,d*.55,12)-1);
        double detail=(1-head+head*TerrainMath.smooth((side-12)/20))*TerrainMath.smooth(side/4);
        cut-=detail*Math.min(7,rise*.35)*Math.pow(TerrainMath.smooth(small),3);
        if(head>0) {
            double cross=station+bankSide*83+noise.noise(HydrologyNoise.Salt.SOURCE_BRANCH_BEND,station,d,24)*12;
            double tributary=1-Math.abs(2*noise.noise(HydrologyNoise.Salt.SOURCE_BRANCH,cross,d*.25,9)-1);
            double relief=TerrainMath.smooth((side-3)/6)*head;
            cut-=relief*(2+3*noise.noise(HydrologyNoise.Salt.SOURCE_BRANCH_DEPTH,station,bankSide*41,22))
                    *Math.pow(TerrainMath.smooth(tributary),2);
        }
        double depth=Math.max(0,natural-cut);
        depth*=1-TerrainMath.smooth(side/8)*(1-TerrainMath.smooth(depth/12));
        double start=24+30*noise.noise(HydrologyNoise.Salt.CANYON_TRANSITION,station,bankSide*71,88);
        return natural-depth*(1-TerrainMath.smooth((d-start)/(reach-start)));
    }

    private double lakeBankHeight(double natural,double distance,double station) {
        var noise=samples.get().noise;
        double along=station+noise.fbm(HydrologyNoise.Salt.LAKE_GULLY_BEND,station,distance*.7,80)*32;
        double extent=125+170*noise.noise(HydrologyNoise.Salt.LAKE_BANK_EXTENT,along,distance*.2,112);
        double width=.35+1.3*noise.noise(HydrologyNoise.Salt.LAKE_BANK_WIDTH,along,distance*.2,48);
        double rise=(distance*.22+Math.pow(distance,1.45)*.025)*width;
        double branch=1-Math.abs(2*noise.noise(HydrologyNoise.Salt.LAKE_BANK_BRANCH,along,distance*.45,28)-1);
        double joint=noise.fbm(HydrologyNoise.Salt.LAKE_BANK_JOINT,along,distance*.75,40);
        double cut=lakeY+1+rise+Math.min(14,rise*.35)*joint-Math.min(22,rise*.7)*Math.pow(TerrainMath.smooth(branch),3);
        double small=1-Math.abs(2*noise.noise(HydrologyNoise.Salt.LAKE_SMALL_GULLY,along,distance*.35,12)-1);
        cut-=Math.min(12,rise*.8)*Math.pow(TerrainMath.smooth(small),3)*(1-TerrainMath.smooth((distance-72)/64));
        double depth=Math.max(0,natural-cut);
        depth*=TerrainMath.smooth(depth/16);
        double start=20+34*noise.noise(HydrologyNoise.Salt.LAKE_BANK_TRANSITION,along,distance*.15,96);
        return natural-depth*(1-TerrainMath.smooth((distance-start)/(extent-start)));
    }

    record WaterBank(double distance,int waterY) {}

    WaterBank waterBank(int x,int z) {
        double nearest=Double.POSITIVE_INFINITY;int level=Integer.MIN_VALUE;
        for(var a:cells.getOrDefault(key(x>>5,z>>5),List.of())) {
            double t=a.length2==0?0:Math.clamp(((x-a.ax)*a.dx+(z-a.az)*a.dz)/a.length2,0,1);
            double d=Math.hypot(x-a.ax-t*a.dx,z-a.az-t*a.dz)-(a.aw+t*(a.bw-a.aw));
            if(d<nearest){nearest=d;level=(int)Math.floor(a.ay+t*(a.by-a.ay));}
        }
        if(nearest<16) {
            nearest=Double.POSITIVE_INFINITY;
            level=Integer.MIN_VALUE;
            for(int dz=-3;dz<=3;dz++)for(int dx=-3;dx<=3;dx++) {
                double distance=Math.hypot(dx,dz);
                if(distance<4 && owner(x+dx,z+dz)>0) {
                    nearest=Math.min(nearest,distance);level=Math.max(level,water(x+dx,z+dz));
                }
            }
            if(nearest==Double.POSITIVE_INFINITY)nearest=4;
        }
        return new WaterBank(Math.max(0,nearest),level);
    }

    int surfaceY(int x,int z,TerrainBasis basis) {
        double height=height(x,z,basis),natural=samples.get().natural(basis,x,z);
        return (int)Math.round(Math.clamp(height,height<natural?-127:63,520));
    }

    double height(int x,int z,TerrainBasis basis) {
        var cache=samples.get();cache.use(x,z);
        double natural=cache.natural(basis,x,z),center=cache.height(this,basis,x,z);
        if(natural-center<.001 || water(x,z)!=Integer.MIN_VALUE)return center;
        double head=0;
        for(var segment:cells.getOrDefault(key(x>>5,z>>5),List.of())) {
            if(!segment.source || segment.station>=160)continue;
            double dx=segment.dx,dz=segment.dz,length=segment.length2;
            double t=Math.clamp(((x-segment.ax)*dx+(z-segment.az)*dz)/length,0,1);
            double distance=Math.hypot(x-segment.ax-t*dx,z-segment.az-t*dz);
            double along=1-TerrainMath.smooth((segment.station+t*segment.length-80)/80);
            head=Math.max(head,along*(1-TerrainMath.smooth((distance-16)/16)));
        }
        double rim=Double.NEGATIVE_INFINITY;
        for(int[] d:NEIGHBORS)for(int n=1;n<=4;n++) {
            int px=x+d[0]*n,pz=z+d[1]*n,id=owner(px,pz);
            if(id==sourceOwner && water(px,pz)-bed(px,pz)==1 && (n<=3 || head==0))return center;
            if(n==1 && id>0)rim=Math.max(rim,water(px,pz));
        }
        if(head>0)for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++) {
            int px=x+dx,pz=z+dz;
            if(owner(px,pz)==sourceOwner && water(px,pz)-bed(px,pz)==1)return center;
        }
        // 3×3の面に支えられる高さを残す。長く連なる薄い板も横幅で除く。
        double opened=Double.NEGATIVE_INFINITY;
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)
            opened=Math.max(opened,cache.minimum(this,basis,x+dx,z+dz));
        double result=Math.max(rim,opened);
        if(head>0) {
            double[] values=cache.neighbors;int n=0;
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)
                values[n++]=cache.height(this,basis,x+dx,z+dz);
            Arrays.sort(values);
            double filled=values[8]-values[0]<1.5 && values[4]>center?
                    Math.max(opened,Math.min(center+.75,values[4])):opened;
            result+=(filled-result)*head;
        }
        return Math.min(natural,result);
    }

    private static double join(double natural,double a,double b) {
        return join(natural,a,b,Double.NEGATIVE_INFINITY);
    }

    private static double join(double natural,double a,double b,double floor) {
        double width=Math.min(2*TerrainMath.smooth(Math.min(natural-a,natural-b)/4),Math.max(0,Math.min(a,b)-floor)*.75);
        if(width==0)return Math.min(a,b);
        double h=Math.max(0,1-Math.abs(a-b)/width);
        return Math.min(a,b)-h*h*width*.25;
    }
}
