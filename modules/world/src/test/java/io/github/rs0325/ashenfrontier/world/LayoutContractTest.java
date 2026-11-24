package io.github.rs0325.ashenfrontier.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LayoutContractTest {
    private JsonObject fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/earth_002/" + name + ".json")) {
            assertNotNull(stream);
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void approvedControlsAndNetworkAreImmutableAndVersioned() throws Exception {
        var land = LandformLayout.parse(fixture("landform"), "earth_002");
        var water = HydrologyLayout.parse(fixture("hydrology"), "earth_002");
        assertEquals(30, land.units().size());
        assertEquals(550, land.document().get("snowline_below_40_min_y").getAsInt());
        assertEquals(56, land.units().values().stream().mapToInt(u -> u.controls().size()).sum());
        assertEquals(8, water.reaches().size());
        assertEquals(12, water.nodes().size());
        assertEquals(1, water.lakes().size());
        assertEquals(350, water.nodes().get("S05").waterY());
        assertEquals(-6510, water.nodes().get("O10").x());
        assertEquals(145, water.lakes().get("L10").surfaceY());
        assertEquals(7, water.reaches().get("R05A").profile().getFirst().halfWidth());
        assertEquals(3, water.outlets().size());
        assertFalse(water.inventories().get(1).finite());
        assertTrue(water.inventories().get(2).finite());
        assertThrows(UnsupportedOperationException.class, () -> water.nodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> water.reaches().get("R05U").profile().clear());
        assertThrows(UnsupportedOperationException.class, () -> land.units().get(1).controls().clear());
        var copy = land.document();
        copy.remove("units");
        assertEquals(30, land.document().getAsJsonArray("units").size());
        var version = new MapVersion("earth_002", 1, "a".repeat(64));
        assertTrue(version.isPreview());
        assertEquals(version, MapVersion.CODEC.parse(JsonOps.INSTANCE,
                MapVersion.CODEC.encodeStart(JsonOps.INSTANCE, version).getOrThrow()).getOrThrow());
    }

    @Test
    void junctionLevelsCyclesAndIdsFailInsteadOfBeingRepaired() throws Exception {
        var water = fixture("hydrology");
        var row = water.getAsJsonArray("reaches").get(4).getAsJsonObject()
                .getAsJsonArray("profile_x_z_surface_full_width_depth").get(3).getAsJsonArray();
        row.set(2, new com.google.gson.JsonPrimitive(137));
        assertTrue(assertThrows(IllegalStateException.class, () -> HydrologyLayout.parse(water, "earth_002"))
                .getMessage().contains("座標水位"));
        var duplicate = fixture("hydrology");
        duplicate.getAsJsonArray("nodes").add(duplicate.getAsJsonArray("nodes").get(0).deepCopy());
        assertThrows(IllegalStateException.class, () -> HydrologyLayout.parse(duplicate, "earth_002"));

        var cycle = fixture("hydrology");
        var node = JsonParser.parseString("{\"id\":\"loop\",\"kind\":\"confluence\",\"xz\":[0,0],\"water_y\":100}").getAsJsonObject();
        cycle.getAsJsonArray("nodes").add(node);
        cycle.getAsJsonArray("reaches").add(JsonParser.parseString("""
                {"id":"cycle","from_node":"loop","to_node":"loop","inventory_id":2,
                 "profile_x_z_surface_full_width_depth":[[0,0,100,2,1],[4,0,100,2,1],[0,0,100,2,1]]}
                """));
        assertTrue(assertThrows(IllegalStateException.class, () -> HydrologyLayout.parse(cycle, "earth_002"))
                .getMessage().contains("閉路"));
    }

    @Test
    void lakeMustOverlapWithPositiveWidthAndKeepItsInventory() throws Exception {
        var pointContact = fixture("hydrology");
        pointContact.getAsJsonArray("nodes").get(2).getAsJsonObject()
                .getAsJsonArray("xz").set(0, new com.google.gson.JsonPrimitive(-6490));
        pointContact.getAsJsonArray("reaches").get(1).getAsJsonObject()
                .getAsJsonArray("profile_x_z_surface_full_width_depth").get(0).getAsJsonArray()
                .set(0, new com.google.gson.JsonPrimitive(-6490));
        assertTrue(assertThrows(IllegalStateException.class, () -> HydrologyLayout.parse(pointContact, "earth_002"))
                .getMessage().contains("正幅"));
        var wrongInventory = fixture("hydrology");
        wrongInventory.getAsJsonArray("reaches").get(4).getAsJsonObject().addProperty("inventory_id", 3);
        assertTrue(assertThrows(IllegalStateException.class, () -> HydrologyLayout.parse(wrongInventory, "earth_002"))
                .getMessage().contains("水量ID"));
    }

    @Test
    void missingFilesUnknownSchemasAndWrongHashesStopAtManifest() throws Exception {
        var resources = resources();
        var manifest = manifest(resources);
        var version = version(resources, manifest);
        assertDoesNotThrow(() -> BundledMaps.verify(version, name -> read(resources, name)));
        assertThrows(IllegalStateException.class, () -> BundledMaps.verify(new MapVersion("earth_002", 1, "0".repeat(64)), name -> read(resources, name)));
        assertThrows(IllegalStateException.class, () -> BundledMaps.verify(new MapVersion("earth_002", 2, version.manifestHash()), name -> read(resources, name)));
        var saved = resources.remove("hydrology.json");
        assertThrows(IllegalStateException.class, () -> BundledMaps.verify(version, name -> read(resources, name)));
        resources.put("hydrology.json", saved);
        resources.put("landform.json", "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> BundledMaps.verify(version, name -> read(resources, name)));

        var changed = resources();
        var badSchema = fixture("hydrology");
        badSchema.addProperty("hydrology_schema_version", 2);
        changed.put("hydrology.json", badSchema.toString().getBytes(StandardCharsets.UTF_8));
        var changedVersion = version(changed, manifest(changed));
        assertThrows(IllegalStateException.class, () -> BundledMaps.verify(changedVersion, name -> read(changed, name)));
    }

    @Test
    void manifestCannotOmitRequiredAssetOrChangeSchema() throws Exception {
        var resources = resources();
        var manifest = manifest(resources);
        manifest.getAsJsonObject("files").remove("landform.json");
        var version = version(resources, manifest);
        assertThrows(IllegalStateException.class, () -> BundledMaps.verify(version, name -> read(resources, name)));
        manifest = manifest(resources);
        manifest.addProperty("landform_schema_version", 2);
        var unsupported = version(resources, manifest);
        assertThrows(IllegalStateException.class, () -> BundledMaps.verify(unsupported, name -> read(resources, name)));
        assertThrows(IllegalStateException.class, () -> BundledMaps.require(new MapVersion("unknown", 1, "0".repeat(64))));
        assertThrows(IllegalStateException.class, () -> BundledMaps.require(new MapVersion("earth_002", 1, "0".repeat(64))));
        var fractional = fixture("landform");
        for (var scalar : new String[]{"1.5", "1.0", "\"1\"", "true"}) {
            fractional.add("landform_schema_version", JsonParser.parseString(scalar));
            assertThrows(IllegalStateException.class, () -> LandformLayout.parse(fractional, "earth_002"));
        }
    }

    @Test
    void lakeCanHaveMultipleInletsButItsNodesMustBelongToALake() throws Exception {
        var water = fixture("hydrology");
        water.getAsJsonArray("nodes").add(JsonParser.parseString("""
                {"id":"extra_source","kind":"source","xz":[-7400,-11000],"water_y":160}
                """));
        water.getAsJsonArray("nodes").add(JsonParser.parseString("""
                {"id":"extra_inlet","kind":"lake_inlet","xz":[-7100,-10400],"water_y":145}
                """));
        water.getAsJsonArray("reaches").add(JsonParser.parseString("""
                {"id":"extra_reach","from_node":"extra_source","to_node":"extra_inlet","inventory_id":2,
                 "profile_x_z_surface_full_width_depth":[[-7400,-11000,160,2,1],[-7100,-10400,145,8,2]]}
                """));
        water.getAsJsonArray("lakes").get(0).getAsJsonObject().getAsJsonArray("inlet_nodes").add("extra_inlet");
        assertEquals(2, HydrologyLayout.parse(water, "earth_002").lakes().get("L10").inlets().size());
        var orphan = fixture("hydrology");
        orphan.getAsJsonArray("nodes").get(3).getAsJsonObject().addProperty("kind", "lake_outlet");
        assertThrows(IllegalStateException.class, () -> HydrologyLayout.parse(orphan, "earth_002"));
    }

    @Test
    void inletMustOverlapByItsTerminalHalfWidth() throws Exception {
        for (int x : new int[]{-7450, -7449}) {
            var water = fixture("hydrology");
            var inlet = water.getAsJsonArray("nodes").get(1).getAsJsonObject();
            inlet.add("xz", JsonParser.parseString("[" + x + ",-10400]"));
            var rows = water.getAsJsonArray("reaches").get(0).getAsJsonObject()
                    .getAsJsonArray("profile_x_z_surface_full_width_depth");
            rows.set(4, JsonParser.parseString("[-7900,-10400,155,8,2]"));
            rows.set(5, JsonParser.parseString("[" + x + ",-10400,145,12,2]"));
            assertTrue(assertThrows(IllegalStateException.class, () -> HydrologyLayout.parse(water, "earth_002"))
                    .getMessage().contains("湖入口の正幅"));
        }
    }

    @Test
    void booleanWidthAndDecimalLakeLevelAreNotNumbersOfTheRequiredType() throws Exception {
        var width = fixture("hydrology");
        width.getAsJsonArray("reaches").get(0).getAsJsonObject()
                .getAsJsonArray("profile_x_z_surface_full_width_depth").get(0).getAsJsonArray()
                .set(3, JsonParser.parseString("true"));
        assertThrows(IllegalStateException.class, () -> HydrologyLayout.parse(width, "earth_002"));
        var level = fixture("hydrology");
        level.getAsJsonArray("lakes").get(0).getAsJsonObject().addProperty("surface_y", 145.0);
        assertThrows(IllegalStateException.class, () -> HydrologyLayout.parse(level, "earth_002"));
    }

    @Test
    void additionalLandformDefinitionsAreRequiredAndBounded() throws Exception {
        for (var key : new String[]{"volcanoes", "snowline_latitude_y"}) {
            var land = fixture("landform");
            land.remove(key);
            assertTrue(assertThrows(IllegalStateException.class, () -> LandformLayout.parse(land, "earth_002"))
                    .getMessage().contains(key));
        }
        var land = fixture("landform");
        land.getAsJsonArray("volcanoes").get(0).getAsJsonObject().addProperty("rim_y", 9999);
        assertTrue(assertThrows(IllegalStateException.class, () -> LandformLayout.parse(land, "earth_002"))
                .getMessage().contains("火山の標高"));
    }

    private Map<String, byte[]> resources() throws Exception {
        var files = new HashMap<String, byte[]>();
        // manifestの完全性だけを検査する。PNGの寸法・画素検査は既存制作テストが担当する。
        for (var name : new String[]{"height.png","region.png","water.png","soil.png","geology.png",
                "coast_river.png","index.json","geology_profiles.json","underground.json"}) files.put(name, new byte[]{1});
        for (var name : new String[]{"landform", "hydrology"}) files.put(name + ".json", fixture(name).toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }
    private static JsonObject manifest(Map<String, byte[]> resources) throws Exception {
        var doc = new JsonObject();
        doc.addProperty("map_version", "earth_002"); doc.addProperty("format_version", 1);
        doc.addProperty("landform_schema_version", 1); doc.addProperty("hydrology_schema_version", 1);
        var files = new JsonObject();
        for (var entry : resources.entrySet()) if (!entry.getKey().equals("manifest.json")) files.addProperty(entry.getKey(), hash(entry.getValue()));
        doc.add("files", files);
        return doc;
    }
    private static MapVersion version(Map<String, byte[]> resources, JsonObject manifest) throws Exception {
        var bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        resources.put("manifest.json", bytes);
        return new MapVersion("earth_002", 1, hash(bytes));
    }
    private static byte[] read(Map<String, byte[]> resources, String name) {
        var bytes = resources.get(name);
        if (bytes == null) throw new IllegalStateException("不足資産: " + name);
        return bytes;
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
