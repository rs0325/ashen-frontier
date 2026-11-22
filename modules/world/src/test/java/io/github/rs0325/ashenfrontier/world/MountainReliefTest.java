package io.github.rs0325.ashenfrontier.world;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class MountainReliefTest {
    @Test
    void fixedSpacesKeepTheirEntrancesAndUndergroundEndsConnected() throws Exception {
        var map=map();
        try(var stream=getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/underground.json")) {
            var underground=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            for(var value:underground.getAsJsonArray("spaces")) {
                var space=value.getAsJsonObject();int x=space.get("x").getAsInt(),z=space.get("z").getAsInt();
                int length=space.get("depth").getAsInt()+16,step=Integer.signum(space.get("end_x").getAsInt()-x),start=x-step*length;
                int entrance=FixedTerrain.surfaceY(map,start,z),end=FixedTerrain.surfaceY(map,x,z)-space.get("depth").getAsInt();
                assertTrue(Math.abs(entrance-end)<=length,"入口と地下端の歩行距離: "+space.get("id"));
                int width=space.get("width").getAsInt();
                for(int i=1;i<length;i++) for(int dx=-width;dx<=width;dx++) for(int dz=-width;dz<=width;dz++) {
                    int ground=FixedTerrain.surfaceY(map,start+step*i+dx,z+dz);
                    assertTrue(entrance<=ground+i,"地表入口から下れる谷口: "+space.get("id")+" entry="+entrance+" limit="+(ground+i));
                    assertTrue(end<=ground+length-i,"地下端へつながる床: "+space.get("id"));
                }
            }
        }
    }
    @Test
    void fixedMassifsHavePeaksColsAndLowFoothills() throws Exception {
        var map=map();int peaks=0,maximum=0;
        for(int x=-13500;x<2000;x+=32) {
            int center=FixedTerrain.surfaceY(map,x,-11850);maximum=Math.max(maximum,center);
            if(center>240 && center>FixedTerrain.surfaceY(map,x-32,-11850) && center>=FixedTerrain.surfaceY(map,x+32,-11850)
                    && center-Math.min(FixedTerrain.surfaceY(map,x-300,-11850),FixedTerrain.surfaceY(map,x+300,-11850))>65)peaks++;
        }
        assertTrue(peaks>=12,"多数の峰と鞍部: "+peaks);
        var foothills=new java.util.ArrayList<Integer>();
        for(int x=-13000;x<-6000;x+=64)foothills.add(FixedTerrain.surfaceY(map,x,-10400));
        foothills.sort(Integer::compareTo);int median=foothills.get(foothills.size()/2);
        assertTrue(median>=85 && median<=130,"山すそを低く保つ: "+median);
        assertTrue(maximum>=480 && maximum-median>=350,"高い峰と山すそからの比高: "+maximum+" / "+(maximum-median));
        System.out.println("MASSIFS peaks="+peaks+" foothill_median="+median+" peak="+maximum+" relief="+(maximum-median));
    }

    @Test
    void fixedOceanFeaturesStaySubmergedAndFollowTheShelf() throws Exception {
        var map=map();var none=new OceanRelief(new com.google.gson.JsonArray());
        for(var value:index().getAsJsonArray("ocean_features")) {
            var feature=value.getAsJsonObject();var point=feature.getAsJsonArray("points").get(0).getAsJsonArray();
            int x=point.get(0).getAsInt(),z=point.get(1).getAsInt();
            if(feature.get("kind").getAsString().equals("trench")){x+=200;z-=13;}
            double coast=map.coastDistance(x,z);
            assertTrue(coast<0,"海底起伏の中心を海に置く");
            var shore=OceanRelief.shore(map,x,z);
            double height=map.ocean().height(map,x,z,-coast,shore),original=none.height(map,x,z,-coast,shore);
            assertTrue(height>=-120 && height<63,"海面下の固定海底起伏");
            assertTrue(Math.abs(height-original)>10,"海溝・海嶺・海山が見える起伏: "+feature.get("id")+" "+coast);
        }
        var shore=new OceanRelief.Shore(0,0,48,400);
        assertTrue(none.height(map,0,0,200,shore)>none.height(map,0,0,400,shore));
        assertTrue(none.height(map,0,0,400,shore)-none.height(map,0,0,800,shore)>50,"大陸斜面");
        assertTrue(Math.abs(none.height(map,0,0,1100,shore)-none.height(map,0,0,1500,shore))<1,"深海平原");
        String destination=System.getenv("ASHEN_MOUNTAIN_PREVIEW");
        if(destination!=null) {
            var rows=new StringBuilder("profile,x,z,station,height,coast,rock,cliff,shelf\n");
            for(var line:new int[][]{{0,-11000,-8192,-16300,-8192},{1,-8800,-13300,-8800,-15700},{2,-3000,0,4500,0},{3,7500,10000,14000,10000}}) {
                double length=Math.hypot(line[3]-line[1],line[4]-line[2]);int steps=(int)Math.ceil(length/4);
                for(int i=0;i<=steps;i++) {
                    int x=(int)Math.round(line[1]+(line[3]-line[1])*i/(double)steps),z=(int)Math.round(line[2]+(line[4]-line[2])*i/(double)steps);
                    var s=OceanRelief.shore(map,x,z);
                    rows.append(line[0]).append(',').append(x).append(',').append(z).append(',').append(length*i/steps).append(',')
                            .append(FixedTerrain.surfaceY(map,x,z)).append(',').append(map.coastDistance(x,z)).append(',').append(s.rock()).append(',')
                            .append(s.cliff()).append(',').append(s.shelfWidth()).append('\n');
                }
            }
            Files.createDirectories(Path.of(destination));Files.writeString(Path.of(destination).resolve("ocean-profiles.csv"),rows);
        }
    }
    @Test
    void elevationDistribution() throws Exception {
        var map = map();
        var groups = new java.util.TreeMap<String, java.util.ArrayList<Integer>>();
        var samples = new StringBuilder("x,z,height,region,mountain,coast\n");
        var shores = new StringBuilder("x,z,kind\n");
        for (int z = -16000; z < 16000; z += 64) for (int x = -16000; x < 16000; x += 64) {
            double mountain = map.mountainWeight(x,z), coast = map.coastDistance(x,z);
            int height = FixedTerrain.surfaceY(map,x,z), region = map.region(x,z);
            if (coast > 160 && region >= 1 && region <= 7 && !map.isProtected(x,z)) {
                String group = mountain > .6 ? "mountain_core" : mountain > .05 ? "mountain_flank"
                        : z < -10000 && z > -13800 ? "foothills" : region == 7 ? "highland" : "plain_hills";
                groups.computeIfAbsent(group,k -> new java.util.ArrayList<>()).add(height);
            }
            if (System.getenv("ASHEN_MOUNTAIN_PREVIEW") != null) samples.append(x).append(',').append(z).append(',').append(height)
                    .append(',').append(region).append(',').append(mountain).append(',').append(coast).append('\n');
            if (System.getenv("ASHEN_MOUNTAIN_PREVIEW") != null) {
                var shore=OceanRelief.shore(map,x,z);
                int kind=Math.abs(coast)>96?0:shore.cliff()>.4?4:shore.rock()>.65?3:shore.rock()>.3?2:1;
                shores.append(x).append(',').append(z).append(',').append(kind).append('\n');
            }
        }
        for (var entry : groups.entrySet()) {
            var values = entry.getValue(); values.sort(Integer::compareTo);
            if(entry.getKey().equals("mountain_core")) assertTrue(values.get(values.size()/2)>=260,"通常の鞍部も含めて山脈全体を持ち上げる");
            System.out.println("ELEVATION group="+entry.getKey()+" n="+values.size()+" min="+values.getFirst()+" p10="+values.get(values.size()/10)
                    +" median="+values.get(values.size()/2)+" p90="+values.get(values.size()*9/10)+" max="+values.getLast());
        }
        assertTrue(groups.size() == 5,"平野・高地・山すそ・山脈の標高分布");
        String destination = System.getenv("ASHEN_MOUNTAIN_PREVIEW");
        if (destination != null) {
            Files.createDirectories(Path.of(destination));
            Files.writeString(Path.of(destination).resolve("elevation.csv"),samples);
            Files.writeString(Path.of(destination).resolve("coast-types.csv"),shores);
            try(var stream=getClass().getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/underground.json")) {
                var underground=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                var entries=new StringBuilder("id,x,z,height\n");
                for(var value:underground.getAsJsonArray("deposits")) {
                    var deposit=value.getAsJsonObject();String id=deposit.get("id").getAsString();
                    if(id.startsWith("oil_") || deposit.get("vanilla").isJsonPrimitive() && deposit.get("vanilla").getAsBoolean())continue;
                    int x=deposit.get("x").getAsInt()-Math.max(96,deposit.get("depth").getAsInt()+24),z=deposit.get("z").getAsInt();
                    entries.append(id).append(',').append(x).append(',').append(z).append(',').append(FixedTerrain.surfaceY(map,x,z)).append('\n');
                }
                for(var value:underground.getAsJsonArray("spaces")) {
                    var space=value.getAsJsonObject();int x=space.get("x").getAsInt(),z=space.get("z").getAsInt();
                    x-=Integer.signum(space.get("end_x").getAsInt()-x)*(space.get("depth").getAsInt()+16);
                    entries.append(space.get("id").getAsString()).append("_entry,").append(x).append(',').append(z).append(',').append(FixedTerrain.surfaceY(map,x,z)).append('\n');
                }
                Files.writeString(Path.of(destination).resolve("entrances.csv"),entries);
            }
        }
    }
    private static JsonObject index() throws Exception {
        try (var stream = MountainReliefTest.class.getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/index.json")) {
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    static FixedMapData map() throws Exception {
        try (var stream = MountainReliefTest.class.getResourceAsStream("/data/ashen_frontier/fixed_map/earth_001/manifest.json")) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            return FixedMapData.require(new MapVersion("earth_001", 1, hash));
        }
    }

    @Test
    void mountainProfiles() throws Exception {
        var map = map();
        int steep = 0, sampleCount = 0, peak = 0, branchPeak = 0;
        for (int z = -15000; z <= -10800; z += 16) for (int x = -14000; x <= 3000; x += 16) {
            int height = FixedTerrain.surfaceY(map, x, z);
            peak = Math.max(peak, height);
            if (z < -13000 && x > -3800 && x < -2000) branchPeak = Math.max(branchPeak, height);
            if (map.mountainWeight(x,z) > .3 && !map.isProtected(x,z)) {
                double slope = Math.abs(height-FixedTerrain.surfaceY(map,x,z+16))/16.0;
                if (slope >= .3) steep++;
                sampleCount++;
            }
        }
        assertTrue(peak >= 460 && peak <= 520, "固定主脈の峰と建築余地: " + peak);
        assertTrue(branchPeak >= 210, "分岐山脈の峰: " + branchPeak);
        assertTrue(steep > sampleCount*.04, "山脈の険しい斜面: " + steep + "/" + sampleCount);
        assertEquals(0,map.mountainWeight(-8500,-8192));
        assertEquals(0,map.mountainWeight(-4000,-14100));
        System.out.println("MOUNTAIN_RELIEF peak="+peak+" branch_peak="+branchPeak+" steep="+steep+" samples="+sampleCount);
        String destination = System.getenv("ASHEN_MOUNTAIN_PREVIEW");
        if (destination == null) return;
        var output = Path.of(destination);
        Files.createDirectories(output);
        var samples = new StringBuilder("x,z,height,raster,protected\n");
        for (int z = -15800; z <= -10200; z += 16) for (int x = -14400; x <= 3600; x += 16) {
            int height = FixedTerrain.surfaceY(map, x, z);
            assertTrue(height <= 520);
            samples.append(x).append(',').append(z).append(',').append(height).append(',').append(map.height(x,z)).append(',')
                    .append(map.isProtected(x,z)).append('\n');
        }
        Files.writeString(output.resolve("relief.csv"), samples);
        samples.setLength(0);
        samples.append("x,z,height,raster\n");
        for (int x : new int[]{-10000,-8000,-5500}) for (int z = -15800; z <= -10000; z++) {
            samples.append(x).append(',').append(z).append(',').append(FixedTerrain.surfaceY(map,x,z)).append(',').append(map.height(x,z)).append('\n');
        }
        Files.writeString(output.resolve("north-south.csv"), samples);
        samples.setLength(0);
        samples.append("id,station,x,z,height,raster\n");
        for (var value : index().getAsJsonArray("mountains")) {
            var range = value.getAsJsonObject(); var points = range.getAsJsonArray("points");
            double station = 0;
            for (int i = 1; i < points.size(); i++) {
                var a = points.get(i-1).getAsJsonArray(); var b = points.get(i).getAsJsonArray();
                double ax=a.get(0).getAsDouble(), az=a.get(1).getAsDouble(), dx=b.get(0).getAsDouble()-ax, dz=b.get(1).getAsDouble()-az;
                double length=Math.hypot(dx,dz); int steps=(int)Math.ceil(length/8);
                for (int j=0;j<steps;j++) {
                    int x=(int)Math.round(ax+dx*j/steps),z=(int)Math.round(az+dz*j/steps);
                    samples.append(range.get("id").getAsString()).append(',').append(station+length*j/steps).append(',').append(x).append(',').append(z).append(',')
                            .append(FixedTerrain.surfaceY(map,x,z)).append(',').append(map.height(x,z)).append('\n');
                }
                station+=length;
            }
        }
        Files.writeString(output.resolve("ridge.csv"),samples);
    }

    @Test
    void islandPeaksDescendNorthAndKeepTheSea() throws Exception {
        var map = map();
        int previous = 520, total = 0;
        for (var value : index().getAsJsonArray("mountain_islands")) {
            var island = value.getAsJsonObject();
            int peak = 0;
            for (var row : island.getAsJsonArray("pixel_spans_y_x0_x1ex")) {
                var span = row.getAsJsonArray();
                int z = MapCoordinates.MIN + span.get(0).getAsInt() * 16;
                for (int px = span.get(1).getAsInt(); px < span.get(2).getAsInt(); px++) {
                    int x = MapCoordinates.MIN + px * 16;
                    assertEquals(3, map.geologyProfile(x,z));
                    peak = Math.max(peak,FixedTerrain.surfaceY(map,x,z));
                    total++;
                }
            }
            assertTrue(peak <= 213 && peak >= island.get("peak_y").getAsInt()-2,"分割後の丸い島の峰: "+peak);
            previous = peak;
            System.out.println("MOUNTAIN_ISLAND id="+island.get("id")+" peak="+peak+" summit="+island.get("summit_xz"));
        }
        assertEquals(577,total,"元の2島の内側の水路だけを除く");
        int shoreline = 0;
        String destination = System.getenv("ASHEN_MOUNTAIN_PREVIEW");
        var samples = new StringBuilder("x,z,height,water,coast,geology\n");
        for (int z = -15360; z <= -13600; z += 4) for (int x = -5600; x <= -4200; x += 4) {
            double coast = map.coastDistance(x,z);
            int height = FixedTerrain.surfaceY(map,x,z);
            if (coast < 0) {
                assertEquals(63,map.waterSurface(x,z));
                assertTrue(height <= 62,"島の間の海を陸にしない");
            }
            if (Math.abs(coast) < 2) {
                assertTrue(height<=70,"岩壁の直前の水際は海面へ接続する");
                shoreline++;
            }
            if (destination != null) samples.append(x).append(',').append(z).append(',').append(height).append(',').append(map.waterSurface(x,z))
                    .append(',').append(coast).append(',').append(map.geologyProfile(x,z)).append('\n');
        }
        assertTrue(shoreline>100,"島の水際の検査数");
        if (destination != null) {
            var output = Path.of(destination);
            Files.createDirectories(output);
            Files.writeString(output.resolve("islands.csv"),samples);
            samples.setLength(0);
            samples.append("x,z,height,water,station\n");
            var points = new java.util.ArrayList<int[]>();
            for (var value : index().getAsJsonArray("mountain_islands")) {
                var point = value.getAsJsonObject().getAsJsonArray("summit_xz");
                points.add(new int[]{point.get(0).getAsInt(),point.get(1).getAsInt()});
            }
            double station = 0;
            for (int i=1;i<points.size();i++) {
                var a=points.get(i-1);var b=points.get(i);
                double length=Math.hypot(b[0]-a[0],b[1]-a[1]);int steps=(int)Math.ceil(length/2);
                for(int j=0;j<=steps;j++) {
                    int x=(int)Math.round(a[0]+(b[0]-a[0])*j/(double)steps),z=(int)Math.round(a[1]+(b[1]-a[1])*j/(double)steps);
                    samples.append(x).append(',').append(z).append(',').append(FixedTerrain.surfaceY(map,x,z)).append(',')
                            .append(map.waterSurface(x,z)).append(',').append(station+length*j/steps).append('\n');
                }
                station+=length;
            }
            Files.writeString(output.resolve("archipelago-profile.csv"),samples);
        }
    }

    @Test
    void requiredWalkwaysReachMiningAndBothPasses() throws Exception {
        var map = map();
        int count=0;
        for (var value:index().getAsJsonArray("walkways")) {
            var walkway=value.getAsJsonObject();var points=walkway.getAsJsonArray("points_x_z_y");
            int previous=Integer.MIN_VALUE;
            for (int i=1;i<points.size();i++) {
                var a=points.get(i-1).getAsJsonArray();var b=points.get(i).getAsJsonArray();
                double ax=a.get(0).getAsDouble(),az=a.get(1).getAsDouble(),dx=b.get(0).getAsDouble()-ax,dz=b.get(1).getAsDouble()-az;
                int steps=Math.max(1,(int)Math.ceil(Math.hypot(dx,dz)));
                for(int j=0;j<=steps;j++) {
                    int x=(int)Math.round(ax+dx*j/steps),z=(int)Math.round(az+dz*j/steps);
                    int height=FixedTerrain.surfaceY(map,x,z);
                    assertTrue(previous==Integer.MIN_VALUE || Math.abs(height-previous)<=1,"徒歩経路の段差: "+walkway.get("id")+" "+x+","+z+" "+previous+" -> "+height+" target="+map.walkways().sample(x,z));
                    assertEquals(Integer.MIN_VALUE,map.waterSurface(x,z),"徒歩経路を水没させない");
                    assertTrue(map.hazardProtected(x,z),"歩行路に粉雪を置かない");
                    previous=height;count++;
                }
            }
        }
        assertTrue(count>8000,"鉱業地帯から寒冷拠点までの徒歩経路");
        for(var range:index().getAsJsonArray("mountains")) for(var saddle:range.getAsJsonObject().getAsJsonArray("passes")) {
            var position=saddle.getAsJsonObject().getAsJsonArray("xz");
            assertTrue(map.walkways().sample(position.get(0).getAsInt(),position.get(1).getAsInt()).distance()<4,"主脈・分岐を越える峠");
        }
        System.out.println("MOUNTAIN_WALKWAY samples="+count);
    }
}
