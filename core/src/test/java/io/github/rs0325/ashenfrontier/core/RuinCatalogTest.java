package io.github.rs0325.ashenfrontier.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.rs0325.ashenfrontier.core.catalog.RelicCatalog;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class RuinCatalogTest {
    @Test
    void newMaterialsHaveCorrectToolsStaticLootAndSixteenPixelAssets() throws Exception {
        var pickaxe = values("data/minecraft/tags/block/mineable/pickaxe.json");
        var axe = values("data/minecraft/tags/block/mineable/axe.json");
        int count = 0;
        for (var entry : RelicCatalog.entries()) {
            if (entry.shape() == null) continue;
            String id = "ashen_frontier:" + entry.id();
            assertEquals(entry.id(), entry.recovery());
            assertEquals(2, entry.hardness());
            assertEquals(1, entry.massKg());
            assertTrue(entry.material().equals("wood") ? axe.contains(id) && !pickaxe.contains(id)
                    : pickaxe.contains(id) && !axe.contains(id), id);
            var loot = json("data/ashen_frontier/loot_table/blocks/" + entry.id() + ".json").toString();
            assertTrue(loot.contains(entry.material().equals("wood") ? "wood_tools" : "rock_tools"), id);
            var png = getClass().getResource("/assets/ashen_frontier/textures/block/" + entry.id() + ".png");
            assertNotNull(png, id);
            var image = ImageIO.read(png);
            assertEquals(16, image.getWidth(), id);
            assertEquals(16, image.getHeight(), id);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int alpha = image.getRGB(x,y) >>> 24;
                assertTrue(alpha == 0 || alpha == 255, id);
                if (!entry.id().endsWith("_glass_window")) assertEquals(255, alpha, id);
            }
            count++;
        }
        assertEquals(64, count);
    }

    private HashSet<String> values(String path) throws Exception {
        var result = new HashSet<String>();
        for (var value : json(path).getAsJsonArray("values")) result.add(value.getAsString());
        return result;
    }

    private JsonObject json(String path) throws Exception {
        var resource = getClass().getResource("/" + path);
        assertNotNull(resource, path);
        try (var reader = new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
