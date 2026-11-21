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
import java.util.Map;
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
        JsonArray soft = new JsonArray();
        JsonArray axe = new JsonArray();
        for (var entry : NatureCatalog.entries()) {
            String id = entry.id();
            if (entry.kind().equals("rock") || entry.kind().equals("ore") || entry.kind().equals("coal")) {
                pickaxe.add("ashen_frontier:" + id);
            } else if (entry.block() && (entry.loose() || entry.kind().equals("soil")
                    || entry.kind().equals("natural") && !entry.material().equals("wood"))) {
                soft.add("ashen_frontier:" + id);
            } else if (entry.block() && entry.kind().equals("natural") && entry.material().equals("wood")) {
                axe.add("ashen_frontier:" + id);
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
        for (String tag : List.of("dirt", "sand", "leaves", "crops", "flowers", "saplings",
                "replaceable", "sword_efficient", "underwater_bonemeals", "wool", "wool_carpets", "beds", "banners",
                "candles", "candle_cakes", "buttons", "pressure_plates", "flower_pots", "coral_blocks",
                "corals", "coral_plants", "wall_corals", "impermeable", "snow")) {
            soft.add("#minecraft:" + tag);
        }
        for (String id : List.of("gravel", "snow_block", "powder_snow", "glass_pane", "tinted_glass",
                "sponge", "wet_sponge", "hay_block", "slime_block", "honey_block", "honeycomb_block",
                "cobweb", "torch", "wall_torch", "soul_torch", "soul_wall_torch", "lantern", "soul_lantern",
                "redstone_wire", "redstone_torch", "redstone_wall_torch", "lever", "repeater", "comparator",
                "tripwire", "tripwire_hook", "cake", "dragon_egg", "turtle_egg", "sniffer_egg", "frogspawn",
                "skeleton_skull", "skeleton_wall_skull", "wither_skeleton_skull", "wither_skeleton_wall_skull",
                "zombie_head", "zombie_wall_head", "player_head", "player_wall_head", "creeper_head",
                "creeper_wall_head", "dragon_head", "dragon_wall_head", "piglin_head", "piglin_wall_head",
                "cactus", "kelp", "kelp_plant", "bamboo", "bamboo_sapling", "sea_pickle", "glowstone",
                "sea_lantern", "end_rod", "ochre_froglight", "verdant_froglight", "pearlescent_froglight",
                "decorated_pot", "tnt", "scaffolding", "azalea", "moss_block", "brown_mushroom_block", "red_mushroom_block",
                "mushroom_stem", "nether_wart_block", "warped_wart_block", "dried_kelp_block", "target", "shroomlight")) {
            soft.add("minecraft:" + id);
        }
        for (String color : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
                "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")) {
            soft.add("minecraft:" + color + "_stained_glass_pane");
        }
        for (String coral : List.of("tube", "brain", "bubble", "fire", "horn")) {
            for (String suffix : List.of("_coral", "_coral_block", "_coral_fan", "_coral_wall_fan")) {
                soft.add("minecraft:dead_" + coral + suffix);
            }
        }
        for (String id : List.of("ice", "packed_ice", "blue_ice", "frosted_ice", "reinforced_deepslate",
                "redstone_lamp", "beacon", "trial_spawner", "vault", "piston", "sticky_piston", "piston_head")) {
            pickaxe.add("minecraft:" + id);
        }
        saveTag(writes, output, "minecraft", "block", "mineable/pickaxe", pickaxe);
        saveTag(writes, output, "minecraft", "block", "mineable/axe", axe);
        saveTag(writes, output, "ashen_frontier", "block", "soft_for_hand", soft);
        saveTag(writes, output, "ashen_frontier", "block", "needs_simple_tool", new JsonArray());
        for (var tool : Map.of("rock_tools", List.of("pickaxes"), "wood_tools", List.of("axes"),
                "soil_tools", List.of("shovels", "hoes"), "simple_tools", List.<String>of()).entrySet()) {
            JsonArray values = new JsonArray();
            tool.getValue().forEach(tag -> values.add("#minecraft:" + tag));
            saveTag(writes, output, "ashen_frontier", "item", tool.getKey(), values);
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private void saveTag(List<CompletableFuture<?>> writes, CachedOutput output, String namespace,
                         String type, String path, JsonArray values) {
        JsonObject tag = new JsonObject();
        tag.addProperty("replace", false);
        tag.add("values", values);
        writes.add(DataProvider.saveStable(output, tag,
                root.resolve("data/" + namespace + "/tags/" + type + "/" + path + ".json")));
    }

    private void save(List<CompletableFuture<?>> writes, CachedOutput output, String path, String json) {
        writes.add(DataProvider.saveStable(output, JsonParser.parseString(json), root.resolve(path + ".json")));
    }

    @Override
    public String getName() {
        return "自然物の表示と回収";
    }

}
