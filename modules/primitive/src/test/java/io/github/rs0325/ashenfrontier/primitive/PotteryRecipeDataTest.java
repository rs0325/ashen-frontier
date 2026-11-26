package io.github.rs0325.ashenfrontier.primitive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PotteryRecipeDataTest {
    private static JsonObject read(String name) {
        var stream = PotteryRecipeDataTest.class.getResourceAsStream("/data/ashen_frontier/" + name + ".json");
        assertNotNull(stream, name);
        return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void everyShapeBalancesClaySandWaterAndDryingLoss() {
        var masses = read("data_maps/item/material").getAsJsonObject("values");
        for (String form : new String[]{"pot", "lid", "tube"}) {
            var molded = read("recipe/primitive/form_" + form + "_raw");
            var dry = read("recipe/primitive/dry_" + form);
            var fired = read("recipe/primitive/fire_" + form);
            double supplied = 2 * masses.getAsJsonObject("minecraft:clay_ball").get("mass_kg").getAsDouble()
                    + masses.getAsJsonObject("minecraft:sand").get("mass_kg").getAsDouble()
                    + molded.getAsJsonObject("processing").getAsJsonObject("water").get("mass_kg").getAsDouble();
            assertEquals(3, supplied);
            assertEquals(supplied, masses.getAsJsonObject("ashen_frontier:green_pot").get("mass_kg").getAsDouble());
            double solid = masses.getAsJsonObject("ashen_frontier:dried_pot").get("mass_kg").getAsDouble();
            assertEquals(supplied, solid + dry.getAsJsonObject("processing").get("loss_kg").getAsDouble());
            var product = fired.getAsJsonArray("outputs").get(0).getAsJsonObject();
            assertEquals(solid, product.get("count").getAsInt() * masses.getAsJsonObject(product.get("id").getAsString()).get("mass_kg").getAsDouble());
            assertEquals(86400, dry.getAsJsonObject("conditions").get("duration_seconds").getAsInt());
            assertEquals(120, fired.getAsJsonObject("conditions").get("duration_seconds").getAsInt());
            assertEquals(973, fired.getAsJsonObject("conditions").getAsJsonObject("temperature_k").get("min").getAsInt());
            assertEquals(1123, fired.getAsJsonObject("conditions").getAsJsonObject("temperature_k").get("max").getAsInt());
        }
        assertFalse(read("recipe/primitive/fire_drill").has("conditions"));
        assertEquals("ashen_frontier:t0/fire", read("recipe/primitive/fire_pot").getAsJsonObject("conditions").get("research").getAsString());
        assertTrue(read("ashen_frontier/research/t0/fire").getAsJsonArray("prerequisites").isEmpty());
    }
}
