package io.github.rs0325.ashenfrontier.core.catalog;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** カタログからアセットと回収テーブルを同時に生成し、参照元の二重管理を避ける。 */
public final class NatureDataProvider implements DataProvider {
    private final Path root;

    private NatureDataProvider(PackOutput output) {
        root = output.getOutputFolder();
    }

    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeClient() || event.includeServer(),
                new NatureDataProvider(event.getGenerator().getPackOutput()));
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        List<CompletableFuture<?>> writes = new ArrayList<>();
        JsonObject english = new JsonObject();
        JsonObject japanese = new JsonObject();
        english.addProperty("tooltip.ashen_frontier.recovery", "Recovers: %s");
        japanese.addProperty("tooltip.ashen_frontier.recovery", "回収先: %s");
        JsonArray pickaxe = new JsonArray();
        for (var entry : NatureCatalog.entries()) {
            String id = entry.id();
            if (entry.kind().equals("rock") || entry.kind().equals("ore") || entry.kind().equals("coal")) {
                pickaxe.add("ashen_frontier:" + id);
            }
            String key = (entry.block() ? "block." : "item.") + "ashen_frontier." + id;
            english.addProperty(key, entry.en());
            japanese.addProperty(key, entry.ja());
            if (entry.fluid()) {
                english.addProperty("fluid_type.ashen_frontier." + id, entry.en());
                japanese.addProperty("fluid_type.ashen_frontier." + id, entry.ja());
                save(writes, output, "assets/ashen_frontier/blockstates/" + id,
                        "{\"variants\":{\"\":{\"model\":\"ashen_frontier:block/" + id + "\"}}}");
                save(writes, output, "assets/ashen_frontier/models/block/" + id,
                        "{\"textures\":{\"particle\":\"ashen_frontier:block/" + id + "_still\"}}");
            } else {
                if (entry.block()) {
                    save(writes, output, "assets/ashen_frontier/blockstates/" + id,
                            "{\"variants\":{\"\":{\"model\":\"ashen_frontier:block/" + id + "\"}}}");
                    boolean cross = entry.loose();
                    save(writes, output, "assets/ashen_frontier/models/block/" + id,
                            cross ? "{\"parent\":\"minecraft:block/cross\",\"render_type\":\"minecraft:cutout\",\"textures\":{\"cross\":\"ashen_frontier:block/" + id + "\"}}"
                                    : "{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"ashen_frontier:block/" + id + "\"}}");
                    String recovery = entry.recovery().contains(":") ? entry.recovery() : "ashen_frontier:" + entry.recovery();
                    save(writes, output, "data/ashen_frontier/loot_table/blocks/" + id,
                            "{\"type\":\"minecraft:block\",\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"" + recovery
                                    + "\"}],\"conditions\":[{\"condition\":\"minecraft:survives_explosion\"}]}]}");
                }
                save(writes, output, "assets/ashen_frontier/models/item/" + id,
                        entry.blockItem() ? "{\"parent\":\"ashen_frontier:block/" + id + "\"}"
                                : "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"ashen_frontier:item/" + id + "\"}}");
            }
        }
        writes.add(DataProvider.saveStable(output, english, root.resolve("assets/ashen_frontier_core/lang/en_us.json")));
        writes.add(DataProvider.saveStable(output, japanese, root.resolve("assets/ashen_frontier_core/lang/ja_jp.json")));
        JsonObject mining = new JsonObject();
        mining.addProperty("replace", false);
        mining.add("values", pickaxe);
        writes.add(DataProvider.saveStable(output, mining, root.resolve("data/minecraft/tags/block/mineable/pickaxe.json")));
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private void save(List<CompletableFuture<?>> writes, CachedOutput output, String path, String json) {
        writes.add(DataProvider.saveStable(output, JsonParser.parseString(json), root.resolve(path + ".json")));
    }

    @Override
    public String getName() {
        return "自然物の表示と回収";
    }

}
