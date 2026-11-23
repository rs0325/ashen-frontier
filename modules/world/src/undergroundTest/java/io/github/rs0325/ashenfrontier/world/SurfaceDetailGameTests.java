package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.rs0325.ashenfrontier.core.vanilla.RemovedItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurfaceDetailGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void shoresVaryAndValleysDoNotFormLevees(GameTestHelper helper) throws Exception {
        var terrain = TerrainPreviewGameTests.generator(helper).terrain();
        var map = terrain.map();
        var samples = new StringBuilder("area,x,z,height,natural,water,material,width,coast,riffle\n");
        var materials = new HashSet<String>();
        double narrow = 100, wide = 0;
        int banks = 0;
        for (var point : new int[][]{{-11216, -6880}, {-5024, -8272}}) {
            for (int dz = -384; dz <= 384; dz += 4) for (int dx = -384; dx <= 384; dx += 4) {
                int x = point[0] + dx, z = point[1] + dz;
                var column = terrain.column(x, z);
                var river = map.river(x, z);
                double coast = map.coastDistance(x, z), natural = FixedTerrain.landHeight(map, x, z);
                String material = BuiltInRegistries.BLOCK.getKey(column.state(column.surfaceY()).getBlock()).toString();
                if (coast > 0 && coast < 16 && !map.isProtected(x, z)) materials.add(material);
                if (coast > 160 && river.distance() >= river.width() && river.distance() < 160) {
                    helper.assertTrue(column.surfaceY() <= Math.round(natural), "実際の河岸を周囲より高くしない");
                    banks++;
                }
                if (river.distance() < 64) { narrow = Math.min(narrow, river.width()); wide = Math.max(wide, river.width()); }
                if (output() != null) samples.append(point[0]).append(',').append(x).append(',').append(z).append(',')
                        .append(column.surfaceY()).append(',').append(natural).append(',').append(map.waterSurface(x, z)).append(',')
                        .append(material).append(',').append(river.width()).append(',').append(coast).append(',').append(river.riffle()).append('\n');
            }
        }
        helper.assertTrue(materials.size() >= 2 && wide - narrow >= 2 && banks > 1000, "岸の材料・河幅の変化と河谷の確認数");
        LogUtils.getLogger().info("SURFACE_SHORES materials={} river_width={}..{} banks={}", materials, narrow, wide, banks);
        if (output() != null) Files.writeString(output().resolve("shore-variations.csv"), samples);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void thickSnowHidesSoilOnStepSides(GameTestHelper helper) throws Exception {
        var terrain = TerrainPreviewGameTests.generator(helper).terrain();
        int checked = 0, steps = 0;
        var depths = new HashSet<Integer>();
        var caps = new HashSet<Integer>();
        for (int z = -14640; z <= -14560; z += 2) for (int x = -3580; x <= -3380; x++) {
            var column = terrain.column(x, z);
            if (!column.state(column.surfaceY()).is(Blocks.SNOW_BLOCK)) continue;
            int depth = 0;
            while (column.state(column.surfaceY() - depth).is(Blocks.SNOW_BLOCK)) depth++;
            if (depth < 2) continue;
            helper.assertTrue(depth >= 2 && depth <= 4, "厚い積雪を2〜4ブロック重ねる");
            depths.add(depth);
            var cap = column.state(column.surfaceY() + 1);
            helper.assertTrue(cap.is(Blocks.SNOW), "厚い雪の上にも薄い雪層を重ねる");
            caps.add(cap.getValue(SnowLayerBlock.LAYERS));
            var adjacent = terrain.column(x + 1, z);
            int low = adjacent.surfaceY();
            if (adjacent.state(low).is(Blocks.SNOW_BLOCK) && column.surfaceY() > low && column.surfaceY() - low <= 2) {
                for (int y = low + 1; y <= column.surfaceY(); y++) {
                    helper.assertTrue(column.state(y).is(Blocks.SNOW_BLOCK), "段差の側面に土を露出させない");
                }
                steps++;
            }
            checked++;
        }
        helper.assertTrue(checked > 100 && steps > 20 && caps.size() >= 3, "厚い積雪と段差の確認数");
        LogUtils.getLogger().info("SURFACE_THICK_SNOW columns={} steps={} depths={} caps={}", checked, steps, depths, caps);
        if (output() != null) {
            section(terrain, "thick-snow", -3464, -14600, 128, 70, 125);
            var profile = new StringBuilder("x,ground,snow,layers,powder\n");
            for (int x = -3592; x <= -3336; x++) {
                var column = terrain.column(x, -14600);
                int snow = 0;
                while (column.state(column.surfaceY() - snow).is(Blocks.SNOW_BLOCK)) snow++;
                var cap = column.state(column.surfaceY() + 1);
                profile.append(x).append(',').append(column.surfaceY()).append(',').append(snow).append(',')
                        .append(cap.is(Blocks.SNOW) ? cap.getValue(SnowLayerBlock.LAYERS).intValue() : 0).append(',')
                        .append(column.state(column.surfaceY()).is(Blocks.POWDER_SNOW)).append('\n');
            }
            Files.writeString(output().resolve("thick-snow.csv"), profile);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void strataHaveLocalIndependentFolds(GameTestHelper helper) throws Exception {
        var terrain = TerrainPreviewGameTests.generator(helper).terrain();
        int low = 320, high = -64, thin = 384, thick = 0;
        for (int x = -8548; x <= -8452; x++) {
            var column = terrain.column(x, -8192);
            int top = -64, bottom = 320;
            for (int y = -63; y <= column.surfaceY(); y++) {
                if (column.rockState(y).is(Blocks.SANDSTONE)) { top = Math.max(top, y); bottom = Math.min(bottom, y); }
            }
            low = Math.min(low, bottom); high = Math.max(high, bottom);
            thin = Math.min(thin, top - bottom + 1); thick = Math.max(thick, top - bottom + 1);
        }
        helper.assertTrue(high - low >= 4, "96ブロック断面の地層境界に見えるうねり");
        helper.assertTrue(thick - thin >= 3, "上下の層を単に平行移動しない厚さの変化");
        LogUtils.getLogger().info("SURFACE_STRATA bottom_range={}..{} thickness_range={}..{}", low, high, thin, thick);
        if (output() != null) {
            section(terrain, "strata-spawn", -8500, -8192, 64, 16, 130);
            section(terrain, "strata-arid", -12400, -5300, 64, 16, 130);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void snowTransitionPowderSafetyAndSeabedMaterials(GameTestHelper helper) throws Exception {
        var terrain = TerrainPreviewGameTests.generator(helper).terrain();
        var thicknesses = new HashSet<Integer>();
        var heights = new HashSet<Integer>();
        int powder = 0, powderX = 0, powderZ = 0, snowX = 0, snowZ = 0;
        var samples = new StringBuilder("x,z,ground,top,layers,powder,material\n");
        for (int z = -12400; z <= -11200; z += 8) for (int x = -12400; x <= -10800; x += 8) {
            var column = terrain.column(x, z);
            var cap = column.state(column.surfaceY() + 1);
            int layers = cap.is(Blocks.SNOW) ? cap.getValue(SnowLayerBlock.LAYERS) : 0;
            if (layers > 0 && layers < 8 && !column.state(column.surfaceY()).is(Blocks.SNOW_BLOCK)
                    && !column.state(column.surfaceY()).is(Blocks.POWDER_SNOW)) {
                thicknesses.add(layers); heights.add(column.surfaceY());
                if (snowX == 0) { snowX = x; snowZ = z; }
            }
            samples.append(x).append(',').append(z).append(',').append(column.surfaceY()).append(',').append(column.topY()).append(',')
                    .append(layers).append(',').append(column.state(column.surfaceY()).is(Blocks.POWDER_SNOW)).append(',')
                    .append(BuiltInRegistries.BLOCK.getKey(column.state(column.topY()).getBlock())).append('\n');
        }
        for (int z = -14600; z <= -13800; z += 8) for (int x = -4600; x <= -3400; x += 8) {
            var column = terrain.column(x, z);
            if (column.state(column.surfaceY()).is(Blocks.POWDER_SNOW)) {
                helper.assertTrue(!terrain.map().hazardProtected(x, z), "粉雪を施設・経路・必須入口の周りに置かない");
                powder++;
                if (powderX == 0) { powderX = x; powderZ = z; }
            }
        }
        helper.assertTrue(thicknesses.size() >= 4 && heights.size() >= 8, "多様な高さと厚さを持つ積雪の移行帯: "+thicknesses+" "+heights.size());
        helper.assertTrue(powder > 0, "寒冷地域の粉雪の溜まり");
        var materials = new HashSet<String>();
        var seabed = new StringBuilder("x,z,height,coast,material\n");
        for (int x = -16384; x <= -12000; x += 4) {
            var column = terrain.column(x, -8192);
            if (terrain.map().coastDistance(x, -8192) >= 0) continue;
            var material = column.state(column.surfaceY());
            String id = BuiltInRegistries.BLOCK.getKey(material.getBlock()).toString();
            helper.assertTrue(Set.of("minecraft:sand", "minecraft:gravel", "minecraft:stone", "minecraft:clay",
                    "ashen_frontier:river_gravel", "ashen_frontier:black_sand", "ashen_frontier:silt", "ashen_frontier:tidal_mud", "ashen_frontier:clay_sediment").contains(id), "登録済み自然物の海底材");
            materials.add(id);
            seabed.append(x).append(',').append(-8192).append(',').append(column.surfaceY()).append(',')
                    .append(terrain.map().coastDistance(x, -8192)).append(',').append(id).append('\n');
        }
        helper.assertTrue(materials.containsAll(Set.of("minecraft:sand","minecraft:gravel","minecraft:clay","ashen_frontier:clay_sediment")), "浅瀬の砂・斜面の砂利・深部の二種の粘土質堆積物");
        for (var block : new net.minecraft.world.level.block.Block[]{Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW,
                Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SAND, Blocks.GRAVEL, Blocks.MUD, Blocks.COBBLESTONE, Blocks.COARSE_DIRT}) {
            helper.assertTrue(!RemovedItems.isRemoved(new ItemStack(block)), "削除済み自然物を復活させない");
        }
        LogUtils.getLogger().info("SURFACE_SNOW layers={} heights={} snow_xz={},{} powder_count={} powder_xz={},{} seabed={}",
                thicknesses, heights.size(), snowX, snowZ, powder, powderX, powderZ, materials);
        if (output() != null) {
            Files.writeString(output().resolve("snow-transition.csv"), samples);
            Files.writeString(output().resolve("seabed-materials.csv"), seabed);
            Files.writeString(output().resolve("viewpoints.txt"), "snow " + snowX + " " + terrain.surfaceY(snowX, snowZ) + " " + snowZ
                    + "\npowder " + powderX + " " + terrain.surfaceY(powderX, powderZ) + " " + powderZ + "\n");
            section(terrain, "snow-transition", snowX, snowZ, 128, 100, 290);
            section(terrain, "powder-pool", powderX, powderZ, 64, 50, 200);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID, timeoutTicks = 1200)
    public static void riverPoolsBanksAndMinimumDepth(GameTestHelper helper) throws Exception {
        var terrain = TerrainPreviewGameTests.generator(helper).terrain();
        var map = terrain.map();
        var version = TerrainPreviewGameTests.generator(helper).mapVersion();
        String path = "/data/ashen_frontier/fixed_map/" + version.mapVersion() + "/index.json";
        int wet = 0, banks = 0, pools = 0, riffles = 0;
        var longitudinal = new StringBuilder("x,z,water,bed,riffle\n");
        try (var stream = SurfaceDetailGameTests.class.getResourceAsStream(path)) {
            var index = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var points = index.getAsJsonArray("water_bodies").get(1).getAsJsonObject().getAsJsonArray("centerline_pixel_x_y_surface");
            int previous = Integer.MAX_VALUE;
            for (int i = 8; i < points.size() - 8; i += 8) {
                var point = points.get(i).getAsJsonArray();
                int x = MapCoordinates.MIN + point.get(0).getAsInt() * 16, z = MapCoordinates.MIN + point.get(1).getAsInt() * 16;
                var center = map.river(x, z);
                x = center.x(); z = center.z(); center = map.river(x, z);
                int water = map.waterSurface(x, z);
                helper.assertTrue(water <= previous, "下流へ非増加の水平な淵");
                previous = water;
                longitudinal.append(x).append(',').append(z).append(',').append(water).append(',').append(terrain.surfaceY(x, z)).append(',')
                        .append(center.riffle()).append('\n');
                if (map.coastDistance(x, z) < 160) continue;
                var a = points.get(i - 8).getAsJsonArray(); var b = points.get(i + 8).getAsJsonArray();
                double dx = b.get(0).getAsDouble() - a.get(0).getAsDouble(), dz = b.get(1).getAsDouble() - a.get(1).getAsDouble();
                double length = Math.hypot(dx, dz);
                if (length == 0) continue;
                for (int offset = -160; offset <= 160; offset += 2) {
                    int sx = (int) Math.round(x - offset * dz / length), sz = (int) Math.round(z + offset * dx / length);
                    var sample = map.river(sx, sz);
                    var column = terrain.column(sx, sz);
                    if (sample.distance() >= sample.width() && sample.distance() < 160) {
                        helper.assertTrue(column.surfaceY() <= Math.round(FixedTerrain.landHeight(map, sx, sz)), "河谷全体で周囲より盛り上げない");
                    }
                    if (sample.distance() < sample.width()) {
                        int level = map.waterSurface(sx, sz);
                        int minimum=sample.tributary() || sample.station()<=1000?1:2;
                        helper.assertTrue(level - column.surfaceY() >= minimum, "源流・支沢は浅く、中下流には2ブロック以上の水深");
                        helper.assertTrue(!column.state(level).getFluidState().isEmpty() && !column.state(level-minimum+1).getFluidState().isEmpty(), "水のないへこみを作らない");
                        if (!sample.tributary() && center.riffle() == 0 && Math.abs(offset) < 20) {
                            helper.assertTrue(level == water, "淵の横断方向の水面は水平"); pools++;
                        }
                        wet++;
                    } else if (sample.distance() < 64) {
                        helper.assertTrue(column.surfaceY() >= sample.surface() + 1, "両岸を水面より1ブロック以上高くする");
                        helper.assertTrue(column.surfaceY() <= Math.round(FixedTerrain.landHeight(map, sx, sz)), "周囲の地形を持ち上げずに川岸を彫る: " + sx + "," + sz);
                        banks++;
                    }
                }
                if (center.riffle() > 0.5) riffles++;
            }
            if (output() != null) {
                longitudinal.setLength(0);
                longitudinal.append("x,z,water,bed,riffle\n");
                for (int i = 1; i < points.size(); i++) {
                    var a = points.get(i - 1).getAsJsonArray(); var b = points.get(i).getAsJsonArray();
                    double ax = MapCoordinates.MIN + a.get(0).getAsInt() * 16, az = MapCoordinates.MIN + a.get(1).getAsInt() * 16;
                    double dx = (b.get(0).getAsInt() - a.get(0).getAsInt()) * 16, dz = (b.get(1).getAsInt() - a.get(1).getAsInt()) * 16;
                    int steps = (int) Math.ceil(Math.hypot(dx, dz) / 2);
                    for (int step = 0; step < steps; step++) {
                        var center = map.river((int) Math.round(ax + dx * step / steps), (int) Math.round(az + dz * step / steps));
                        int x = center.x(), z = center.z();
                        longitudinal.append(x).append(',').append(z).append(',').append(map.waterSurface(x, z)).append(',')
                                .append(terrain.surfaceY(x, z)).append(',').append(center.riffle()).append('\n');
                    }
                }
            }
        }
        helper.assertTrue(wet > 1000 && banks > 100 && pools > 100 && riffles > 0, "淵・早瀬・両岸の検査数");
        LogUtils.getLogger().info("SURFACE_RIVER wet={} banks={} pools={} riffles={}", wet, banks, pools, riffles);
        if (output() != null) {
            Files.writeString(output().resolve("river-longitudinal.csv"), longitudinal);
            section(terrain, "river-cross-section", -5024, -8272, 256, 40, 140);
        }
        helper.succeed();
    }

    private static Path output() throws Exception {
        String phase = System.getenv("ASHEN_TERRAIN_PREVIEW");
        if (phase == null) return null;
        var out = Path.of("../../../../../tools/fixed-map/preview", phase, "details").normalize();
        Files.createDirectories(out);
        return out;
    }

    private static void section(FixedTerrain terrain, String name, int x, int z, int radius, int bottom, int top) throws Exception {
        var image = new BufferedImage(radius * 2 + 1, top - bottom + 1, BufferedImage.TYPE_INT_RGB);
        for (int dx = -radius; dx <= radius; dx++) {
            var column = terrain.column(x + dx, z);
            for (int y = bottom; y <= top; y++) image.setRGB(dx + radius, top - y, color(column.state(y)));
        }
        ImageIO.write(image, "png", output().resolve(name + ".png").toFile());
    }

    private static int color(BlockState state) {
        if (state.isAir()) return 0xc3e0fa;
        if (!state.getFluidState().isEmpty()) return 0x397cae;
        return switch (BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()) {
            case "minecraft:snow", "minecraft:snow_block" -> 0xffffff;
            case "minecraft:powder_snow" -> 0xb6d9ec;
            case "minecraft:packed_ice", "minecraft:blue_ice" -> 0x7aafda;
            case "minecraft:sand", "minecraft:sandstone" -> 0xdcc895;
            case "minecraft:gravel" -> 0x96928d;
            case "ashen_frontier:clay_sediment" -> 0xaa8a79;
            case "ashen_frontier:shale" -> 0x646570;
            case "ashen_frontier:limestone" -> 0xb9b5a4;
            default -> 0x404040 | (BuiltInRegistries.BLOCK.getKey(state.getBlock()).hashCode() & 0xbfbfbf);
        };
    }
}
