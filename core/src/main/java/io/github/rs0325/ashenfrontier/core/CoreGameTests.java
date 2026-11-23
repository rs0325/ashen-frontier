package io.github.rs0325.ashenfrontier.core;

import com.mojang.serialization.JsonOps;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import io.github.rs0325.ashenfrontier.core.terminal.TerminalAttachments;
import io.github.rs0325.ashenfrontier.core.terminal.TerminalModules;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.world.item.crafting.Ingredient;
import io.github.rs0325.ashenfrontier.core.vanilla.RemovedItems;
import io.github.rs0325.ashenfrontier.core.vanilla.VanillaWorldEvents;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.LootTable;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import io.github.rs0325.ashenfrontier.core.loot.LifeSupportLoot;
import io.github.rs0325.ashenfrontier.core.loot.LifeSupportLootModifier;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public class CoreGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 40)
    public static void lifeSupportLootScalingPreservesQualityAndExclusions(GameTestHelper helper) {
        var food = new ItemStack(RelicContent.item("canned_food"), 3);
        LifeSupportLoot.scale(food, 0);
        if (!food.isEmpty()) {
            helper.fail("倍率0で任意食料が残った: " + food);
            return;
        }
        var rations = new ItemStack(RelicContent.item("preserved_rations"), 3);
        LifeSupportLoot.scale(rations, 1);
        if (rations.getCount() != 3) {
            helper.fail("既定倍率で数量が変化した: " + rations);
            return;
        }
        var water = RelicProperties.container("sealed_water_container", AshenFrontierCore.id("clean_water"), 0.5);
        LifeSupportLoot.scale(water, 4);
        if (water.getCount() != 4 || water.get(RelicProperties.CONTENT_MASS) != 0.5
                || !AshenFrontierCore.id("clean_water").equals(water.get(NatureProperties.QUALITY))) {
            helper.fail("最大倍率で水量または品質が不正: " + water);
            return;
        }
        if (!LifeSupportLootModifier.appliesTo(AshenFrontierCore.id("buildings/shelter/supplies"))
                || LifeSupportLootModifier.appliesTo(AshenFrontierCore.id("buildings/guaranteed/first_aid"))
                || LifeSupportLootModifier.appliesTo(AshenFrontierCore.id("loot_table/blocks/damaged_boiler"))) {
            helper.fail("建物任意lootの適用範囲が不正");
            return;
        }
        AshenFrontierCore.LOGGER.info("生命維持loot倍率GameTest: 3ケース成功");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 40)
    public static void buildingLifeSupportLootTableScalesAtResolution(GameTestHelper helper) throws IOException {
        var id = AshenFrontierCore.id("buildings/shelter/supplies");
        var resource = helper.getLevel().getServer().getResourceManager().getResource(
                AshenFrontierCore.id("loot_table/buildings/shelter/supplies.json"));
        if (resource.isEmpty()) {
            helper.fail("代表建物loot tableが読み込まれていない");
            return;
        }
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        LootTable table;
        try (var reader = resource.orElseThrow().openAsReader()) {
            table = LootTable.DIRECT_CODEC.parse(ops, JsonParser.parseReader(reader))
                    .resultOrPartial(helper::fail).orElse(null);
        }
        if (table == null) return;
        table.setLootTableId(id);
        var params = new LootParams.Builder(helper.getLevel()).create(LootContextParamSets.EMPTY);
        var loot = table.getRandomItems(params, 1L);
        if (loot.size() != 1 || !loot.getFirst().is(RelicContent.item("canned_food"))
                || loot.getFirst().getCount() != 3) {
            helper.fail("建物lootの既定倍率が不一致: " + loot);
            return;
        }
        AshenFrontierCore.LOGGER.info("建物生命維持loot解決: 1件成功");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 40)
    public static void netherPortalDoesNotForm(GameTestHelper helper) {
        for (int x = 0; x <= 3; x++) {
            helper.setBlock(new BlockPos(x, 0, 0), Blocks.OBSIDIAN);
            helper.setBlock(new BlockPos(x, 4, 0), Blocks.OBSIDIAN);
        }
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(0, y, 0), Blocks.OBSIDIAN);
            helper.setBlock(new BlockPos(3, y, 0), Blocks.OBSIDIAN);
        }
        BlockPos fire = new BlockPos(1, 1, 0);
        helper.setBlock(fire, BaseFireBlock.getState(helper.getLevel(), helper.absolutePos(fire)));
        PortalShape.findEmptyPortalShape(helper.getLevel(), helper.absolutePos(fire), Direction.Axis.X)
                .ifPresent(PortalShape::createPortalBlocks);
        helper.runAfterDelay(5, () -> {
            for (int x = 1; x <= 2; x++) {
                for (int y = 1; y <= 3; y++) {
                    if (helper.getBlockState(new BlockPos(x, y, 0)).is(Blocks.NETHER_PORTAL)) {
                        helper.fail("Nether portal block formed inside the obsidian frame");
                        return;
                    }
                }
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void vanillaRemovalRecipesAndTag(GameTestHelper helper) {
        var recipes = helper.getLevel().getRecipeManager();
        for (String id : List.of("minecraft:hopper", "minecraft:iron_pickaxe",
                "minecraft:bucket", "minecraft:iron_ingot")) {
            if (recipes.byKey(ResourceLocation.parse(id)).isPresent()) {
                helper.fail("Removed recipe is still loaded: " + id + "; matching IDs="
                        + recipes.getRecipes().stream().map(holder -> holder.id().toString())
                                .filter(recipeId -> recipeId.contains("hopper")).toList());
                return;
            }
        }
        if (recipes.byKey(ResourceLocation.parse("minecraft:stone_bricks")).isEmpty()) {
            helper.fail("Building block recipe was removed");
            return;
        }
        var removedItems = helper.getLevel().registryAccess()
                .registryOrThrow(Registries.ITEM)
                .getTag(RemovedItems.TAG).orElseThrow();
        var recipeDisabledItems = helper.getLevel().registryAccess()
                .registryOrThrow(Registries.ITEM)
                .getTag(RemovedItems.RECIPE_DISABLED).orElseThrow();
        if (!removedItems.contains(Items.HOPPER.builtInRegistryHolder())
                || !removedItems.contains(Items.SCULK_SENSOR.builtInRegistryHolder())
                || !removedItems.contains(Items.ENDER_PEARL.builtInRegistryHolder())
                || !removedItems.contains(Items.VILLAGER_SPAWN_EGG.builtInRegistryHolder())
                || removedItems.contains(Items.STONE_BRICKS.builtInRegistryHolder())
                || removedItems.contains(Items.IRON_PICKAXE.builtInRegistryHolder())
                || removedItems.contains(Items.BUCKET.builtInRegistryHolder())
                || removedItems.contains(Items.IRON_INGOT.builtInRegistryHolder())
                || !recipeDisabledItems.contains(Items.IRON_PICKAXE.builtInRegistryHolder())
                || !recipeDisabledItems.contains(Items.BUCKET.builtInRegistryHolder())
                || !recipeDisabledItems.contains(Items.IRON_INGOT.builtInRegistryHolder())) {
            helper.fail("Removed item tag does not match its listed categories");
            return;
        }
        var villages = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE_SET)
                .get(ResourceLocation.withDefaultNamespace("villages"));
        if (villages == null || !villages.structures().isEmpty()) {
            helper.fail("Vanilla village structure set is not empty");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 40)
    public static void removedLootModifierFiltersTaggedItems(GameTestHelper helper) throws IOException {
        ResourceLocation id = AshenFrontierCore.id("removed_item_test");
        var resource = helper.getLevel().getServer().getResourceManager()
                .getResource(ResourceLocation.fromNamespaceAndPath(AshenFrontierCore.MOD_ID,
                        "loot_table/removed_item_test.json"));
        if (resource.isEmpty()) {
            helper.fail("GameTest loot table fixture was not loaded");
            return;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE,
                helper.getLevel().registryAccess());
        LootTable lootTable;
        try (var reader = resource.orElseThrow().openAsReader()) {
            var decoded = LootTable.DIRECT_CODEC.parse(ops, JsonParser.parseReader(reader));
            lootTable = decoded.resultOrPartial(error -> helper.fail("Could not decode loot table: " + error))
                    .orElse(null);
        }
        if (lootTable == null) return;
        lootTable.setLootTableId(id);
        var params = new LootParams.Builder(helper.getLevel()).create(LootContextParamSets.EMPTY);
        ObjectArrayList<ItemStack> loot = lootTable.getRandomItems(params, 1L);
        if (loot.size() != 1 || loot.stream().anyMatch(stack -> stack.is(Items.HOPPER))
                || loot.stream().noneMatch(stack -> stack.is(Items.IRON_PICKAXE))) {
            helper.fail("Global loot modifier did not remove tagged loot: " + loot);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void vanillaNaturalSpawnTypesAreBlocked(GameTestHelper helper) {
        if (!VanillaWorldEvents.shouldCancelSpawn(MobSpawnType.NATURAL)
                || !VanillaWorldEvents.shouldCancelSpawn(MobSpawnType.CHUNK_GENERATION)
                || !VanillaWorldEvents.shouldCancelSpawn(MobSpawnType.PATROL)
                || VanillaWorldEvents.shouldCancelSpawn(MobSpawnType.SPAWNER)) {
            helper.fail("Natural spawn event policy is incorrect");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void terminalModulesPersistAndCanBeInstalled(GameTestHelper helper) {
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "terminal-module-test"));
        ResourceLocation moduleId = AshenFrontierCore.id("game_test_module");
        ResourceLocation display = AshenFrontierCore.id("test");
        player.getInventory().add(new ItemStack(Items.COMPASS));
        if (!TerminalModules.insert(player, moduleId)
                || !TerminalModules.isDisplayEnabled(player, display)
                || !player.getInventory().isEmpty()) {
            helper.fail("Terminal module insertion did not consume the item and enable its display");
            return;
        }
        CompoundTag savedPlayer = new CompoundTag();
        player.saveWithoutId(savedPlayer);
        var restored = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "terminal-module-restored"));
        restored.load(savedPlayer);
        if (!restored.getData(TerminalAttachments.MODULES)
                .equals(player.getData(TerminalAttachments.MODULES))) {
            helper.fail("Terminal module attachment did not round trip through player save data");
            return;
        }
        var respawned = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "terminal-module-respawned"));
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(respawned, player, true));
        if (!respawned.getData(TerminalAttachments.MODULES)
                .equals(player.getData(TerminalAttachments.MODULES))) {
            helper.fail("Terminal module attachment was not copied on death");
            return;
        }
        if (!TerminalModules.remove(player, moduleId)
                || TerminalModules.isDisplayEnabled(player, display)
                || player.getInventory().countItem(Items.COMPASS) != 1) {
            helper.fail("Terminal module removal did not return the item and disable its display");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void coreGameTestRuns(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
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

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void materialDataLoads(GameTestHelper helper) {
        MaterialData material = MaterialData.get(Items.STONE).orElseThrow();
        if (material.massKg() != 1.0) {
            helper.fail("Stone material mass was not loaded");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void itemPropertiesStack(GameTestHelper helper) {
        ItemStack first = new ItemStack(Items.STONE);
        first.set(ItemProperties.TEMPERATURE, ItemProperties.Temperature.of(504.9, 119));
        first.set(ItemProperties.WEAR, ItemProperties.Wear.of(4.9, 10.9));
        first.set(ItemProperties.COMPOSITION, ItemProperties.Composition.of(Map.of("iron", 0.292)));
        first.set(ItemProperties.CONTAMINATION, ItemProperties.Contamination.of(Map.of("lead", 0.061)));
        first.set(ItemProperties.RADIOACTIVITY, ItemProperties.Radioactivity.of("test:short_lived", 10.19, 119));
        first.set(ItemProperties.ROT, ItemProperties.Rot.of(119, 119, 601));
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

        second.set(ItemProperties.TEMPERATURE, ItemProperties.Temperature.of(505, 100));
        if (ItemStack.isSameItemSameComponents(first, second)) {
            helper.fail("Different temperature buckets stacked");
            return;
        }
        ItemStack sameBucket = first.copy();
        sameBucket.set(ItemProperties.TEMPERATURE, ItemProperties.Temperature.of(504, 119));
        if (!ItemStack.isSameItemSameComponents(first, sameBucket)) {
            helper.fail("Values in the same temperature bucket did not stack");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void itemPropertyStackPersistenceRoundTrips(GameTestHelper helper) {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.set(ItemProperties.TEMPERATURE, ItemProperties.Temperature.of(504.9, 119));
        stack.set(ItemProperties.WEAR, ItemProperties.Wear.of(4.9, 10.9));
        stack.set(ItemProperties.COMPOSITION, ItemProperties.Composition.of(Map.of("iron", 0.292)));
        stack.set(ItemProperties.CONTAMINATION, ItemProperties.Contamination.of(Map.of("lead", 0.061)));
        stack.set(ItemProperties.RADIOACTIVITY, ItemProperties.Radioactivity.of("test:boundary", 10.19, 119));
        stack.set(ItemProperties.ROT, ItemProperties.Rot.of(119, 119, 601));
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        ItemStack expected = stack.copy();
        for (int i = 0; i < 5; i++) {
            JsonElement saved = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
            stack = ItemStack.CODEC.parse(ops, saved).getOrThrow();
        }
        if (!ItemStack.isSameItemSameComponents(expected, stack)
                || stack.get(ItemProperties.COMPOSITION).massFractions().get("iron") != 0.29
                || stack.get(ItemProperties.CONTAMINATION).amounts().get("lead") != 0.061) {
            helper.fail("ItemStack persistence changed rounded component values");
            return;
        }
        ItemStack rotStack = new ItemStack(Items.STONE);
        rotStack.set(ItemProperties.ROT, ItemProperties.Rot.of(0, 0, 0));
        ItemProperties.writeRotAtTemperature(rotStack, 19, 303.15);
        ItemProperties.Rot firstCommit = rotStack.get(ItemProperties.ROT);
        ItemProperties.writeRotAtTemperature(rotStack, 19, 303.15);
        if (!firstCommit.equals(rotStack.get(ItemProperties.ROT))) {
            helper.fail("Committing rot twice at the same time changed its value");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void delayedPropertiesAdvance(GameTestHelper helper) {
        ItemProperties.registerHalfLife("test:short_lived", 72.0);
        ItemProperties.registerHalfLife("test:day_lived", 86_400.0);
        if (ItemProperties.currentTemperature(new ItemStack(Items.AIR), 20, 300).isPresent()) {
            helper.fail("Temperature was calculated without material specific heat");
            return;
        }
        ItemStack stack = new ItemStack(Items.STONE);
        ItemStack unregistered = new ItemStack(Items.STONE);
        unregistered.set(ItemProperties.RADIOACTIVITY,
                ItemProperties.Radioactivity.of("test:unregistered", 100, 0));
        if (ItemProperties.currentActivity(unregistered, 20).isPresent()
                || ItemProperties.writeCurrentActivity(unregistered, 20).isPresent()) {
            helper.fail("Unregistered nuclide was unexpectedly evaluated");
            return;
        }
        stack.set(ItemProperties.TEMPERATURE, ItemProperties.Temperature.of(400, 0));
        stack.set(ItemProperties.RADIOACTIVITY, ItemProperties.Radioactivity.of("test:short_lived", 100, 0));
        stack.set(ItemProperties.ROT, ItemProperties.Rot.of(0, 0, 0));

        double currentTemperature = ItemProperties.currentTemperature(stack, 200, 300).orElseThrow();
        double currentActivity = ItemProperties.currentActivity(stack, 20).orElseThrow();
        double currentRot = ItemProperties.currentRotProgress(stack, 20, 303.15).orElseThrow();
        if (!(currentTemperature < 400 && currentTemperature > 300) || currentActivity != 50 || currentRot <= 20) {
            helper.fail("Delayed temperature, activity, or rot calculation was incorrect");
            return;
        }
        ItemStack dayLived = new ItemStack(Items.STONE);
        dayLived.set(ItemProperties.RADIOACTIVITY,
                ItemProperties.Radioactivity.of("test:day_lived", 100, 0));
        if (ItemProperties.currentActivity(dayLived, 24_000).orElseThrow() != 50.0) {
            helper.fail("A one-day half-life did not decay by half in 24,000 ticks");
            return;
        }

        ItemProperties.writeCurrentTemperature(stack, 200, 300).orElseThrow();
        ItemProperties.writeCurrentActivity(stack, 20).orElseThrow();
        ItemProperties.writeRotAtTemperature(stack, 20, 303.15).orElseThrow();
        if (stack.get(ItemProperties.TEMPERATURE).gameTime() != 200
                || stack.get(ItemProperties.RADIOACTIVITY).gameTime() != 20
                || stack.get(ItemProperties.ROT).lastCalculatedAt() != 20
                || stack.get(ItemProperties.ROT).referenceSeconds() != ItemProperties.ROT_STEP_SECONDS) {
            helper.fail("Current values were not committed to their components");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
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

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
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

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
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
        for (int i = 0; i < 5; i++) {
            codec.encode(buffer, value);
            buffer.readerIndex(0);
            T decoded = codec.decode(buffer);
            if (!value.equals(decoded)) return false;
            value = decoded;
            buffer.clear();
        }
        return true;
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
