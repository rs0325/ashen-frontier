package io.github.rs0325.ashenfrontier.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import io.github.rs0325.ashenfrontier.core.catalog.RelicCatalog;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NatureCatalogTest {
    @Test
    void relicCatalogAssetsAndRecoveryReferencesAreComplete() throws Exception {
        Set<String> ids = new HashSet<>();
        NatureCatalog.entries().forEach(entry -> assertTrue(ids.add(entry.id())));
        var materials = json("data/ashen_frontier/data_maps/item/material.json").getAsJsonObject("values");
        var english = json("assets/ashen_frontier_core/lang/en_us.json");
        var japanese = json("assets/ashen_frontier_core/lang/ja_jp.json");
        for (var entry : RelicCatalog.entries()) {
            String id = entry.id();
            assertTrue(ids.add(id), "自然物とのID衝突: " + id);
            assertTrue(id.matches("[a-z]+(?:_[a-z]+)*"), id);
            if (entry.kind().equals("equipment")) assertTrue(id.startsWith("damaged_"), id);
            String key = (entry.block() ? "block." : "item.") + "ashen_frontier." + id;
            assertEquals(entry.en(), english.get(key).getAsString());
            assertEquals(entry.ja(), japanese.get(key).getAsString());
            assertEquals(entry.massKg(), materials.getAsJsonObject("ashen_frontier:" + id).get("mass_kg").getAsDouble());
            resource("assets/ashen_frontier/textures/" + (entry.block() ? "block/" : "item/") + id + ".png");
            json("assets/ashen_frontier/models/item/" + id + ".json");
            if (entry.block()) {
                json("assets/ashen_frontier/blockstates/" + id + ".json");
                // 接続形状には単一の同名モデルがないため、状態が参照する全モデルを照合する。
                assertModelsExist(json("assets/ashen_frontier/blockstates/" + id + ".json"));
                json("data/ashen_frontier/loot_table/blocks/" + id + ".json");
                assertNotNull(RelicCatalog.require(entry.recovery()));
            }
        }
        assertEquals(50, RelicCatalog.entries().stream().filter(e -> e.kind().equals("structure")).count());
        assertEquals(32, RelicCatalog.entries().stream().filter(e -> e.kind().equals("equipment")).count());
        assertEquals(50, RelicCatalog.entries().stream().filter(e -> e.kind().equals("item")).count());
    }

    @Test
    void allIdsAndRecoveryReferencesHaveGeneratedAssetsAndMaterial() throws Exception {
        Set<String> ids = new HashSet<>();
        Set<String> items = new HashSet<>();
        for (var entry : NatureCatalog.entries()) {
            assertTrue(ids.add(entry.id()), "ID重複: " + entry.id());
            assertTrue(entry.id().matches("[a-z]+(?:_[a-z]+)*"), entry.id());
            assertFalse(entry.en().isBlank() || entry.ja().isBlank() || entry.material().isBlank()
                    || entry.domain().isBlank() || entry.behavior().isBlank(), entry.id());
            if (!entry.fluid()) items.add("ashen_frontier:" + entry.id());
        }
        JsonObject materials = json("data/ashen_frontier/data_maps/item/material.json").getAsJsonObject("values");
        JsonObject english = json("assets/ashen_frontier_core/lang/en_us.json");
        JsonObject japanese = json("assets/ashen_frontier_core/lang/ja_jp.json");
        for (var entry : NatureCatalog.entries()) {
            String id = entry.id();
            String key = (entry.block() ? "block." : "item.") + "ashen_frontier." + id;
            assertEquals(entry.en(), english.get(key).getAsString());
            assertEquals(entry.ja(), japanese.get(key).getAsString());
            if (entry.block()) json("assets/ashen_frontier/blockstates/" + id + ".json");
            if (entry.fluid()) {
                resource("assets/ashen_frontier/textures/block/" + id + "_still.png");
                resource("assets/ashen_frontier/textures/block/" + id + "_flow.png");
                continue;
            }
            assertTrue(materials.has("ashen_frontier:" + id), id);
            json("assets/ashen_frontier/models/item/" + id + ".json");
            resource("assets/ashen_frontier/textures/" + (entry.block() ? "block/" : "item/") + id + ".png");
            if (entry.block()) {
                var loot = json("data/ashen_frontier/loot_table/blocks/" + id + ".json");
                String recovery = loot.getAsJsonArray("pools").get(0).getAsJsonObject()
                        .getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString();
                assertEquals(entry.recovery().contains(":") ? entry.recovery() : "ashen_frontier:" + entry.recovery(), recovery);
                assertTrue(items.contains(recovery) || recovery.equals("minecraft:oak_log"), recovery);
            }
        }
        RelicCatalog.entries().forEach(entry -> items.add("ashen_frontier:" + entry.id()));
        assertEquals(items, materials.keySet());
    }

    @Test
    void oresUseTheirHostHardnessAndRegisteredMinerals() {
        for (var entry : NatureCatalog.entries()) {
            if (!entry.kind().equals("ore")) continue;
            assertEquals(entry.host() + "_" + entry.material() + "_ore", entry.id());
            assertEquals("raw_" + entry.material(), entry.recovery());
            assertEquals("item", NatureCatalog.require(entry.recovery()).kind());
            float expected = switch (entry.host()) {
                case "granite", "andesite" -> 1.5f;
                case "sandstone" -> .8f;
                default -> NatureCatalog.require(entry.host()).hardness();
            };
            assertEquals(expected, entry.hardness(), entry.id());
            assertTrue(NatureCatalog.sources().containsKey(entry.source()), entry.id());
        }
        assertEquals(8, NatureCatalog.entries().stream().filter(entry -> entry.kind().equals("water")).count());
        assertEquals(4, NatureCatalog.entries().stream().filter(entry -> entry.kind().equals("oil")).count());
        assertFalse(NatureCatalog.require("pure_water").surface());
        assertFalse(NatureCatalog.require("ultrapure_water").surface());
    }

    @Test
    void natureIdsAreNotRemovedOrRecipeDisabled() throws Exception {
        for (String tag : new String[]{"removed", "recipe_disabled"}) {
            var values = json("data/ashen_frontier/tags/item/" + tag + ".json").getAsJsonArray("values");
            for (var value : values) {
                String id = value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString();
                assertFalse(id.startsWith("#"), "間接タグが増えた場合は検査を拡張する: " + id);
                for (var entry : NatureCatalog.entries()) assertNotEquals("ashen_frontier:" + entry.id(), id);
            }
        }
    }

    @Test
    void fuelProfilesResolveOnceForItemsAndBothFluidVariants() throws Exception {
        var itemFuel = json("data/ashen_frontier/data_maps/item/fuel.json").getAsJsonObject("values");
        var fluidFuel = json("data/ashen_frontier/data_maps/fluid/fuel.json").getAsJsonObject("values");
        Set<String> referenced = new HashSet<>();
        for (var entry : NatureCatalog.entries()) {
            if (entry.fuel() == null) continue;
            var profile = NatureCatalog.requireFuel(entry.fuel());
            referenced.add(entry.fuel());
            if (profile.source() != null) assertTrue(NatureCatalog.sources().containsKey(profile.source()));
            for (String prefix : entry.fluid() ? new String[]{"", "flowing_"} : new String[]{""}) {
                var generated = (entry.fluid() ? fluidFuel : itemFuel).getAsJsonObject("ashen_frontier:" + prefix + entry.id());
                assertNotNull(generated, entry.id());
                assertEquals("ashen_frontier:" + entry.fuel(), generated.get("profile").getAsString());
                assertEquals(profile.values().size() + 1, generated.size());
                profile.values().forEach((key, value) -> assertEquals(value, generated.get(key).getAsDouble(), entry.id()));
            }
        }
        assertEquals(NatureCatalog.fuelProfiles().keySet(), referenced);
        for (String coal : new String[]{"peat", "lignite", "bituminous_coal", "anthracite"}) {
            assertEquals(NatureCatalog.require(coal).fuel(), NatureCatalog.require("raw_" + coal).fuel());
        }
    }

    private static JsonObject json(String path) throws Exception {
        try (var reader = new InputStreamReader(resource(path).openStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void assertModelsExist(JsonElement value) throws Exception {
        if (value.isJsonArray()) {
            for (var child : value.getAsJsonArray()) assertModelsExist(child);
        } else if (value.isJsonObject()) {
            for (var pair : value.getAsJsonObject().entrySet()) {
                if (pair.getKey().equals("model")) {
                    String model = pair.getValue().getAsString();
                    String[] location = model.split(":", 2);
                    json("assets/" + location[0] + "/models/" + location[1] + ".json");
                } else assertModelsExist(pair.getValue());
            }
        }
    }

    private static URL resource(String path) {
        var resource = NatureCatalogTest.class.getResource("/" + path);
        assertNotNull(resource, path);
        return resource;
    }
}
