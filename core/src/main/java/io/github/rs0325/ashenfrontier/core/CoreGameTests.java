package io.github.rs0325.ashenfrontier.core;

import com.mojang.serialization.JsonOps;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import com.google.gson.JsonElement;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.rs0325.ashenfrontier.core.team.TeamData;
import io.github.rs0325.ashenfrontier.core.research.ResearchProgress;
import io.github.rs0325.ashenfrontier.core.research.ResearchTree;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.recipe.RecipeTypeRegistry;
import io.github.rs0325.ashenfrontier.core.recipe.RecipeConditions;
import io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe;
import net.minecraft.world.item.crafting.Ingredient;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public class CoreGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void coreGameTestRuns(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void rescueCommandsRepairMoveAndLog(GameTestHelper helper) throws CommandSyntaxException {
        var server = helper.getLevel().getServer();
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "rescue-test"));
        var source = player.createCommandSourceStack().withPermission(2);
        Path log = server.getServerDirectory().resolve("ashen-frontier-rescue.log");
        long previousEntries;
        try {
            previousEntries = Files.exists(log) ? Files.readAllLines(log).stream()
                    .filter(line -> line.contains("target=rescue-test")).count() : 0;
        } catch (IOException exception) {
            helper.fail("Could not read rescue command log before test: " + exception.getMessage());
            return;
        }
        int repairResult = server.getCommands().getDispatcher().execute(
                "ashenfrontier rescue repair @s", source);
        TeamData.Team team = TeamData.get(server).teamFor(player.getUUID()).orElse(null);
        if (repairResult != 1 || team == null) {
            helper.fail("Rescue repair did not create the target's personal team");
            return;
        }
        ResearchProgress progress = ResearchProgress.get(server);
        progress.unlock(team.id(), "ashen_frontier:stale_test_unlock");
        server.getCommands().getDispatcher().execute("ashenfrontier rescue repair @s", source);
        if (progress.isUnlocked(team.id(), "ashen_frontier:stale_test_unlock")) {
            helper.fail("Rescue repair did not remove an inconsistent research unlock");
            return;
        }
        int moveResult = server.getCommands().getDispatcher().execute(
                "ashenfrontier rescue move @s 3 80 -2", source);
        if (moveResult != 1) {
            helper.fail("Rescue move command failed");
            return;
        }
        if (player.blockPosition().getX() != 3 || player.blockPosition().getY() != 80
                || player.blockPosition().getZ() != -2) {
            helper.fail("Rescue move did not move the target to the specified block: " + player.blockPosition());
            return;
        }
        try {
            long entries = Files.readAllLines(log).stream()
                    .filter(line -> line.contains("target=rescue-test")).count();
            if (entries - previousEntries != 3) {
                helper.fail("Rescue execution log did not append one line per command");
                return;
            }
        } catch (IOException exception) {
            helper.fail("Could not read rescue command log: " + exception.getMessage());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void materialDataLoads(GameTestHelper helper) {
        MaterialData material = MaterialData.get(Items.STONE).orElseThrow();
        if (material.massKg() != 1.0) {
            helper.fail("Stone material mass was not loaded");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void itemPropertiesStack(GameTestHelper helper) {
        ItemStack first = new ItemStack(Items.STONE);
        first.set(ItemProperties.TEMPERATURE, new ItemProperties.Temperature(500, 100));
        first.set(ItemProperties.WEAR, new ItemProperties.Wear(4, 10));
        first.set(ItemProperties.COMPOSITION, new ItemProperties.Composition(Map.of("iron", 0.9)));
        first.set(ItemProperties.CONTAMINATION, new ItemProperties.Contamination(Map.of("lead", 0.1)));
        first.set(ItemProperties.RADIOACTIVITY, new ItemProperties.Radioactivity("test:short_lived", 10, 100));
        first.set(ItemProperties.ROT, new ItemProperties.Rot(100, 100, 0));
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        ItemStack loaded = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, first).getOrThrow()).getOrThrow();
        ItemStack second = loaded.copy();
        if (!ItemStack.isSameItemSameComponents(first, second)) {
            helper.fail("ItemStack codec round trip changed components");
            return;
        }

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        if (!streamRoundTrips(ItemProperties.Temperature.STREAM_CODEC, buffer, first.get(ItemProperties.TEMPERATURE))
                || !streamRoundTrips(ItemProperties.Wear.STREAM_CODEC, buffer, first.get(ItemProperties.WEAR))
                || !streamRoundTrips(ItemProperties.Composition.STREAM_CODEC, buffer, first.get(ItemProperties.COMPOSITION))
                || !streamRoundTrips(ItemProperties.Contamination.STREAM_CODEC, buffer, first.get(ItemProperties.CONTAMINATION))
                || !streamRoundTrips(ItemProperties.Radioactivity.STREAM_CODEC, buffer, first.get(ItemProperties.RADIOACTIVITY))
                || !streamRoundTrips(ItemProperties.Rot.STREAM_CODEC, buffer, first.get(ItemProperties.ROT))) {
            buffer.release();
            helper.fail("A component stream codec round trip changed its value");
            return;
        }
        buffer.release();

        second.set(ItemProperties.TEMPERATURE, new ItemProperties.Temperature(505, 100));
        if (ItemStack.isSameItemSameComponents(first, second)) {
            helper.fail("Different temperature buckets stacked");
            return;
        }
        ItemStack sameBucket = first.copy();
        sameBucket.set(ItemProperties.TEMPERATURE, new ItemProperties.Temperature(504, 119));
        if (!ItemStack.isSameItemSameComponents(first, sameBucket)) {
            helper.fail("Values in the same temperature bucket did not stack");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void delayedPropertiesAdvance(GameTestHelper helper) {
        ItemProperties.registerHalfLife("test:short_lived", 1.0);
        if (ItemProperties.currentTemperature(new ItemStack(Items.AIR), 20, 300).isPresent()) {
            helper.fail("Temperature was calculated without material specific heat");
            return;
        }
        ItemStack stack = new ItemStack(Items.STONE);
        ItemStack unregistered = new ItemStack(Items.STONE);
        unregistered.set(ItemProperties.RADIOACTIVITY,
                new ItemProperties.Radioactivity("test:unregistered", 100, 0));
        if (ItemProperties.currentActivity(unregistered, 20).isPresent()
                || ItemProperties.writeCurrentActivity(unregistered, 20).isPresent()) {
            helper.fail("Unregistered nuclide was unexpectedly evaluated");
            return;
        }
        stack.set(ItemProperties.TEMPERATURE, new ItemProperties.Temperature(400, 0));
        stack.set(ItemProperties.RADIOACTIVITY, new ItemProperties.Radioactivity("test:short_lived", 100, 0));
        stack.set(ItemProperties.ROT, new ItemProperties.Rot(0, 0, 0));

        double currentTemperature = ItemProperties.currentTemperature(stack, 200, 300).orElseThrow();
        double currentActivity = ItemProperties.currentActivity(stack, 20).orElseThrow();
        double currentRot = ItemProperties.currentRotProgress(stack, 20, 303.15).orElseThrow();
        if (!(currentTemperature < 400 && currentTemperature > 300) || currentActivity != 50 || currentRot <= 20) {
            helper.fail("Delayed temperature, activity, or rot calculation was incorrect");
            return;
        }

        ItemProperties.writeCurrentTemperature(stack, 200, 300).orElseThrow();
        ItemProperties.writeCurrentActivity(stack, 20).orElseThrow();
        ItemProperties.writeRotAtTemperature(stack, 20, 303.15).orElseThrow();
        if (stack.get(ItemProperties.TEMPERATURE).gameTime() != 200
                || stack.get(ItemProperties.RADIOACTIVITY).gameTime() != 20
                || stack.get(ItemProperties.ROT).lastCalculatedAt() != 20) {
            helper.fail("Current values were not committed to their components");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void teamOperationsPersist(GameTestHelper helper) {
        TeamData data = TeamData.get(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        TeamData.Team team = data.create(owner, "test-team");
        if (!data.invite(owner, member) || !data.accept(member) || data.teamFor(member).orElseThrow() != team) {
            helper.fail("Team invite and acceptance did not add the member");
            return;
        }
        if (data.leave(member, "member-team").isPresent()
                || data.teamFor(member).orElseThrow().id().equals(team.id())) {
            helper.fail("Team leave did not create a personal team");
            return;
        }
        CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
        TeamData loaded = TeamData.load(saved, helper.getLevel().registryAccess());
        if (loaded.teamFor(owner).isEmpty()
                || loaded.teamFor(member).orElseThrow().id().equals(team.id())) {
            helper.fail("Saved team data did not round trip");
            return;
        }
        ResearchProgress progress = ResearchProgress.get(helper.getLevel().getServer());
        progress.record(team.id(), "test:condition");
        progress.know(team.id(), "test:document");
        TeamData.Team singleton = data.create(UUID.randomUUID(), "singleton");
        UUID leaving = singleton.members().iterator().next();
        progress.record(singleton.id(), "test:condition");
        if (!ResearchManager.leaveTeam(helper.getLevel().getServer(), leaving, "leaving-owner")
                || data.teamFor(leaving).orElseThrow().id().equals(singleton.id())
                || progress.hasCondition(singleton.id(), "test:condition")) {
            helper.fail("Leaving the last member did not clear old progress and create a personal team");
            return;
        }
        CompoundTag progressSaved = progress.save(new CompoundTag(), helper.getLevel().registryAccess());
        ResearchProgress progressLoaded = ResearchProgress.load(progressSaved, helper.getLevel().registryAccess());
        if (!progressLoaded.knowledge(team.id()).contains("test:document")
                || !progressLoaded.hasCondition(team.id(), "test:condition")) {
            helper.fail("Research progress did not round trip knowledge and conditions");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void researchConditionUnlocksForTeam(GameTestHelper helper) {
        TeamData data = TeamData.get(helper.getLevel().getServer());
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "research-test"));
        TeamData.Team team = data.create(player.getUUID(), "research-team");
        UUID teammate = UUID.randomUUID();
        data.invite(player.getUUID(), teammate);
        data.accept(teammate);
        ResourceLocation testResearch = ResourceLocation.parse("ashen_frontier:game_test_survey");
        ResearchTree.Resolution tree = ResearchManager.tree();
        if (!tree.definitions().containsKey(testResearch.toString())) {
            helper.fail("GameTest research datapack was not loaded");
            return;
        }
        ResourceLocation conditionType = ResourceLocation.parse("ashen_frontier:game_test");
        ResearchManager.registerConditionType(conditionType, (reporter, id) -> id.equals("surveyed"));
        ResearchProgress progress = ResearchProgress.get(helper.getLevel().getServer());
        ResourceLocation document = ResourceLocation.parse("ashen_frontier:game_test_document");
        ResearchManager.reportKnowledge(player, document);
        ResearchManager.report(player, conditionType, "surveyed");
        if (!progress.isUnlocked(team.id(), testResearch.toString())
                || !data.teamFor(teammate).orElseThrow().id().equals(team.id())) {
            helper.fail("Condition report did not unlock research for its team");
            return;
        }
        CompoundTag saved = progress.save(new CompoundTag(), helper.getLevel().registryAccess());
        ResearchProgress loaded = ResearchProgress.load(saved, helper.getLevel().registryAccess());
        if (!loaded.hasCondition(team.id(), "ashen_frontier:game_test:surveyed")
                || !loaded.hasCondition(team.id(), "ashen_frontier:document:ashen_frontier:game_test_document")
                || !loaded.knowledge(team.id()).contains(document.toString())
                || !loaded.isUnlocked(team.id(), testResearch.toString())) {
            helper.fail("Research progress did not round trip");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void recipeLoadsAndConditionsRoundTrip(GameTestHelper helper) {
        ResourceLocation type = ResourceLocation.parse("ashen_frontier:game_test");
        var recipes = helper.getLevel().getRecipeManager().getAllRecipesFor(RecipeTypeRegistry.type(type));
        var holder = recipes.stream().findFirst().orElse(null);
        CommonRecipe recipe = holder == null ? null : holder.value();
        if (recipe == null || recipe.inputs().size() != 1 || recipe.outputs().size() != 1) {
            helper.fail("GameTest recipe datapack was not loaded");
            return;
        }
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var serializer = RecipeTypeRegistry.serializer(type);
        JsonElement encoded = serializer.codec().codec().encodeStart(ops, recipe).getOrThrow();
        CommonRecipe decoded = (CommonRecipe) serializer.codec().codec().parse(ops, encoded).getOrThrow();
        if (!sameIngredients(decoded.inputs(), recipe.inputs())
                || !sameStacks(decoded.outputs(), recipe.outputs())
                || !sameConditions(decoded.conditions(), recipe.conditions())) {
            helper.fail("Recipe codec round trip changed the recipe");
            return;
        }
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        serializer.streamCodec().encode(buffer, recipe);
        buffer.readerIndex(0);
        CommonRecipe streamed = serializer.streamCodec().decode(buffer);
        buffer.release();
        if (!sameIngredients(streamed.inputs(), recipe.inputs())
                || !sameStacks(streamed.outputs(), recipe.outputs())
                || !sameConditions(streamed.conditions(), recipe.conditions())) {
            helper.fail("Recipe stream codec round trip changed the recipe");
            return;
        }
        RecipeConditions conditions = recipe.conditions();
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "recipe-research-test"));
        ResourceLocation research = ResourceLocation.parse("ashen_frontier:game_test_survey");
        ResourceLocation conditionType = ResourceLocation.parse("ashen_frontier:game_test");
        ResourceLocation document = ResourceLocation.parse("ashen_frontier:game_test_document");
        ResearchManager.registerConditionType(conditionType, (reporter, condition) -> condition.equals("surveyed"));
        boolean initiallyUnlocked = ResearchManager.isUnlocked(player, research);
        ResearchManager.reportKnowledge(player, document);
        ResearchManager.report(player, conditionType, "surveyed");
        boolean unlocked = ResearchManager.isUnlocked(player, research);
        if (initiallyUnlocked
                || !conditions.matches(1000, 100000, 5, 2500, true, true, unlocked)
                || conditions.matches(1000, 100000, 5, 2500, true, true, initiallyUnlocked)
                || conditions.matches(800, 100000, 5, 2500, true, true, true)) {
            helper.fail("Recipe conditions did not account for research and process limits");
            return;
        }
        helper.succeed();
    }

    private static <T> boolean streamRoundTrips(StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                                RegistryFriendlyByteBuf buffer, T value) {
        buffer.clear();
        codec.encode(buffer, value);
        buffer.readerIndex(0);
        return value.equals(codec.decode(buffer));
    }

    private static boolean sameStacks(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!ItemStack.isSameItemSameComponents(first.get(i), second.get(i))) return false;
        }
        return true;
    }

    private static boolean sameIngredients(List<Ingredient> first, List<Ingredient> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!sameStacks(List.of(first.get(i).getItems()), List.of(second.get(i).getItems()))) return false;
        }
        return true;
    }

    private static boolean sameConditions(RecipeConditions first, RecipeConditions second) {
        return first.temperatureK().equals(second.temperatureK())
                && first.pressurePa().equals(second.pressurePa())
                && first.durationSeconds().equals(second.durationSeconds())
                && first.energyJoules().equals(second.energyJoules())
                && first.catalyst().isPresent() == second.catalyst().isPresent()
                && (first.catalyst().isEmpty() || ItemStack.isSameItemSameComponents(
                        first.catalyst().orElseThrow(), second.catalyst().orElseThrow()))
                && first.facility().equals(second.facility())
                && first.research().equals(second.research());
    }

}
