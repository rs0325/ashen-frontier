package io.github.rs0325.ashenfrontier.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.rs0325.ashenfrontier.core.catalog.RelicCatalog;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuinModelTest {
    private static final String[] FACES = {"west", "down", "north", "east", "up", "south"};

    @Test
    void generatedFacesNeverOverlapOutsideTheSolid() throws Exception {
        var models = new HashSet<String>();
        for (var entry : RelicCatalog.entries()) {
            if (entry.shape() == null) continue;
            for (var value : json("blockstates/" + entry.id()).getAsJsonObject("variants").entrySet()) {
                var variants = value.getValue().isJsonArray() ? value.getValue().getAsJsonArray() : new JsonArray();
                if (!value.getValue().isJsonArray()) variants.add(value.getValue());
                for (var variant : variants) models.add(variant.getAsJsonObject().get("model").getAsString());
            }
        }
        int checked = 0;
        for (String name : models) {
            var model = json("models/" + name.substring(name.indexOf(':') + 1));
            if (!model.has("elements")) continue;
            assertEquals(0, duplicateExteriorCells(model.getAsJsonArray("elements")), name);
            checked++;
        }
        assertTrue(checked > 100, "全方向・全軸の部分形状モデルを検査する");
    }

    @Test
    void exteriorFaceCheckRejectsTheReviewedCoplanarCases() {
        for (int[][] boxes : List.of(
                new int[][]{{0,0,0,5,7,7},{3,2,2,5,14,4}},
                new int[][]{{3,2,2,5,14,4},{2,12,1,8,14,3}},
                new int[][]{{11,0,7,13,13,9},{0,3,8,13,5,10}},
                new int[][]{{3,7,7,15,9,9},{11,0,7,13,12,9}})) {
            var elements = new JsonArray();
            for (int[] box : boxes) {
                var element = new JsonObject();
                var from = new JsonArray();
                var to = new JsonArray();
                for (int i = 0; i < 3; i++) { from.add(box[i]); to.add(box[i + 3]); }
                element.add("from", from);
                element.add("to", to);
                var faces = new JsonObject();
                for (String face : FACES) faces.add(face, new JsonObject());
                element.add("faces", faces);
                elements.add(element);
            }
            assertTrue(duplicateExteriorCells(elements) > 0, "修正前の共面を検出できない");
        }
    }

    @Test
    void windowThicknessUsesOpaqueFramePixelsInAllFourDirections() throws Exception {
        int windows = 0;
        for (var entry : RelicCatalog.entries()) {
            if (entry.shape() == null || !entry.id().endsWith("_glass_window")) continue;
            var image = ImageIO.read(getClass().getResource("/assets/ashen_frontier/textures/block/" + entry.id() + ".png"));
            for (String direction : new String[]{"", "_east", "_south", "_west"}) {
                int frontAxis = direction.equals("_east") || direction.equals("_west") ? 0 : 2;
                var model = json("models/block/" + entry.id() + direction);
                for (var element : model.getAsJsonArray("elements")) {
                    var faces = element.getAsJsonObject().getAsJsonObject("faces");
                    for (int face = 0; face < 6; face++) {
                        var data = faces.getAsJsonObject(FACES[face]);
                        assertEquals("#all", data.get("texture").getAsString());
                        if (face % 3 == frontAxis) continue;
                        var uv = data.getAsJsonArray("uv");
                        assertNotNull(uv, entry.id() + direction + FACES[face]);
                        int x0 = uv.get(0).getAsInt(), y0 = uv.get(1).getAsInt();
                        int x1 = uv.get(2).getAsInt(), y1 = uv.get(3).getAsInt();
                        assertTrue(x1 > x0 && y1 > y0);
                        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++)
                            assertEquals(255, image.getRGB(x, y) >>> 24, entry.id() + direction + FACES[face]);
                    }
                }
            }
            windows++;
        }
        assertEquals(4, windows);
    }

    @Test
    void beamsAndBothSheetsReferenceTheSameFourMaterialImages() throws Exception {
        String[] stages = {"coated", "rusted", "perforated", "remnant"};
        String[] beams = {"intact", "weathered", "pitted", "section_loss"};
        for (int i = 0; i < stages.length; i++) {
            String beam = beams[i] + "_steel_beam";
            for (String shape : new String[]{"_steel_sheet", "_corrugated_steel_sheet"}) {
                String id = stages[i] + shape;
                assertEquals(RelicCatalog.require(beam).sprite(), RelicCatalog.require(id).sprite());
                for (String direction : new String[]{"", "_east", "_south", "_west"}) {
                    var textures = json("models/block/" + id + direction).getAsJsonObject("textures");
                    assertEquals("ashen_frontier:block/" + beam, textures.get("all").getAsString());
                    assertEquals(textures.get("all"), textures.get("particle"));
                }
            }
        }
    }

    // 各出力面を1px角で照合する。和集合の内部に隠れた面は対象外とする。
    private int duplicateExteriorCells(JsonArray elements) {
        var boxes = new ArrayList<double[]>();
        for (var element : elements) {
            double[] box = new double[6];
            for (int i = 0; i < 3; i++) {
                box[i] = element.getAsJsonObject().getAsJsonArray("from").get(i).getAsDouble();
                box[i + 3] = element.getAsJsonObject().getAsJsonArray("to").get(i).getAsDouble();
                assertEquals(Math.rint(box[i]), box[i]);
                assertEquals(Math.rint(box[i + 3]), box[i + 3]);
            }
            boxes.add(box);
        }
        var seen = new HashSet<String>();
        int duplicates = 0;
        for (int index = 0; index < boxes.size(); index++) {
            var box = boxes.get(index);
            var faces = elements.get(index).getAsJsonObject().getAsJsonObject("faces");
            for (int face = 0; face < 6; face++) {
                if (!faces.has(FACES[face])) continue;
                int axis = face % 3, u = (axis + 1) % 3, v = (axis + 2) % 3;
                double plane = box[face];
                for (int x = (int) box[u]; x < box[u + 3]; x++) for (int y = (int) box[v]; y < box[v + 3]; y++) {
                    double[] point = new double[3];
                    point[axis] = plane + (face < 3 ? -.01 : .01);
                    point[u] = x + .5;
                    point[v] = y + .5;
                    boolean hidden = boxes.stream().anyMatch(other -> point[0] > other[0] && point[0] < other[3]
                            && point[1] > other[1] && point[1] < other[4] && point[2] > other[2] && point[2] < other[5]);
                    if (!hidden && !seen.add(face + ":" + plane + ":" + x + ":" + y)) duplicates++;
                }
            }
        }
        return duplicates;
    }

    private JsonObject json(String path) throws Exception {
        var resource = getClass().getResource("/assets/ashen_frontier/" + path + ".json");
        assertNotNull(resource, path);
        try (var reader = new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
