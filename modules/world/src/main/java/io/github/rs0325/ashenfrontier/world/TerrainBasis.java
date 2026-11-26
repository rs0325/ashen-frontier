package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** earth_002の骨格補間と、斜面方向に沿う決定的な小起伏。 */
final class TerrainBasis {
    private static final double[] COS={1,Math.sqrt(.5),0,-Math.sqrt(.5)};
    private static final double[] SIN={0,Math.sqrt(.5),1,Math.sqrt(.5)};
    private record Pin(int x,int z,double correction,double cos,double sin) {}
    private final int x0, z0, width, height;
    private final short[] ground;
    private final byte[] classes;
    private final float[][] grain;
    private final Map<Long,List<Pin>> pins=new HashMap<>();
    private final long seed;
    final MapVersion background;

    TerrainBasis(MapVersion version) {
        String root = "/data/ashen_frontier/fixed_map/" + version.mapVersion() + "/";
        try (var source = TerrainBasis.class.getResourceAsStream(root + "index.json");
             var metadata = TerrainBasis.class.getResourceAsStream(root + "manifest.json")) {
            var manifest = JsonParser.parseString(new String(metadata.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (LayoutJson.integer(manifest,"terrain_basis_schema_version") != 1
                    || !manifest.get("stage").getAsString().equals("D2")
                    || !manifest.getAsJsonObject("files").has("landform.png"))
                throw new IllegalStateException("骨格のmanifest・分類資産が不正です");
            if (LayoutJson.integer(manifest,"runtime_relief_version") != 1)
                throw new IllegalStateException("実行時起伏の版が一致しません");
            seed=manifest.get("fixed_seed").getAsLong();
            var index = JsonParser.parseString(new String(source.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var definition = index.getAsJsonObject("terrain_basis");
            if (definition.get("schema_version").getAsInt() != 1 || !definition.get("stage").getAsString().equals("D1")
                    || LayoutJson.integer(definition,"runtime_relief_version") != 1)
                throw new IllegalStateException("骨格のschema・制作段階が一致しません");
            background = new MapVersion(definition.get("background_map").getAsString(), 1, definition.get("background_manifest_sha256").getAsString());
            if (!background.mapVersion().equals("earth_001")) throw new IllegalStateException("継承元はearth_001です");
            BundledMaps.require(background);
            var bounds = definition.getAsJsonArray("tile_bounds");
            int pz = bounds.get(0).getAsInt(), px = bounds.get(2).getAsInt();
            height = bounds.get(1).getAsInt() - pz; width = bounds.get(3).getAsInt() - px;
            x0 = px*16-16384; z0 = pz*16-16384;
            ground = new short[width*height]; classes = new byte[ground.length];
            try (var hs = TerrainBasis.class.getResourceAsStream(root + "height.png");
                 var cs = TerrainBasis.class.getResourceAsStream(root + "landform.png")) {
                var image = ImageIO.read(hs); var classification = ImageIO.read(cs);
                if (image.getWidth() != 2048 || image.getHeight() != 2048 || image.getSampleModel().getSampleSize(0) != 16
                        || classification.getWidth() != 2048 || classification.getHeight() != 2048 || classification.getSampleModel().getSampleSize(0) != 8)
                    throw new IllegalStateException("骨格のPNG寸法・型が不正です");
                var heights = image.getRaster(); var ids = classification.getRaster();
                for (int z=0; z<height; z++) for (int x=0; x<width; x++) {
                    int value = heights.getSample(px+x,pz+z,0), at=z*width+x;
                    if (value>639) throw new IllegalStateException("骨格標高が範囲外です");
                    ground[at]=(short)(value-64); classes[at]=(byte)ids.getSample(px+x,pz+z,0);
                }
                image.flush(); classification.flush();
            }
            grain=new float[4][ground.length];
            var layout=BundledMaps.landform(version);
            var axes=new HashMap<Integer,double[]>();
            for (var unit:layout.units().values()) {
                double xx=0,zz=0,xz=0;
                if(unit.controls().size()>1) {
                    var a=unit.controls().getFirst();var b=unit.controls().getLast();
                    xx=(double)(b.x()-a.x())*(b.x()-a.x());zz=(double)(b.z()-a.z())*(b.z()-a.z());
                    xz=(double)(b.x()-a.x())*(b.z()-a.z());
                } else if(!unit.boundary().isEmpty()) {
                    double cx=unit.boundary().stream().mapToDouble(p -> p.x()).average().orElseThrow();
                    double cz=unit.boundary().stream().mapToDouble(p -> p.z()).average().orElseThrow();
                    for(var p:unit.boundary()) {xx+=(p.x()-cx)*(p.x()-cx);zz+=(p.z()-cz)*(p.z()-cz);xz+=(p.x()-cx)*(p.z()-cz);}
                } else {xx=1;zz=2;xz=.6;}
                double angle=.5*Math.atan2(2*xz,xx-zz),total=0;
                double[] weights=new double[4];
                for(int i=0;i<4;i++) {weights[i]=.16+Math.pow(Math.abs(COS[i]*Math.cos(angle)+SIN[i]*Math.sin(angle)),4);total+=weights[i];}
                for(int i=0;i<4;i++)weights[i]/=total;
                axes.put(unit.id(),weights);
            }
            for(int at=0;at<ground.length;at++) {
                var unit=layout.units().get(Byte.toUnsignedInt(classes[at]));
                if(unit==null)continue;
                double amplitude=switch(unit.kind()) {
                    case "range" -> 20;case "massif" -> 18;case "hills" -> 8;
                    case "plateau" -> 5;case "basin","lowland" -> 2.6;
                    default -> 1.8+18*TerrainMath.smooth((ground[at]-110)/200.0);
                };
                for(int i=0;i<4;i++)grain[i][at]=(float)(amplitude*axes.get(unit.id())[i]);
            }
            for(int i=0;i<4;i++)grain[i]=smoothGrain(grain[i]);
            for(var unit:layout.units().values()) if(!unit.kind().equals("detail")) for(var p:unit.controls()) {
                double angle=0;
                if(unit.controls().size()>1) {
                    var a=unit.controls().getFirst();var b=unit.controls().getLast();angle=Math.atan2(b.z()-a.z(),b.x()-a.x());
                }
                var pin=new Pin(p.x(),p.z(),roughHeight(p.x(),p.z())-baseHeight(p.x(),p.z()),Math.cos(angle),Math.sin(angle));
                for(int z=(p.z()-96)>>7;z<=(p.z()+96)>>7;z++)for(int x=(p.x()-96)>>7;x<=(p.x()+96)>>7;x++)
                    pins.computeIfAbsent(key(x,z),ignored -> new ArrayList<>()).add(pin);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("固定マップ版 earth_002: 骨格を読めません", e);
        }
    }

    int classification(int x, int z) {
        int px=Math.floorDiv(x-x0,16), pz=Math.floorDiv(z-z0,16);
        return px<0 || pz<0 || px>=width || pz>=height ? 0 : Byte.toUnsignedInt(classes[pz*width+px]);
    }

    boolean contains(double x, double z) { return classification((int)Math.floor(x),(int)Math.floor(z)) != 0; }

    double baseHeight(double x, double z) {
        double px=Math.clamp((x-x0)/16,0,width-1), pz=Math.clamp((z-z0)/16,0,height-1);
        int ix=(int)px, iz=(int)pz;
        return TerrainMath.cubic(row(ix,iz-1,px-ix),row(ix,iz,px-ix),row(ix,iz+1,px-ix),row(ix,iz+2,px-ix),pz-iz);
    }

    double height(double x,double z) {
        double value=roughHeight(x,z);
        for(var p:pins.getOrDefault(key(((int)Math.floor(x))>>7,((int)Math.floor(z))>>7),List.of())) {
            double dx=x-p.x,dz=z-p.z;
            double along=(dx*p.cos+dz*p.sin)/96,across=(-dx*p.sin+dz*p.cos)/48;
            value-=p.correction*(1-TerrainMath.smooth(Math.hypot(along,across)));
        }
        return value;
    }

    private double roughHeight(double x,double z) {
        double px=Math.clamp((x-x0)/16,0,width-1),pz=Math.clamp((z-z0)/16,0,height-1);
        int ix=(int)px,iz=(int)pz;double tx=px-ix,tz=pz-iz;
        double a=row(ix,iz-1,tx),b=row(ix,iz,tx),c=row(ix,iz+1,tx),d=row(ix,iz+2,tx);
        double base=TerrainMath.cubic(a,b,c,d,tz);
        double sx=(baseHeight(x+64,z)-baseHeight(x-64,z))/128;
        double sz=(baseHeight(x,z+64)-baseHeight(x,z-64))/128,slope=Math.hypot(sx,sz);
        double blend=TerrainMath.smooth(slope/.4),total=0,aspectTotal=0,result=0;
        double wx=x+(TerrainMath.noise(seed+61,"basis_warp_x",x,z,192)-.5)*96
                +(TerrainMath.noise(seed+67,"basis_warp_fine_x",x,z,80)-.5)*24;
        double wz=z+(TerrainMath.noise(seed+71,"basis_warp_z",x,z,176)-.5)*96
                +(TerrainMath.noise(seed+73,"basis_warp_fine_z",x,z,96)-.5)*24;
        double bend=(TerrainMath.noise(seed,"basis_bend",wx,wz,64)-.5)*24;
        double erosion=.45+TerrainMath.noise(seed+79,"basis_erosion",wx,wz,112);
        for(int i=0;i<4;i++) {
            double dot=slope>0?(sx*COS[i]+sz*SIN[i])/slope:0;
            aspectTotal+=.16+Math.pow(Math.abs(dot),4);
            total+=grain(i,ix,iz,tx,tz);
        }
        for(int i=0;i<4;i++) {
            double dot=slope>0?(sx*COS[i]+sz*SIN[i])/slope:0;
            double weight=grain(i,ix,iz,tx,tz)*(1-blend)+total*blend*(.16+Math.pow(Math.abs(dot),4))/aspectTotal;
            double along=wx*COS[i]+wz*SIN[i],across=-wx*SIN[i]+wz*COS[i];
            double joint=TerrainMath.noise(seed,"basis_joint",across+bend,along*.65,32)*2-1;
            double cleft=TerrainMath.noise(seed+19,"basis_cleft",across+bend+joint*5,along*.65,24)*2-1;
            double ridge=1-Math.sqrt(joint*joint+.012),gully=1-Math.sqrt(cleft*cleft+.025);
            result+=weight*((ridge-.65)*.7-(gully*gully-.40)*.8)*erosion;
        }
        double gentle=1-TerrainMath.smooth((slope-.12)/.30);
        double rill=TerrainMath.noise(seed+37,"basis_rill",.8*x+.6*z,(-.6*x+.8*z)*.37,12)*2-1;
        double cross=TerrainMath.noise(seed+43,"basis_cross",-.6*x+.8*z,(.8*x+.6*z)*.43,21)*2-1;
        rill=1-Math.sqrt(rill*rill+.04);cross=1-Math.sqrt(cross*cross+.04);
        result-=Math.min(total,8)*gentle*(rill*rill*1.2+cross*cross*.75);
        result+=total*(TerrainMath.noise(seed,"basis_grit",.8*x+.6*z,-.6*x+.8*z,8)-.5)*.12;
        double strength=(.65+.35*TerrainMath.smooth(slope/.7))*(1+.35*TerrainMath.smooth((base-180)/200));
        strength*=TerrainMath.smooth((base-63)/24);
        if(result>0)strength*=Math.clamp((520-base)/32,0,1);
        return base+result*strength;
    }

    private double grain(int direction,int x,int z,double tx,double tz) {
        int a=z*width+x,b=Math.min(height-1,z+1)*width+x,dx=Math.min(width-1,x+1)-x;
        var values=grain[direction];
        return TerrainMath.bilinear(values[a],values[a+dx],values[b],values[b+dx],tx,tz);
    }

    private float[] smoothGrain(float[] values) {
        for(int pass=0;pass<4;pass++) {
            float[] next=new float[values.length];
            for(int z=0;z<height;z++)for(int x=0;x<width;x++) {
                int at=z*width+x;
                next[at]=(values[at]*4+values[z*width+Math.max(0,x-1)]+values[z*width+Math.min(width-1,x+1)]
                        +values[Math.max(0,z-1)*width+x]+values[Math.min(height-1,z+1)*width+x])/8;
            }
            values=next;
        }
        return values;
    }

    private static long key(int x,int z) {return ((long)x<<32)^(z&0xffffffffL);}

    private double row(int x, int z, double t) {
        int at=Math.clamp(z,0,height-1)*width;
        return TerrainMath.cubic(ground[at+Math.max(0,x-1)],ground[at+x],ground[at+Math.min(width-1,x+1)],ground[at+Math.min(width-1,x+2)],t);
    }
}
