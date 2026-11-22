package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;

/** 固定した海底地形と、背後の高地に連続する海岸断面。 */
final class OceanRelief {
    record Shore(double rock, double cliff, double beachWidth, double shelfWidth) {}
    private record Feature(double[][] points, double width, double relief, boolean seamount, double length) {}
    private final List<Feature> features;

    OceanRelief(JsonArray definitions) {
        var features = new ArrayList<Feature>();
        for (var value : definitions) {
            var definition = value.getAsJsonObject();
            var source = definition.getAsJsonArray("points");
            double[][] points = new double[source.size()][2];
            for (int i = 0; i < points.length; i++) {
                points[i][0] = source.get(i).getAsJsonArray().get(0).getAsDouble();
                points[i][1] = source.get(i).getAsJsonArray().get(1).getAsDouble();
            }
            double length=0;
            for(int i=1;i<points.length;i++)length+=Math.hypot(points[i][0]-points[i-1][0],points[i][1]-points[i-1][1]);
            features.add(new Feature(points, definition.get("width_blocks").getAsDouble(),
                    definition.get("relief_blocks").getAsDouble() * (definition.get("kind").getAsString().equals("trench") ? -1 : 1),
                    definition.get("kind").getAsString().equals("seamount"),length));
        }
        this.features = List.copyOf(features);
    }

    static Shore shore(FixedMapData map, int x, int z) {
        double back = Math.max(Math.max(map.height((double)x - 128,z), map.height((double)x + 128,z)),
                Math.max(map.height((double)x,z - 128), map.height((double)x,z + 128)));
        double exposure = TerrainMath.noise(map.seed(), "shore_exposure", x,z,384);
        double rock = Math.max(TerrainMath.smooth((back - 95)/65), TerrainMath.smooth((exposure - .60)/.30)*.7);
        double cliff = rock * TerrainMath.smooth((back - 115)/75);
        return new Shore(rock,cliff,(24 + 48*(1-exposure))*(1-.85*rock),
                (300 + 260*(1-exposure))*(1-.75*rock));
    }

    static double shoreHeight(double coast, Shore shore) {
        return 63 + coast*(.08 + .16*shore.rock)
                + 22*shore.cliff*TerrainMath.smooth((coast-8)/28);
    }

    double height(FixedMapData map, int x, int z, double distance, Shore shore) {
        double depth = 12*TerrainMath.smooth(distance/shore.shelfWidth)
                + 88*TerrainMath.smooth((distance-shore.shelfWidth)/(360+shore.shelfWidth*.5));
        double detail = (TerrainMath.noise(map.seed(),"abyssal",x,z,768)-.5)*6
                + (TerrainMath.noise(map.seed(),"shelf_ripples",x,z,96)-.5)*2;
        double height = 63-depth+detail*TerrainMath.smooth(distance/96);
        for (var feature : features) {
            double px=x,pz=z;
            if(feature.relief<0) {
                px+=(TerrainMath.noise(map.seed(),"trench_axis_x",x,z,192)-.5)*18;
                pz+=(TerrainMath.noise(map.seed(),"trench_axis_z",x,z,192)-.5)*18;
            }
            double nearest = Math.hypot(px-feature.points[0][0],pz-feature.points[0][1]);
            double station=0,nearestStation=0;
            for (int i=1; i<feature.points.length; i++) {
                var a=feature.points[i-1];var b=feature.points[i];
                double dx=b[0]-a[0],dz=b[1]-a[1];
                double projection=((px-a[0])*dx+(pz-a[1])*dz)/(dx*dx+dz*dz);
                double t=Math.clamp(projection,0,1),length=Math.hypot(dx,dz);
                double d=Math.hypot(px-a[0]-t*dx,pz-a[1]-t*dz);
                if(d<=nearest){nearest=d;nearestStation=station+projection*length;}
                station+=length;
            }
            if (nearest < feature.width*(feature.relief<0?3:2)) {
                if (feature.relief < 0) {
                    double tip=TerrainMath.smooth(Math.min(nearestStation,feature.length-nearestStation)/900);
                    if(tip==0)continue;
                    double width=feature.width*(.65+.7*TerrainMath.noise(map.seed(),"trench_width",x,z,128))*tip;
                    double coastWeight=TerrainMath.smooth(distance/320);
                    double margin=(1-TerrainMath.smooth((nearest-width)/width))*tip;
                    height += (Math.min(height,-37)-height)*margin*coastWeight;
                    double wall=Math.pow(Math.max(0,1-nearest/width),.38);
                    wall += (TerrainMath.noise(map.seed(),"trench_ledge",x,z,18)-.5)*.25*wall*(1-wall);
                    double depthTip=TerrainMath.smooth(Math.min(nearestStation,feature.length-nearestStation)/160);
                    double floor=-37+feature.relief*depthTip+12*TerrainMath.noise(map.seed(),"trench_floor",x,z,72);
                    height += (Math.min(height,floor)-height)*wall*coastWeight;
                } else if(feature.seamount) {
                    double r=nearest/feature.width;
                    double profile=Math.pow(1-TerrainMath.smooth(r),2);
                    double angle=Math.atan2(z-feature.points[0][1],x-feature.points[0][0]);
                    double relief=Math.sin(angle*5+nearest/72)*24+TerrainMath.fbm(map.seed(),"seamount_relief",x,z,64)*40;
                    height += (feature.relief*profile+relief*profile*(1-profile))
                            *TerrainMath.smooth((distance-shore.shelfWidth)/200);
                } else {
                    double shape=Math.exp(-3*nearest*nearest/(feature.width*feature.width));
                    height += feature.relief*shape*TerrainMath.smooth((distance-shore.shelfWidth)/200);
                }
            }
        }
        return Math.clamp(height,-120,62);
    }
}
