package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** 固定地形の列サンプラー。ワールドseed・SavedData・共有Randomを参照しない。 */
public final class FixedTerrain {
    private record Layer(BlockState rock, int min, int max, String salt) {}
    private record Fault(double x, double z, double nx, double nz, double displacement) {}
    private record Uplift(double axisZ, double width, double transition, double drop) {}
    private record Profile(List<Layer> layers, BlockState basement, double referenceY, Uplift uplift, double ax, double az,
                           double gx, double gz, List<Fault> faults) {}
    private final FixedMapData map;
    private final Profile[] profiles = new Profile[6];
    private final BlockState[] soils = new BlockState[6];
    private final BlockState[] waters = new BlockState[9];
    private final BlockState[] waterIce = new BlockState[9];
    private final FixedUnderground underground;
    private final Map<Long, Integer> anchorSurfaces;
    private final BlockState clay;
    private final BlockState talus, riverGravel, till, silt, loess, blackSand;
    private final BlockState talusLayer, siltLayer, loessLayer;
    private final BlockState tidalMud, organicMud, peat, crackedMud;
    private final BlockState[] weathered;
    private record Surface(int height, int water, int quality, double coast, RiverCourse.Sample river, OceanRelief.Shore shore, double continuous) {}

    public FixedTerrain(MapVersion version) {
        map = FixedMapData.require(version);
        clay = block("ashen_frontier:clay_sediment");
        talus = block("ashen_frontier:talus");
        riverGravel = block("ashen_frontier:river_gravel");
        till = block("ashen_frontier:glacial_till");
        silt = block("ashen_frontier:silt");
        loess = block("ashen_frontier:loess");
        blackSand = block("ashen_frontier:black_sand");
        talusLayer = block("ashen_frontier:talus_layer");
        siltLayer = block("ashen_frontier:silt_layer");
        loessLayer = block("ashen_frontier:loess_layer");
        tidalMud = block("ashen_frontier:tidal_mud");
        organicMud = block("ashen_frontier:organic_mud");
        peat = block("ashen_frontier:peat");
        crackedMud = block("ashen_frontier:cracked_mud");
        weathered = new BlockState[]{Blocks.ANDESITE.defaultBlockState(), block("ashen_frontier:gneiss"),
                Blocks.TUFF.defaultBlockState(), block("ashen_frontier:quartzite"), Blocks.DIORITE.defaultBlockState(),
                Blocks.COBBLESTONE.defaultBlockState()};
        var geology = map.geology();
        String salt = geology.get("thickness_salt").getAsString();
        for (var entry : geology.getAsJsonObject("profiles").entrySet()) {
            var profile = entry.getValue().getAsJsonObject();
            List<Layer> layers = new ArrayList<>();
            for (var value : profile.getAsJsonArray("layers_top_to_bottom")) {
                var layer = value.getAsJsonObject();
                var range = layer.getAsJsonArray("thickness_blocks");
                layers.add(new Layer(block(layer.get("rock").getAsString()), range.get(0).getAsInt(),
                        range.get(1).getAsInt(), salt + ":" + entry.getKey() + ":" + layer.get("id").getAsString()));
            }
            List<Fault> faults = new ArrayList<>();
            for (var value : profile.getAsJsonArray("faults")) {
                var fault = value.getAsJsonObject();
                faults.add(new Fault(component(fault, "point_xz", 0), component(fault, "point_xz", 1),
                        component(fault, "normal_xz", 0), component(fault, "normal_xz", 1), fault.get("throw_blocks").getAsDouble()));
            }
            var dip = profile.getAsJsonObject("dip");
            var upliftData = profile.getAsJsonObject("uplift");
            Uplift uplift = upliftData == null ? null : new Uplift(upliftData.get("axis_z").getAsDouble(),
                    upliftData.get("half_width_blocks").getAsDouble(), upliftData.get("transition_blocks").getAsDouble(),
                    upliftData.get("drop_blocks").getAsDouble());
            profiles[Integer.parseInt(entry.getKey())] = new Profile(List.copyOf(layers),
                    block(profile.get("basement").getAsString()), profile.get("reference_y").getAsDouble(), uplift, component(dip, "anchor_xz", 0), component(dip, "anchor_xz", 1),
                    component(dip, "gradient_xz", 0), component(dip, "gradient_xz", 1), List.copyOf(faults));
        }
        String[] soilIds = {"fertile_soil", "poor_soil", "degraded_soil", "contaminated_soil", "toxic_soil"};
        String[] waterIds = {"ultrapure_water", "pure_water", "clean_water", "raw_water", "turbid_water",
                "contaminated_water", "toxic_water", "polluted_seawater"};
        for (int i = 0; i < soilIds.length; i++) soils[i + 1] = block("ashen_frontier:" + soilIds[i]);
        for (int i = 0; i < waterIds.length; i++) waters[i + 1] = block("ashen_frontier:" + waterIds[i]);
        String[] iceIds = {"raw_water_ice", "turbid_water_ice", "contaminated_water_ice", "toxic_water_ice", "sea_ice"};
        for (int i = 0; i < iceIds.length; i++) waterIce[i + 4] = block("ashen_frontier:" + iceIds[i]);
        var anchorSurfaces = new HashMap<Long, Integer>();
        for (var deposit : UndergroundLayout.read(version).deposits())
            anchorSurfaces.put(((long) deposit.x() << 32) ^ (deposit.z() & 0xffffffffL), surfaceY(map, deposit.x(), deposit.z()));
        this.anchorSurfaces = Map.copyOf(anchorSurfaces);
        underground = new FixedUnderground(version, this);
    }

    private static double component(JsonObject object, String name, int i) {
        return object.getAsJsonArray(name).get(i).getAsDouble();
    }

    private static BlockState block(String id) {
        var key = ResourceLocation.parse(id);
        if (!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalStateException("地形のブロックが未登録です: " + id);
        return BuiltInRegistries.BLOCK.get(key).defaultBlockState();
    }

    public FixedMapData map() { return map; }
    FixedUnderground underground() { return underground; }

    /** 地面の基準Y。地面の上の雪層・水面を含めず、範囲外は−65。 */
    public int surfaceY(int x, int z) {
        return surfaceY(map, x, z);
    }

    static int surfaceY(FixedMapData map, int x, int z) {
        if (!MapCoordinates.contains(x, z)) return -65;
        return sampleSurface(map, x, z).height;
    }

    private static Surface sampleSurface(FixedMapData map, int x, int z) {
        return sampleSurface(map,x,z,OceanRelief.shore(map,x,z));
    }

    private static Surface sampleSurface(FixedMapData map, int x, int z, OceanRelief.Shore shore) {
        double coast = map.coastDistance(x, z), weight = map.detailWeight(x, z);
        var river = map.river(x, z);
        double height = coast < 0 ? map.ocean().height(map,x,z,-coast,shore) : landHeight(map, x, z, coast, weight, shore);
        int water = Integer.MIN_VALUE, quality = 0;
        if (coast < 0) {
            water = 63; quality = 8;
        } else {
            if (river.distance() < river.width()) {
                water = (int) Math.floor(river.surface()); quality = map.riverQuality(river);
                height = riverBed(river);
            } else if (river.distance() < 256) {
                double reach=TerrainMath.noise(map.seed(), "river_bank", river.x(), river.z(), 192);
                double slope = .07 + .12*reach + .3*Math.max(0,river.bank());
                double cut=TerrainMath.valleyHeight(height,river.grade()+1,river.distance(),river.width(),slope);
                double terrace=(1-TerrainMath.smooth(Math.abs(river.distance()-river.width()-65)/32))
                        *TerrainMath.smooth((reach-.45)/.3)*(1-Math.max(0,river.bank()));
                double bench=river.grade()+8+(river.distance()-river.width()-50)*.025;
                height = Math.min(height,cut+(Math.min(cut,bench)-cut)*terrace);
                double lip=TerrainMath.smooth((river.distance()-river.width())/4);
                height=Math.min(height,river.surface()+1+(height-river.surface()-1)*lip);
            }
            if (river.distance() >= river.width() && (map.category(4, x, z) & 4) != 0) {
                water = map.waterSurface(x, z); quality = map.waterQuality(x, z);
            }
        }
        if (coast < 0 && river.distance() < river.width()) height = Math.min(height, riverBed(river));
        int rounded = (int) Math.round(height);
        if (water != Integer.MIN_VALUE) rounded = Math.min(rounded, water - (coast >= 0 && river.distance() < river.width() ? 2 : 1));
        else rounded = Math.max(coast < 16 ? 63 : 64, rounded);
        return new Surface(Math.clamp(rounded, -127, 520), water, quality, coast, river, shore, Math.clamp(height,-127,520));
    }

    private static double riverBed(RiverCourse.Sample river) {
        double depth = 2 + (river.depth()-2-1.2*river.riffle()) * (1-Math.pow(river.distance()/river.width(),2));
        return river.surface() - depth;
    }

    static double landHeight(FixedMapData map, int x, int z) {
        return landHeight(map, x, z, map.coastDistance(x, z), map.detailWeight(x, z), OceanRelief.shore(map,x,z));
    }

    private static double landHeight(FixedMapData map, int x, int z, double coast, double weight, OceanRelief.Shore shore) {
        double shoreHeight = OceanRelief.shoreHeight(coast,shore);
        var island = map.mountainIsland(x, z);
        if (island != null && coast >= 0) {
            double radius=Math.hypot(x-island.x(),z-island.z());
            double profile=Math.sqrt(Math.max(0,1-Math.pow(radius/(radius+coast*1.3+1e-6),2)));
            profile += TerrainMath.fbm(map.seed(), "island_relief", x, z, 32) * .25 * profile * (1-profile);
            double height=63+(island.peakY()-63)*Math.clamp(profile,0,1);
            return 63 + (height - 63) * TerrainMath.smooth(coast / 12);
        }
        double wx = (TerrainMath.noise(map.seed(), "relief_x", x, z, 256) - 0.5) * 24 * weight;
        double wz = (TerrainMath.noise(map.seed(), "relief_z", x, z, 256) - 0.5) * 24 * weight;
        double base = map.height(x + wx, z + wz);
        double mountain = map.mountainWeight(x, z) * TerrainMath.smooth((base - 130) / 70);
        double amplitude=8+18*TerrainMath.noise(map.seed(),"relief_region",x,z,768);
        double detail=TerrainMath.fbm(map.seed(), "surface_detail", x+wx,z+wz,224);
        if(mountain>0)detail=detail*(1-mountain)+TerrainMath.fbm(map.seed(),"mountain_detail",x+wx,z+wz,48)*mountain;
        detail*=amplitude+64*mountain;
        detail*=1-mountain+mountain*TerrainMath.smooth((coast-96)/192);
        double height = base + detail*Math.clamp((520-base)/70,0,1)*weight
                + TerrainMath.fbm(map.seed(), "terrace_detail", x, z, 32) * 1.4 * weight * TerrainMath.smooth(coast / 32);
        double blend = TerrainMath.smooth(coast / (180-80*shore.rock()));
        return map.walkways().height(x, z, Math.max(coast < 16 ? 63 : 64, shoreHeight + (height - shoreHeight) * blend));
    }

    static int biomeIndex(FixedMapData map, int x, int z, int surface) {
        if (!MapCoordinates.contains(x, z)) return 0;
        return biomeIndex(map, x, z, surface, map.boundary(x, z).region(), map.coastDistance(x, z));
    }

    private static int biomeIndex(FixedMapData map, int x, int z, int surface, int boundary, double coast) {
        int region = map.region(x, z);
        if (coast < 0) return 0;
        if (boundary != 0 && region != 0) region = boundary;
        double latitude = Math.abs(MapCoordinates.latitude(z));
        int biome = region;
        if (region == 255) biome = latitude >= 60 ? 9 : latitude < 25 ? 10 : 8;
        double alpine = TerrainMath.smooth((surface - 194) / 32.0);
        if (region != 0 && TerrainMath.noise(map.seed(), "alpine_edge", x, z, 1) < alpine) biome = 11;
        return biome;
    }

    private int iceDepth(int x, int z, int surface, boolean wet) {
        if (wet) return 0;
        double latitude = MapCoordinates.latitude(z);
        int region = map.region(x, z);
        if (latitude < 50 || (region != 3 && region != 7)) return 0;
        boolean valley = false;
        if (latitude >= 60 && MapCoordinates.contains(x - 96, z - 96) && MapCoordinates.contains(x + 96, z + 96)) {
            double surroundings = (map.height(x - 96, z) + map.height(x + 96, z)
                    + map.height(x, z - 96) + map.height(x, z + 96)) / 4;
            valley = surroundings - map.height(x, z) >= 2;
        }
        double detail = TerrainMath.noise(map.seed(), "ice_depth", x, z, 192);
        if (region == 7 && latitude >= 65) {
            return 8 + (int) ((latitude - 65) * 0.8) + Math.min(4, surface / 80) + (int) (detail * 4) + (valley ? 4 : 0);
        }
        if ((latitude < 65 && surface >= 180) || valley) return 3 + (int) (detail * 5) + Math.max(0, (surface - 180) / 24);
        return 0;
    }

    private int soil(int x, int z, boolean wet, int boundary) {
        int local = map.category(2, x, z);
        if (local == 0 || wet || map.isProtected(x,z) && map.walkways().sample(x,z).distance()>4) return local;
        return boundary;
    }

    /** 列ごとに地層境界を一度計算する。列は呼出し元専有で、全Yの照会に再利用できる。 */
    public Column column(int x, int z) {
        if (!MapCoordinates.contains(x, z)) return new Column(-65, Integer.MIN_VALUE, null, null, new int[0], null, 0, 0, x, z, null, 0, null, false, null, 0,0,0,1, null, 575);
        var relief = sampleSurface(map, x, z);
        int surface = relief.height;
        var profile = profiles[relief.coast >= 0 && map.mountainIsland(x,z) != null ? 3 : map.category(3, x, z)];
        double displacement = (x - profile.ax) * profile.gx + (z - profile.az) * profile.gz;
        for (var fault : profile.faults) {
            if ((x - fault.x) * fault.nx + (z - fault.z) * fault.nz >= 0) displacement += fault.displacement;
        }
        int[] boundaries = new int[profile.layers.size()];
        double regionalFold = (TerrainMath.fbm(map.seed(), "strata_fold", x, z, 512)
                + TerrainMath.fbm(map.seed(), "strata_fold_local", x, z, 96)) * 0.5;
        var anchor = map.geologyAnchor(x, z);
        double geologyWeight = anchor == null ? 1 : anchor.weight();
        double anchorY = anchor == null ? 0 : anchorSurfaces.get(((long) anchor.x() << 32) ^ (anchor.z() & 0xffffffffL));
        double datum = profile.referenceY;
        if (profile.uplift != null) {
            var uplift = profile.uplift;
            datum -= TerrainMath.smooth((Math.abs(z - uplift.axisZ) - uplift.width) / uplift.transition) * uplift.drop;
        }
        double reference=(map.height((double)x-192,z)+map.height((double)x+192,z)
                +map.height((double)x,z-192)+map.height((double)x,z+192))/4;
        double tilt=(x> -4500 && z< -12500 ? (x+3200)*.12-(z+13400)*.04
                : Math.sin((x+11000)/1600.0)*36+(z+11800)*.05)*map.mountainWeight(x,z);
        int previous = 575;
        double thickness = 0;
        for (int i = 0; i < boundaries.length; i++) {
            var layer = profile.layers.get(i);
            double pinch=TerrainMath.smooth((TerrainMath.noise(map.seed(),layer.salt+"_lens",x,z,192)-.12)/.7);
            thickness += TerrainMath.layerThickness(map.seed(), layer.salt, x, z, layer.min, layer.max, regionalFold)
                    *(1+geologyWeight*(pinch*1.8-1));
            double structure = regionalFold * (24 + i * 8) + tilt * (.3 + i * .15);
            double plane = datum - thickness + displacement + structure;
            if (anchor != null) {
                double contact = Math.min(anchorY - thickness + displacement,
                        (i == 0 ? anchorY : previous) - layer.min);
                plane = contact + (plane - contact) * geologyWeight;
            }
            boundaries[i] = Math.clamp((int) Math.floor(plane), -63, Math.max(-63, previous));
            previous = boundaries[i];
        }
        var boundary = map.boundary(x, z);
        boolean wet = relief.water != Integer.MIN_VALUE;
        int soil = soil(x, z, wet, boundary.soil()), quality = relief.quality;
        int biome = biomeIndex(map, x, z, surface, boundary.region(), relief.coast);
        double east = sampleSurface(map,x+8,z,relief.shore).continuous, west = sampleSurface(map,x-8,z,relief.shore).continuous;
        double south = sampleSurface(map,x,z+8,relief.shore).continuous, north = sampleSurface(map,x,z-8,relief.shore).continuous;
        double sx = (east-west)/16, sz = (south-north)/16, slope = Math.hypot(sx,sz);
        double hollow = (east+west+south+north)/4-relief.continuous;
        double mountain = map.mountainWeight(x,z);
        boolean rock = map.mountainIsland(x,z) != null && relief.coast < 64
                || slope >= .9 || relief.shore.rock() > .65 && relief.coast < 96;
        int layers = 0;
        double aboveLine = 0;
        if (!wet && (MapCoordinates.latitude(z) >= 45 || biome == 9 || surface >= 180)) {
            double line = TerrainMath.snowline(map.seed(), x, z, Math.abs(MapCoordinates.latitude(z)), sx, sz, false);
            line+=(45-(230-Math.max(0,Math.abs(MapCoordinates.latitude(z))-45)*3))*boundary.cold();
            line -= Math.clamp(reference - relief.continuous, 0, 80) * .5 * mountain;
            layers = rock ? 0 : TerrainMath.snowLayers(surface, line, slope);
            double margin=TerrainMath.smooth((relief.coast-8)/48);
            double edge=TerrainMath.smooth((relief.continuous-line+16)/32)*margin;
            double scatter=TerrainMath.noise(map.seed(),"snow_edge",x,z,1);
            layers=Math.min(layers,TerrainMath.depositLayers(edge,scatter));
            aboveLine = relief.continuous-line;
        }
        int ice = !wet && !rock && relief.coast > 48 && slope < .65
                ? iceDepth(x, z, surface, false) : 0;
        if (map.region(x,z)==7)ice=(int)Math.floor(ice*boundary.cold());
        ice = (int) Math.floor(ice * TerrainMath.smooth((aboveLine - 8) / 24));
        double lee = sz + Math.max(0,map.height((double)x,z-64)-relief.continuous)/8;
        double pack = layers == 8 ? TerrainMath.snowPack(aboveLine,relief.coast,slope,hollow,lee) : 0;
        int snow = (int) Math.floor(pack);
        boolean powder = false;
        if (layers == 8 && (ice > 0 || Math.abs(MapCoordinates.latitude(z)) >= 60)
                && pack > 1.5 && relief.coast > 64 && slope < .2 && !map.hazardProtected(x, z)) {
            double driftX = x + TerrainMath.fbm(map.seed(), "powder_warp_x", x,z,96)*28;
            double driftZ = z + TerrainMath.fbm(map.seed(), "powder_warp_z", x,z,96)*28;
            double powderField = TerrainMath.fbm(map.seed(), "powder_pool", driftX,driftZ,64)
                    + hollow*.15 + lee*.08 - slope*2;
            powder = powderField > .22;
        }
        var cell = underground != null && !map.isProtected(x, z) && map.region(x, z) >= 1 && map.region(x, z) <= 7
                ? underground.cell(x, z) : null;
        if (cell != null && cell.bodies().isEmpty() && cell.passages().isEmpty()) cell = null;
        double patch=TerrainMath.noise(map.seed(),"surface_patches",x,z,18);
        BlockState surfaceMaterial = surfaceMaterial(x,z,relief,profile,soil,slope,hollow,rock,boundary.dry(),patch);
        int soilDepth = 2 + (int) (TerrainMath.noise(map.seed(), "soil_thickness", x, z, 20) * 3);
        boolean walkway=map.walkways().sample(x,z).distance()<1;
        if(walkway && layers<8)layers=Math.min(layers,2);
        int cover = TerrainMath.snowCover(relief.continuous,surface,layers);
        BlockState cap = cover == 0 || powder ? null : Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, cover);
        if (cap == null && !wet && snow == 0 && ice == 0 && !powder && map.detailWeight(x,z)>.05 && slope < .65) {
            BlockState sediment = surfaceMaterial == silt ? siltLayer : surfaceMaterial == loess ? loessLayer
                    : surfaceMaterial == talus || surfaceMaterial == null && mountain > .05 && slope > .15 && hollow > .2 ? talusLayer : null;
            if (sediment != null) {
                double edge=sediment==loessLayer?TerrainMath.smooth((boundary.dry()-.25)/.5):TerrainMath.smooth((.65-slope)/.2);
                if(sediment==siltLayer)edge*=TerrainMath.smooth((relief.river.width()+100-relief.river.distance())/48);
                if(sediment==talusLayer)edge*=TerrainMath.smooth((slope-.08)/.18)*TerrainMath.smooth((hollow+.1)/.8);
                double scatter = TerrainMath.noise(map.seed(),"sediment_edge",x,z,1);
                int capLayers = TerrainMath.depositLayers(edge,scatter);
                if(capLayers>0)cap=sediment.setValue(SnowLayerBlock.LAYERS,capLayers);
            }
        }
        if(walkway && cap!=null)
            cap=cap.setValue(SnowLayerBlock.LAYERS,Math.min(2,cap.getValue(SnowLayerBlock.LAYERS)));
        int waterKind = relief.coast < 0 ? 3 : relief.river.distance() < relief.river.width() ? 2 : 1;
        BlockState frozen = wet ? frozenWater(quality, x, z, relief.water, relief.water - surface, waterKind, boundary.cold()) : null;
        return new Column(surface, relief.water, surfaceMaterial, waters[quality], boundaries, profile, ice, snow, x, z, cell,
                soilDepth, cap, powder, surfaceMaterial==null && (slope>.35 || mountain>.1 || map.mountainIsland(x,z)!=null) ? weathered:null,
                (int)(regionalFold*24+patch*3),geologyWeight,tilt+regionalFold*96,
                18+24*TerrainMath.noise(map.seed(),"rock_band_width",x,z,192), frozen, anchor == null ? 575 : (int) anchorY - 20);
    }

    BlockState frozenWater(int quality, int x, int z, int water, int depth, int kind, double cold) {
        double latitude = Math.abs(MapCoordinates.latitude(z)) + TerrainMath.fbm(map.seed(), "freezing_edge", x, z, 384) * 1.5;
        return quality >= 4 && quality <= 8 && TerrainMath.freezes(latitude, water, depth, kind, cold)
                ? waterIce[quality] : null;
    }

    private BlockState surfaceMaterial(int x, int z, Surface relief, Profile profile, int soil,
                                       double slope, double hollow, boolean rock, double dry, double patch) {
        if (rock || map.mountainIsland(x,z) != null) return null;
        BlockState material = soils[soil];
        double beachWidth = relief.shore.beachWidth();
        var river = relief.river;
        double scatter = TerrainMath.noise(map.seed(), "material_edge", x, z, 1);
        boolean beach = relief.coast >= 0 && scatter < TerrainMath.smooth((beachWidth - relief.coast + 6) / 12);
        boolean bank = scatter < TerrainMath.smooth((river.width() + 12 + beachWidth * .4 - river.distance() + 5) / 10);
        double flood=TerrainMath.smooth((river.width()+100-river.distance())/48)
                *TerrainMath.smooth((94-river.grade())/14)*TerrainMath.smooth((.25-slope)/.12);
        boolean floodplain = TerrainMath.noise(map.seed(),"floodplain_edge",x,z,1)<flood;
        boolean mountain = map.mountainWeight(x,z) > .05 || map.mountainIsland(x,z) != null;
        boolean editable=map.detailWeight(x,z)>.05;
        double gravel=relief.shore.rock()+slope*.6+(patch-.5)*.35+(scatter-.5)*.12;
        if (editable && relief.coast >= 0 && (beach || bank)) {
            material = gravel>.85 || slope>.8 ? profile.layers.getFirst().rock
                    : gravel>.35 ? riverGravel : Blocks.SAND.defaultBlockState();
            if (beach && relief.shore.rock() > .3 && slope < .35 && relief.shore.cliff() < .6
                    && TerrainMath.noise(map.seed(), "heavy_mineral_beach", x,z,256) > .55) material = blackSand;
            if (bank && !beach) material = river.bank()>.3 && slope>.3 ? null
                    : floodplain ? (patch>.68?Blocks.CLAY.defaultBlockState():silt)
                    : river.bank()<-.15 && patch<.5 ? Blocks.SAND.defaultBlockState():riverGravel;
        }
        if (relief.coast < 0) {
            int depth = 63 - relief.height;
            material = slope > .6 ? profile.layers.getFirst().rock
                    : depth <= 12 ? (relief.shore.rock() > .35 ? riverGravel : Blocks.SAND.defaultBlockState())
                    : depth < 40 && slope < .3 ? Blocks.GRAVEL.defaultBlockState()
                    : TerrainMath.noise(map.seed(), "abyssal_clay",x,z,384) > .5 ? clay : Blocks.CLAY.defaultBlockState();
            if (depth < 18 && river.distance() < Math.min(150,river.width()+96) && river.surface() < 68 && slope < .25) material = silt;
        } else if (relief.water != Integer.MIN_VALUE) {
            material = floodplain ? (patch>.65?Blocks.CLAY.defaultBlockState():silt)
                    : river.bank()<-.15 && patch<.45 ? Blocks.SAND.defaultBlockState():riverGravel;
        }
        if (relief.water == Integer.MIN_VALUE && editable) {
            if (rock || map.mountainIsland(x,z) != null || map.mountainWeight(x,z)>.1 && relief.height>140 && slope>.25) material = null;
            else if (mountain && scatter < TerrainMath.smooth((slope-.08)/.18)
                    *TerrainMath.smooth((hollow+.1)/.8)) material = talus;
            else if (!beach && slope < .35 && relief.height < 150 && z < -12300+(patch-.5)*80 && mountain) material = till;
            else if (!beach && floodplain) material = silt;
            else if (!beach && hollow > .8 && relief.height < 115 && soil < 4) material = Blocks.MUD.defaultBlockState();
            else if (!beach && !bank && slope>.08 && hollow>.15 && patch>.7) material = Blocks.GRAVEL.defaultBlockState();
            else if (!beach && !bank && dry>.35+patch*.25 && slope < .2) material = loess;
            else if (!beach && !bank && slope>.08 && patch>.75) material = Blocks.COARSE_DIRT.defaultBlockState();
        }
        if (editable && !rock && slope < .18 && material != null) {
            boolean tidal = relief.coast > -64 && relief.coast < 48 && relief.height >= 59 && relief.height <= 66
                    && relief.shore.cliff() < .15 && (river.surface() < 68 || relief.shore.rock() < .2);
            if (tidal && patch > .25) material = tidalMud;
            else if (!beach && relief.coast > 48 && relief.height < 150 && hollow > .25) {
                if (MapCoordinates.latitude(z) > 58 && dry < .4 && patch > .3) material = peat;
                else if (dry > .4 + patch*.2 && !bank && relief.water == Integer.MIN_VALUE)
                    material = crackedMud;
                else if (dry < .3 && relief.height < 120 && patch > .35) material = organicMud;
                else if (dry > .2 && hollow > .5) material = Blocks.PACKED_MUD.defaultBlockState();
            }
        }
        return material;
    }

    /** 地面・雪層・水面と母岩を持つ不変の列。stateは生成範囲外で空気を返す。 */
    public static final class Column {
        private final int surface, waterY;
        private final BlockState soil, water;
        private final BlockState frozenWater;
        private final int[] boundaries;
        private final Profile profile;
        private final int ice;
        private final int snow;
        private final int x, z;
        private final FixedUnderground.Cell underground;
        private final int[] passageFloors;
        private final int soilDepth;
        private final BlockState cover;
        private final boolean powder;
        private final BlockState[] facing;
        private final int facingOffset;
        private final double geologyWeight, fold, bandWidth;
        private final int contactFloor;

        private Column(int surface, int waterY, BlockState soil, BlockState water, int[] boundaries, Profile profile,
                       int ice, int snow, int x, int z, FixedUnderground.Cell underground, int soilDepth, BlockState cover, boolean powder,
                       BlockState[] facing, int facingOffset, double geologyWeight, double fold, double bandWidth, BlockState frozenWater, int contactFloor) {
            this.surface = surface;
            this.waterY = waterY;
            this.frozenWater = frozenWater;
            this.soil = soil;
            this.water = water;
            this.boundaries = boundaries;
            this.profile = profile;
            this.ice = ice;
            this.snow = snow;
            this.x = x;
            this.z = z;
            this.underground = underground;
            passageFloors = FixedUnderground.passageFloors(underground, x, z);
            this.soilDepth = soilDepth;
            this.cover = FixedUnderground.carved(passageFloors, surface + 1) ? null : cover;
            this.powder = powder;
            this.facing=facing;
            this.facingOffset=facingOffset;
            this.geologyWeight=geologyWeight;
            this.fold=fold;
            this.bandWidth=bandWidth;
            this.contactFloor = contactFloor;
        }

        public int surfaceY() { return surface; }
        public int topY() { return Math.max(surface + (cover == null ? 0 : 1), waterY); }

        public BlockState state(int y) {
            if (profile == null || y < -128 || y > 575 || y > topY()) return Blocks.AIR.defaultBlockState();
            if (y == -128) return Blocks.BEDROCK.defaultBlockState();
            if (FixedUnderground.carved(passageFloors, y)) return Blocks.AIR.defaultBlockState();
            if (y > surface) return frozenWater != null && y == waterY ? frozenWater : cover != null && y == surface + 1 ? cover : water;
            if (powder && y >= surface - 1) return Blocks.POWDER_SNOW.defaultBlockState();
            if (snow > 0 && y > surface - snow) return Blocks.SNOW_BLOCK.defaultBlockState();
            int iceDepth = surface - snow + 1 - y;
            if (iceDepth >= 1 && iceDepth <= ice) return (iceDepth >= 4 ? Blocks.BLUE_ICE : Blocks.PACKED_ICE).defaultBlockState();
            if (soil != null && y > surface - soilDepth) return soil;
            var rock = rockState(y);
            return underground == null ? rock : FixedUnderground.state(underground, x, y, z, rock);
        }

        /** 土壌・水・雪氷を重ねる前の母岩。Eの母岩条件に使い、範囲外と地表より上は空気、底は岩盤を返す。 */
        public BlockState rockState(int y) {
            if (profile == null || y < -128 || y > surface) return Blocks.AIR.defaultBlockState();
            if (y == -128) return Blocks.BEDROCK.defaultBlockState();
            BlockState material=profile.basement;
            for (int i=0;i<boundaries.length;i++)if(y>boundaries[i]){material=profile.layers.get(i).rock;break;}
            if(facing!=null && (y>surface-3 || geologyWeight<1 && y>contactFloor)) {
                material = weatheredState(y, material);
            }
            return underground==null?material:FixedUnderground.rock(underground,x,y,z,material);
        }

        private BlockState weatheredState(int y, BlockState rock) {
            double position = (y + facingOffset + fold * geologyWeight) / (5 + (bandWidth - 5) * geologyWeight);
            int stratum = (int) Math.floor(position);
            int band = geologyWeight == 0 ? Math.floorMod(stratum, 9) : Math.floorMod(stratum ^ (stratum >> 2), 9);
            return band < 6 ? facing[band] : rock;
        }
    }
}
