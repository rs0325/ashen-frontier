package io.github.rs0325.ashenfrontier.primitive;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.MaterialData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import java.util.ArrayList;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

@GameTestHolder(AshenFrontierPrimitive.MOD_ID)
@PrefixGameTestTemplate(false)
public class PrimitiveGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void stoneRecipesWithSharedAttributeSourcesRemainLoaded(GameTestHelper helper) {
        for(var name:new String[]{"chopper","chopper_flint","biface"}) {
            var holder=helper.getLevel().getRecipeManager().byKey(id("primitive/"+name));
            check(holder.isPresent(),"既存石器レシピを読み込む: "+name);
            var recipe=(io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe)holder.orElseThrow().value();
            check(recipe.outputs().size()==2&&recipe.processing().inheritance().size()==2
                    &&recipe.processing().inheritance().stream().allMatch(v->v.ingredient()==0),"石器と剥片への同一原料の継承定義を保持");
        }
        helper.succeed();
    }
    private static final BlockPos TABLE = new BlockPos(1, 1, 1);
    private static final BlockPos RAW = new BlockPos(4, 1, 4);

    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void allPathsStartWithEmptyInventoryAndRecoverAfterToolLoss(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            check(player.getInventory().isEmpty(), "初期所持品は空");
            check(MaterialData.get(NatureContent.item("pebble")).orElseThrow().massKg() == 1, "既存材料データを保持");
            check(MaterialData.get(PrimitiveContent.item("chopper")).orElseThrow().massKg() == 0.7
                    && MaterialData.get(PrimitiveContent.item("stone_flake")).orElseThrow().massKg() == 0.1
                    && MaterialData.get(PrimitiveContent.item("cordage")).orElseThrow().massKg() == 0.1, "配布した材料データの単位質量");

            for (int i = 0; i < 4; i++) gather(helper, player, "loose_pebble");
            gather(helper, player, "loose_flint");
            for (int i = 0; i < 2; i++) gather(helper, player, "fiber_bundle");
            for (int i = 0; i < 3; i++) gather(helper, player, "fallen_branch");
            player.setItemInHand(InteractionHand.OFF_HAND, extract(player, NatureContent.item("plant_fiber"), 1));
            var offhand = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickItem(player, InteractionHand.OFF_HAND));
            check(!offhand.isCanceled(), "主手以外へ観察成功と誤通知しない");
            var fiber = player.getOffhandItem();
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.getInventory().add(fiber);
            hold(player, NatureContent.item("pebble"), 1);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND));
            emptyHand(player);
            hold(player, NatureContent.item("plant_fiber"), 1);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND));
            emptyHand(player);
            check(!ResearchManager.isUnlocked(player, id("t0/stoneworking")), "観察だけで解放しない");
            var table = placeSurface(helper, player);
            craft(player, "stone_hammer", Map.of(1, NatureContent.item("pebble")));
            handwork(helper, table, player, "chopper", NatureContent.item("pebble"), 1, RelicContent.item("stone_hammer"), 10);
            check(ResearchManager.isUnlocked(player, id("t0/stoneworking")), "自己ロックせず礫器で研究解放");
            handwork(helper, table, player, "biface", PrimitiveContent.item("chopper"), 1, RelicContent.item("stone_hammer"), 20);
            handwork(helper, table, player, "chopper_flint", NatureContent.item("flint"), 1, RelicContent.item("stone_hammer"), 10);
            handwork(helper, table, player, "biface", PrimitiveContent.item("chopper"), 1, RelicContent.item("stone_hammer"), 20);
            check(count(player, PrimitiveContent.item("stone_flake")) == 10, "剥片3+2を2組、確定副産物");
            handwork(helper, table, player, "tool_handle", NatureContent.item("dry_branch"), 1, PrimitiveContent.item("stone_flake"), 20);
            handwork(helper, table, player, "tool_handle_biface", NatureContent.item("dry_branch"), 1, PrimitiveContent.item("biface"), 20);
            handwork(helper, table, player, "digging_stick", NatureContent.item("dry_branch"), 1, PrimitiveContent.item("stone_flake"), 20);
            handwork(helper, table, player, "cordage", NatureContent.item("plant_fiber"), 1, null, 10);
            check(ResearchManager.isUnlocked(player, id("t0/fiberworking")), "初回の紐で繊維研究を解放");
            handwork(helper, table, player, "cordage", NatureContent.item("plant_fiber"), 1, null, 10);
            check(count(player, PrimitiveContent.item("cordage")) == 18, "繊維2 kgから紐18個");
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                var blade = player.getInventory().getItem(i);
                if (blade.is(PrimitiveContent.item("biface"))) {
                    blade.set(ItemProperties.WEAR, ItemProperties.Wear.of(17, 64));
                    blade.set(ItemProperties.CONTAMINATION, new ItemProperties.Contamination(Map.of("lead", 0.00001)));
                }
            }
            craft(player, "hafted_axe", Map.of(1, PrimitiveContent.item("biface"), 3, PrimitiveContent.item("tool_handle"), 4, PrimitiveContent.item("cordage")));
            craft(player, "stone_adze", Map.of(2, PrimitiveContent.item("biface"), 3, PrimitiveContent.item("tool_handle"), 4, PrimitiveContent.item("cordage")));
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                var assembled = player.getInventory().getItem(i);
                if (assembled.is(PrimitiveContent.item("hafted_axe")) || assembled.is(PrimitiveContent.item("stone_adze"))) {
                    check(assembled.get(ItemProperties.WEAR).value() == 17 && assembled.has(ItemProperties.CONTAMINATION), "組立で刃の摩耗・汚染を初期化しない");
                }
            }
            handwork(helper, table, player, "fiber_cloth", PrimitiveContent.item("cordage"), 9, null, 30);
            for (String name : new String[]{"hafted_axe", "stone_adze", "digging_stick", "fiber_cloth"}) {
                check(count(player, PrimitiveContent.item(name)) == 1, "全経路の出力 " + name);
            }
            check(count(player, PrimitiveContent.item("cordage")) == 7, "組立2個と布9個を二重計上しない");
            emptyHand(player);
            check(player.gameMode.destroyBlock(helper.absolutePos(TABLE)), "台石は素手で回収可能");
            drops(helper, TABLE).forEach(ItemEntity::discard);
            player.getInventory().clearContent();
            for (int i = 0; i < 3; i++) gather(helper, player, "loose_pebble");
            gather(helper, player, "fiber_bundle");
            var restarted = placeSurface(helper, player);
            craft(player, "stone_hammer", Map.of(1, NatureContent.item("pebble")));
            handwork(helper, restarted, player, "chopper", NatureContent.item("pebble"), 1, RelicContent.item("stone_hammer"), 10);
            handwork(helper, restarted, player, "cordage", NatureContent.item("plant_fiber"), 1, null, 10);
            check(count(player, PrimitiveContent.item("chopper")) == 1 && count(player, PrimitiveContent.item("cordage")) == 9,
                    "全工具紛失後も天然素材だけから再開");
            helper.succeed();
        } finally {
            setTime(helper, before);
            player.discard();
        }
    }

    // gameTimeを進めるfixtureと分離し、実際のscheduled tickだけを検査する。
    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID, timeoutTicks = 260, batch = "primitive_scheduled_clock")
    public static void scheduledWorkUsesRealSecondsAndCompletesOnlyOnce(GameTestHelper helper) {
        var player = player(helper);
        var table = fixtureSurface(helper);
        insert(table, player, new ItemStack(RelicContent.item("stone_hammer")));
        insert(table, player, new ItemStack(NatureContent.item("pebble")));
        emptyHand(player);
        table.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
        check(table.station().active() && table.station().output(0).isEmpty(), "開始では消費・出力なし");
        helper.runAfterDelay(190, () -> check(table.station().output(0).isEmpty(), "10実秒未満は未完成"));
        helper.runAfterDelay(215, () -> {
            try {
                check(!table.station().active() && table.station().output(0).is(PrimitiveContent.item("chopper")), "scheduled tickで完成");
                check(table.station().output(1).getCount() == 3 && table.station().tool().get(ItemProperties.WEAR).value() == 1, "工具保持と1回の摩耗");
                table.work();
                check(table.station().output(1).getCount() == 3 && table.station().input(0).isEmpty(), "二重完了しない");
                helper.succeed();
            } finally { player.discard(); }
        });
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void cancellationSaveResumeAndCompetingUsersPreserveInputs(GameTestHelper helper) {
        var player = player(helper);
        var other = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            var table = fixtureSurface(helper);
            insert(table, player, new ItemStack(RelicContent.item("stone_hammer")));
            insert(table, player, new ItemStack(NatureContent.item("pebble")));
            emptyHand(player);
            table.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
            table.interact(other, InteractionHand.MAIN_HAND, Direction.UP);
            advance(helper, table, 100);
            check(table.station().input(0).getCount() == 1 && table.station().tool().get(ItemProperties.WEAR).value() == 0, "競合と途中停止で未消費");
            player.setShiftKeyDown(true);
            table.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
            player.setShiftKeyDown(false);
            check(!table.station().active() && table.station().input(0).getCount() == 1, "取消で素材保持");
            table.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
            advance(helper, table, 100);
            var tag = table.saveWithFullMetadata(helper.getLevel().registryAccess());
            helper.getLevel().removeBlockEntity(table.getBlockPos());
            var restored = (KnappingSurfaceEntity) BlockEntity.loadStatic(table.getBlockPos(), PrimitiveContent.SURFACE.get().defaultBlockState(),
                    tag, helper.getLevel().registryAccess());
            check(restored != null, "実際のBE復元経路");
            helper.getLevel().setBlockEntity(restored);
            advance(helper, restored, 500);
            check(restored.station().active() && restored.station().output(0).isEmpty(), "未ロード時間は加工しない");
            restored.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
            advance(helper, restored, 99);
            check(restored.station().output(0).isEmpty(), "再開後も残り5実秒未満は未完成");
            advance(helper, restored, 1);
            check(restored.station().output(0).is(PrimitiveContent.item("chopper")) && restored.station().output(1).getCount() == 3, "保存進捗から1回だけ完成");
            check(restored.station().tool().get(ItemProperties.WEAR).value() == 1, "保存再開でも工具摩耗1回");
            restored.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
            restored.interact(other, InteractionHand.MAIN_HAND, Direction.UP);
            check(count(player, PrimitiveContent.item("chopper")) == 1 && count(other, PrimitiveContent.item("chopper")) == 0, "出力を二重回収しない");
            helper.succeed();
        } finally {
            setTime(helper, before);
            player.discard();
            other.discard();
        }
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void actualMiningRequiresTheRightToolAndEnforcesWear(GameTestHelper helper) {
        var player = player(helper);
        try {
            mining(helper, player, Blocks.OAK_LOG, ItemStack.EMPTY, false);
            mining(helper, player, Blocks.OAK_LOG, new ItemStack(PrimitiveContent.item("digging_stick")), false);
            mining(helper, player, Blocks.OAK_LOG, new ItemStack(PrimitiveContent.item("hafted_axe")), true);
            mining(helper, player, Blocks.OAK_LOG, new ItemStack(PrimitiveContent.item("stone_adze")), true);
            mining(helper, player, Blocks.SNOW_BLOCK, ItemStack.EMPTY, true);
            mining(helper, player, Blocks.CLAY, ItemStack.EMPTY, false);
            mining(helper, player, Blocks.CLAY, new ItemStack(PrimitiveContent.item("digging_stick")), true);
            mining(helper, player, Blocks.DIRT, ItemStack.EMPTY, false);
            mining(helper, player, Blocks.DIRT, new ItemStack(PrimitiveContent.item("hafted_axe")), false);
            mining(helper, player, Blocks.DIRT, new ItemStack(PrimitiveContent.item("digging_stick")), true);
            for (String name : new String[]{"hafted_axe", "stone_adze", "digging_stick", "biface", "chopper"}) {
                mining(helper, player, Blocks.STONE, new ItemStack(PrimitiveContent.item(name)), false);
            }
            var lastUse = new ItemStack(PrimitiveContent.item("digging_stick"));
            lastUse.set(ItemProperties.WEAR, ItemProperties.Wear.of(63, 64));
            var tooltip = new ArrayList<Component>();
            PrimitiveTooltips.tooltip(new ItemTooltipEvent(lastUse, player, tooltip, TooltipFlag.NORMAL, Item.TooltipContext.EMPTY));
            check(tooltip.contains(Component.translatable("tooltip.ashen_frontier.primitive_mass", 0.8))
                    && tooltip.contains(Component.translatable("tooltip.ashen_frontier.primitive_wear", 63.0, 64.0)), "質量・摩耗の表示データ");

            mining(helper, player, Blocks.DIRT, lastUse, true);
            check(lastUse.get(ItemProperties.WEAR).value() == 64, "最終使用で摩耗上限");
            mining(helper, player, Blocks.DIRT, lastUse, false);
            helper.succeed();
        } finally { player.discard(); }
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void breakingSurfaceReturnsStoredItemsExactlyOnce(GameTestHelper helper) {
        var player = player(helper);
        try {
            var table = fixtureSurface(helper);
            insert(table, player, new ItemStack(RelicContent.item("stone_hammer")));
            insert(table, player, new ItemStack(NatureContent.item("flint")));
            emptyHand(player);
            check(player.gameMode.destroyBlock(table.getBlockPos()), "素手で台石破壊");
            check(dropCount(helper, "knapping_surface") == 1 && dropCount(helper, "stone_hammer") == 1 && dropCount(helper, "flint") == 1,
                    "設備・原料・打撃具を1回だけ回収: table=" + dropCount(helper, "knapping_surface")
                            + ", hammer=" + dropCount(helper, "stone_hammer") + ", flint=" + dropCount(helper, "flint"));
            player.gameMode.destroyBlock(table.getBlockPos());
            check(dropCount(helper, "stone_hammer") == 1 && dropCount(helper, "flint") == 1, "破壊の重複操作で増えない");
            helper.succeed();
        } finally { player.discard(); }
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void quickMoveAssemblyPreservesBladeWearAndContamination(GameTestHelper helper) {
        var player = player(helper);
        try {
            for (String name : new String[]{"hafted_axe", "stone_adze"}) {
                for (int wear : new int[]{17, 64}) {
                    player.getInventory().clearContent();
                    var blade = new ItemStack(PrimitiveContent.item("biface"));
                    blade.set(ItemProperties.WEAR, ItemProperties.Wear.of(wear, 64));
                    var contamination = new ItemProperties.Contamination(Map.of("lead", 0.00001));
                    blade.set(ItemProperties.CONTAMINATION, contamination);
                    var menu = player.inventoryMenu;
                    menu.getSlot(name.equals("hafted_axe") ? 1 : 2).set(blade);
                    menu.getSlot(3).set(new ItemStack(PrimitiveContent.item("tool_handle")));
                    menu.getSlot(4).set(new ItemStack(PrimitiveContent.item("cordage")));
                    check(menu.getSlot(0).getItem().is(PrimitiveContent.item(name)), "組立結果が用意される");
                    menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
                    check(count(player, PrimitiveContent.item(name)) == 1, "Shiftクリックで工具を1個取得");
                    for (var stack : player.getInventory().items) {
                        if (!stack.is(PrimitiveContent.item(name))) continue;
                        check(stack.get(ItemProperties.WEAR).value() == wear
                                && contamination.equals(stack.get(ItemProperties.CONTAMINATION)),
                                "QUICK_MOVEで取得した工具も刃の摩耗と汚染を保持: " + name + " / " + wear);
                        check(PrimitiveToolItem.usable(stack) == (wear < 64), "完全摩耗した刃を再生しない");
                    }
                    for (int i = 1; i <= 4; i++) check(menu.getSlot(i).getItem().isEmpty(), "組立材料を1個ずつ消費");
                }
            }
            helper.succeed();
        } finally { player.discard(); }
    }

    private static FakePlayer player(GameTestHelper helper) {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "primitive-test"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.getInventory().clearContent();
        var pos = helper.absolutePos(TABLE);
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        helper.getLevel().addNewPlayer(player);
        return player;
    }

    private static ResourceLocation id(String path) { return AshenFrontierCore.id(path); }

    private static void gather(GameTestHelper helper, ServerPlayer player, String natural) {
        emptyHand(player);
        var pos = helper.absolutePos(RAW);
        helper.getLevel().setBlockAndUpdate(pos, NatureContent.block(natural).defaultBlockState());
        check(player.gameMode.destroyBlock(pos), "素手の素材fixture " + natural);
        var drops = drops(helper, RAW);
        check(!drops.isEmpty(), "素材fixtureの確実なドロップ");
        drops.forEach(entity -> { player.getInventory().add(entity.getItem().copy()); entity.discard(); });
    }

    private static KnappingSurfaceEntity placeSurface(GameTestHelper helper, ServerPlayer player) {
        hold(player, NatureContent.item("pebble"), 1);
        helper.setBlock(TABLE, Blocks.AIR);
        var ground = helper.absolutePos(TABLE.below());
        helper.getLevel().setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());
        player.setShiftKeyDown(true);
        var placed = player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false));
        player.setShiftKeyDown(false);
        check(helper.getLevel().getBlockState(helper.absolutePos(TABLE)).is(PrimitiveContent.SURFACE.get()), "礫だけで台石を設置: " + placed + " / " + helper.getLevel().getBlockState(helper.absolutePos(TABLE)));
        check(player.getMainHandItem().isEmpty(), "設置の礫を1個だけ消費");
        return (KnappingSurfaceEntity) helper.getLevel().getBlockEntity(helper.absolutePos(TABLE));
    }

    private static KnappingSurfaceEntity fixtureSurface(GameTestHelper helper) {
        helper.setBlock(TABLE, PrimitiveContent.SURFACE.get());
        return (KnappingSurfaceEntity) helper.getLevel().getBlockEntity(helper.absolutePos(TABLE));
    }

    private static void emptyHand(ServerPlayer player) {
        var held = player.getMainHandItem();
        if (held.isEmpty()) return;
        int free = player.getInventory().getFreeSlot();
        check(free >= 0, "試験用所持品に空き");
        player.getInventory().setItem(free, held);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private static ItemStack extract(ServerPlayer player, Item item, int count) {
        emptyHand(player);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(item) && player.getInventory().getItem(i).getCount() >= count) {
                return player.getInventory().removeItem(i, count);
            }
        }
        throw new AssertionError("経路上に必要な材料がない: " + item);
    }

    private static void hold(ServerPlayer player, Item item, int count) {
        player.setItemInHand(InteractionHand.MAIN_HAND, extract(player, item, count));
    }

    private static int count(ServerPlayer player, Item item) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(item)) count += player.getInventory().getItem(i).getCount();
        }
        return count;
    }

    private static void craft(ServerPlayer player, String name, Map<Integer, Item> ingredients) {
        emptyHand(player);
        var menu = player.inventoryMenu;
        ingredients.forEach((slot, item) -> menu.getSlot(slot).set(extract(player, item, 1)));
        var resultSlot = menu.getSlot(0);
        var expected = name.equals("stone_hammer") ? RelicContent.item(name) : PrimitiveContent.item(name);
        check(resultSlot.getItem().is(expected), "所持品2x2の製法 " + name + " / " + resultSlot.getItem());
        var result = resultSlot.remove(1);
        resultSlot.onTake(player, result);
        player.getInventory().add(result);
        for (int i = 1; i <= 4; i++) check(menu.getSlot(i).getItem().isEmpty(), "組立材料を1個ずつ消費");
    }

    private static void insert(KnappingSurfaceEntity table, ServerPlayer player, ItemStack stack) {
        emptyHand(player);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        table.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
    }

    private static void handwork(GameTestHelper helper, KnappingSurfaceEntity table, ServerPlayer player,
                                 String recipe, Item material, int count, Item tool, int seconds) {
        emptyHand(player);
        player.setShiftKeyDown(true);
        use(helper, table, player, Direction.NORTH);
        for (int i = 0; i < 20 && !table.saveWithoutMetadata(helper.getLevel().registryAccess()).getString("selected").equals(id("primitive/" + recipe).toString()); i++) {
            emptyHand(player);
            use(helper, table, player, Direction.UP);
        }
        player.setShiftKeyDown(false);
        check(table.saveWithoutMetadata(helper.getLevel().registryAccess()).getString("selected").equals(id("primitive/" + recipe).toString()), "製法を選択可能");
        if (tool != null) {
            hold(player, tool, 1);
            use(helper, table, player, Direction.UP);
        }
        hold(player, material, count);
        player.setShiftKeyDown(count > 1);
        use(helper, table, player, Direction.UP);
        player.setShiftKeyDown(false);
        emptyHand(player);
        use(helper, table, player, Direction.UP);
        check(table.station().active(), "条件を満たして開始 " + recipe);
        advance(helper, table, seconds * 20 - 1);
        check(table.station().active() && table.station().output(0).isEmpty(), "時間未達は完成しない " + recipe);
        advance(helper, table, 1);
        check(!table.station().active() && !table.station().output(0).isEmpty(), "全時間後に完成 " + recipe);
        use(helper, table, player, Direction.UP);
        check(table.station().output(0).isEmpty() && table.station().output(1).isEmpty(), "全出力を回収");
    }

    private static void use(GameTestHelper helper, KnappingSurfaceEntity table, ServerPlayer player, Direction face) {
        player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(table.getBlockPos().getCenter(), face, table.getBlockPos(), false));
    }

    private static void setTime(GameTestHelper helper, long time) {
        helper.getLevel().getServer().getWorldData().overworldData().setGameTime(time);
    }

    // 同期試験内だけ時刻を進める。各試験のfinallyで戻し、他の試験へ持ち越さない。
    private static void advance(GameTestHelper helper, KnappingSurfaceEntity table, int ticks) {
        setTime(helper, helper.getLevel().getGameTime() + ticks);
        table.work();
    }

    private static void mining(GameTestHelper helper, ServerPlayer player, Block block, ItemStack tool, boolean allowed) {
        var pos = helper.absolutePos(RAW);
        helper.getLevel().setBlockAndUpdate(pos, block.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        float progress = block.defaultBlockState().getDestroyProgress(player, helper.getLevel(), pos);
        check(allowed ? progress > 0 : progress == 0, "破壊進行の適性 " + block + " / " + tool);
        check(player.gameMode.destroyBlock(pos) == allowed, "サーバーの直接破壊も適性を強制 " + block + " / " + tool);
        drops(helper, RAW).forEach(ItemEntity::discard);
    }

    private static java.util.List<ItemEntity> drops(GameTestHelper helper, BlockPos relative) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(relative)).inflate(0.5));
    }

    private static int dropCount(GameTestHelper helper, String path) {
        return drops(helper, TABLE).stream().filter(e -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).equals(id(path)))
                .mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
