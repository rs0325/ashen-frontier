package io.github.rs0325.ashenfrontier.core.recipe;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public class ProcessingReloadGameTests {
    // 他のテストの一時レシピをreloadで消さないよう、専用バッチで実行する。
    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, batch = "processing_reload", timeoutTicks = 1200)
    public static void actualReloadValidatesMissingAndDeletedResearch(GameTestHelper helper) throws IOException {
        var server = helper.getLevel().getServer();
        var repository = server.getPackRepository();
        var original = List.copyOf(repository.getSelectedIds());
        var folder = server.getWorldPath(LevelResource.DATAPACK_DIR);
        Files.createDirectories(folder);
        String name = "processing_reload_" + UUID.randomUUID();
        var withResearch = folder.resolve(name + "_with.zip");
        var withoutResearch = folder.resolve(name + "_without.zip");
        var researchId = AshenFrontierCore.id(name);
        var recipeId = AshenFrontierCore.id(name + "/valid");
        var badId = AshenFrontierCore.id(name + "/missing");
        var type = AshenFrontierCore.id("game_test_processing");
        var recipe = new CommonRecipe(type, List.of(Ingredient.of(Items.STICK)), List.of(new ItemStack(Items.STRING)),
                new RecipeConditions(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.of(researchId)));
        var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        var json = RecipeTypeRegistry.serializer(type).codec().codec().encodeStart(ops, recipe).getOrThrow().getAsJsonObject();
        json.addProperty("type", type.toString());
        var missing = json.deepCopy();
        missing.getAsJsonObject("conditions").addProperty("research", researchId + "_missing");
        String recipePath = "data/ashen_frontier/recipe/" + name + "/valid.json";
        String badPath = "data/ashen_frontier/recipe/" + name + "/missing.json";
        try {
            writePack(withResearch, Map.of(recipePath, json.toString(), badPath, missing.toString(),
                    "data/ashen_frontier/ashen_frontier/research/" + name + ".json",
                    "{\"name\":\"test\",\"description\":\"test\",\"conditions\":[]}"));
            writePack(withoutResearch, Map.of(recipePath, json.toString()));
            repository.reload();
            var selected = new ArrayList<>(original);
            selected.add("file/" + withResearch.getFileName());
            server.reloadResources(selected).join();
            helper.assertTrue(ResearchManager.tree().definitions().containsKey(researchId.toString()), "実reloadで研究を読込");
            helper.assertTrue(server.getRecipeManager().byKey(recipeId).isPresent(), "有効な研究参照を保持");
            helper.assertTrue(server.getRecipeManager().byKey(badId).isEmpty(), "登録されたイベントで不正参照を除外");
            selected.set(selected.size() - 1, "file/" + withoutResearch.getFileName());
            server.reloadResources(selected).join();
            helper.assertTrue(!ResearchManager.tree().definitions().containsKey(researchId.toString()), "実reloadで研究を削除");
            helper.assertTrue(server.getRecipeManager().byKey(recipeId).isEmpty(), "削除された研究の参照も除外");
        } finally {
            try {
                server.reloadResources(original).join();
            } finally {
                Files.deleteIfExists(withResearch);
                Files.deleteIfExists(withoutResearch);
            }
        }
        helper.succeed();
    }

    private static void writePack(Path path, Map<String, String> files) throws IOException {
        var metadata = new JsonObject();
        var pack = new JsonObject();
        pack.addProperty("pack_format", 48);
        pack.addProperty("description", "研究参照のreload試験");
        metadata.add("pack", pack);
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            var entries = new java.util.LinkedHashMap<>(files);
            entries.put("pack.mcmeta", metadata.toString());
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
