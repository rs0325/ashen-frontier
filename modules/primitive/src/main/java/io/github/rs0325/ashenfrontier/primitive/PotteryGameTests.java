package io.github.rs0325.ashenfrontier.primitive;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import io.github.rs0325.ashenfrontier.core.recipe.ProcessingStation;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

@GameTestHolder(AshenFrontierPrimitive.MOD_ID)
@PrefixGameTestTemplate(false)
public class PotteryGameTests {
    private static final BlockPos TABLE = new BlockPos(1, 1, 1);
    private static final BlockPos HEARTH = new BlockPos(4, 1, 1);
    private static final BlockPos RACK = new BlockPos(7, 1, 1);
    private static final BlockPos RESTORED = new BlockPos(10, 1, 1);
    private record Site(ServerLevel level, BlockPos pos) {}
    private record Climate(long day, double temperature, double humidity, boolean covered, boolean ventilated) {}
    private static final Map<Site, Climate> SAMPLES = new HashMap<>();
    private static final Map<Site, List<CombustionEvent>> EMISSIONS = new HashMap<>();
    private static boolean registered;

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void initialCraftingMoldingDryingAndFrictionFireReachAPot(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            // Bの自製品と#55確保済み水だけをfixtureにし、遺物道具・燃料は使わない。
            add(player, NatureContent.item("pebble"), 4);
            add(player, NatureContent.item("dry_branch"), 7);
            add(player, NatureContent.item("plant_fiber"), 1);
            add(player, PrimitiveContent.item("tool_handle"), 1);
            add(player, PrimitiveContent.item("cordage"), 3);
            add(player, Items.CLAY_BALL, 3);
            add(player, Items.SAND, 1);
            craft(player, "fire_drill", Map.of(1, PrimitiveContent.item("tool_handle"), 2, NatureContent.item("dry_branch"),
                    3, PrimitiveContent.item("cordage"), 4, NatureContent.item("pebble")));
            craft(player, "hearth", Map.of(1, NatureContent.item("pebble"), 2, NatureContent.item("pebble"), 3, Items.CLAY_BALL));
            craft(player, "drying_rack", Map.of(1, NatureContent.item("dry_branch"), 2, NatureContent.item("dry_branch"),
                    3, PrimitiveContent.item("cordage"), 4, PrimitiveContent.item("cordage")));
            helper.setBlock(TABLE, PrimitiveContent.SURFACE.get());
            var table = (KnappingSurfaceEntity) helper.getLevel().getBlockEntity(helper.absolutePos(TABLE));
            select(table, player, "tinder");
            tableUse(table, player, extract(player, NatureContent.item("plant_fiber"), 1), false);
            tableUse(table, player, ItemStack.EMPTY, false);
            advance(helper, 200); table.work();
            tableUse(table, player, ItemStack.EMPTY, false);
            check(count(player, PrimitiveContent.item("tinder")) == 10, "台石で確定火口を製作");
            select(table, player, "form_pot_raw");
            var clay = extract(player, Items.CLAY_BALL, 2);
            clay.set(ItemProperties.CONTAMINATION, new ItemProperties.Contamination(Map.of("lead", 0.00001)));
            tableUse(table, player, clay, true);
            tableUse(table, player, extract(player, Items.SAND, 1), false);
            table.station().water().fill(player, id("raw_water"), 0.5);
            tableUse(table, player, ItemStack.EMPTY, false);
            advance(helper, 199); table.work();
            check(table.station().input(0).getCount() == 2 && table.station().water().contents().massKg() == 0.5, "成形未完了で材料と水を事前消費しない");
            advance(helper, 1); table.work();
            check(table.station().water().contents().massKg() == 0, "成形出力と水0.5 kgの消費を同時確定");
            tableUse(table, player, ItemStack.EMPTY, false);
            var rack = place(helper, player, RACK, extract(player, PrimitiveContent.item("drying_rack"), 1));
            environment(helper, RACK, 24000, 293.15, 0.5, true, true);
            use(rack, player, extract(player, PrimitiveContent.item("green_pot"), 1));
            use(rack, player, ItemStack.EMPTY);
            advance(helper, 23999); rack.work();
            check(rack.station().output(0).isEmpty(), "1ゲーム日に届くまでは乾燥不足");
            advance(helper, 1); rack.work();
            use(rack, player, ItemStack.EMPTY);
            check(count(player, PrimitiveContent.item("dried_pot")) == 1, "棚で自由水0.5 kgを抜き乾燥");
            var hearth = place(helper, player, HEARTH, extract(player, PrimitiveContent.item("hearth"), 1));
            environment(helper, HEARTH, 24000, 293.15, 0.5, true, true);
            use(hearth, player, extract(player, PrimitiveContent.item("tinder"), 1));
            use(hearth, player, extract(player, NatureContent.item("dry_branch"), 1));
            player.setShiftKeyDown(true);
            use(hearth, player, extract(player, NatureContent.item("dry_branch"), 3));
            player.setShiftKeyDown(false);
            use(hearth, player, extract(player, PrimitiveContent.item("fire_drill"), 1));
            advance(helper, 599); hearth.work();
            check(!hearth.lit(), "30実秒の前に着火しない");
            advance(helper, 1); hearth.work();
            check(hearth.lit() && ResearchManager.isUnlocked(player, id("t0/fire")), "摩擦着火でfireを自己ロックせず解放");
            check(player.getMainHandItem().get(ItemProperties.WEAR).value() == 1, "着火成功で道具だけ1摩耗");
            var coldSave = hearth.saveWithFullMetadata(helper.getLevel().registryAccess());
            var hearthPos = hearth.getBlockPos();
            var hearthState = hearth.getBlockState();
            helper.getLevel().removeBlockEntity(hearthPos);
            advance(helper, 12000);
            hearth = (ThermalStationEntity) BlockEntity.loadStatic(hearthPos, hearthState, coldSave, helper.getLevel().registryAccess());
            helper.getLevel().setBlockEntity(hearth);
            use(hearth, player, ItemStack.EMPTY);
            for (int i = 0; i < 200 && (!Double.isFinite(hearth.heat().temperatureK()) || hearth.heat().temperatureK() < 975); i++) {
                advance(helper, 20); hearth.work();
            }
            use(hearth, player, extract(player, PrimitiveContent.item("dried_pot"), 1));
            use(hearth, player, ItemStack.EMPTY);
            check(hearth.station().active(), "気温293 Kでも炉内温度で焼成を開始");
            for (int i = 0; i < 119; i++) { advance(helper, 20); hearth.work(); }
            check(hearth.station().output(0).isEmpty(), "120実秒前は焼成未完了");
            advance(helper, 20); hearth.work();
            var pot = hearth.station().output(0);
            check(pot.is(PrimitiveContent.item("ceramic_pot")) && pot.has(ItemProperties.TEMPERATURE), "共通加工が熱い土鍋を確定");
            check(pot.has(ItemProperties.CONTAMINATION), "焼成で数値汚染を無害化しない");
            check(pot.get(RelicProperties.CONTENT_MASS) == 0, "4 kg容量の空土鍋");
            check(ResearchManager.isUnlocked(player, id("t0/pottery")), "基本土鍋が蓋/陶管を解放");
            use(hearth, player, ItemStack.EMPTY);
            check(count(player, PrimitiveContent.item("ceramic_pot")) == 1, "完成品を1回だけ回収");
            helper.succeed();
        } finally { cleanup(helper, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void dryingRequiresEnvironmentAndHonorsDayLengthSegments(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        long dayBefore = helper.getLevel().getDayTime();
        try {
            helper.setBlock(RACK, PrimitiveContent.DRYING_RACK.get());
            var rack = (ThermalStationEntity) helper.getLevel().getBlockEntity(helper.absolutePos(RACK));
            use(rack, player, pottery("green_pot", "pot"));
            use(rack, player, ItemStack.EMPTY);
            check(!rack.station().active(), "環境未接続を晴天とみなさない");
            environment(helper, RACK, 24000, 293.15, 0.9, true, true);
            use(rack, player, ItemStack.EMPTY);
            check(!rack.station().active(), "湿りすぎる環境で乾燥を開始しない");
            environment(helper, RACK, 24000, 293.15, 0.5, false, true);
            use(rack, player, ItemStack.EMPTY);
            check(!rack.station().active(), "覆い不足を拒否");
            environment(helper, RACK, 24000, 293.15, 0.5, true, true);
            use(rack, player, ItemStack.EMPTY);
            advance(helper, 6000); rack.work();
            helper.getLevel().setDayTime(900000);
            environment(helper, RACK, 12000, 293.15, 0.5, true, true);
            advance(helper, 8999); rack.work();
            check(rack.station().output(0).isEmpty(), "dayTime変更と日長変更で乾燥を早めない");
            advance(helper, 1); rack.work();
            check(rack.station().output(0).is(PrimitiveContent.item("dried_pot")), "旧日長1/4日と新日長3/4日で1ゲーム日");
            helper.succeed();
        } finally { helper.getLevel().setDayTime(dayBefore); cleanup(helper, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void wetCoveredAndCompetingIgnitionNeverConsumeBeforeSuccess(GameTestHelper helper) {
        var player = player(helper);
        var other = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            helper.setBlock(HEARTH, PrimitiveContent.HEARTH.get());
            var hearth = (ThermalStationEntity) helper.getLevel().getBlockEntity(helper.absolutePos(HEARTH));
            use(hearth, player, new ItemStack(PrimitiveContent.item("tinder"), 2));
            player.setShiftKeyDown(true);
            use(hearth, player, new ItemStack(NatureContent.item("dry_branch"), 2));
            player.setShiftKeyDown(false);
            environment(helper, HEARTH, 24000, 293.15, 0.5, true, true);
            var drill = new ItemStack(PrimitiveContent.item("fire_drill"));
            drill.set(NatureProperties.MOISTURE, 0.1);
            use(hearth, player, drill);
            advance(helper, 600); hearth.work();
            check(!hearth.lit() && stored(hearth, "tinder").getCount() == 1, "湿った工具では火口を使わない");
            drill.set(NatureProperties.MOISTURE, 0.0);
            environment(helper, HEARTH, 24000, 293.15, 0.5, false, true);
            use(hearth, player, drill);
            check(!hearth.lit(), "覆いなしで着火できない");
            environment(helper, HEARTH, 24000, 293.15, 0.5, true, true);
            use(hearth, player, drill);
            advance(helper, 200); hearth.work();
            use(hearth, other, new ItemStack(PrimitiveContent.item("fire_drill")));
            advance(helper, 200); hearth.work();
            check(!hearth.lit() && stored(hearth, "tinder").getCount() == 1, "同時操作で着火時間や消費を二重化しない");
            empty(player);
            player.setShiftKeyDown(true);
            hearth.interact(player, InteractionHand.MAIN_HAND, Direction.DOWN);
            player.setShiftKeyDown(false);
            check(!hearth.lit() && stored(hearth, "fuel").getCount() == 2, "取消で燃料・火口を保持");
            use(hearth, player, extract(player, PrimitiveContent.item("fire_drill"), 1));
            advance(helper, 599); hearth.work();
            check(!hearth.lit(), "取消後も30実秒必要");
            advance(helper, 1); hearth.work();
            check(hearth.lit() && stored(hearth, "tinder").isEmpty(), "成功で火口だけを1個消費");
            helper.succeed();
        } finally { other.discard(); cleanup(helper, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void allShapesKeepFullOutputsAndRequireRealHeatAndTime(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            ResearchManager.report(player, id("primitive_event"), "friction_ignition");
            ResearchManager.report(player, id("primitive_event"), "ceramic_pot");
            for (String form : new String[]{"pot", "lid", "tube"}) {
                var station = new ProcessingStation(1, 2, 0.000001);
                for (int i = 0; i < 2; i++) {
                    station.setInput(player, 0, pottery("dried_pot", "pot"));
                    long start = helper.getLevel().getGameTime();
                    var initial = sample(start, start);
                    check(!station.start(player, id("primitive/fire_pot"), id("hearth"), Optional.of(initial)), "気温を炉内温度と取り違えない");
                    check(station.start(player, id("primitive/fire_pot"), id("hearth"), Optional.of(initial), OptionalDouble.of(1000)), "炉内温度を別入力");
                    advance(helper, 2400);
                    var interval = sample(start, helper.getLevel().getGameTime());
                    station.advance(player, id("hearth"), Optional.of(interval), 2_100_000, OptionalDouble.of(1000), 30);
                    check(!station.finish(player, id("hearth"), Optional.of(interval), OptionalDouble.of(1000)), "熱だけあっても適合時間30秒では未完了");
                    advance(helper, 1800);
                    var remaining = sample(interval.endTick(), helper.getLevel().getGameTime());
                    station.advance(player, id("hearth"), Optional.of(remaining), 0, OptionalDouble.of(1000), 90);
                    check(station.finish(player, id("hearth"), Optional.of(remaining), OptionalDouble.of(1000)), "合計120実秒を確定");
                }
                var input = pottery("dried_pot", form);
                input.set(ItemProperties.CONTAMINATION, new ItemProperties.Contamination(Map.of("lead", 0.00001)));
                station.setInput(player, 0, input);
                long start = helper.getLevel().getGameTime();
                check(station.start(player, id("primitive/fire_" + form), id("hearth"), Optional.of(sample(start, start)), OptionalDouble.of(1000)), "各成形種別の焼成開始");
                advance(helper, 2400);
                var interval = sample(start, helper.getLevel().getGameTime());
                station.advance(player, id("hearth"), Optional.of(interval), 2_100_000, OptionalDouble.of(1000), 120);
                check(!station.finish(player, id("hearth"), Optional.of(interval), OptionalDouble.of(1000))
                        && !station.finish(player, id("hearth"), Optional.of(interval), OptionalDouble.of(1000)), "満杯時の二重確定を拒否");
                check(station.input(0).getCount() == 1 && station.output(0).is(PrimitiveContent.item("ceramic_pot")), "全原料と既存出力を保持");
                player.getInventory().add(station.takeOutput(player, 0, 1));
                check(station.finish(player, id("hearth"), Optional.of(interval), OptionalDouble.of(1000)), "空きができたら1回だけ完成");
                var output = station.output(0);
                check(output.is(PrimitiveContent.item("ceramic_" + form)) && output.getCount() == (form.equals("pot") ? 1 : 5), "土鍋1個または蓋/陶管5個");
                check(output.has(ItemProperties.CONTAMINATION) && output.has(ItemProperties.TEMPERATURE), "汚染保持と温度の確定");
                check(station.input(0).isEmpty(), "材料を1回だけ消費");
            }
            helper.succeed();
        } finally { cleanup(helper, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void waterAndMoldingProgressSurviveBreakAndReplacement(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            helper.setBlock(TABLE, PrimitiveContent.SURFACE.get());
            var table = (KnappingSurfaceEntity) helper.getLevel().getBlockEntity(helper.absolutePos(TABLE));
            select(table, player, "form_pot_raw");
            tableUse(table, player, new ItemStack(Items.CLAY_BALL, 2), true);
            tableUse(table, player, new ItemStack(Items.SAND), false);
            var container = RelicProperties.container("sealed_water_container", id("raw_water"), 2);
            tableUse(table, player, container, false);
            check(container.get(RelicProperties.CONTENT_MASS) == 1 && table.station().water().contents().massKg() == 1, "容器から槽への実移送");
            tableUse(table, player, ItemStack.EMPTY, false);
            advance(helper, 100); table.work();
            empty(player);
            check(player.gameMode.destroyBlock(table.getBlockPos()), "成形途中の台石を破壊");
            var items = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(table.getBlockPos()).inflate(1));
            check(items.size() == 1 && items.getFirst().getItem().is(PrimitiveContent.item("knapping_surface")), "水と全所有物を設備1個へ保存");
            var recovered = items.getFirst().getItem().copy();
            items.getFirst().discard();
            var ground = helper.absolutePos(RESTORED.below());
            helper.setBlock(RESTORED, Blocks.AIR);
            helper.getLevel().setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());
            move(helper, player, RESTORED);
            player.setItemInHand(InteractionHand.MAIN_HAND, recovered);
            player.gameMode.useItemOn(player, helper.getLevel(), recovered, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false));
            var restored = (KnappingSurfaceEntity) helper.getLevel().getBlockEntity(helper.absolutePos(RESTORED));
            check(restored.station().water().contents().massKg() == 1 && restored.station().input(0).getCount() == 2, "再配置で水も粘土も消失しない");
            advance(helper, 10000);
            tableUse(restored, player, ItemStack.EMPTY, false);
            advance(helper, 99); restored.work();
            check(restored.station().output(0).isEmpty(), "保存/再配置の未ロード区間を進めない");
            advance(helper, 1); restored.work();
            check(restored.station().output(0).getCount() == 1 && restored.station().water().contents().massKg() == 0.5, "残り5秒で1回だけ成形");
            restored.work();
            check(restored.station().output(0).getCount() == 1, "同時刻再評価で複製しない");
            helper.succeed();
        } finally { cleanup(helper, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void fuelExhaustionReloadAndStoredHearthPreserveEveryOwner(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            helper.setBlock(HEARTH, PrimitiveContent.HEARTH.get());
            var hearth = (ThermalStationEntity) helper.getLevel().getBlockEntity(helper.absolutePos(HEARTH));
            environment(helper, HEARTH, 24000, 293.15, 0.5, true, true);
            use(hearth, player, new ItemStack(PrimitiveContent.item("tinder")));
            use(hearth, player, new ItemStack(NatureContent.item("dry_branch")));
            use(hearth, player, new ItemStack(PrimitiveContent.item("fire_drill")));
            advance(helper, 600); hearth.work();
            empty(player);
            player.setShiftKeyDown(true);
            hearth.interact(player, InteractionHand.MAIN_HAND, Direction.EAST);
            player.setShiftKeyDown(false);
            // 部分燃料0.015 kgと1000 Kを持つ再開状態のfixture。製品に固定環境は追加しない。
            hearth.heat().restore(240000, 16000000, 1000);
            use(hearth, player, pottery("dried_pot", "pot"));
            use(hearth, player, ItemStack.EMPTY);
            check(hearth.station().active(), "部分燃料を持つ炉で開始");
            advance(helper, 20); hearth.work();
            advance(helper, 20); hearth.work();
            check(!hearth.lit() && hearth.heat().fuelJoules() == 0, "燃料が尽きたら停止");
            check(hearth.station().input(0).getCount() == 1 && hearth.station().output(0).isEmpty(), "燃料切れでも材料は無作為破壊されない");
            var saved = hearth.saveWithFullMetadata(helper.getLevel().registryAccess());
            var pos = hearth.getBlockPos();
            var state = hearth.getBlockState();
            helper.getLevel().removeBlockEntity(pos);
            advance(helper, 12000);
            var loaded = (ThermalStationEntity) BlockEntity.loadStatic(pos, state, saved, helper.getLevel().registryAccess());
            helper.getLevel().setBlockEntity(loaded);
            loaded.work();
            check(loaded.station().input(0).getCount() == 1 && loaded.heat().fuelJoules() == 0, "プレイヤーなしの実BlockEntity復元で入力保持");
            use(loaded, player, ItemStack.EMPTY);
            advance(helper, 20); loaded.work();
            check(loaded.station().output(0).isEmpty(), "再開しても無燃料の時間を焼成へ足さない");
            player.setShiftKeyDown(true);
            use(loaded, player, new ItemStack(NatureContent.item("dry_branch"), 3));
            player.setShiftKeyDown(false);
            use(loaded, player, new ItemStack(PrimitiveContent.item("tinder")));
            use(loaded, player, extract(player, PrimitiveContent.item("fire_drill"), 1));
            advance(helper, 600); loaded.work();
            for (int i = 0; i < 500 && loaded.station().output(0).isEmpty(); i++) {
                advance(helper, 20); loaded.work();
            }
            check(loaded.station().output(0).is(PrimitiveContent.item("ceramic_pot")), "再給薪・再着火後に1回だけ焼成");
            double reserve = loaded.heat().fuelJoules();
            int queued = stored(loaded, "fuel").getCount();
            empty(player);
            check(player.gameMode.destroyBlock(pos), "燃料残量と出力を持つ炉を破壊");
            var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1));
            check(drops.size() == 1 && drops.getFirst().getItem().is(PrimitiveContent.item("hearth")), "炉と全内容は1個の回収物");
            var recovered = drops.getFirst().getItem().copy();
            drops.getFirst().discard();
            move(helper, player, RESTORED);
            var rebuilt = place(helper, player, RESTORED, recovered);
            check(rebuilt.heat().fuelJoules() == reserve && stored(rebuilt, "fuel").getCount() == queued, "部分燃料と未燃焼の薪を再配置で保存");
            check(rebuilt.station().output(0).getCount() == 1, "完成出力も保持");
            advance(helper, 24000); rebuilt.work();
            check(rebuilt.heat().fuelJoules() == reserve && rebuilt.station().output(0).getCount() == 1, "未ロード区間を再燃焼・再生成しない");
            use(rebuilt, player, ItemStack.EMPTY);
            check(rebuilt.station().output(0).isEmpty() && count(player, PrimitiveContent.item("ceramic_pot")) == 1, "再配置後の出力回収も1回");
            helper.succeed();
        } finally { cleanup(helper, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void wetMaterialsCannotBeAssembledIntoDryFireStarters(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            for (String recipe : new String[]{"furnace", "campfire", "flint_and_steel"}) {
                check(helper.getLevel().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace(recipe)).isEmpty(), "バニラの着火/加熱製法を停止: " + recipe);
            }
            helper.setBlock(TABLE, PrimitiveContent.SURFACE.get());
            var table = (KnappingSurfaceEntity) helper.getLevel().getBlockEntity(helper.absolutePos(TABLE));
            select(table, player, "tinder");
            var fiber = new ItemStack(NatureContent.item("plant_fiber"));
            fiber.set(NatureProperties.MOISTURE, 0.2);
            player.setItemInHand(InteractionHand.MAIN_HAND, fiber);
            player.gameMode.useItem(player, helper.getLevel(), fiber, InteractionHand.MAIN_HAND);
            tableUse(table, player, fiber, false);
            tableUse(table, player, ItemStack.EMPTY, false);
            advance(helper, 200); table.work();
            check(!table.station().active() && table.station().output(0).isEmpty()
                    && table.station().input(0).get(NatureProperties.MOISTURE) == 0.2, "濡れた繊維を加工して乾燥火口へ迂回しない");
            empty(player);
            var menu = player.inventoryMenu;
            menu.getSlot(1).set(new ItemStack(PrimitiveContent.item("tool_handle")));
            var branch = new ItemStack(NatureContent.item("dry_branch"));
            branch.set(NatureProperties.MOISTURE, 0.1);
            menu.getSlot(2).set(branch);
            menu.getSlot(3).set(new ItemStack(PrimitiveContent.item("cordage")));
            menu.getSlot(4).set(new ItemStack(NatureContent.item("pebble")));
            check(menu.getSlot(0).getItem().isEmpty(), "濡れた原料から乾いた弓錐を組み立てない");
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            check(menu.getSlot(2).getItem().get(NatureProperties.MOISTURE) == 0.1, "拒否時は材料も付着水も保持");
            player.getInventory().add(menu.getSlot(2).remove(1));
            menu.getSlot(2).set(new ItemStack(NatureContent.item("dry_branch")));
            check(menu.getSlot(0).getItem().is(PrimitiveContent.item("fire_drill")), "乾いた材料なら標準の組立で製作可能");
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            check(count(player, PrimitiveContent.item("fire_drill")) == 1, "完成品1個だけ");
            helper.succeed();
        } finally { cleanup(helper, player, before); }
    }


    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void partialInventoryReturnsEveryTinder(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            helper.setBlock(TABLE, PrimitiveContent.SURFACE.get());
            var table = (KnappingSurfaceEntity) helper.getLevel().getBlockEntity(helper.absolutePos(TABLE));
            select(table, player, "tinder");
            tableUse(table, player, new ItemStack(NatureContent.item("plant_fiber")), false);
            tableUse(table, player, ItemStack.EMPTY, false);
            advance(helper, 200); table.work();
            table.station().setTool(player, new ItemStack(RelicContent.item("stone_hammer")));
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
            player.getInventory().setItem(10, new ItemStack(PrimitiveContent.item("tinder"), 60));
            player.setShiftKeyDown(true);
            table.interact(player, InteractionHand.MAIN_HAND, Direction.EAST);
            player.setShiftKeyDown(false);
            int dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(2)).stream()
                    .filter(e -> e.getItem().is(PrimitiveContent.item("tinder"))).mapToInt(e -> e.getItem().getCount()).sum();
            check(count(player, PrimitiveContent.item("tinder")) == 64 && dropped == 6, "部分収納の残り6個を消失させずドロップ");
            check(count(player, RelicContent.item("stone_hammer")) == 1 && table.station().output(0).isEmpty(), "工具と火口の所有者を1回だけ移す");
            helper.succeed();
        } finally { cleanup(helper, player, before); }
    }


    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void wetFiberAndWoodRemainWetAfterPreprocessing(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            helper.setBlock(TABLE, PrimitiveContent.SURFACE.get());
            var table = (KnappingSurfaceEntity) helper.getLevel().getBlockEntity(helper.absolutePos(TABLE));
            select(table, player, "cordage");
            var fiber = new ItemStack(NatureContent.item("plant_fiber"));
            fiber.set(NatureProperties.MOISTURE, 0.2);
            player.setItemInHand(InteractionHand.MAIN_HAND, fiber);
            player.gameMode.useItem(player, helper.getLevel(), fiber, InteractionHand.MAIN_HAND);
            tableUse(table, player, fiber, false);
            tableUse(table, player, ItemStack.EMPTY, false);
            advance(helper, 200); table.work();
            var cord = table.station().output(0);
            check(cord.getCount() == 9 && Math.abs(cord.get(NatureProperties.MOISTURE) * 9 - 0.2) < 1e-12, "繊維の自由水0.2 kgを紐9個へ保存");
            tableUse(table, player, ItemStack.EMPTY, false);
            select(table, player, "tool_handle");
            var branch = new ItemStack(NatureContent.item("dry_branch"));
            branch.set(NatureProperties.MOISTURE, 0.2);
            tableUse(table, player, branch, false);
            tableUse(table, player, new ItemStack(PrimitiveContent.item("stone_flake")), false);
            tableUse(table, player, ItemStack.EMPTY, false);
            advance(helper, 400); table.work();
            var handle = table.station().output(0);
            check(handle.is(PrimitiveContent.item("tool_handle")) && handle.get(NatureProperties.MOISTURE) == 0.2, "木工の損失で追加自由水を消さない");
            tableUse(table, player, ItemStack.EMPTY, false);
            var menu = player.inventoryMenu;
            menu.getSlot(1).set(handle.copy());
            menu.getSlot(2).set(new ItemStack(NatureContent.item("dry_branch")));
            menu.getSlot(3).set(cord.copyWithCount(1));
            menu.getSlot(4).set(new ItemStack(NatureContent.item("pebble")));
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            check(menu.getSlot(0).getItem().isEmpty() && count(player, PrimitiveContent.item("fire_drill")) == 0, "前段加工でも弓錐の乾燥条件を迂回しない");
            for (int i = 1; i <= 4; i++) menu.getSlot(i).set(ItemStack.EMPTY);
            select(table, player, "fiber_cloth");
            tableUse(table, player, extract(player, PrimitiveContent.item("cordage"), 9), true);
            tableUse(table, player, ItemStack.EMPTY, false);
            advance(helper, 600); table.work();
            check(table.station().output(0).is(PrimitiveContent.item("fiber_cloth"))
                    && Math.abs(table.station().output(0).get(NatureProperties.MOISTURE) - 0.2) < 1e-12, "9個分の湿りを布1枚へ集約");
            helper.succeed();
        } finally { cleanup(helper, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void moistureIsPartitionedAcrossAllProductsOnlyOnce(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            var station = new ProcessingStation(1, 2, 1);
            var pebbles = new ItemStack(NatureContent.item("pebble"), 2);
            pebbles.set(NatureProperties.MOISTURE, 0.2);
            var hammer = new ItemStack(RelicContent.item("stone_hammer"));
            hammer.set(ItemProperties.WEAR, ItemProperties.Wear.of(0, 64));
            station.setInput(player, 0, pebbles);
            station.setTool(player, hammer);
            check(station.start(player, id("primitive/chopper"), id("knapping_surface"), Optional.empty()), "湿った礫を1個だけ加工");
            advance(helper, 200);
            station.advance(player, id("knapping_surface"), Optional.empty(), 0);
            check(station.finish(player, id("knapping_surface"), Optional.empty()), "複数出力を確定");
            check(Math.abs(station.output(0).get(NatureProperties.MOISTURE) - 0.14) < 1e-12
                    && Math.abs(station.output(1).get(NatureProperties.MOISTURE) - 0.02) < 1e-12, "0.7 kgの礫器と3個の0.1 kg剥片へ質量比例配分");
            check(Math.abs(station.output(0).get(NatureProperties.MOISTURE)
                    + 3 * station.output(1).get(NatureProperties.MOISTURE) - 0.2) < 1e-12, "複数出力へ湿りを複製しない");
            check(station.input(0).getCount() == 1 && station.input(0).get(NatureProperties.MOISTURE) == 0.2, "未消費の礫の湿りは保持");
            var molding = new ProcessingStation(2, 2, 1);
            var clay = new ItemStack(Items.CLAY_BALL, 2);
            clay.set(NatureProperties.MOISTURE, 0.1);
            var sand = new ItemStack(Items.SAND);
            sand.set(NatureProperties.MOISTURE, 0.05);
            molding.setInput(player, 0, clay);
            molding.setInput(player, 1, sand);
            molding.water().fill(player, id("raw_water"), 0.5);
            check(molding.start(player, id("primitive/form_pot_raw"), id("knapping_surface"), Optional.empty()), "複数の湿った原料で成形");
            advance(helper, 200);
            molding.advance(player, id("knapping_surface"), Optional.empty(), 0);
            check(molding.finish(player, id("knapping_surface"), Optional.empty())
                    && Math.abs(molding.output(0).get(NatureProperties.MOISTURE) - 0.25) < 1e-12, "属性継承指定のない砂の自由水も消さない");

            helper.succeed();
        } finally { cleanup(helper, player, before); }
    }


    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void idleLoadedHearthCoolsByGameTimeAndEmitsOnce(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        long dayBefore = helper.getLevel().getDayTime();
        try {
            var hearth = hotExtinguishedHearth(helper, player);
            double fuel = hearth.heat().fuelJoules();
            var events = new ArrayList<CombustionEvent>();
            EMISSIONS.put(new Site(helper.getLevel(), hearth.getBlockPos()), events);
            helper.getLevel().setDayTime(900000);
            advance(helper, 2000);
            use(hearth, player, ItemStack.EMPTY);
            double expected = 293.15 + (1000 - 293.15) * Math.exp(-1);
            check(Math.abs(hearth.heat().temperatureK() - expected) < 1e-8, "ロード中の放置も7200ゲーム秒で1時定数冷える");
            check(hearth.heat().fuelJoules() == fuel && hearth.station().output(0).isEmpty(), "放置中に燃料・加工を進めない");
            check(events.size() == 1 && events.getFirst().burnedKg() == 0 && events.getFirst().energyJoules() == 0
                    && Math.abs(events.getFirst().retainedHeatJoules() - 500 * (expected - 1000)) < 1e-6, "冷却分だけをM3へ1回通知");
            hearth.work();
            check(events.size() == 1, "同じ時刻の評価で放熱を複製しない");
            SAMPLES.remove(new Site(helper.getLevel(), hearth.getBlockPos()));
            advance(helper, 1000);
            hearth.work();
            check(hearth.heat().temperatureK() == expected, "M3未接続を仮の気温で補わない");
            environment(helper, HEARTH, 24000, 293.15, 0.5, true, true);
            hearth.work();
            check(Math.abs(hearth.heat().temperatureK() - (293.15 + (1000 - 293.15) * Math.exp(-1.5))) < 1e-8,
                    "入力を再接続したら未評価のゲーム時間も冷却");
            helper.succeed();
        } finally { helper.getLevel().setDayTime(dayBefore); cleanup(helper, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void restoredHearthCoolsAcrossUnloadedTimeWithoutAPlayer(GameTestHelper helper) {
        var player = player(helper);
        long before = helper.getLevel().getGameTime();
        try {
            var hearth = hotExtinguishedHearth(helper, player);
            environment(helper, HEARTH, 12000, 293.15, 0.5, true, true);
            double fuel = hearth.heat().fuelJoules();
            advance(helper, 500);
            var saved = hearth.saveWithFullMetadata(helper.getLevel().registryAccess());
            var pos = hearth.getBlockPos();
            var state = hearth.getBlockState();
            helper.getLevel().removeBlockEntity(pos);
            player.discard();
            advance(helper, 500);
            var loaded = (ThermalStationEntity) BlockEntity.loadStatic(pos, state, saved, helper.getLevel().registryAccess());
            helper.getLevel().setBlockEntity(loaded);
            loaded.onLoad();
            loaded.work();
            double expected = 293.15 + (1000 - 293.15) * Math.exp(-1);
            check(Math.abs(loaded.heat().temperatureK() - expected) < 1e-8, "未評価のロード期間と未ロード期間を日長12000のGAME時計で冷却");
            check(loaded.heat().fuelJoules() == fuel && !loaded.lit(), "プレイヤーなしの読み込みで燃料を消費せず消火を保持");
            var secondSave = loaded.saveWithFullMetadata(helper.getLevel().registryAccess());
            helper.getLevel().removeBlockEntity(pos);
            var again = (ThermalStationEntity) BlockEntity.loadStatic(pos, state, secondSave, helper.getLevel().registryAccess());
            helper.getLevel().setBlockEntity(again);
            again.work();
            check(again.heat().temperatureK() == loaded.heat().temperatureK(), "更新した冷却時刻を再保存し二重冷却しない");
            helper.succeed();
        } finally { cleanup(helper, player, before); }
    }


    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID, timeoutTicks = 200)
    public static void diskReloadOfAnActuallyUnloadedChunkCoolsTheHearth(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = player(helper);
        long before = level.getGameTime();
        var far = helper.absolutePos(HEARTH.offset(512, 0, 0));
        int chunkX = far.getX() >> 4;
        int chunkZ = far.getZ() >> 4;
        check(!level.getForcedChunks().contains(ChunkPos.asLong(chunkX, chunkZ)), "実アンロード用の専用チャンク");
        try {
            var fixture = hotExtinguishedHearth(helper, player);
            var tag = fixture.saveWithFullMetadata(level.registryAccess());
            tag.putLong("last_tick", before);
            level.getServer().getWorldData().overworldData().setGameTime(before);
            level.setChunkForced(chunkX, chunkZ, true);
            level.getChunkAt(far);
            level.setBlockAndUpdate(far, PrimitiveContent.HEARTH.get().defaultBlockState());
            var original = (ThermalStationEntity) level.getBlockEntity(far);
            original.loadWithComponents(tag, level.registryAccess());
            double fuel = original.heat().fuelJoules();
            environment(helper, HEARTH.offset(512, 0, 0), 24000, 293.15, 0.5, true, true);
            player.discard();
            level.getChunkSource().save(true);
            level.setChunkForced(chunkX, chunkZ, false);
            helper.runAfterDelay(80, () -> {
                long restoreTick = level.getGameTime();
                try {
                    check(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "チャンクの実アンロードを確認");
                    advance(helper, 2000);
                    level.getChunkAt(far);
                    var loaded = (ThermalStationEntity) level.getBlockEntity(far);
                    check(loaded != original, "ディスクから別のBlockEntityへ再読込み");
                    loaded.work();
                    double gameSeconds = (level.getGameTime() - before) * 3.6;
                    double expected = 293.15 + (1000 - 293.15) * Math.exp(-gameSeconds / 7200);
                    check(Math.abs(loaded.heat().temperatureK() - expected) < 1e-7, "ディスク再読込みでも全経過ゲーム時間で冷える");
                    check(loaded.heat().fuelJoules() == fuel && !loaded.lit(), "実アンロード中も燃焼を進めない");
                    helper.succeed();
                } finally {
                    level.setBlockAndUpdate(far, Blocks.AIR.defaultBlockState());
                    level.getEntitiesOfClass(ItemEntity.class, new AABB(far).inflate(2)).forEach(ItemEntity::discard);
                    level.setChunkForced(chunkX, chunkZ, false);
                    SAMPLES.remove(new Site(level, far));
                    cleanup(helper, player, restoreTick);
                }
            });
        } catch (RuntimeException | AssertionError error) {
            level.setChunkForced(chunkX, chunkZ, false);
            SAMPLES.remove(new Site(level, far));
            cleanup(helper, player, before);
            throw error;
        }
    }

    private static ThermalStationEntity hotExtinguishedHearth(GameTestHelper helper, ServerPlayer player) {
        helper.setBlock(HEARTH, PrimitiveContent.HEARTH.get());
        var hearth = (ThermalStationEntity) helper.getLevel().getBlockEntity(helper.absolutePos(HEARTH));
        environment(helper, HEARTH, 24000, 293.15, 0.5, true, true);
        use(hearth, player, new ItemStack(PrimitiveContent.item("tinder")));
        use(hearth, player, new ItemStack(NatureContent.item("dry_branch")));
        use(hearth, player, new ItemStack(PrimitiveContent.item("fire_drill")));
        advance(helper, 600); hearth.work();
        advance(helper, 20); hearth.work();
        empty(player);
        player.setShiftKeyDown(true);
        hearth.interact(player, InteractionHand.MAIN_HAND, Direction.DOWN);
        player.setShiftKeyDown(false);
        // 温度だけを試験用の1000 Kへ置く。燃料・profile・消火は実操作で準備する。
        hearth.heat().restore(hearth.heat().fuelJoules(), 16000000, 1000);
        return hearth;
    }

    private static EnvironmentSample sample(long from, long to) {
        return new EnvironmentSample(from, to, 24000, 293.15, 101325, 0.5, 0, 0, Optional.empty(), true, true);
    }

    private static ItemStack stored(ThermalStationEntity entity, String field) {
        var tag = entity.saveWithoutMetadata(entity.getLevel().registryAccess());
        return ItemStack.OPTIONAL_CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, entity.getLevel().registryAccess()), tag.get(field)).getOrThrow();
    }

    private static void environment(GameTestHelper helper, BlockPos relative, long day, double temperature,
                                    double humidity, boolean covered, boolean ventilated) {
        if (!registered) {
            NeoForge.EVENT_BUS.addListener((EnvironmentQuery event) -> {
                var value = SAMPLES.get(new Site(event.level(), event.position()));
                if (value != null) event.provide(new EnvironmentSample(event.startTick(), event.endTick(), value.day,
                        value.temperature, 101325, value.humidity, 0, 0, Optional.empty(), value.covered, value.ventilated));
            });
            NeoForge.EVENT_BUS.addListener((CombustionEvent event) -> {
                var events = EMISSIONS.get(new Site(event.level(), event.position()));
                if (events != null) events.add(event);
            });
            registered = true;
        }
        SAMPLES.put(new Site(helper.getLevel(), helper.absolutePos(relative)),
                new Climate(day, temperature, humidity, covered, ventilated));
    }

    private static FakePlayer player(GameTestHelper helper) {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "pottery-test"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.getInventory().clearContent();
        move(helper, player, HEARTH);
        helper.getLevel().addNewPlayer(player);
        return player;
    }

    private static void move(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        var pos = helper.absolutePos(relative);
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
    }

    private static void cleanup(GameTestHelper helper, ServerPlayer player, long before) {
        SAMPLES.keySet().removeIf(site -> site.level == helper.getLevel()
                && new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)), Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(16, 6, 16)))).contains(Vec3.atCenterOf(site.pos)));
        EMISSIONS.keySet().removeIf(site -> site.level == helper.getLevel()
                && new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)), Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(16, 6, 16)))).contains(Vec3.atCenterOf(site.pos)));
        helper.getLevel().getServer().getWorldData().overworldData().setGameTime(before);
        player.discard();
    }

    private static void advance(GameTestHelper helper, int ticks) {
        helper.getLevel().getServer().getWorldData().overworldData().setGameTime(helper.getLevel().getGameTime() + ticks);
    }

    private static ResourceLocation id(String name) { return AshenFrontierCore.id(name); }
    private static void add(ServerPlayer player, Item item, int count) { player.getInventory().add(new ItemStack(item, count)); }
    private static void empty(ServerPlayer player) {
        var stack = player.getMainHandItem();
        if (stack.isEmpty()) return;
        int free = player.getInventory().getFreeSlot();
        check(free >= 0, "fixture所持品に空き");
        player.getInventory().setItem(free, stack);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private static ItemStack extract(ServerPlayer player, Item item, int count) {
        empty(player);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(item) && player.getInventory().getItem(i).getCount() >= count) {
                return player.getInventory().removeItem(i, count);
            }
        }
        throw new AssertionError("不足するfixture " + item + " / " + count);
    }

    private static int count(ServerPlayer player, Item item) {
        return player.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static ItemStack pottery(String item, String form) {
        var stack = new ItemStack(PrimitiveContent.item(item));
        stack.set(PotteryProperties.FORM, form);
        return stack;
    }

    private static void craft(ServerPlayer player, String name, Map<Integer, Item> input) {
        empty(player);
        input.forEach((slot, item) -> player.inventoryMenu.getSlot(slot).set(extract(player, item, 1)));
        check(player.inventoryMenu.getSlot(0).getItem().is(PrimitiveContent.item(name)), "2x2で組立 " + name);
        player.inventoryMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        check(count(player, PrimitiveContent.item(name)) == 1, "組立完成品を取得 " + name);
    }

    private static ThermalStationEntity place(GameTestHelper helper, ServerPlayer player, BlockPos relative, ItemStack stack) {
        helper.setBlock(relative, Blocks.AIR);
        var ground = helper.absolutePos(relative.below());
        helper.getLevel().setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());
        empty(player);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.gameMode.useItemOn(player, helper.getLevel(), stack, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false));
        check(stack.isEmpty(), "設備アイテムを1個だけ配置");
        return (ThermalStationEntity) helper.getLevel().getBlockEntity(helper.absolutePos(relative));
    }

    private static void use(ThermalStationEntity entity, ServerPlayer player, ItemStack stack) {
        if (stack != player.getMainHandItem()) empty(player);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        entity.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
    }

    private static void tableUse(KnappingSurfaceEntity entity, ServerPlayer player, ItemStack stack, boolean sneak) {
        if (stack != player.getMainHandItem()) empty(player);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.setShiftKeyDown(sneak);
        entity.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
        player.setShiftKeyDown(false);
    }

    private static void select(KnappingSurfaceEntity table, ServerPlayer player, String name) {
        for (int i = 0; i < 30 && !table.saveWithoutMetadata(player.registryAccess()).getString("selected").equals(id("primitive/" + name).toString()); i++) {
            tableUse(table, player, ItemStack.EMPTY, true);
        }
        check(table.saveWithoutMetadata(player.registryAccess()).getString("selected").equals(id("primitive/" + name).toString()), "台石で製法選択 " + name);
    }

    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
