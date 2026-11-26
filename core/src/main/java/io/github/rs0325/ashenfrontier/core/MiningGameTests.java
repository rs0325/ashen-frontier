package io.github.rs0325.ashenfrontier.core;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.catalog.RelicCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MiningGameTests {
    private MiningGameTests() {}

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void blockToolClassificationHasNoGaps(GameTestHelper helper) {
        int classified = 0;
        int excluded = 0;
        var missing = new ArrayList<String>();
        for (var block : BuiltInRegistries.BLOCK) {
            var state = block.defaultBlockState();
            if (state.isAir() || block instanceof LiquidBlock || state.getDestroySpeed(helper.getLevel(), BlockPos.ZERO) < 0
                    || state.is(Blocks.MOVING_PISTON) || state.is(Blocks.BUBBLE_COLUMN)
                    || state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.LIGHT)
                    || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.NETHER_PORTAL)) {
                excluded++;
            } else if (state.is(MiningRules.HAND_SOFT) || state.is(MiningRules.NEEDS_SIMPLE_TOOL)
                    || state.is(BlockTags.MINEABLE_WITH_PICKAXE) || state.is(BlockTags.MINEABLE_WITH_AXE)
                    || state.is(BlockTags.MINEABLE_WITH_SHOVEL) || state.is(BlockTags.MINEABLE_WITH_HOE)) {
                classified++;
            } else {
                missing.add(BuiltInRegistries.BLOCK.getKey(block).toString());
            }
        }
        AshenFrontierCore.LOGGER.info("ブロック適性分類: {}件確認、除外{}件、未分類{}件: {}",
                classified, excluded, missing.size(), missing);
        helper.assertTrue(missing.isEmpty(), "適性分類のないブロック: " + missing);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void miningProgressBreakingCracksAndDrops(GameTestHelper helper) {
        try (var mining = new Mining(helper)) {
            for (var entry : NatureCatalog.entries()) {
                if (!List.of("rock", "ore", "coal").contains(entry.kind())) continue;
                mining.check(NatureContent.block(entry.id()), Items.DIAMOND_PICKAXE, Items.IRON_AXE,
                        BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.recovery().contains(":")
                                ? entry.recovery() : "ashen_frontier:" + entry.recovery())), false);
            }
            mining.check(Blocks.IRON_BLOCK, Items.DIAMOND_PICKAXE, Items.IRON_AXE, Items.IRON_BLOCK, false);
            mining.check(Blocks.WHITE_CONCRETE, Items.DIAMOND_PICKAXE, Items.IRON_AXE, Items.WHITE_CONCRETE, false);
            mining.check(Blocks.OAK_LOG, Items.IRON_AXE, Items.IRON_PICKAXE, Items.OAK_LOG, false);
            mining.check(NatureContent.block("dead_tree"), Items.IRON_AXE, Items.IRON_PICKAXE,
                    Items.OAK_LOG, false);
            mining.check(NatureContent.block("fallen_log"), Items.IRON_AXE, Items.IRON_PICKAXE,
                    Items.OAK_LOG, false);
            mining.check(Blocks.DIRT, Items.IRON_SHOVEL, Items.IRON_PICKAXE, Items.DIRT, true);
            mining.check(Blocks.WHITE_WOOL, Items.SHEARS, Items.IRON_PICKAXE, Items.WHITE_WOOL, true);
            mining.check(Blocks.SCAFFOLDING, Items.IRON_AXE, Items.IRON_PICKAXE, Items.SCAFFOLDING, true);
            mining.check(NatureContent.block("fallen_branch"), Items.IRON_AXE, Items.IRON_PICKAXE,
                    NatureContent.item("dry_branch"), true);
            for (var entry : RelicCatalog.entries()) {
                if (!entry.kind().equals("equipment")) continue;
                for (String tool : List.of("stone_hammer", "simple_wrench")) {
                    mining.check(RelicContent.block(entry.id()), RelicContent.item(tool), Items.IRON_AXE,
                            RelicContent.item(entry.recovery()), false);
                }
            }
            AshenFrontierCore.LOGGER.info("採掘経路検証: {}通り成功", mining.checks);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void zeroHardnessProgressNeverBecomesNaN(GameTestHelper helper) {
        try (var mining = new Mining(helper)) {
            // テスト専用タグで硬さ0の設備を代用し、素手・不適正・適正工具の実経路を確認する。
            helper.assertTrue(!Blocks.SLIME_BLOCK.defaultBlockState().is(MiningRules.HAND_SOFT)
                    && Blocks.SLIME_BLOCK.defaultBlockState().is(MiningRules.NEEDS_SIMPLE_TOOL),
                    "硬さ0の工具必須fixtureが有効でない");
            mining.check(Blocks.SLIME_BLOCK, RelicContent.item("stone_hammer"), Items.IRON_AXE, Items.SLIME_BLOCK, false);
            int count = 0;
            for (var block : BuiltInRegistries.BLOCK) {
                var state = block.defaultBlockState();
                if (state.getDestroySpeed(helper.getLevel(), mining.pos) != 0) continue;
                for (var tool : List.of(ItemStack.EMPTY, new ItemStack(Items.IRON_AXE), new ItemStack(Items.SHEARS))) {
                    mining.player.setItemInHand(InteractionHand.MAIN_HAND, tool);
                    float progress = state.getDestroyProgress(mining.player, helper.getLevel(), mining.pos);
                    helper.assertTrue(!Float.isNaN(progress), "硬さ0で進行がNaN: " + BuiltInRegistries.BLOCK.getKey(block));
                    if (!MiningRules.canMine(state, tool)) {
                        helper.assertTrue(progress == 0, "硬さ0の不適正工具で進行が0でない: " + block);
                    }
                    count++;
                }
            }
            AshenFrontierCore.LOGGER.info("硬さ0の境界検証: {}通り、実採掘{}通り成功", count, mining.checks);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void switchingToolsCannotReuseUnsuitableTime(GameTestHelper helper) {
        try (var mining = new Mining(helper)) {
            for (boolean delayed : List.of(false, true)) {
                mining.prepare(Blocks.IRON_BLOCK, new ItemStack(Items.DIAMOND_PICKAXE));
                mining.action(Action.START_DESTROY_BLOCK);
                mining.tick(2);
                if (delayed) mining.action(Action.STOP_DESTROY_BLOCK);
                mining.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                mining.tick(200);
                helper.assertTrue(mining.cracks.getLast() == -1, "持ち替え時にヒビが消えない");
                mining.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                mining.tick(1);
                mining.action(Action.STOP_DESTROY_BLOCK);
                helper.assertTrue(mining.state().is(Blocks.IRON_BLOCK), "不適正工具の経過時間で破壊された");
                helper.assertTrue(mining.drops().isEmpty(), "中断中にドロップが発生した");
                mining.action(Action.START_DESTROY_BLOCK);
                mining.tick(1);
                mining.action(Action.STOP_DESTROY_BLOCK);
                helper.assertTrue(mining.state().is(Blocks.IRON_BLOCK), "再開直後に過去の時間が使われた");
                mining.tick(300);
                helper.assertTrue(mining.state().isAir(), "適正工具で再開しても破壊できない");
                helper.assertTrue(mining.drops().size() == 1, "再開後のドロップが不正");
            }
            mining.prepare(Blocks.IRON_BLOCK, ItemStack.EMPTY);
            mining.action(Action.START_DESTROY_BLOCK);
            mining.tick(200);
            mining.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            mining.action(Action.START_DESTROY_BLOCK);
            mining.tick(1);
            mining.action(Action.STOP_DESTROY_BLOCK);
            helper.assertTrue(mining.state().is(Blocks.IRON_BLOCK), "素手で待った時間が再開後に使われた");
            mining.action(Action.ABORT_DESTROY_BLOCK);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void hostHardnessControlsMiningTime(GameTestHelper helper) {
        try (var mining = new Mining(helper)) {
            float previousHardness = 0;
            float previousTime = 0;
            for (var entry : NatureCatalog.entries().stream().filter(e -> e.kind().equals("rock"))
                    .sorted(Comparator.comparingDouble(NatureCatalog.Entry::hardness)).toList()) {
                mining.prepare(NatureContent.block(entry.id()), new ItemStack(Items.DIAMOND_PICKAXE));
                float hardness = mining.state().getDestroySpeed(helper.getLevel(), mining.pos);
                float time = 1 / mining.state().getDestroyProgress(mining.player, helper.getLevel(), mining.pos);
                helper.assertTrue(hardness == entry.hardness(), "母岩硬度が登録値と一致しない: " + entry.id());
                if (hardness > previousHardness && previousHardness > 0) {
                    helper.assertTrue(time > previousTime, "硬い母岩の採掘時間が長くならない: " + entry.id());
                }
                previousHardness = hardness;
                previousTime = time;
            }
        }
        helper.succeed();
    }

    private static final class Mining implements AutoCloseable {
        private final GameTestHelper helper;
        private final BlockPos pos;
        private final FakePlayer player;
        private final FakePlayer observer;
        private final List<ServerPlayer> players;
        private final List<Integer> cracks = new ArrayList<>();
        private int checks;
        private boolean started;

        private Mining(GameTestHelper helper) {
            this.helper = helper;
            pos = helper.absolutePos(new BlockPos(0, 1, 0));
            player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "miner"));
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            player.setOnGround(true);
            observer = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "observer"));
            observer.setPos(player.position());
            observer.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(),
                    new Connection(PacketFlow.SERVERBOUND), observer,
                    CommonListenerCookie.createInitial(observer.getGameProfile(), false)) {
                @Override
                public void send(Packet<?> packet) {
                    if (packet instanceof ClientboundBlockDestructionPacket crack
                            && crack.getId() == Mining.this.player.getId() && crack.getPos().equals(pos)) {
                        cracks.add(crack.getProgress());
                    }
                }
            };
            // 公開リストは読み取り専用。通信のない観測者だけをfixture内で登録する。
            try {
                var field = PlayerList.class.getDeclaredField("players");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var list = (List<ServerPlayer>) field.get(helper.getLevel().getServer().getPlayerList());
                players = list;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("GameTest観測者の登録に失敗", e);
            }
            players.add(observer);
        }

        private void check(Block block, Item suitable, Item unsuitable, Item expectedDrop, boolean soft) {
            for (var tool : List.of(ItemStack.EMPTY, new ItemStack(unsuitable), new ItemStack(suitable))) {
                prepare(block, tool);
                boolean allowed = soft || tool.is(suitable);
                String context = BuiltInRegistries.BLOCK.getKey(block) + " / " + tool;
                float progress = state().getDestroyProgress(player, helper.getLevel(), pos);
                helper.assertTrue(allowed ? progress > 0 : progress == 0, "破壊進行が不正: " + context);
                action(Action.START_DESTROY_BLOCK);
                tick(300);
                action(Action.STOP_DESTROY_BLOCK);
                tick(300);
                helper.assertTrue(state().isAir() == allowed, "実際の破壊結果が不正: " + context);
                if (allowed) {
                    helper.assertTrue(drops().size() == 1 && drops().getFirst().getItem().is(expectedDrop)
                            && drops().getFirst().getItem().getCount() == 1, "ドロップが不正: " + context + " / " + drops());
                    helper.assertTrue(progress >= 1 || cracks.stream().anyMatch(stage -> stage >= 0),
                            "ヒビ通知がない: " + context);
                    player.gameMode.destroyBlock(pos);
                    helper.assertTrue(drops().size() == 1 && drops().getFirst().getItem().getCount() == 1,
                            "破壊済みの位置から二重に回収: " + context);
                } else {
                    helper.assertTrue(cracks.stream().noneMatch(stage -> stage >= 0), "不適正工具でヒビが出る: " + context);
                    helper.assertTrue(!player.gameMode.destroyBlock(pos), "直接の破壊経路で適性を迂回できる: " + context);
                    helper.assertTrue(drops().isEmpty(), "不適正工具でドロップした: " + context);
                }
                action(Action.ABORT_DESTROY_BLOCK);
                checks++;
            }
        }

        private void prepare(Block block, ItemStack tool) {
            action(Action.ABORT_DESTROY_BLOCK);
            drops().forEach(ItemEntity::discard);
            helper.getLevel().setBlockAndUpdate(pos, block.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND, tool);
            cracks.clear();
            helper.assertTrue(!state().requiresCorrectToolForDrops() || state().canHarvestBlock(helper.getLevel(), pos, player)
                    || !MiningRules.canMine(state(), tool), "適正工具で回収できない: " + block);
            helper.assertTrue(helper.getLevel().getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS),
                    "ブロックドロップが無効");
        }

        private BlockState state() {
            return helper.getLevel().getBlockState(pos);
        }

        private List<ItemEntity> drops() {
            return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1));
        }

        private void action(Action action) {
            if (action == Action.ABORT_DESTROY_BLOCK && !started) return;
            if (action == Action.START_DESTROY_BLOCK) {
                started = MiningRules.canMine(state(), player.getMainHandItem())
                        && state().getDestroyProgress(player, helper.getLevel(), pos) < 1;
            }
            player.gameMode.handleBlockBreakAction(pos, action, Direction.UP, helper.getLevel().getMaxBuildHeight(), 0);
            if (action == Action.ABORT_DESTROY_BLOCK) started = false;
        }

        private void tick(int ticks) {
            for (int i = 0; i < ticks; i++) player.gameMode.tick();
        }

        @Override
        public void close() {
            action(Action.ABORT_DESTROY_BLOCK);
            players.remove(observer);
            drops().forEach(ItemEntity::discard);
        }
    }
}
