package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonObject;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.SedimentLayerBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.LiquidBlock;
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
    private final BlockState[] soils = new BlockState[4];
    private final FixedContamination contamination;
    private final BlockState[] waters = new BlockState[9];
    private final BlockState[] waterIce = new BlockState[9];
    private final FixedUnderground underground;
    private final Map<Long, Integer> anchorSurfaces;
    private final double islandReferenceY;
    private final BlockState clay;
    private final BlockState talus, riverGravel, till, silt, loess, blackSand;
    private final BlockState talusLayer, siltLayer, loessLayer;
    private final BlockState tidalMud, organicMud, peat, crackedMud;
    private final BlockState[] weathered;
    private record Surface(int height, int water, int quality, double coast, RiverCourse.Sample river, OceanRelief.Shore shore, double continuous) {}

    public FixedTerrain(MapVersion version) {
        map = FixedMapData.require(version);
        contamination = new FixedContamination(map);
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
        islandReferenceY = geology.get("island_reference_y").getAsDouble();
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
        String[] soilIds = {"fertile_soil", "poor_soil", "degraded_soil"};
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
        return sampleSurface(map,x,z,shore,true);
    }

    static int unconstrainedSurfaceY(FixedMapData map,int x,int z) {
        return sampleSurface(map,x,z,OceanRelief.shore(map,x,z),false).height;
    }

    private static Surface sampleSurface(FixedMapData map, int x, int z, OceanRelief.Shore shore, boolean controlled) {
        double coast = map.coastDistance(x, z), weight = map.detailWeight(x, z);
        var river = map.river(x, z);
        double height = coast < 0 ? map.ocean().height(map,x,z,-coast,shore) : landHeight(map, x, z, coast, weight, shore);
        double natural=height;
        int water = Integer.MIN_VALUE, quality = 0;
        if (coast < 0) {
            water = 63; quality = 8;
        } else {
            if (river.distance() < river.width()) {
                water = (int) Math.floor(river.surface()); quality = map.riverQuality(river);
                height = riverBed(river);
            } else if (river.distance() < 256) {
                height=riverBankHeight(map,x,z,natural,river);
                if(river.distance()<144) {
                    var other=map.riverBankAlternative(x,z);
                    if(other!=null) {
                        double bank=riverBankHeight(map,x,z,natural,other);
                        double edge=Math.min(river.distance()-river.width(),other.distance()-other.width());
                        double width=6*TerrainMath.smooth((edge-3)/10);
                        double overlap=TerrainMath.smooth(Math.min(natural-height,natural-bank)/8);
                        double round=width==0?0:Math.pow(1-Math.min(1,Math.abs(height-bank)/width),2)*width*.25*overlap;
                        double joined=Math.min(height,bank)-round;
                        height+=(joined-height)*TerrainMath.smooth((edge-2)/6);
                    }
                }
            }
            var source=map.riverSource();double north=source.z()-z;
            if(north>0 && north<320 && Math.abs(x-source.x())<220) {
                height+=(natural-height)*TerrainMath.smooth(north/20);
                height=Math.min(height,TerrainMath.valleyHead(map.seed(),x,z,source,natural));
            }
            if (river.distance() >= river.width() && (map.category(4, x, z) & 4) != 0) {
                water = map.waterSurface(x, z); quality = map.waterQuality(x, z);
            }
        }
        if (coast < 0 && river.distance() < river.width()) height = Math.min(height, riverBed(river));
        if(controlled)height+=map.surfaceCorrection(x,z);
        int rounded = (int) Math.round(height);
        if (water != Integer.MIN_VALUE) rounded = Math.min(rounded, water - (coast >= 0 && river.distance() < river.width() ? (int)Math.min(2,Math.ceil(river.depth()-.5)) : 1));
        else rounded = Math.max(coast < 16 ? 63 : 64, rounded);
        return new Surface(Math.clamp(rounded, -127, 520), water, quality, coast, river, shore, Math.clamp(height,-127,520));
    }

    static double riverBankHeight(FixedMapData map,int x,int z,double height,RiverCourse.Sample river) {
        double reach=TerrainMath.noise(map.seed(),"river_bank",river.x(),river.z(),192);
        double slope=.07+.12*reach+.3*Math.max(0,river.bank());
        double headwater=river.tributary()?1:1-TerrainMath.smooth((river.station()-2400)/1800);
        double gorge=1-TerrainMath.smooth((river.station()-550)/650);
        double cut=TerrainMath.valleyHeight(height,river.grade()+1,river.distance(),river.width(),slope);
        double d=Math.max(0,river.distance()-river.width());
        if(headwater>0) {
            double shoulder=TerrainMath.fbm(map.seed(),"brook_shoulder",x,z,36);
            double level=river.surface()+(river.grade()-river.surface())*TerrainMath.smooth((d-2)/8);
            double detail=1-TerrainMath.smooth((river.station()-1600)/400);
            double v=level+.35+d*(.32+shoulder*.12)+Math.pow(d,1.35)*.02
                    +shoulder*(3+3*detail*TerrainMath.smooth(d/32))*TerrainMath.smooth(d/8);
            if(gorge>0) {
                double strength=1.8+.4*TerrainMath.smooth(river.station()/32);
                double narrow=level+.35+d*.35+Math.pow(Math.max(0,d-.8),1.25)*(strength+shoulder*.35);
                double rocks=d>3?(TerrainMath.massifRelief(map.seed(),x,z)+12)*.9*TerrainMath.smooth((d-3)/10):0;
                narrow=Math.max(level+.35+d*.35,narrow+rocks);
                v+=(narrow-v)*gorge;
            }
            if(river.tributary() && d<3)v+=.8*TerrainMath.smooth((TerrainMath.noise(map.seed(),"brook_boulders",x,z,9)-.65)/.2)
                    *(1-TerrainMath.smooth(d/3));
            double bank=Math.min(height,v)+(height-Math.min(height,v))*TerrainMath.smooth((d-(20-12*gorge))/(100-60*gorge));
            cut+=(bank-cut)*headwater;
        }
        double terrace=river.tributary()?0:(1-TerrainMath.smooth(Math.abs(d-65)/32))*TerrainMath.smooth((reach-.45)/.3)
                *(1-Math.max(0,river.bank()))*TerrainMath.smooth((river.station()-1400)/400);
        double bench=river.grade()+8+(d-50)*.025;
        height=Math.min(height,cut+(Math.min(cut,bench)-cut)*terrace);
        double lip=TerrainMath.smooth(d/(4-2*gorge)),rim=river.surface()+1-headwater*.65;
        height=Math.min(height,rim+(height-rim)*lip);
        double bankFloor=river.surface()+(river.riffle()>0?1:0);
        return height+(Math.max(height,bankFloor)-height)*headwater*(1-TerrainMath.smooth((d-2)/6));
    }

    private static double riverBed(RiverCourse.Sample river) {
        double edge=Math.min(2,river.depth());
        double depth = edge + (Math.max(edge,river.depth()-1.2*river.riffle())-edge) * (1-Math.pow(river.distance()/river.width(),2));
        return river.surface() - depth;
    }

    static double landHeight(FixedMapData map, int x, int z) {
        return landHeight(map, x, z, map.coastDistance(x, z), map.detailWeight(x, z), OceanRelief.shore(map,x,z));
    }

    private static double landHeight(FixedMapData map, int x, int z, double coast, double weight, OceanRelief.Shore shore) {
        double shoreHeight = OceanRelief.shoreHeight(coast,shore);
        var island = map.mountainIsland(x, z);
        if (island != null && coast >= 0) {
            double rocks=TerrainMath.rockRelief(map.seed(),x,z);
            double detail = TerrainMath.fbm(map.seed(), "island_gullies", x,z,32);
            double massif=TerrainMath.massifRelief(map.seed(),x,z),strait=map.islandStraitWeight(x,z);
            var junction=map.islandJunction(x,z);
            if(junction==null)return islandHeight(island,x,z,coast,rocks,detail,massif,strait);
            return TerrainMath.bilinear(
                    islandHeight(junction.nw(),x,z,coast,rocks,detail,massif,strait),
                    islandHeight(junction.ne(),x,z,coast,rocks,detail,massif,strait),
                    islandHeight(junction.sw(),x,z,coast,rocks,detail,massif,strait),
                    islandHeight(junction.se(),x,z,coast,rocks,detail,massif,strait),
                    TerrainMath.smooth(TerrainMath.fraction(x)),TerrainMath.smooth(TerrainMath.fraction(z)));
        }
        double wx = (TerrainMath.noise(map.seed(), "relief_x", x, z, 256) - 0.5) * 24 * weight;
        double wz = (TerrainMath.noise(map.seed(), "relief_z", x, z, 256) - 0.5) * 24 * weight;
        double base = map.height(x + wx, z + wz);
        double terminal=map.terminationWeight(x,z);
        double headwater=TerrainMath.smooth((z+12650)/240.0)*TerrainMath.smooth((-10000-z)/300.0)
                *TerrainMath.smooth((x+5100)/240.0)*TerrainMath.smooth((-3500-x)/240.0);
        double mountain = map.mountainWeight(x, z) * TerrainMath.smooth((base - 130+40*terminal) / (70-20*terminal));
        double amplitude=8+18*TerrainMath.noise(map.seed(),"relief_region",x,z,768);
        double detail=TerrainMath.fbm(map.seed(), "surface_detail", x+wx,z+wz,224);
        detail=.2-Math.abs(detail);
        if(mountain>0) {
            double ridge=TerrainMath.noise(map.seed(),"mountain_detail",x+wx,z+wz,48)*2-1;
            if(terminal>0)ridge+=(TerrainMath.fbm(map.seed(),"mountain_detail",x+wx,z+wz,128)-ridge)*terminal;
            detail=detail*(1-mountain)+ridge*mountain;
        }
        detail*=amplitude+(64-40*terminal)*mountain;
        detail*=1-mountain+mountain*TerrainMath.smooth((coast-96*(1-terminal))/(192-168*terminal));
        double rough=mountain>0?TerrainMath.rockRelief(map.seed(),x,z)
                :(TerrainMath.noise(map.seed(),"lowland_rills",x+wx,z+wz,12)*2-1)*3;
        if(terminal>0)rough+=(TerrainMath.massifRelief(map.seed(),x,z)-rough)*terminal*mountain;
        if(headwater>0 && mountain>0)rough+=(TerrainMath.massifRelief(map.seed(),x,z)-rough)*headwater;
        if(headwater>0 && mountain>0 && mountain<.35) {
            double lowland=(TerrainMath.noise(map.seed(),"lowland_rills",x+wx,z+wz,12)*2-1)*3;
            rough+=(lowland-rough)*headwater*(1-TerrainMath.smooth(mountain/.35));
        }
        double steep=mountain>0?TerrainMath.smooth((Math.abs(map.height(x+16.0,z)-base)
                +Math.abs(map.height((double)x,z+16)-base))/16):0;
        if(headwater>0 && mountain>0) {
            double sx=(map.height(x+wx+16,z+wz)-map.height(x+wx-16,z+wz))/32;
            double sz=(map.height(x+wx,z+wz+16)-map.height(x+wx,z+wz-16))/32;
            steep+=(TerrainMath.smooth(Math.hypot(sx,sz))-steep)*headwater;
        }
        double bedding=(base+x*.12-z*.08)/8;
        double fraction=bedding-Math.floor(bedding);
        double ledge=(TerrainMath.smooth((fraction-.2)/.6)-fraction)*8*Math.max(0,rough/16)*mountain*steep*(1-headwater);
        int ceiling=base>350?map.mountainCeiling(x,z):520;
        double variation=(detail+rough*(.3+.3*terminal+(1.6-.4*terminal)*mountain*steep)+ledge)*weight
                + TerrainMath.fbm(map.seed(), "terrace_detail", x, z, 32) * 1.4 * weight * TerrainMath.smooth(coast / 32);
        double height = base + variation*Math.clamp((ceiling-base)/(70+Math.max(0,variation)),0,1);
        height=Math.min(height,ceiling);
        double blend = TerrainMath.smooth(coast / ((180-80*shore.rock())*(1-terminal)+48*terminal));
        return map.walkways().height(x, z, Math.max(coast < 16 ? 63 : 64, shoreHeight + (height - shoreHeight) * blend));
    }

    static double islandHeight(FixedMapData.MountainIsland island,int x,int z,double coast,
                                       double rocks,double detail,double massif,double strait) {
        double height=63+Math.max(0,4+rocks*.5),peakDistance=Double.MAX_VALUE;
        for(var peak:island.peaks()) {
            height=Math.max(height,TerrainMath.islandPeak(x-peak.x(),z-peak.z(),peak.y(),peak.radius(),peak.cos(),peak.sin(),detail));
            peakDistance=Math.min(peakDistance,Math.hypot(x-peak.x(),z-peak.z()));
        }
        double relief=rocks*TerrainMath.smooth((peakDistance-16)/24);
        double lower=TerrainMath.smooth((63+island.peakY()*.65-height)/32);
        relief+=massif*.55*lower*TerrainMath.smooth((peakDistance-24)/32)*TerrainMath.smooth(coast/24);
        double margin=strait*TerrainMath.smooth((peakDistance-20)/32)*(1-TerrainMath.smooth((coast-24)/24));
        relief+=massif*.5*margin;
        height=Math.min(island.peakY(),height+relief*TerrainMath.smooth((height-63)/24));
        double rise=8+8*TerrainMath.smooth((peakDistance-12)/20)+margin*(20+detail*8);
        return 63+(height-63)*TerrainMath.smooth(coast/rise);
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
        if (!MapCoordinates.contains(x, z)) return new Column(SoilContamination.CLEAN, -65, Integer.MIN_VALUE, null, null, new int[0], null, 0, 0, x, z, null, 0, null, -64, false, null, 0,0,0,1, null, 575, false, Integer.MIN_VALUE);
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
        var island = map.mountainIsland(x,z);
        double datum = island != null ? islandReferenceY : profile.referenceY;
        double terminal=map.terminationWeight(x,z);
        datum+=70*terminal;
        displacement+=((x+13500)*.25-(z+11712)*.25)*terminal;
        if(island!=null) {
            displacement+=(x-island.x())*.38-(z-island.z())*.26;
            regionalFold+=TerrainMath.fbm(map.seed(),"island_bedding",x,z,48)*1.5;
        }
        if (profile.uplift != null && island == null) {
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
        boolean rock = slope >= .9 || island != null && relief.coast < 8
                || island == null && relief.shore.rock() > .65 && relief.coast < 96;
        int layers = 0;
        double aboveLine = 0;
        if (!wet && (MapCoordinates.latitude(z) >= 45 || biome == 9 || surface >= 180)) {
            double line = TerrainMath.snowline(map.seed(), x, z, Math.abs(MapCoordinates.latitude(z)), sx, sz, false);
            line+=(45-(230-Math.max(0,Math.abs(MapCoordinates.latitude(z))-45)*3))*boundary.cold();
            line -= Math.clamp(reference - relief.continuous, 0, 80) * .5 * mountain;
            if(x>-4400 && x<-3600 && z>-12750 && z<-11750) {
                double drift=TerrainMath.fbm(map.seed(),"headwater_snow",x,z,48);
                double along=-12100-z,across=x+4000;
                double catchment=Math.min(Math.min(TerrainMath.smooth((along+180+drift*50)/120),TerrainMath.smooth((540-along+drift*70)/160)),
                        Math.min(TerrainMath.smooth((220-across+drift*60)/100),TerrainMath.smooth((across+260+drift*80)/120)));
                catchment*=TerrainMath.smooth((190-relief.continuous+drift*20)/60);
                line-=(80+drift*35+Math.clamp(hollow+sz*4,0,8)*3)*catchment;
            }
            layers = rock ? 0 : TerrainMath.snowLayers(surface, line, slope);
            double margin=TerrainMath.smooth((relief.coast-8)/48);
            double edge=TerrainMath.smooth((relief.continuous-line+16)/32)*margin;
            double scatter=TerrainMath.noise(map.seed(),"snow_edge",x,z,1);
            layers=Math.min(layers,TerrainMath.depositLayers(edge,scatter));
            double bankDistance=relief.river.distance()-relief.river.width();
            if(bankDistance>=0 && bankDistance<4 && (relief.river.tributary() || relief.river.station()<2400))
                layers=surface<=relief.river.surface()+2?0:Math.min(layers,Math.max(1,(int)Math.ceil(bankDistance*1.3)));
            aboveLine = relief.continuous-line;
        }
        if (island != null) layers = Math.min(layers,4);
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
        int soilDepth = (island == null ? 2 : 1) + (int) (TerrainMath.noise(map.seed(), "soil_thickness", x, z, 20) * (island == null ? 3 : 2));
        boolean walkway=map.walkways().sample(x,z).distance()<1;
        if(walkway && layers<8)layers=Math.min(layers,2);
        int cover = TerrainMath.snowCover(relief.continuous,surface,layers);
        BlockState cap = cover == 0 || powder ? null : Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, cover);
        int coverY = surface + 1;
        if (cap == null && !wet && snow == 0 && ice == 0 && !powder && map.detailWeight(x,z)>.05 && slope < .65) {
            BlockState sediment = surfaceMaterial == silt ? siltLayer : surfaceMaterial == loess ? loessLayer
                    : surfaceMaterial == talus || surfaceMaterial == null && mountain > .05 && slope > .15 && hollow > .2 ? talusLayer : null;
            if (sediment != null) {
                double edge=sediment==loessLayer?TerrainMath.smooth((boundary.dry()-.25)/.5):TerrainMath.smooth((.65-slope)/.2);
                if(sediment==siltLayer)edge*=TerrainMath.smooth((relief.river.width()+100-relief.river.distance())/48);
                if(sediment==talusLayer)edge*=TerrainMath.smooth((slope-.08)/.18)*TerrainMath.smooth((hollow+.1)/.8);
                double coastWidth=sediment==talusLayer?0:relief.shore.beachWidth();
                double coastCover=TerrainMath.smooth((relief.coast-coastWidth+6)/32);
                if(sediment==loessLayer && coastCover<=.92)surfaceMaterial=Blocks.SAND.defaultBlockState();
                edge*=coastCover;
                if(sediment==loessLayer)edge*=TerrainMath.smooth((.28-slope)/.1);
                double scatter = TerrainMath.noise(map.seed(),"sediment_edge",x,z,1);
                int top = TerrainMath.sedimentCover(relief.continuous, surface, edge, scatter, slope, hollow);
                if(top>0) {
                    coverY = surface + (top-1)/8;
                    cap=sediment.setValue(SnowLayerBlock.LAYERS,1+(top-1)%8);
                }
            }
        }
        var pollution = contamination.surface(x, z, relief.coast, relief.river);
        if (cap == null && !wet && surfaceMaterial != null && BlockContamination.isTarget(surfaceMaterial) && pollution.severe()
                && TerrainMath.noise(map.seed(), "contamination_dead_bush", x, z, 1) < .08)
            cap = NatureContent.block("dead_grass").defaultBlockState();
        if(walkway && cap!=null && cap.hasProperty(SnowLayerBlock.LAYERS)) {
            coverY=surface+1;
            cap=cap.setValue(SnowLayerBlock.LAYERS,Math.min(2,cap.getValue(SnowLayerBlock.LAYERS)));
        }
        int waterKind = relief.coast < 0 ? 3 : relief.river.distance() < relief.river.width() ? 2 : 1;
        BlockState frozen = wet ? frozenWater(quality, x, z, relief.water, relief.water - surface, waterKind, boundary.cold()) : null;
        int spill=relief.water;
        if(frozen==null && relief.coast>=0 && relief.river.distance()<relief.river.width()
                && (relief.river.riffle()>0 || relief.river.tributary())) {
            for(var direction:Direction.Plane.HORIZONTAL) {
                var neighbor=map.river(x+direction.getStepX(),z+direction.getStepZ());
                if(neighbor.distance()<neighbor.width())spill=Math.max(spill,(int)neighbor.surface());
            }
        }
        boolean contactWeathered=anchor!=null && anchor.weathered();
        return new Column(pollution, surface, relief.water, surfaceMaterial, waters[quality], boundaries, profile, ice, snow, x, z, cell,
                soilDepth, cap, coverY, powder, surfaceMaterial==null && (slope>.35 || mountain>.1) || contactWeathered ? weathered:null,
                (int)(regionalFold*24+patch*3),geologyWeight,tilt+regionalFold*96+(island==null?0:displacement*3),
                18+24*TerrainMath.noise(map.seed(),"rock_band_width",x,z,192), frozen, anchor == null ? 575 : (int) anchorY - 20, contactWeathered, spill);
    }

    BlockState frozenWater(int quality, int x, int z, int water, int depth, int kind, double cold) {
        double latitude = Math.abs(MapCoordinates.latitude(z)) + TerrainMath.fbm(map.seed(), "freezing_edge", x, z, 384) * 1.5;
        return quality >= 4 && quality <= 8 && TerrainMath.freezes(latitude, water, depth, kind, cold)
                ? waterIce[quality] : null;
    }

    private BlockState surfaceMaterial(int x, int z, Surface relief, Profile profile, int soil,
                                       double slope, double hollow, boolean rock, double dry, double patch) {
        if (rock) return null;
        if (map.mountainIsland(x,z) != null && relief.coast >= 0) {
            double scatter=TerrainMath.noise(map.seed(),"island_sediment_edge",x,z,1);
            double rubble=TerrainMath.smooth((hollow+.2)/.9)*TerrainMath.smooth((slope-.08)/.25);
            if(scatter<rubble)return talus;
            if(slope<.28 && relief.coast>24 && hollow>.15)return soils[soil]==null?Blocks.COARSE_DIRT.defaultBlockState():soils[soil];
            return null;
        }
        BlockState material = soils[soil];
        double beachWidth = relief.shore.beachWidth();
        var river = relief.river;
        if(river.tributary() && relief.water==Integer.MIN_VALUE && river.distance()<river.width()+2
                && TerrainMath.noise(map.seed(),"brook_boulders",x,z,9)>.65)return Blocks.COBBLESTONE.defaultBlockState();
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
            if(scatter<TerrainMath.smooth((map.islandOffshoreWeight(x,z)-.3)/.4) && depth<48)material=slope>.45?null:talus;
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
        private final SoilContamination pollution;
        private final int surface, waterY;
        private final int spillY;
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
        private final int coverY;
        private final boolean powder;
        private final BlockState[] facing;
        private final int facingOffset;
        private final double geologyWeight, fold, bandWidth;
        private final int contactFloor;
        private final boolean contactWeathered;

        private Column(SoilContamination pollution, int surface, int waterY, BlockState soil, BlockState water, int[] boundaries, Profile profile,
                       int ice, int snow, int x, int z, FixedUnderground.Cell underground, int soilDepth, BlockState cover, int coverY, boolean powder,
                       BlockState[] facing, int facingOffset, double geologyWeight, double fold, double bandWidth, BlockState frozenWater, int contactFloor, boolean contactWeathered, int spillY) {
            this.pollution = pollution;
            this.surface = surface;
            this.contactWeathered=contactWeathered;
            this.waterY = waterY;
            this.spillY = spillY;
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
            this.cover = FixedUnderground.carved(passageFloors, coverY) ? null : cover;
            this.coverY = coverY;
            this.powder = powder;
            this.facing=facing;
            this.facingOffset=facingOffset;
            this.geologyWeight=geologyWeight;
            this.fold=fold;
            this.bandWidth=bandWidth;
            this.contactFloor = contactFloor;
        }

        /** 表層から離れた地下堆積物は清浄。薄層の上面を深さ0とする。 */
        public SoilContamination contamination(int y) {
            int top = cover != null && cover.getBlock() instanceof SedimentLayerBlock ? coverY : surface;
            if (y > top) return SoilContamination.CLEAN;
            return pollution.atDepth(Math.max(0, top - y));
        }

        /** 地質・施設用の基準Y。堆積薄層はこの最上段内または直上に収まり、実際の最上ブロックはtopYで得る。 */
        public int surfaceY() { return surface; }
        public int topY() { return Math.max(cover == null ? surface : coverY, spillY); }

        public BlockState state(int y) {
            if (profile == null || y < -128 || y > 575 || y > topY()) return Blocks.AIR.defaultBlockState();
            if (y == -128) return Blocks.BEDROCK.defaultBlockState();
            if (FixedUnderground.carved(passageFloors, y)) return Blocks.AIR.defaultBlockState();
            if (y > surface) return frozenWater != null && y == waterY ? frozenWater : cover != null && y == coverY ? cover
                    : y>waterY ? water.setValue(LiquidBlock.LEVEL,8) : water;
            if (cover != null && y == coverY) return cover;
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
            if(facing!=null && (y>surface-3 || contactWeathered && geologyWeight<1 && y>contactFloor)) {
                material = weatheredState(y, material);
            }
            return underground==null?material:FixedUnderground.rock(underground,x,y,z,material);
        }

        private BlockState weatheredState(int y, BlockState rock) {
            double position = (y + facingOffset + fold * geologyWeight) / (5 + (bandWidth - 5) * geologyWeight);
            int stratum = (int) Math.floor(position);
            int band = geologyWeight == 0 ? Math.floorMod(stratum, 9) : Math.floorMod(stratum ^ (stratum >> 2), 9);
            if(geologyWeight==0)return band<6?facing[band]:rock;
            return band<6?facing[band]:band==6?rock:facing[band-5];
        }
    }
}
