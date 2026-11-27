package io.github.rs0325.ashenfrontier.core.catalog;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.MaterialData;
import io.github.rs0325.ashenfrontier.core.research.ResearchProgress;
import io.github.rs0325.ashenfrontier.core.team.TeamData;
import io.github.rs0325.ashenfrontier.core.vanilla.RemovedItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RelicGameTests {
    private RelicGameTests() {}

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void fencesConnectAcrossMaterialsAndDisconnectOnRemoval(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "fence-builder"));
        var fences = RelicCatalog.entries().stream().filter(e -> e.id().endsWith("_fence")).toList();
        var connections = Map.of(Direction.NORTH, FenceBlock.NORTH, Direction.SOUTH, FenceBlock.SOUTH,
                Direction.EAST, FenceBlock.EAST, Direction.WEST, FenceBlock.WEST);
        int count = 0;
        for (var first : fences) {
            for (var second : fences) {
                for (var direction : connections.keySet()) {
                    var neighbour = pos.relative(direction);
                    var block = RelicContent.block(first.id());
                    var other = RelicContent.block(second.id());
                    level.setBlock(pos, block.getStateForPlacement(new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                            new ItemStack(block), new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false))), Block.UPDATE_ALL);
                    level.setBlock(neighbour, other.getStateForPlacement(new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                            new ItemStack(other), new BlockHitResult(Vec3.atCenterOf(neighbour), Direction.UP, neighbour, false))), Block.UPDATE_ALL);
                    String context = first.id() + " / " + second.id() + " / " + direction;
                    helper.assertTrue(level.getBlockState(pos).getValue(connections.get(direction))
                            && level.getBlockState(neighbour).getValue(connections.get(direction.getOpposite())),
                            "柵が相互接続しない: " + context);
                    level.removeBlock(neighbour, false);
                    helper.assertTrue(!level.getBlockState(pos).getValue(connections.get(direction)),
                            "隣の柵を除去しても接続が残る: " + context);
                    level.removeBlock(pos, false);
                    count++;
                }
            }
        }
        AshenFrontierCore.LOGGER.info("遺物柵の相互接続・除去: {}種、{}通り成功", fences.size(), count);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void componentsAndMaterialsSurviveSaving(GameTestHelper helper) {
        var lookup = helper.getLevel().registryAccess();
        List<ItemStack> stacks = new ArrayList<>();
        for (var entry : RelicCatalog.entries()) stacks.add(new ItemStack(RelicContent.item(entry.id()), 1));
        for (var entry : NatureCatalog.entries()) {
            if (entry.fluid()) stacks.add(RelicProperties.container(entry.kind().equals("water")
                    ? "sealed_water_container" : "sealed_oil_container", AshenFrontierCore.id(entry.id()), 2.75));
        }
        stacks.add(RelicProperties.container("sealed_reagent_container", AshenFrontierCore.id("archived_catalyst"), .125));
        stacks.add(RelicProperties.document(AshenFrontierCore.id("shelter_operations")));
        for (var stack : stacks) {
            var loaded = ItemStack.parse(lookup, stack.save(lookup)).orElseThrow();
            helper.assertTrue(ItemStack.matches(stack, loaded), "保存往復で品質・量・資料IDが変化: " + stack);
            helper.assertTrue(MaterialData.get(loaded.getItem()).isPresent(), "MaterialDataが欠落: " + loaded);
            helper.assertTrue(!RemovedItems.isRemoved(loaded), "削除品を再導入: " + loaded);
        }
        var clean = RelicProperties.container("sealed_water_container", AshenFrontierCore.id("clean_water"), 1);
        var toxic = RelicProperties.container("sealed_water_container", AshenFrontierCore.id("toxic_water"), 1);
        helper.assertTrue(!ItemStack.isSameItemSameComponents(clean, toxic), "水質が違う容器がスタック可能");
        AshenFrontierCore.LOGGER.info("遺物Item保存・材料・削除品検証: {}件成功", stacks.size());
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void equipmentKeepsCoreEntityWithoutTicker(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        int count = 0;
        for (var entry : RelicCatalog.entries()) {
            if (!entry.kind().equals("equipment")) continue;
            var block = RelicContent.block(entry.id());
            level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
            var entity = level.getBlockEntity(pos);
            helper.assertTrue(entity instanceof RelicEquipmentEntity, "core所有のBlockEntityがない: " + entry.id());
            var saved = entity.saveWithFullMetadata(level.registryAccess());
            var loaded = BlockEntity.loadStatic(pos, block.defaultBlockState(), saved, level.registryAccess());
            helper.assertTrue(loaded instanceof RelicEquipmentEntity && loaded.getType() == entity.getType(), "設備保存が不一致: " + entry.id());
            helper.assertTrue(((EntityBlock) block).getTicker(level, block.defaultBlockState(), RelicContent.EQUIPMENT.get()) == null,
                    "旧設備にtickerがある: " + entry.id());
            count++;
        }
        level.removeBlock(pos, false);
        AshenFrontierCore.LOGGER.info("非tick型旧設備の配置・保存: {}件成功", count);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void lootUsesToolsAndDoesNotDuplicateDoorsOrSlabs(GameTestHelper helper) {
        int count = 0;
        for (var entry : RelicCatalog.entries()) {
            if (!entry.block()) continue;
            var block = RelicContent.block(entry.id());
            var state = block.defaultBlockState();
            var tool = entry.shape() == null ? new ItemStack(RelicContent.item("simple_wrench"))
                    : new ItemStack(entry.material().equals("wood") ? Items.IRON_AXE
                            : Items.IRON_PICKAXE);
            var drops = Block.getDrops(state, helper.getLevel(), BlockPos.ZERO, null, null, tool);
            helper.assertTrue(drops.size() == 1 && drops.getFirst().is(RelicContent.item(entry.recovery()))
                    && drops.getFirst().getCount() == 1 && !RemovedItems.isRemoved(drops.getFirst()), "回収lootが不一致: " + entry.id());
            if (entry.kind().equals("equipment")) helper.assertTrue(Block.getDrops(state, helper.getLevel(), BlockPos.ZERO,
                    null, null, ItemStack.EMPTY).isEmpty(), "工具なしのloot: " + entry.id());
            if (block instanceof DoorBlock) helper.assertTrue(Block.getDrops(state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER),
                    helper.getLevel(), BlockPos.ZERO, null, null, tool).isEmpty(), "扉の上半分から二重回収: " + entry.id());
            if (block instanceof SlabBlock) helper.assertTrue(Block.getDrops(state.setValue(SlabBlock.TYPE, SlabType.DOUBLE),
                    helper.getLevel(), BlockPos.ZERO, null, null, tool).getFirst().getCount() == 2, "二重ハーフの回収数が不正: " + entry.id());
            count++;
        }
        AshenFrontierCore.LOGGER.info("遺物loot・扉・ハーフ検証: {}件成功", count);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void usingDocumentKeepsTeamKnowledgeAfterLoss(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "archive-reader"));
        var member = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "archive-member"));
        var teams = TeamData.get(level.getServer());
        var team = teams.ensurePersonalTeam(player);
        teams.ensurePersonalTeam(member);
        teams.invite(player.getUUID(), member.getUUID());
        teams.accept(member.getUUID());
        var id = AshenFrontierCore.id("shelter_operations");
        player.setItemInHand(InteractionHand.MAIN_HAND, RelicProperties.document(id));
        for (int i = 0; i < 2; i++) {
            var result = player.getMainHandItem().getItem().use(level, player, InteractionHand.MAIN_HAND);
            helper.assertTrue(result.getResult().consumesAction(), "資料使用が失敗");
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var progress = ResearchProgress.get(level.getServer());
        helper.assertTrue(teams.teamFor(member.getUUID()).orElseThrow().id().equals(team.id())
                && progress.knowledge(team.id()).contains(id.toString()), "資料紛失後にチーム知識がない");
        var loaded = ResearchProgress.load(progress.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        helper.assertTrue(loaded.knowledge(team.id()).contains(id.toString()), "保存後に資料の知識を失った");
        helper.succeed();
    }
}
