package io.github.rs0325.ashenfrontier.primitive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 配布する製法データ自体の収支・時間・工具保持を検証する。 */
class PrimitiveRecipeDataTest {
    private static JsonObject read(String path) {
        var stream = PrimitiveRecipeDataTest.class.getResourceAsStream("/data/ashen_frontier/" + path + ".json");
        assertNotNull(stream, path);
        return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void allHandworkRecipesBalanceMassWithoutConsumingTheTool() {
        var masses = new HashMap<String, Double>();
        for (String raw : new String[]{"pebble", "flint", "plant_fiber", "dry_branch"}) masses.put("ashen_frontier:" + raw, 1.0);
        read("data_maps/item/material").getAsJsonObject("values").entrySet().forEach(e ->
                masses.put(e.getKey(), e.getValue().getAsJsonObject().get("mass_kg").getAsDouble()));
        for (String name : new String[]{"chopper", "chopper_flint", "biface", "cordage", "tool_handle",
                "digging_stick", "tool_handle_biface", "digging_stick_biface", "fiber_cloth"}) {
            var recipe = read("recipe/primitive/" + name);
            var processing = recipe.getAsJsonObject("processing");
            String source = recipe.getAsJsonArray("inputs").get(0).getAsJsonObject().get("item").getAsString();
            double input = masses.get(source) * processing.getAsJsonArray("inputs").get(0).getAsJsonObject().get("count").getAsInt();
            double output = 0;
            for (var entry : recipe.getAsJsonArray("outputs")) {
                var stack = entry.getAsJsonObject();
                output += masses.get(stack.get("id").getAsString()) * stack.get("count").getAsInt();
            }
            assertEquals(input, output + processing.get("loss_kg").getAsDouble(), 1e-9, name);
            if (processing.has("tool_wear")) {
                assertEquals(1, processing.get("tool_wear").getAsInt(), name);
                assertTrue(recipe.getAsJsonObject("conditions").has("catalyst"), name);
                assertEquals(1, recipe.getAsJsonArray("inputs").size(), name);
            }
        }
    }

    @Test
    void timeAndBootstrapRequirementsMatchTheApprovedProgression() {
        for (var entry : Map.of("chopper", 10, "chopper_flint", 10, "biface", 20, "cordage", 10,
                "tool_handle", 20, "digging_stick", 20, "fiber_cloth", 30).entrySet()) {
            assertEquals(entry.getValue(), read("recipe/primitive/" + entry.getKey()).getAsJsonObject("conditions").get("duration_seconds").getAsInt());
        }
        assertFalse(read("recipe/primitive/chopper").getAsJsonObject("conditions").has("research"));
        assertFalse(read("recipe/primitive/cordage").getAsJsonObject("conditions").has("research"));
        assertTrue(read("ashen_frontier/research/t0/stoneworking").getAsJsonArray("prerequisites").isEmpty());
        assertTrue(read("ashen_frontier/research/t0/fiberworking").getAsJsonArray("prerequisites").isEmpty());
    }
}
