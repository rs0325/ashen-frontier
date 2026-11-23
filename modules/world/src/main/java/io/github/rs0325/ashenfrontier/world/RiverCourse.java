package io.github.rs0325.ashenfrontier.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 承認済み中心線から水平な淵と短い早瀬を持つ距離場を復元する。 */
final class RiverCourse {
    record Sample(double distance, double surface, int x, int z, double riffle, double width, double bank, double depth, double grade, double station, boolean tributary) {}
    private record Segment(double ax, double az, double ay, double bx, double bz, double by, double station, double bend, boolean tributary) {}
    private record Drop(double station) {}
    private final Map<Long, List<Segment>> cells;
    private final Map<Long, List<Drop>> drops;
    private final long seed;

    RiverCourse(double[][] points, long seed) {
        this.seed = seed;
        var controls = new ArrayList<double[]>();
        for (int i = 0; i < points.length; i += 8) controls.add(points[i]);
        controls.add(points[points.length - 1]);
        var cells = new HashMap<Long, List<Segment>>();
        var drops = new HashMap<Long, List<Drop>>();
        var junctions = new ArrayList<Segment>();
        double station = 0;
        for (int i = 0; i < controls.size() - 1; i++) {
            double[] a = controls.get(Math.max(0, i - 1)), b = controls.get(i),
                    c = controls.get(i + 1), d = controls.get(Math.min(controls.size() - 1, i + 2));
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(c[0] - b[0], c[1] - b[1]) / 4));
            double px = b[0], pz = b[1], py = b[2];
            for (int step = 1; step <= steps; step++) {
                double t = step / (double) steps;
                double x = TerrainMath.cubic(a[0], b[0], c[0], d[0], t);
                double z = TerrainMath.cubic(a[1], b[1], c[1], d[1], t);
                double y = b[2] + t * (c[2] - b[2]);
                double length = Math.hypot(x - px, z - pz);
                double ux=c[0]-b[0],uz=c[1]-b[1],vx=b[0]-a[0],vz=b[1]-a[1],wx=d[0]-c[0],wz=d[1]-c[1];
                double turnA=(vx*uz-vz*ux)/Math.max(1,Math.hypot(ux,uz)*Math.hypot(vx,vz));
                double turnB=(ux*wz-uz*wx)/Math.max(1,Math.hypot(ux,uz)*Math.hypot(wx,wz));
                double bend=Math.clamp((turnA+(turnB-turnA)*t)*4,-1,1);
                var segment = new Segment(px, pz, py, x, z, y, station, bend, false);
                index(cells,drops,segment);
                for(int join:new int[]{420,1050,2000})if(station<=join && station+length>join)junctions.add(segment);
                px = x; pz = z; py = y;
                station += length;
            }
        }
        for(int i=0;i<junctions.size();i++) {
            var join=junctions.get(i);double dx=join.bx-join.ax,dz=join.bz-join.az,length=Math.hypot(dx,dz);
            double side=i%2==0?1:-1,run=160+i*40,across=90+i*30;
            double ax=join.ax-dx/length*run-dz/length*across*side;
            double az=join.az-dz/length*run+dx/length*across*side;
            double branchLength=Math.hypot(join.ax-ax,join.az-az),base=Math.ceil(join.ay-1e-8)-.75;
            int steps=(int)Math.ceil(branchLength/4);
            double px=ax,pz=az;
            for(int step=0;step<steps;step++) {
                double t=step/(double)steps,u=(step+1)/(double)steps;
                double bend=Math.sin(Math.PI*u)*(Math.sin(u*7+i)*9+TerrainMath.fbm(seed,"brook_bend",ax+(join.ax-ax)*u,az+(join.az-az)*u,48)*14);
                bend+=Math.sin(Math.PI*u)*TerrainMath.fbm(seed,"brook_fine_bend",ax+(join.ax-ax)*u,az+(join.az-az)*u,12)*3;
                double bx=ax+(join.ax-ax)*u-(join.az-az)/branchLength*bend;
                double bz=az+(join.az-az)*u+(join.ax-ax)/branchLength*bend;
                index(cells,drops,new Segment(px,pz,base+branchLength*(1-t)*.004,
                        bx,bz,base+branchLength*(1-u)*.004,
                        join.station-branchLength*(1-t),0,true));
                px=bx;pz=bz;
            }
        }
        cells.replaceAll((key, value) -> List.copyOf(value));
        this.cells = Map.copyOf(cells);
        drops.replaceAll((key, value) -> List.copyOf(value));
        this.drops = Map.copyOf(drops);
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static void index(Map<Long,List<Segment>> cells,Map<Long,List<Drop>> drops,Segment segment) {
        Drop drop=null;
        if(Math.ceil(segment.ay-1e-8)>Math.ceil(segment.by-1e-8)) {
            double crossing=(segment.ay-Math.ceil(segment.by-1e-8))/(segment.ay-segment.by);
            drop=new Drop(segment.station+Math.hypot(segment.bx-segment.ax,segment.bz-segment.az)*Math.clamp(crossing,0,1));
        }
        int radius=segment.tributary?144:256;
        for(int cx=(int)Math.floor((Math.min(segment.ax,segment.bx)-radius)/128);cx<=(int)Math.floor((Math.max(segment.ax,segment.bx)+radius)/128);cx++) {
            for(int cz=(int)Math.floor((Math.min(segment.az,segment.bz)-radius)/128);cz<=(int)Math.floor((Math.max(segment.az,segment.bz)+radius)/128);cz++) {
                cells.computeIfAbsent(key(cx,cz),ignored->new ArrayList<>()).add(segment);
                if(drop!=null)drops.computeIfAbsent(key(cx,cz),ignored->new ArrayList<>()).add(drop);
            }
        }
    }

    Sample sample(int x, int z) {
        double distance = 256 * 256, surface = 63, nearestX = x, nearestZ = z, station = 0, bank = 0, grade=63,side=0;
        double branchDistance=Double.POSITIVE_INFINITY,branchX=x,branchZ=z,branchGrade=63,branchStation=0;
        for (var segment : cells.getOrDefault(key(x >> 7, z >> 7), List.of())) {
            double dx = segment.bx - segment.ax, dz = segment.bz - segment.az;
            double length = dx * dx + dz * dz;
            double t = length == 0 ? 0 : Math.clamp(((x - segment.ax) * dx + (z - segment.az) * dz) / length, 0, 1);
            double sx = segment.ax + t * dx, sz = segment.az + t * dz;
            double squared = (x - sx) * (x - sx) + (z - sz) * (z - sz);
            if(segment.tributary) {
                if(squared<branchDistance){branchDistance=squared;branchX=sx;branchZ=sz;branchGrade=segment.ay+t*(segment.by-segment.ay);branchStation=segment.station+t*Math.sqrt(length);}
                continue;
            }
            if (squared < distance) {
                distance = squared;
                grade=segment.ay+t*(segment.by-segment.ay);
                surface = Math.ceil(grade - 1e-8);
                station = segment.station + t * Math.sqrt(length);
                nearestX = sx; nearestZ = sz;
                side=Math.signum(dx*(z-sz)-dz*(x-sx));
                bank=-Math.signum(dx*(z-sz)-dz*(x-sx))*segment.bend;
            }
        }
        if(distance==256*256)return new Sample(256,63,x,z,0,32,0,7,63,Double.NaN,false);
        double toDrop = 8;
        for (var drop : drops.getOrDefault(key(x >> 7, z >> 7), List.of())) {
            toDrop = Math.min(toDrop, Math.abs(station - drop.station));
        }
        double reach=TerrainMath.noise(seed,"river_reach",nearestX,nearestZ,320);
        double growth=TerrainMath.smooth((station-2400)/1800);
        double width=.75+.0006*station+1.5*TerrainMath.smooth((station-320)/200)
                +2*TerrainMath.smooth((station-900)/250)+3*TerrainMath.smooth((station-1800)/300);
        double depth=1+.5*TerrainMath.smooth((station-400)/600)+TerrainMath.smooth((station-1000)/1700);
        width+=(22+reach*26+TerrainMath.noise(seed,"river_width",nearestX,nearestZ,96)*8-width)*growth;
        depth+=(5+reach*4-depth)*growth;
        double mainDistance=Math.sqrt(distance),fine=TerrainMath.smooth(station/16)*(1-TerrainMath.smooth((station-700)/500));
        if(fine>0) {
            mainDistance=Math.abs(mainDistance*(side==0?1:side)+(TerrainMath.noise(seed,"brook_fine_edge",nearestX,nearestZ,6)-.5)*.7*fine);
            width=Math.max(.72,width+(TerrainMath.noise(seed,"brook_fine_width",nearestX,nearestZ,8)-.5)*.2*fine);
        }
        double branchWidth=branchDistance<144*144?.72+.25*TerrainMath.noise(seed,"brook_width",branchX,branchZ,6):.8;
        if(Math.sqrt(branchDistance)-branchWidth<mainDistance-width && branchDistance<144*144)
            return new Sample(Math.sqrt(branchDistance),Math.ceil(branchGrade-1e-8),(int)Math.round(branchX),(int)Math.round(branchZ),
                    1-TerrainMath.smooth(Math.min(branchGrade-Math.floor(branchGrade),Math.ceil(branchGrade)-branchGrade)/.032),branchWidth,0,1,branchGrade,branchStation,true);
        return new Sample(mainDistance, surface, (int) Math.round(nearestX), (int) Math.round(nearestZ),
                1 - TerrainMath.smooth(toDrop / 8),width,bank,depth,grade,station,false);
    }
}
