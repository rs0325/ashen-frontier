package io.github.rs0325.ashenfrontier.world.screenshots;

import com.google.gson.JsonParser;
import io.github.rs0325.ashenfrontier.screenshots.ScreenshotPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScreenshotPlanTest {
    @TempDir Path temporary;

    private static final String VALID = """
            {
              "world": {"name": "撮影", "preset": "ashen_frontier:fixed_map", "seed": 42},
              "gameMode": "spectator",
              "environment": {"time": 6000, "weather": "clear"},
              "view": {"width": 1280, "height": 720, "fov": 70, "hud": false, "debug": false, "renderDistance": 8},
              "timeoutSeconds": 120,
              "startupTimeoutSeconds": 300,
              "points": [{"name": "valley-head", "x": -4000, "y": 125, "z": -12100, "yaw": 0, "pitch": 40, "waitSeconds": 2}]
            }
            """;

    @Test
    void loadsUtf8PlanAndPreservesCamera() throws Exception {
        var plan = load(VALID);
        assertEquals("撮影", plan.world().name());
        assertEquals(42, plan.world().seed());
        assertEquals(new ScreenshotPlan.Point("valley-head", -4000, 125, -12100, 0, 40, 2), plan.points().getFirst());
        assertFalse(plan.view().hud());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "a/b", "a\\b", "CON", "nul", "", "重複不可"})
    void rejectsUnsafeImageNames(String name) {
        var json = JsonParser.parseString(VALID).getAsJsonObject();
        json.getAsJsonArray("points").get(0).getAsJsonObject().addProperty("name", name);
        assertThrows(IllegalArgumentException.class, () -> load(json.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"pitch\": 40|\"pitch\": 91", "\"x\": -4000|\"x\": 16384",
            "\"y\": 125|\"y\": 1e999", "\"waitSeconds\": 2|\"waitSeconds\": -1",
            "\"waitSeconds\": 2|\"waitSeconds\": 120", "\"timeoutSeconds\": 120|\"timeoutSeconds\": 0",
            "\"startupTimeoutSeconds\": 300|\"startupTimeoutSeconds\": 0",
            "\"fov\": 70|\"fov\": 200", "\"renderDistance\": 8|\"renderDistance\": 0",
            "\"width\": 1280|\"width\": 0", "\"debug\": false|\"debug\": true",
            "\"width\": 1280|\"width\": 1280.5", "\"width\": 1280|\"width\": 4294968576",
            "\"hud\": false|\"hud\": \"false\"", "\"x\": -4000|\"x\": \"-4000\"",
            "\"time\": 6000|\"time\": 24000", "\"clear\"|\"snow\"",
            "\"spectator\"|\"unknown\"", "ashen_frontier:fixed_map|minecraft:normal"
    })
    void rejectsInvalidValues(String replacement) {
        var parts = replacement.split("\\|", -1);
        assertThrows(IllegalArgumentException.class, () -> load(VALID.replace(parts[0], parts[1])));
    }

    @Test
    void rejectsDuplicateAndMissingPoints() {
        var json = JsonParser.parseString(VALID).getAsJsonObject();
        var points = json.getAsJsonArray("points");
        points.add(points.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> load(json.toString()));
        points.get(1).getAsJsonObject().addProperty("name", "VALLEY-HEAD");
        assertThrows(IllegalArgumentException.class, () -> load(json.toString()));
        points.remove(1);
        points.remove(0);
        assertThrows(IllegalArgumentException.class, () -> load(json.toString()));
        json.remove("points");
        assertThrows(IllegalArgumentException.class, () -> load(json.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "y", "z", "yaw", "pitch", "waitSeconds", "name"})
    void rejectsMissingCameraFields(String field) {
        var json = JsonParser.parseString(VALID).getAsJsonObject();
        json.getAsJsonArray("points").get(0).getAsJsonObject().remove(field);
        assertThrows(IllegalArgumentException.class, () -> load(json.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{", "{}", "[]"})
    void rejectsMalformedOrEmptyPlan(String json) {
        assertThrows(IllegalArgumentException.class, () -> load(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {"say unsafe", "stop", "execute run stop"})
    void rejectsUnsafeSetupCommands(String command) {
        var json = JsonParser.parseString(VALID).getAsJsonObject();
        json.add("setupCommands", JsonParser.parseString("[\"" + command + "\"]"));
        assertThrows(IllegalArgumentException.class, () -> load(json.toString()));
    }

    @Test
    void acceptsOptionalSetupWithoutChangingExistingPlans() throws Exception {
        assertTrue(load(VALID).setupCommands().isEmpty());
        var json = JsonParser.parseString(VALID).getAsJsonObject();
        json.add("setupCommands", JsonParser.parseString("[\"setblock ~ ~ ~ minecraft:stone\"]"));
        assertEquals(List.of("setblock ~ ~ ~ minecraft:stone"), load(json.toString()).setupCommands());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[1]", "[null]"})
    void rejectsInvalidSetupTypes(String setup) {
        var json = JsonParser.parseString(VALID).getAsJsonObject();
        json.add("setupCommands", JsonParser.parseString(setup));
        assertThrows(IllegalArgumentException.class, () -> load(json.toString()));
    }

    private ScreenshotPlan load(String json) throws Exception {
        var path = temporary.resolve("plan.json");
        Files.writeString(path, json);
        return ScreenshotPlan.load(path);
    }
}
