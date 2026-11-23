package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/** 版ごとに一度だけ読み込む配置図。配列と索引は構築後に変更・公開しない。 */
public final class FixedMapData {
    private static final Map<MapVersion, FixedMapData> LOADED = new HashMap<>();
    private static final int SIZE = 2048;
    private final short[] heights = new short[SIZE * SIZE];
    private final byte[][] categories = new byte[5][SIZE * SIZE];
    private final short[] waterSurface = new short[SIZE * SIZE];
    private final boolean[] protectedArea = new boolean[SIZE * SIZE];
    private final float[] coastDistance;
    private final float[] protectionDistance;
    private record Entrance(int x, int z) {}
    private record Facility(double x, double z, double radius) {}
    private final Map<Long, List<Entrance>> entrances;
    private final Map<Long, List<Entrance>> geologyAnchors;
    private final Set<Long> weatheredContacts;
    private final List<Facility> facilities;
    private final RiverCourse river;
    private final RiverCourse.Sample riverSource;
    private record Mountain(double[][] points, double radius, int peakY) {}
    private int terminationJoin, terminationTransition;
    private final List<Mountain> mountains;
    record IslandPeak(int x, int z, int y, double radius, double cos, double sin) {}
    record MountainIsland(int peakY, int inlandDepth, int x, int z, List<IslandPeak> peaks) {}
    private final Map<Integer, MountainIsland> mountainIslands;
    private final List<MountainIsland> islandCoasts;
    record IslandJunction(MountainIsland nw, MountainIsland ne, MountainIsland sw, MountainIsland se) {}
    private final Map<Integer, IslandJunction> islandJunctions;
    private final Map<Integer, Float> islandStraits;
    private final int straitMinX,straitMaxX,straitMinZ,straitMaxZ;
    private record SurfaceCorrection(int x,int z,double displacement) {}
    private final Map<Long,List<SurfaceCorrection>> surfaceCorrections;
    private final TerrainWalkways walkways;
    private final OceanRelief ocean;
    private final JsonObject geology;
    private final long seed;

    /** ハッシュ照合後にロードする。不正な画像・索引は版名を含む例外で停止する。 */
    public static synchronized FixedMapData require(MapVersion version) {
        return LOADED.computeIfAbsent(version, FixedMapData::new);
    }

    private FixedMapData(MapVersion version) {
        BundledMaps.require(version);
        String root = "/data/ashen_frontier/fixed_map/" + version.mapVersion() + "/";
        try {
            seed = json(root + "manifest.json").get("fixed_seed").getAsLong();
            String[] names = {"height", "region", "water", "soil", "geology", "coast_river"};
            for (int layer = 0; layer < names.length; layer++) {
                try (var stream = FixedMapData.class.getResourceAsStream(root + names[layer] + ".png")) {
                    var image = ImageIO.read(stream);
                    if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE
                            || image.getRaster().getNumBands() != 1
                            || image.getSampleModel().getSampleSize(0) != (layer == 0 ? 16 : 8)) {
                        throw new IllegalStateException("不正な配置図: " + names[layer]);
                    }
                    var raster = image.getRaster();
                    for (int z = 0; z < SIZE; z++) {
                        for (int x = 0; x < SIZE; x++) {
                            int value = raster.getSample(x, z, 0), at = z * SIZE + x;
                            if (layer == 0) {
                                if (value > 639) throw new IllegalStateException("標高が範囲外です");
                                heights[at] = (short) (value - 64);
                            } else categories[layer - 1][at] = (byte) value;
                        }
                    }
                    image.flush();
                }
            }
            var index = json(root + "index.json");
            var contacts=new HashSet<Long>();
            for(var value:index.getAsJsonArray("underground_weathered_contacts")) {
                var point=value.getAsJsonArray();contacts.add(key(point.get(0).getAsInt(),point.get(1).getAsInt()));
            }
            weatheredContacts=Set.copyOf(contacts);
            ocean = new OceanRelief(index.getAsJsonArray("ocean_features"));
            var islandPixels = new HashMap<Integer, MountainIsland>();
            var islandCoasts=new ArrayList<MountainIsland>();
            for (var value : index.getAsJsonArray("mountain_islands")) {
                var island = value.getAsJsonObject();
                var summit = island.getAsJsonArray("summit_xz");
                var peaks = new ArrayList<IslandPeak>();
                for (var definition : island.getAsJsonArray("summits")) {
                    var point = definition.getAsJsonObject();var xz = point.getAsJsonArray("xz");
                    double angle = Math.toRadians(point.get("axis_degrees").getAsDouble());
                    peaks.add(new IslandPeak(xz.get(0).getAsInt(),xz.get(1).getAsInt(),point.get("peak_y").getAsInt(),
                            point.get("radius_blocks").getAsDouble(),Math.cos(angle),Math.sin(angle)));
                }
                var peak = new MountainIsland(island.get("peak_y").getAsInt(), island.get("inland_depth_blocks").getAsInt(),
                        summit.get(0).getAsInt(), summit.get(1).getAsInt(),List.copyOf(peaks));
                islandCoasts.add(peak);
                for (var row : island.getAsJsonArray("pixel_spans_y_x0_x1ex")) {
                    var span = row.getAsJsonArray();
                    for (int x = span.get(1).getAsInt(); x < span.get(2).getAsInt(); x++) {
                        islandPixels.put(span.get(0).getAsInt() * SIZE + x, peak);
                    }
                }
            }
            var shorePixels = new HashMap<>(islandPixels);
            for (var entry : islandPixels.entrySet()) {
                int px = entry.getKey() % SIZE, pz = entry.getKey() / SIZE;
                for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                    int at = (pz + dz) * SIZE + px + dx;
                    var other = shorePixels.get(at);
                    var peak = entry.getValue();
                    double x = (px + dx) * 16 - 16384, z = (pz + dz) * 16 - 16384;
                    if (!islandPixels.containsKey(at) && (other == null
                            || Math.hypot(x - peak.x, z - peak.z) < Math.hypot(x - other.x, z - other.z)))
                        shorePixels.put(at, peak);
                }
            }
            mountainIslands = Map.copyOf(shorePixels);
            this.islandCoasts=List.copyOf(islandCoasts);
            var junctions=new HashMap<Integer,IslandJunction>();
            for(int at:shorePixels.keySet()) {
                var nw=shorePixels.get(at);var ne=shorePixels.get(at+1);
                var sw=shorePixels.get(at+SIZE);var se=shorePixels.get(at+SIZE+1);
                if(ne==null || sw==null || se==null || nw==ne && nw==sw && nw==se)continue;
                junctions.put(at,new IslandJunction(nw,ne,sw,se));
            }
            var mountains = new ArrayList<Mountain>();
            for (var value : index.getAsJsonArray("mountains")) {
                var mountain = value.getAsJsonObject();
                var source = mountain.getAsJsonArray("points");
                double[][] points = new double[source.size()][2];
                for (int i = 0; i < points.length; i++) {
                    points[i][0] = source.get(i).getAsJsonArray().get(0).getAsDouble();
                    points[i][1] = source.get(i).getAsJsonArray().get(1).getAsDouble();
                }
                mountains.add(new Mountain(points, mountain.get("radius_blocks").getAsDouble(),mountain.get("peak_y").getAsInt()));
            }
            this.mountains = List.copyOf(mountains);
            var termination = index.getAsJsonArray("mountains").get(0).getAsJsonObject().getAsJsonObject("termination");
            terminationJoin=termination.get("join_x").getAsInt();
            terminationTransition=termination.get("transition_blocks").getAsInt();
            walkways = new TerrainWalkways(index.getAsJsonArray("walkways"), seed);
            geology = json(root + "geology_profiles.json");
            if (!geology.get("map_version").getAsString().equals(version.mapVersion())
                    || geology.get("format_version").getAsInt() != version.formatVersion()
                    || !geology.get("profile_version").equals(index.get("geology_profile_version"))) {
                throw new IllegalStateException("地質定義の版が一致しません");
            }
            Arrays.fill(waterSurface, Short.MIN_VALUE);
            for (var entry : index.getAsJsonArray("water_bodies")) {
                var body = entry.getAsJsonObject();
                int bit = body.get("membership_bit").getAsInt();
                if (body.has("surface_y")) {
                    for (int i = 0; i < waterSurface.length; i++) {
                        if ((Byte.toUnsignedInt(categories[4][i]) & bit) != 0) waterSurface[i] = body.get("surface_y").getAsShort();
                    }
                } else {
                    for (var value : body.getAsJsonArray("surface_spans_y_x0_x1ex_y")) {
                        var span = value.getAsJsonArray();
                        for (int x = span.get(1).getAsInt(); x < span.get(2).getAsInt(); x++) {
                            waterSurface[span.get(0).getAsInt() * SIZE + x] = span.get(3).getAsShort();
                        }
                    }
                }
            }
            for (int i = 0; i < heights.length; i++) {
                int region = Byte.toUnsignedInt(categories[0][i]);
                int water = Byte.toUnsignedInt(categories[1][i]), soil = Byte.toUnsignedInt(categories[2][i]);
                if ((region > 7 && region != 255) || water > 8 || soil > 5
                        || !geology.getAsJsonObject("profiles").has(Integer.toString(Byte.toUnsignedInt(categories[3][i])))) {
                    throw new IllegalStateException("未定義の配置図カテゴリです");
                }
                if ((Byte.toUnsignedInt(categories[4][i]) & 14) != 0
                        && (water == 0 || waterSurface[i] == Short.MIN_VALUE)) {
                    throw new IllegalStateException("水域の品質・水面が不足しています");
                }
            }
            var facilities = new ArrayList<Facility>();
            for (var value : index.getAsJsonArray("facilities")) {
                var facility = value.getAsJsonObject();
                var xz = facility.getAsJsonArray("xz");
                facilities.add(new Facility(xz.get(0).getAsDouble(), xz.get(1).getAsDouble(),
                        facility.get("protection_radius").getAsDouble()));
                markProtection(xz.get(0).getAsDouble(), xz.get(1).getAsDouble(),
                        xz.get(0).getAsDouble(), xz.get(1).getAsDouble(), facility.get("protection_radius").getAsDouble());
            }
            this.facilities = List.copyOf(facilities);
            for (var value : index.getAsJsonArray("routes")) {
                var route = value.getAsJsonObject();
                var points = route.getAsJsonArray("points");
                for (int i = 1; i < points.size(); i++) {
                    var a = points.get(i - 1).getAsJsonArray();
                    var b = points.get(i).getAsJsonArray();
                    markProtection(a.get(0).getAsDouble(), a.get(1).getAsDouble(), b.get(0).getAsDouble(),
                            b.get(1).getAsDouble(), route.get("protection_radius").getAsDouble());
                }
            }
            for (var value : index.getAsJsonArray("walkways")) {
                var points = value.getAsJsonObject().getAsJsonArray("points_x_z_y");
                for (int i = 1; i < points.size(); i++) {
                    var a = points.get(i - 1).getAsJsonArray(); var b = points.get(i).getAsJsonArray();
                    markProtection(a.get(0).getAsDouble(), a.get(1).getAsDouble(), b.get(0).getAsDouble(), b.get(1).getAsDouble(), 8);
                }
            }
            var underground = UndergroundLayout.read(version);
            coastDistance = smoothDistance(distances(true));
            var straits=new HashMap<Integer,Float>();
            var activeJunctions=new HashMap<Integer,IslandJunction>();
            var criticalContacts=new HashSet<Set<MountainIsland>>();
            for(int at:junctions.keySet()) {
                int px=at%SIZE,pz=at/SIZE;
                int x=px*16+MapCoordinates.MIN,z=pz*16+MapCoordinates.MIN;
                var junction=junctions.get(at);
                for(int offset=2;offset<16;offset+=4) {
                    var a=offset<8?junction.nw:junction.sw;var b=offset<8?junction.ne:junction.se;
                    if(discontinuousIslandEdge(a,b,x+8,z+offset))criticalContacts.add(Set.of(a,b));
                    a=offset<8?junction.nw:junction.ne;b=offset<8?junction.sw:junction.se;
                    if(discontinuousIslandEdge(a,b,x+offset,z+8))criticalContacts.add(Set.of(a,b));
                }
            }
            for(int at:junctions.keySet()) {
                var junction=junctions.get(at);
                if(!criticalContacts.contains(Set.copyOf(List.of(junction.nw,junction.ne,junction.sw,junction.se))))continue;
                activeJunctions.put(at,junction);
                int px=at%SIZE,pz=at/SIZE;
                if(coastDistance(px*16+MapCoordinates.MIN+8,pz*16+MapCoordinates.MIN+8)<=0)continue;
                for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++) {
                    int cell=(pz+dz)*SIZE+px+dx;
                    float weight=(3-Math.abs(dx))*(3-Math.abs(dz))/9f;
                    straits.merge(cell,weight,Math::max);
                }
            }
            islandJunctions=Map.copyOf(activeJunctions);
            islandStraits=Map.copyOf(straits);
            straitMinX=(straits.keySet().stream().mapToInt(at->at%SIZE).min().orElseThrow()-1)*16+MapCoordinates.MIN;
            straitMaxX=(straits.keySet().stream().mapToInt(at->at%SIZE).max().orElseThrow()+1)*16+MapCoordinates.MIN;
            straitMinZ=(straits.keySet().stream().mapToInt(at->at/SIZE).min().orElseThrow()-1)*16+MapCoordinates.MIN;
            straitMaxZ=(straits.keySet().stream().mapToInt(at->at/SIZE).max().orElseThrow()+1)*16+MapCoordinates.MIN;
            protectionDistance = distances(false);
            var entrances = new HashMap<Long, List<Entrance>>();
            var geologyAnchors = new HashMap<Long, List<Entrance>>();
            for (var deposit : underground.deposits()) {
                addEntrance(geologyAnchors,deposit.x(),deposit.z());
                if (!deposit.id().startsWith("oil_") && !Boolean.TRUE.equals(deposit.vanilla())) {
                    addEntrance(entrances, deposit.x() - Math.max(96, deposit.depth() + 24), deposit.z());
                }
            }
            for (var space : underground.spaces()) {
                addEntrance(entrances, space.x() - Integer.signum(space.end_x() - space.x()) * (space.depth() + 16), space.z());
            }
            entrances.replaceAll((key, value) -> List.copyOf(value));
            this.entrances = Map.copyOf(entrances);
            this.geologyAnchors = Map.copyOf(geologyAnchors);
            smoothRegionHeights();
            // 元の河床を埋め戻し、連続した谷の断面で彫り直す。
            for (int i = 0; i < heights.length; i++) {
                if ((categories[4][i] & 2) != 0 && !protectedArea[i]) heights[i] = (short) Math.max(heights[i],waterSurface[i]+6);
            }
            var centerline = index.getAsJsonArray("water_bodies").get(1).getAsJsonObject()
                    .getAsJsonArray("centerline_pixel_x_y_surface");
            double[][] points = new double[centerline.size()][3];
            for (int i = 0; i < points.length; i++) {
                var point = centerline.get(i).getAsJsonArray();
                points[i][0] = MapCoordinates.MIN + point.get(0).getAsInt() * 16;
                points[i][1] = MapCoordinates.MIN + point.get(1).getAsInt() * 16;
                double coast = coastDistance((int) points[i][0], (int) points[i][1]);
                points[i][2] = Math.min(point.get(2).getAsDouble(), 63 + Math.max(0, coast - 80) / 32);
                double ground = Double.POSITIVE_INFINITY;
                for (int dz = -112; dz <= 112; dz += 16) for (int dx = -112; dx <= 112; dx += 16) {
                    ground = Math.min(ground, FixedTerrain.landHeight(this, (int) points[i][0] + dx, (int) points[i][1] + dz));
                }
                points[i][2] = Math.min(points[i][2], Math.max(63, Math.floor(ground) - 4));
                if (i > 0) points[i][2] = Math.min(points[i][2], points[i - 1][2]);
            }
            river = new RiverCourse(points, seed);
            riverSource=river.sample((int)points[0][0],(int)points[0][1]);
            var corrections=new HashMap<Long,List<SurfaceCorrection>>();
            for(var value:index.getAsJsonArray("underground_surface_controls")) {
                var control=value.getAsJsonArray();int x=control.get(0).getAsInt(),z=control.get(1).getAsInt();
                double displacement=control.get(2).getAsInt()-FixedTerrain.unconstrainedSurfaceY(this,x,z);
                var correction=new SurfaceCorrection(x,z,displacement);
                for(int cz=(z-96)>>7;cz<=((z+96)>>7);cz++)for(int cx=(x-96)>>7;cx<=((x+96)>>7);cx++)
                    corrections.computeIfAbsent(key(cx,cz),k->new ArrayList<>()).add(correction);
            }
            surfaceCorrections=Map.copyOf(corrections);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("固定マップ版 " + version.mapVersion() + ": 配置図を読めません", e);
        }
    }

    private float[] distances(boolean coast) {
        float[] values = new float[heights.length];
        Arrays.fill(values, coast ? 32768 : 128);
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            int i = z * SIZE + x;
            if (!coast) {
                if (protectedArea[i]) values[i] = 0;
            } else if ((x > 0 && (categories[0][i] == 0) != (categories[0][i - 1] == 0))
                    || (x < SIZE - 1 && (categories[0][i] == 0) != (categories[0][i + 1] == 0))
                    || (z > 0 && (categories[0][i] == 0) != (categories[0][i - SIZE] == 0))
                    || (z < SIZE - 1 && (categories[0][i] == 0) != (categories[0][i + SIZE] == 0))) values[i] = 8;
        }
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            int i = z * SIZE + x;
            if (x > 0) values[i] = Math.min(values[i], values[i - 1] + 16);
            if (z > 0) {
                values[i] = Math.min(values[i], values[i - SIZE] + 16);
                if (x > 0) values[i] = Math.min(values[i], values[i - SIZE - 1] + 22.627417f);
                if (x < SIZE - 1) values[i] = Math.min(values[i], values[i - SIZE + 1] + 22.627417f);
            }
        }
        for (int z = SIZE - 1; z >= 0; z--) for (int x = SIZE - 1; x >= 0; x--) {
            int i = z * SIZE + x;
            if (x < SIZE - 1) values[i] = Math.min(values[i], values[i + 1] + 16);
            if (z < SIZE - 1) {
                values[i] = Math.min(values[i], values[i + SIZE] + 16);
                if (x > 0) values[i] = Math.min(values[i], values[i + SIZE - 1] + 22.627417f);
                if (x < SIZE - 1) values[i] = Math.min(values[i], values[i + SIZE + 1] + 22.627417f);
            }
        }
        if (coast) for (int i = 0; i < values.length; i++) if (categories[0][i] == 0) values[i] = -values[i];
        return values;
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static void addEntrance(Map<Long, List<Entrance>> cells, int x, int z) {
        var entrance = new Entrance(x, z);
        for (int cx = (x - 48) >> 7; cx <= (x + 48) >> 7; cx++) {
            for (int cz = (z - 48) >> 7; cz <= (z + 48) >> 7; cz++) {
                cells.computeIfAbsent(key(cx, cz), ignored -> new ArrayList<>()).add(entrance);
            }
        }
    }

    private float[] smoothDistance(float[] values) {
        float[] horizontal = new float[values.length];
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            int i = z * SIZE + x;
            horizontal[i] = (values[z * SIZE + Math.max(0, x - 1)] + 2 * values[i]
                    + values[z * SIZE + Math.min(SIZE - 1, x + 1)]) / 4;
        }
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            values[z * SIZE + x] = (horizontal[Math.max(0, z - 1) * SIZE + x]
                    + 2 * horizontal[z * SIZE + x] + horizontal[Math.min(SIZE - 1, z + 1) * SIZE + x]) / 4;
        }
        return values;
    }

    private double interpolate(float[] values, double x, double z) {
        double px = Math.clamp((x - MapCoordinates.MIN) / 16, 0, SIZE - 1);
        double pz = Math.clamp((z - MapCoordinates.MIN) / 16, 0, SIZE - 1);
        int ix = (int) px, iz = (int) pz, nx = Math.min(ix + 1, SIZE - 1), nz = Math.min(iz + 1, SIZE - 1);
        return TerrainMath.bilinear(values[iz * SIZE + ix], values[iz * SIZE + nx],
                values[nz * SIZE + ix], values[nz * SIZE + nx], px - ix, pz - iz);
    }

    double coastDistance(int x, int z) {
        double wx = (TerrainMath.noise(seed, "coast_x", x, z, 48) - 0.5) * 12;
        double wz = (TerrainMath.noise(seed, "coast_z", x, z, 48) - 0.5) * 12;
        double px = Math.clamp((x + wx - MapCoordinates.MIN) / 16, 0, SIZE - 1);
        double pz = Math.clamp((z + wz - MapCoordinates.MIN) / 16, 0, SIZE - 1);
        int ix = (int) px, iz = (int) pz;
        return TerrainMath.cubic(distanceRow(ix, iz - 1, px - ix), distanceRow(ix, iz, px - ix),
                distanceRow(ix, iz + 1, px - ix), distanceRow(ix, iz + 2, px - ix), pz - iz);
    }

    private double distanceRow(int x, int z, double t) {
        int offset = Math.clamp(z, 0, SIZE - 1) * SIZE;
        return TerrainMath.cubic(coastDistance[offset + Math.max(0, x - 1)], coastDistance[offset + x],
                coastDistance[offset + Math.min(SIZE - 1, x + 1)], coastDistance[offset + Math.min(SIZE - 1, x + 2)], t);
    }

    double detailWeight(int x, int z) {
        double weight = 0.25 + 0.75 * TerrainMath.smooth(interpolate(protectionDistance, x, z) / 96);
        for (var facility : facilities) {
            double dx = x - facility.x, dz = z - facility.z;
            if (Math.abs(dx) < facility.radius + 48 && Math.abs(dz) < facility.radius + 48) {
                weight *= TerrainMath.smooth((Math.hypot(dx, dz) - facility.radius) / 48);
            }
        }
        for (var entrance : entrances.getOrDefault(key(x >> 7, z >> 7), List.of())) {
            weight = Math.min(weight, TerrainMath.smooth((Math.hypot(x - entrance.x, z - entrance.z) - 12) / 32));
        }
        return weight;
    }

    RiverCourse.Sample river(int x, int z) { return river.sample(x, z); }
    RiverCourse.Sample riverBankAlternative(int x,int z) { return river.bankAlternative(x,z); }
    RiverCourse.Sample riverSource() { return riverSource; }

    boolean contaminationProtected(int x, int z) {
        if (walkways.sample(x, z).distance() <= 4) return true;
        for (var entrance : entrances.getOrDefault(key(x >> 7, z >> 7), List.of()))
            if (Math.hypot(x - entrance.x, z - entrance.z) <= 12) return true;
        return false;
    }

    boolean hazardProtected(int x, int z) {
        if (isProtected(x, z) || walkways.sample(x, z).distance() <= 16) return true;
        for (var entrance : entrances.getOrDefault(key(x >> 7, z >> 7), List.of())) {
            if (Math.hypot(x - entrance.x, z - entrance.z) <= 48) return true;
        }
        return false;
    }

    private void smoothRegionHeights() {
        byte[] distance = new byte[heights.length];
        Arrays.fill(distance, (byte) 6);
        for (int z = 1; z < SIZE - 1; z++) for (int x = 1; x < SIZE - 1; x++) {
            int i = z * SIZE + x, region = Byte.toUnsignedInt(categories[0][i]);
            if (region == 0) continue;
            for (int offset : new int[]{-1, 1, -SIZE, SIZE}) {
                int neighbor = Byte.toUnsignedInt(categories[0][i + offset]);
                if (neighbor != 0 && neighbor != region) distance[i] = 0;
            }
        }
        for (int z = 1; z < SIZE; z++) for (int x = 1; x < SIZE; x++) {
            int i = z * SIZE + x;
            distance[i] = (byte) Math.min(distance[i], 1 + Math.min(distance[i - 1], distance[i - SIZE]));
        }
        for (int z = SIZE - 2; z >= 0; z--) for (int x = SIZE - 2; x >= 0; x--) {
            int i = z * SIZE + x;
            distance[i] = (byte) Math.min(distance[i], 1 + Math.min(distance[i + 1], distance[i + SIZE]));
        }
        short[] original = heights.clone();
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            int i = z * SIZE + x;
            if (distance[i] >= 6 || protectedArea[i] || categories[4][i] != 0 || categories[0][i] == 0) continue;
            double sum = 0;
            int count = 0;
            for (int dz = -6; dz <= 6; dz++) for (int dx = -6; dx <= 6; dx++) {
                int nx = x + dx, nz = z + dz;
                if (nx < 0 || nx >= SIZE || nz < 0 || nz >= SIZE) continue;
                int at = nz * SIZE + nx;
                if (categories[4][at] == 0 && categories[0][at] != 0) { sum += original[at]; count++; }
            }
            double weight = 1 - distance[i] / 6.0;
            heights[i] = (short) Math.round(original[i] + (sum / count - original[i]) * weight);
        }
    }

    private void markProtection(double ax, double az, double bx, double bz, double radius) {
        // 最近傍セルの全域が保護円柱・経路幅を覆うよう半対角長を加える。
        double r = radius + 12, dx = bx - ax, dz = bz - az, length = dx * dx + dz * dz;
        int x0 = Math.max(0, TerrainMath.pixel((int) Math.floor(Math.min(ax, bx) - r)));
        int x1 = Math.min(SIZE - 1, TerrainMath.pixel((int) Math.ceil(Math.max(ax, bx) + r)));
        int z0 = Math.max(0, TerrainMath.pixel((int) Math.floor(Math.min(az, bz) - r)));
        int z1 = Math.min(SIZE - 1, TerrainMath.pixel((int) Math.ceil(Math.max(az, bz) + r)));
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                double px = MapCoordinates.MIN + 16 * x - ax, pz = MapCoordinates.MIN + 16 * z - az;
                double t = length == 0 ? 0 : Math.clamp((px * dx + pz * dz) / length, 0, 1);
                if (Math.pow(px - t * dx, 2) + Math.pow(pz - t * dz, 2) <= r * r) protectedArea[z * SIZE + x] = true;
            }
        }
    }

    private static JsonObject json(String path) throws IOException {
        try (var stream = FixedMapData.class.getResourceAsStream(path)) {
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private int at(int x, int z) {
        return TerrainMath.nearestPixel(z) * SIZE + TerrainMath.nearestPixel(x);
    }

    long seed() { return seed; }
    TerrainWalkways walkways() { return walkways; }
    MountainIsland mountainIsland(int x, int z) {
        return MapCoordinates.contains(x, z) ? mountainIslands.get(at(x, z)) : null;
    }

    IslandJunction islandJunction(int x,int z) {
        return islandJunctions.get(TerrainMath.pixel(z)*SIZE+TerrainMath.pixel(x));
    }

    private boolean discontinuousIslandEdge(MountainIsland a,MountainIsland b,int x,int z) {
        if(a==b)return false;
        double coast=coastDistance(x,z);if(coast<=0)return false;
        double rocks=TerrainMath.rockRelief(seed,x,z),detail=TerrainMath.fbm(seed,"island_gullies",x,z,32);
        double massif=TerrainMath.massifRelief(seed,x,z);
        return Math.abs(FixedTerrain.islandHeight(a,x,z,coast,rocks,detail,massif,0)
                -FixedTerrain.islandHeight(b,x,z,coast,rocks,detail,massif,0))>4;
    }

    double islandStraitWeight(int x,int z) {
        if(x<straitMinX || x>straitMaxX || z<straitMinZ || z>straitMaxZ)return 0;
        int at=TerrainMath.pixel(z)*SIZE+TerrainMath.pixel(x);
        return TerrainMath.bilinear(islandStraits.getOrDefault(at,0f),islandStraits.getOrDefault(at+1,0f),
                islandStraits.getOrDefault(at+SIZE,0f),islandStraits.getOrDefault(at+SIZE+1,0f),
                TerrainMath.smooth(TerrainMath.fraction(x)),TerrainMath.smooth(TerrainMath.fraction(z)));
    }

    double islandOffshoreWeight(int x,int z) {
        double weight=0;
        for(int i=0;i<islandCoasts.size();i++) {
            var island=islandCoasts.get(i);
            double reach=Math.hypot((x-island.x())*.7,z-island.z());
            weight=Math.max(weight,TerrainMath.smooth((360-reach)/180));
        }
        return weight;
    }

    double surfaceCorrection(int x,int z) {
        double sum=0,total=0,strength=0;
        var controls=surfaceCorrections.getOrDefault(key(x>>7,z>>7),List.of());
        for(int i=0;i<controls.size();i++) {
            var control=controls.get(i);
            double squared=(double)(x-control.x)*(x-control.x)+(double)(z-control.z)*(z-control.z);
            double taper=TerrainMath.smooth((96-Math.sqrt(squared))/80);
            double weight=taper*taper/((1+squared)*(1+squared));
            sum+=control.displacement*weight;total+=weight;strength=Math.max(strength,taper);
        }
        return total==0?0:sum/total*strength;
    }

    double mountainWeight(int x, int z) {
        double weight = 0;
        for (var mountain : mountains) {
            double nearest = mountain.radius;
            for (int i = 1; i < mountain.points.length; i++) {
                var a = mountain.points[i - 1]; var b = mountain.points[i];
                double dx = b[0] - a[0], dz = b[1] - a[1];
                double t = Math.clamp(((x - a[0]) * dx + (z - a[1]) * dz) / (dx * dx + dz * dz), 0, 1);
                nearest = Math.min(nearest, Math.hypot(x - a[0] - t * dx, z - a[1] - t * dz));
            }
            weight = Math.max(weight, TerrainMath.smooth(1 - nearest / mountain.radius));
        }
        return weight;
    }

    double terminationWeight(int x,int z) {
        return TerrainMath.smooth((terminationJoin-(double)x)/terminationTransition)*mountainWeight(x,z);
    }

    int mountainCeiling(int x,int z) {
        int ceiling=0;
        for(int m=0;m<mountains.size();m++) {
            var mountain=mountains.get(m);
            for(int i=1;i<mountain.points.length;i++) {
                var a=mountain.points[i-1];var b=mountain.points[i];
                double dx=b[0]-a[0],dz=b[1]-a[1];
                double t=Math.clamp(((x-a[0])*dx+(z-a[1])*dz)/(dx*dx+dz*dz),0,1);
                if(Math.hypot(x-a[0]-t*dx,z-a[1]-t*dz)<mountain.radius)ceiling=Math.max(ceiling,mountain.peakY);
            }
        }
        return ceiling==0?520:ceiling;
    }
    OceanRelief ocean() { return ocean; }
    JsonObject geology() { return geology.deepCopy(); }

    /** 範囲外は0。範囲内は最近傍の地域ID（0〜7、255）を返す。 */
    public int region(int x, int z) { return category(0, x, z); }

    record GeologyAnchor(int x, int z, double weight, boolean weathered) {}

    GeologyAnchor geologyAnchor(int x, int z) {
        Entrance nearest = null;
        double distance = 48;
        for (var anchor : geologyAnchors.getOrDefault(key(x >> 7, z >> 7), List.of())) {
            double candidate = Math.hypot(x - anchor.x, z - anchor.z);
            if (candidate < distance) { distance = candidate; nearest = anchor; }
        }
        return nearest == null ? null : new GeologyAnchor(nearest.x, nearest.z, TerrainMath.smooth((distance - 4) / 44),weatheredContacts.contains(key(nearest.x,nearest.z)));
    }

    record Boundary(int region, int soil, double dry, double cold) {}

    Boundary boundary(int x, int z) {
        double weight = detailWeight(x, z);
        int wx = (int) Math.round(TerrainMath.fbm(seed, "boundary_x", x, z, 192) * 64 * weight);
        int wz = (int) Math.round(TerrainMath.fbm(seed, "boundary_z", x, z, 192) * 64 * weight);
        int nx = Math.clamp(x + wx, MapCoordinates.MIN, MapCoordinates.MAX - 1);
        int nz = Math.clamp(z + wz, MapCoordinates.MIN, MapCoordinates.MAX - 1);
        double gx=(nx-MapCoordinates.MIN)/96.0,gz=(nz-MapCoordinates.MIN)/96.0;
        int ix=(int)Math.floor(gx),iz=(int)Math.floor(gz);
        double tx=TerrainMath.smooth(gx-ix),tz=TerrainMath.smooth(gz-iz),dry=0,cold=0,total=0;
        double regionPick=TerrainMath.noise(seed,"region_edge",Math.floorDiv(x,4),Math.floorDiv(z,4),1);
        double soilPick=TerrainMath.noise(seed,"soil_edge",x,z,1);
        int region=category(0,nx,nz),soil=category(2,nx,nz);
        for(int dz=0;dz<=1;dz++)for(int dx=0;dx<=1;dx++) {
            int sx=Math.clamp(MapCoordinates.MIN+(ix+dx)*96,MapCoordinates.MIN,MapCoordinates.MAX-1);
            int sz=Math.clamp(MapCoordinates.MIN+(iz+dz)*96,MapCoordinates.MIN,MapCoordinates.MAX-1);
            int r=category(0,sx,sz),s=category(2,sx,sz);
            double part=(dx==0?1-tx:tx)*(dz==0?1-tz:tz);
            if(r==4)dry+=part;
            if(r==7)cold+=part;
            if(regionPick>=total && regionPick<total+part)region=r;
            if(soilPick>=total && soilPick<total+part)soil=s;
            total+=part;
        }
        return new Boundary(region,soil,dry,cold);
    }
    int category(int layer, int x, int z) {
        return MapCoordinates.contains(x, z) ? Byte.toUnsignedInt(categories[layer][at(x, z)]) : 0;
    }

    /** 海・河道・湖のbit。範囲外は0で、海域として扱わない。 */
    public int waterMask(int x, int z) {
        if (!MapCoordinates.contains(x, z)) return 0;
        double coast = coastDistance(x, z);
        if (coast < 0) return 8;
        var sample = river.sample(x, z);
        return (coast < 48 ? 1 : 0) | (sample.distance() < sample.width() ? 2 : 0) | (category(4, x, z) & 4);
    }

    /** 水質対応表のID。乾燥・範囲外は0。 */
    public int waterQuality(int x, int z) {
        if (!MapCoordinates.contains(x, z)) return 0;
        if (coastDistance(x, z) < 0) return 8;
        var sample = river.sample(x, z);
        if (sample.distance() < sample.width()) return riverQuality(sample);
        return (category(4, x, z) & 4) != 0 ? category(1, x, z) : 0;
    }

    int riverQuality(RiverCourse.Sample sample) {
        return sample.tributary() ? 4 : category(1, sample.x(), sample.z());
    }

    /** 土の質3種類のID。0は露岩・未割当て・範囲外で、汚染とは独立した材料混合前の値。 */
    public int soilQuality(int x, int z) { return category(2, x, z); }

    /** geology_profiles.jsonを参照するID。範囲外は0なのでcontainsと併用する。 */
    public int geologyProfile(int x, int z) { return category(3, x, z); }

    /** 指定水域の水面ブロックY。水域以外はInteger.MIN_VALUE。 */
    public int waterSurface(int x, int z) {
        if (!MapCoordinates.contains(x, z)) return Integer.MIN_VALUE;
        if (coastDistance(x, z) < 0) return 63;
        var sample = river.sample(x, z);
        if (sample.distance() < sample.width()) return (int) Math.floor(sample.surface());
        return (category(4, x, z) & 4) != 0 ? waterSurface[at(x, z)] : Integer.MIN_VALUE;
    }

    /** 施設・経路の水平保護域。16ブロック格子の被覆により最大24ブロック広く保護する。 */
    public boolean isProtected(int x, int z) {
        return MapCoordinates.contains(x, z) && protectedArea[at(x, z)];
    }

    /** 範囲内の標高Yを3次補間する。最終画素の外挿はせず、端の値を延長する。 */
    public double height(int x, int z) {
        if (!MapCoordinates.contains(x, z)) throw new IllegalArgumentException("配置図の範囲外です");
        return height((double) x, (double) z);
    }

    double height(double x, double z) {
        double px = Math.clamp((x - MapCoordinates.MIN) / 16, 0, SIZE - 1);
        double pz = Math.clamp((z - MapCoordinates.MIN) / 16, 0, SIZE - 1);
        int ix = (int) px, iz = (int) pz;
        return TerrainMath.cubic(row(ix, iz - 1, px - ix), row(ix, iz, px - ix),
                row(ix, iz + 1, px - ix), row(ix, iz + 2, px - ix), pz - iz);
    }

    private double row(int x, int z, double t) {
        int offset = Math.clamp(z, 0, SIZE - 1) * SIZE;
        return TerrainMath.cubic(heights[offset + Math.max(0, x - 1)], heights[offset + x],
                heights[offset + Math.min(SIZE - 1, x + 1)], heights[offset + Math.min(SIZE - 1, x + 2)], t);
    }
}
