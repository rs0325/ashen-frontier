package io.github.rs0325.ashenfrontier.primitive;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import net.minecraft.world.inventory.ClickType;
import java.util.ArrayList;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import io.github.rs0325.ashenfrontier.core.recipe.WaterMass;
import io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.water.WaterSourceKey;
import io.github.rs0325.ashenfrontier.core.water.WaterTransfers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.Items;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@GameTestHolder(AshenFrontierPrimitive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WaterTreatmentGameTests {
    private record Site(ServerLevel level, BlockPos position) {}
    private record Climate(long day, double pressure) {}
    private static final Map<Site, Climate> CLIMATE = new HashMap<>();
    private static final Map<Site, List<CombustionEvent>> EMISSIONS = new HashMap<>();
    private static boolean registered;
    private static final BlockPos FILTER = new BlockPos(4, 2, 1);

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void allEightQualitiesKeepTheirIdentityAndOnlyTurbidWaterCanBeFiltered(GameTestHelper h) {
        var player = player(h);
        long before = h.getLevel().getGameTime();
        try {
            unlockPottery(player);
            for (var q : NatureCatalog.entries().stream().filter(e -> e.kind().equals("water")).map(NatureCatalog.Entry::id).toList()) {
                h.setBlock(FILTER, Blocks.AIR);
                h.setBlock(FILTER, PrimitiveContent.FILTER.get());
                var filter = filter(h);
                environment(filter, 24000, 101325);
                var source = WaterTransferGameTests.sourceFixture(h, player, q, 4_000_000);
                var pot = new ItemStack(PrimitiveContent.item("ceramic_pot"));
                player.setItemInHand(InteractionHand.MAIN_HAND, pot);
                check(WaterTransfers.sourceToContainer(player, source, InteractionHand.MAIN_HAND, 4).movedMg() == 4_000_000, "有限水源から4 kgを移す");
                check(WaterTransfers.inspect(h.getLevel(), source, WaterSourceKey.Kind.SURFACE).remainingMg().orElseThrow() == 0, "元水源が枯渇する");
                player.setPos(filter.getBlockPos().getX() + 0.5, filter.getBlockPos().getY() + 1, filter.getBlockPos().getZ() + 0.5);
                filter.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
                check(filter.waterTank().contents().quality().orElseThrow().equals(id(q)), "8水質を槽へ保存");
                use(filter, player, new ItemStack(PrimitiveContent.item("fiber_cloth")));
                use(filter, player, pot);
                use(filter, player, ItemStack.EMPTY);
                check(filter.station().active() == q.equals("turbid_water"), "濁水以外は浄化しない " + q);
                if (q.equals("turbid_water")) {
                    advance(h, 12000); filter.work();
                    check(filter.station().output(0).isEmpty(), "静置だけではろ過が完了しない");
                    advance(h, 600); filter.work();
                    check(mass(filter.station().output(0)) == 3_600_000, "生水3.6 kgを製造");
                    check(filter.station().output(0).get(NatureProperties.QUALITY).equals(id("raw_water")), "出力は生水");
                    check(WaterMass.fromKg(filter.station().tool().get(NatureProperties.MOISTURE)) == 400_000, "布に0.4 kgを保持");
                    check(filter.waterTank().contents().massKg() == 0, "槽・出力・保持水で4 kg");
                } else check(filter.waterTank().contents().massKg() == 4, "拒否時は元品質4 kgが残る");
            }
            h.succeed();
        } finally { cleanup(h, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void clothKeepsFourKgAfterTenBatchesAndCannotWorkAnEleventhBatch(GameTestHelper h) {
        var player = player(h); long before = h.getLevel().getGameTime();
        try {
            unlockPottery(player);
            h.setBlock(FILTER, PrimitiveContent.FILTER.get());
            var filter = filter(h); environment(filter, 12000, 101325);
            use(filter, player, new ItemStack(PrimitiveContent.item("fiber_cloth")));
            for (int i = 0; i < 10; i++) {
                fill(filter, player, "turbid_water", 4);
                use(filter, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
                use(filter, player, ItemStack.EMPTY);
                advance(h, 5999); filter.work();
                check(filter.station().output(0).isEmpty(), "短い日長でも半ゲーム日前は未完了");
                advance(h, 1); filter.work();
                advance(h, 599); filter.work();
                check(filter.station().output(0).isEmpty(), "ろ過は30実秒");
                advance(h, 1); filter.work();
                check(mass(filter.station().takeOutput(player, 0, 1)) == 3_600_000, "各バッチ3.6 kg");
            }
            check(filter.station().tool().get(ItemProperties.WEAR).value() == 10, "10バッチで布が消耗");
            check(WaterMass.fromKg(filter.station().tool().get(NatureProperties.MOISTURE)) == 4_000_000, "10バッチの保持水は正確に4 kg");
            fill(filter, player, "turbid_water", 4);
            use(filter, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            use(filter, player, ItemStack.EMPTY);
            check(!filter.station().active(), "消耗した布では再開できない");
            use(filter, player, new ItemStack(PrimitiveContent.item("fiber_cloth")));
            use(filter, player, ItemStack.EMPTY);
            check(filter.station().active(), "自製布の交換で再開できる");
            check(player.getInventory().items.stream().anyMatch(s -> s.is(PrimitiveContent.item("fiber_cloth"))
                    && s.getOrDefault(NatureProperties.MOISTURE.get(), 0.0) == 4), "交換しても旧布の保持水を消さない");
            h.succeed();
        } finally { cleanup(h, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void fullOutputCancellationAndCompetingPlayersDoNotConsumeWaterOrWetClothTwice(GameTestHelper h) {
        var player = player(h); var other = player(h); long before = h.getLevel().getGameTime();
        try {
            unlockPottery(player); unlockPottery(other);
            h.setBlock(FILTER, PrimitiveContent.FILTER.get());
            var filter = filter(h); environment(filter, 24000, 101325);
            use(filter, player, new ItemStack(PrimitiveContent.item("fiber_cloth")));
            fill(filter, player, "turbid_water", 4);
            use(filter, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            use(filter, player, ItemStack.EMPTY);
            use(filter, other, new ItemStack(PrimitiveContent.item("fiber_cloth")));
            check(filter.station().active(), "別人の操作で処理を変更しない");
            advance(h, 12600); filter.work();
            fill(filter, player, "turbid_water", 4);
            use(filter, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            long start = h.getLevel().getGameTime();
            check(filter.station().start(player, id("primitive/filter_water"), id("water_filter"),
                    EnvironmentQuery.sample(h.getLevel(), filter.getBlockPos(), start, start)), "満杯の出力でも共通基盤は予約せず開始可能");
            advance(h, 600);
            var sample = EnvironmentQuery.sample(h.getLevel(), filter.getBlockPos(), start, h.getLevel().getGameTime());
            filter.station().advance(player, id("water_filter"), sample, 0);
            check(!filter.station().finish(player, id("water_filter"), sample), "満杯なら出力と水消費を確定しない");
            check(filter.waterTank().contents().massKg() == 4, "満杯時に水を事前消費しない");
            check(filter.station().tool().get(ItemProperties.WEAR).value() == 1, "満杯時に布を摩耗させない");
            check(filter.station().tool().get(NatureProperties.MOISTURE) == 0.4, "保持水も一度だけ");
            player.setShiftKeyDown(true); use(filter, player, ItemStack.EMPTY); player.setShiftKeyDown(false);
            check(filter.waterTank().contents().massKg() == 4 && !filter.station().active(), "取消は未消費水を槽へ残す");
            h.succeed();
        } finally { other.discard(); cleanup(h, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void saveLoadKeepsSettlingAndDoesNotCreditUnloadedWork(GameTestHelper h) {
        var player = player(h); long before = h.getLevel().getGameTime();
        try {
            unlockPottery(player); h.setBlock(FILTER, PrimitiveContent.FILTER.get());
            var filter = filter(h); environment(filter, 24000, 101325);
            fill(filter, player, "turbid_water", 4);
            use(filter, player, new ItemStack(PrimitiveContent.item("fiber_cloth")));
            use(filter, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            use(filter, player, ItemStack.EMPTY);
            advance(h, 6000); filter.work();
            var saved = filter.saveWithFullMetadata(h.getLevel().registryAccess());
            h.getLevel().removeBlockEntity(filter.getBlockPos());
            advance(h, 24000);
            filter = restoreFilter(h, filter, saved);
            use(filter, player, ItemStack.EMPTY);
            check(filter.station().output(0).isEmpty() && filter.waterTank().contents().massKg() == 4,
                    "保存復元で未ロードの加工を進めず水量を保持");
            advance(h, 6000); filter.work();
            advance(h, 600); filter.work();
            check(mass(filter.station().output(0)) == 3_600_000, "残りの静置とろ過だけで完了");
            var completed = filter.saveWithFullMetadata(h.getLevel().registryAccess());
            filter = restoreFilter(h, filter, completed);
            use(filter, player, ItemStack.EMPTY);
            check(filter.waterTank().contents().massKg() == 0 && filter.station().tool().get(NatureProperties.MOISTURE) == 0.4,
                    "完成後の復元は水・保持水を再生成しない");
            h.succeed();
        } finally { cleanup(h, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void hearthBoilsRawWaterWithRealHeatAndUnlocksTheWaterResearch(GameTestHelper h) {
        var player = player(h); long before = h.getLevel().getGameTime();
        try {
            unlockPottery(player);
            h.setBlock(FILTER, PrimitiveContent.FILTER.get());
            var filter = filter(h); environment(filter, 24000, 101325);
            fill(filter, player, "turbid_water", 4);
            use(filter, player, new ItemStack(PrimitiveContent.item("fiber_cloth")));
            use(filter, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            use(filter, player, ItemStack.EMPTY);
            advance(h, 12600); filter.work();
            var water = filter.station().takeOutput(player, 0, 1);
            check(!ResearchManager.isUnlocked(player, id("t0/drinking_water")), "ろ過だけで飲用水研究を完了しない");
            var pos = h.absolutePos(new BlockPos(7, 2, 1));
            h.getLevel().setBlockAndUpdate(pos, PrimitiveContent.HEARTH.get().defaultBlockState());
            var hearth = (ThermalStationEntity) h.getLevel().getBlockEntity(pos);
            environment(hearth, 24000, 101325);
            move(player, pos);
            player.setItemInHand(InteractionHand.MAIN_HAND, water);
            hearth.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
            var extra = pot("raw_water", 0.4);
            player.setItemInHand(InteractionHand.MAIN_HAND, extra);
            hearth.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
            check(hearth.waterTank().contents().massKg() == 4 && mass(water) == 0 && mass(extra) == 0, "3.6+0.4 kgを同品質で合算");
            use(hearth, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            selectBoiling(hearth, player);
            use(hearth, player, ItemStack.EMPTY);
            advance(h, 1200); hearth.work();
            check(hearth.station().output(0).isEmpty() && hearth.waterTank().contents().massKg() == 4,
                    "火と実供給熱がなければ60秒待っても清浄化しない");
            use(hearth, player, new ItemStack(PrimitiveContent.item("tinder")));
            use(hearth, player, new ItemStack(NatureContent.item("dry_branch")));
            use(hearth, player, new ItemStack(PrimitiveContent.item("fire_drill")));
            advance(h, 600); hearth.work();
            check(hearth.lit(), "Cの摩擦着火を使う");
            // テスト専用の既知燃料・既知余熱。配布物へ固定環境や燃料供給を置かない。
            hearth.heat().restore(48_000_000, 16_000_000, 1000);
            use(hearth, player, ItemStack.EMPTY);
            advance(h, 20); hearth.work();
            check(hearth.station().output(0).isEmpty(), "熱い炉でも水の予熱と保持を省略しない");
            for (int i = 0; i < 250 && hearth.station().output(0).isEmpty(); i++) { advance(h, 20); hearth.work(); }
            var boiled = hearth.station().output(0);
            check(mass(boiled) == 3_800_000 && boiled.get(NatureProperties.QUALITY).equals(id("clean_water")), "生水4→清浄水3.8 kg");
            check(hearth.waterTank().contents().massKg() == 0
                    && hearth.saveWithoutMetadata(player.registryAccess()).getLong("evaporated_mg") == 200_000,
                    "3.8 kg+蒸発0.2 kgの収支");
            check(boiled.get(ItemProperties.TEMPERATURE).kelvin() <= 373.15, "炉内温度を飲料へ転写しない");
            check(ResearchManager.isUnlocked(player, id("t0/drinking_water")), "ろ過と煮沸の実績で研究完了");
            var saved = hearth.saveWithFullMetadata(h.getLevel().registryAccess());
            h.getLevel().removeBlockEntity(pos);
            var restored = (ThermalStationEntity) BlockEntity.loadStatic(pos, hearth.getBlockState(), saved, h.getLevel().registryAccess());
            h.getLevel().setBlockEntity(restored);
            check(mass(restored.station().output(0)) == 3_800_000 && restored.waterTank().contents().massKg() == 0
                    && restored.saveWithoutMetadata(player.registryAccess()).getLong("evaporated_mg") == 200_000,
                    "完成後保存復元で蒸発や水を再計上しない");
            h.succeed();
        } finally { cleanup(h, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void recipeResearchAndUnknownEnvironmentCannotBeBypassed(GameTestHelper h) {
        var player = player(h); long before = h.getLevel().getGameTime();
        try {
            h.setBlock(FILTER, PrimitiveContent.FILTER.get());
            var filter = filter(h); environment(filter, 24000, 101325);
            fill(filter, player, "turbid_water", 4);
            use(filter, player, new ItemStack(PrimitiveContent.item("fiber_cloth")));
            use(filter, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            use(filter, player, ItemStack.EMPTY);
            check(!filter.station().active(), "未研究では加工不可");
            unlockPottery(player);
            CLIMATE.remove(new Site(h.getLevel(), filter.getBlockPos()));
            use(filter, player, ItemStack.EMPTY);
            check(!filter.station().active(), "環境未接続を晴天とみなさない");
            environment(filter, 24000, 101325);
            use(filter, player, ItemStack.EMPTY);
            check(filter.station().active(), "研究・環境接続で再挑戦可能");
            var full = pot("raw_water", 4);
            var input = CraftingInput.of(2, 2, List.of(full, new ItemStack(Items.SAND),
                    new ItemStack(Items.GRAVEL), ItemStack.EMPTY));
            var assembly = h.getLevel().getRecipeManager().byKey(id("primitive/water_filter")).orElseThrow().value();
            var crafting = (CraftingRecipe) assembly;
            check(!crafting.matches(input, h.getLevel()), "水入り土鍋を組立材料として消さない");
            check(h.getLevel().getRecipeManager().byKey(id("primitive/boil_water")).orElseThrow().value().getType()
                    != RecipeType.CRAFTING, "クラフトグリッドで煮沸を迂回しない");
            h.succeed();
        } finally { cleanup(h, player, before); }
    }


    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void boilingAcceptsOnlyRawWaterAndRequiresTheWholeBatchToTheMilligram(GameTestHelper h) {
        var player = player(h); long before = h.getLevel().getGameTime();
        try {
            unlockPottery(player);
            var pos = h.absolutePos(new BlockPos(7, 2, 1));
            for (var q : NatureCatalog.entries().stream().filter(e -> e.kind().equals("water")).map(NatureCatalog.Entry::id).toList()) {
                h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                h.getLevel().setBlockAndUpdate(pos, PrimitiveContent.HEARTH.get().defaultBlockState());
                var hearth = (ThermalStationEntity) h.getLevel().getBlockEntity(pos);
                environment(hearth, 24000, 101325); move(player, pos);
                use(hearth, player, pot(q, 3.999999));
                use(hearth, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
                selectBoiling(hearth, player);
                use(hearth, player, ItemStack.EMPTY);
                check(!hearth.station().active(), "1 mgでも不足したら開始しない " + q);
                var extra = pot(q, 0.000001);
                use(hearth, player, extra);
                use(hearth, player, ItemStack.EMPTY);
                check(hearth.station().active() == q.equals("raw_water"), "生水だけを対応工程で清浄化する " + q);
                check(hearth.waterTank().contents().quality().orElseThrow().equals(id(q))
                        && hearth.waterTank().contents().massKg() == 4, "拒否・開始とも水質と全量を保持");
            }
            var filterRecipe = (CommonRecipe)
                    h.getLevel().getRecipeManager().byKey(id("primitive/filter_water")).orElseThrow().value();
            check(filterRecipe.instructions().orElseThrow().equals("primitive.ashen_frontier.filter_recipe"),
                    "静置と保持水の案内をJEI/EMIの共通レシピから表示する");
            h.succeed();
        } finally { cleanup(h, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void highAltitudeHearthNeedsOneHundredEightyBoilingSeconds(GameTestHelper h) {
        var player = player(h); long before = h.getLevel().getGameTime();
        var origin = h.absolutePos(new BlockPos(7, 2, 1));
        var pos = new BlockPos(origin.getX(), 144, origin.getZ());
        try {
            unlockPottery(player);
            h.getLevel().setBlockAndUpdate(pos, PrimitiveContent.HEARTH.get().defaultBlockState());
            var hearth = (ThermalStationEntity) h.getLevel().getBlockEntity(pos);
            environment(hearth, 24000, 79500); move(player, pos);
            use(hearth, player, pot("raw_water", 4));
            use(hearth, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            selectBoiling(hearth, player);
            check(hearth.saveWithoutMetadata(player.registryAccess()).getString("selected").endsWith("_high"), "仮想標高2025 mでは高地用製法");
            var tag = hearth.saveWithFullMetadata(h.getLevel().registryAccess());
            tag.putBoolean("lit", true); tag.putDouble("fuel_j", 64_000_000);
            tag.putDouble("heating_value", 16_000_000); tag.putDouble("temperature_k", 1000);
            tag.putString("fuel_profile", id("dry_wood").toString());
            h.getLevel().removeBlockEntity(pos);
            hearth = (ThermalStationEntity) BlockEntity.loadStatic(pos, hearth.getBlockState(), tag, h.getLevel().registryAccess());
            h.getLevel().setBlockEntity(hearth);
            use(hearth, player, ItemStack.EMPTY);
            for (int i = 0; i < 200 && hearth.station().save(player.registryAccess()).getDouble("seconds") == 0; i++) {
                advance(h, 20); hearth.work();
            }
            check(hearth.station().save(player.registryAccess()).getDouble("seconds") > 0, "局所沸点へ予熱してから保持開始");
            for (int i = 0; i < 60; i++) { advance(h, 20); hearth.work(); }
            check(hearth.station().output(0).isEmpty() && hearth.waterTank().contents().massKg() == 4, "高地で60実秒では未確定");
            for (int i = 0; i < 130 && hearth.station().output(0).isEmpty(); i++) { advance(h, 20); hearth.work(); }
            check(mass(hearth.station().output(0)) == 3_800_000, "高地では180実秒の保持で完了");
            check(hearth.station().output(0).get(ItemProperties.TEMPERATURE).kelvin() < 370, "低い気圧の局所沸点を使う");
            h.succeed();
        } finally {
            CLIMATE.remove(new Site(h.getLevel(), pos));
            h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            cleanup(h, player, before);
        }
    }


    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID, batch = "water_treatment_disk", timeoutTicks = 220)
    public static void realChunkReloadKeepsFilterWaterClothAndSettling(GameTestHelper h) {
        var player = player(h); unlockPottery(player);
        var level = h.getLevel();
        var pos = new BlockPos(h.absolutePos(FILTER).getX() + 1024, -58, h.absolutePos(FILTER).getZ());
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        long before = level.getGameTime();
        level.setChunkForced(cx, cz, true); level.getChunkAt(pos);
        level.setBlockAndUpdate(pos, PrimitiveContent.FILTER.get().defaultBlockState());
        var original = (WaterFilterEntity) level.getBlockEntity(pos);
        environment(original, 24000, 101325);
        fill(original, player, "turbid_water", 4);
        use(original, player, new ItemStack(PrimitiveContent.item("fiber_cloth")));
        use(original, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
        use(original, player, ItemStack.EMPTY);
        advance(h, 6000); original.work();
        move(player, h.absolutePos(FILTER));
        level.getChunkSource().save(true);
        level.getServer().getWorldData().overworldData().setGameTime(before);
        level.setChunkForced(cx, cz, false);
        ChunkReloadFixture.afterUnload(h, original, () -> {
            try {
                level.getChunkAt(pos);
                var restored = (WaterFilterEntity) level.getBlockEntity(pos);
                check(restored != original, "元BEが破棄されディスクから別のBEを復元");
                check(restored.waterTank().contents().massKg() == 4 && restored.station().active(), "途中の水と未消費材料を保存");
                check(restored.saveWithoutMetadata(player.registryAccess()).getDouble("settling_seconds") == 21600,
                        "実チャンクの静置進捗を保存");
                use(restored, player, ItemStack.EMPTY);
                advance(h, 6000); restored.work();
                advance(h, 600); restored.work();
                check(mass(restored.station().output(0)) == 3_600_000, "再開して一度だけろ過完了");
                h.succeed();
            } finally {
                CLIMATE.remove(new Site(level, pos));
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                level.setChunkForced(cx, cz, false);
                cleanup(h, player, before);
            }
        });
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void breakingAndReplacingAFilterKeepsItsWholeOwnedBatch(GameTestHelper h) {
        var player = player(h); long before = h.getLevel().getGameTime();
        try {
            unlockPottery(player); h.setBlock(FILTER, PrimitiveContent.FILTER.get());
            var filter = filter(h); environment(filter, 24000, 101325);
            fill(filter, player, "turbid_water", 4);
            use(filter, player, new ItemStack(PrimitiveContent.item("fiber_cloth")));
            use(filter, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
            use(filter, player, ItemStack.EMPTY);
            advance(h, 6000); filter.work();
            var pos = filter.getBlockPos();
            h.setBlock(FILTER, Blocks.AIR);
            var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(pos).inflate(0.5));
            var equipment = drops.stream().filter(e -> e.getItem().is(PrimitiveContent.item("water_filter"))).toList();
            check(equipment.size() == 1, "破壊時の所有状態は設備1個だけに保存");
            var stack = equipment.getFirst().getItem().copy(); equipment.getFirst().discard();
            h.getLevel().setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND, stack); move(player, pos);
            player.gameMode.useItemOn(player, h.getLevel(), stack, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0, 0.5, 0),
                            Direction.UP, pos.below(), false));
            var placed = (WaterFilterEntity) h.getLevel().getBlockEntity(pos);
            check(stack.isEmpty() && placed != null && placed.waterTank().contents().massKg() == 4,
                    "設備アイテムを一度だけ消費し4 kgを再配置");
            check(placed.station().input(0).is(PrimitiveContent.item("ceramic_pot"))
                    && placed.station().tool().is(PrimitiveContent.item("fiber_cloth")), "容器と布も二重ドロップせず保持");
            use(placed, player, ItemStack.EMPTY);
            advance(h, 6600); placed.work();
            check(mass(placed.station().output(0)) == 3_600_000 && placed.waterTank().contents().massKg() == 0,
                    "再配置後に残り時間だけで一度完了");
            h.succeed();
        } finally { cleanup(h, player, before); }
    }


    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID)
    public static void wetFilterAssemblyCannotDeleteFreeWaterWithQuickMove(GameTestHelper h) {
        var player = player(h);
        try {
            for (int wet = 0; wet < 3; wet++) {
                var parts = List.of(new ItemStack(PrimitiveContent.item("ceramic_pot")),
                        new ItemStack(Items.SAND), new ItemStack(Items.GRAVEL));
                parts.get(wet).set(NatureProperties.MOISTURE, 0.4);
                for (int i = 0; i < 3; i++) player.inventoryMenu.getSlot(i + 1).set(parts.get(i));
                player.inventoryMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
                check(player.getInventory().items.stream().noneMatch(s -> s.is(PrimitiveContent.item("water_filter"))),
                        "湿った土鍋・砂・砂利の組立を拒否する " + wet);
                check(player.inventoryMenu.getSlot(wet + 1).getItem().get(NatureProperties.MOISTURE) == 0.4,
                        "拒否で自由水と原料を消さない");
                for (int i = 0; i < 3; i++) player.inventoryMenu.getSlot(i + 1).set(ItemStack.EMPTY);
            }
            player.inventoryMenu.getSlot(1).set(new ItemStack(PrimitiveContent.item("ceramic_pot")));
            player.inventoryMenu.getSlot(2).set(new ItemStack(Items.SAND));
            player.inventoryMenu.getSlot(3).set(new ItemStack(Items.GRAVEL));
            player.inventoryMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
            check(player.getInventory().items.stream().anyMatch(s -> s.is(PrimitiveContent.item("water_filter"))),
                    "乾いた材料なら実QUICK_MOVEで組立可能");
            h.succeed();
        } finally { player.discard(); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID, batch = "water_treatment_rebuttal")
    public static void cancellingASavedBoilingBatchReleasesItsReservedLatentHeatOnce(GameTestHelper h) {
        var player = player(h); long before = h.getLevel().getGameTime();
        try {
            var hearth = boilingFixture(h, player, h.absolutePos(new BlockPos(7, 2, 1)), 64_000_000);
            advance(h, 1180); hearth.work();
            check(hearth.station().save(player.registryAccess()).getDouble("seconds") == 59, "59秒まで保持");
            var tag = hearth.saveWithFullMetadata(player.registryAccess());
            var pos = hearth.getBlockPos();
            h.getLevel().removeBlockEntity(pos);
            hearth = (ThermalStationEntity) BlockEntity.loadStatic(pos, hearth.getBlockState(), tag, player.registryAccess());
            h.getLevel().setBlockEntity(hearth);
            double retained = retained(h.getLevel(), pos);
            player.setShiftKeyDown(true); use(hearth, player, ItemStack.EMPTY); player.setShiftKeyDown(false);
            check(Math.abs(retained(h.getLevel(), pos) - retained + 451400) < 1e-5,
                    "保存復元後の取消で留保した潜熱451400 Jを放熱として返す");
            double released = retained(h.getLevel(), pos);
            use(hearth, player, ItemStack.EMPTY);
            player.setShiftKeyDown(true); use(hearth, player, ItemStack.EMPTY); player.setShiftKeyDown(false);
            check(retained(h.getLevel(), pos) == released, "再度の取消で同じ熱を返さない");
            check(hearth.waterTank().contents().massKg() == 4, "取消では槽の生水を消費しない");
            h.succeed();
        } finally { cleanup(h, player, before); }
    }

    private static ThermalStationEntity boilingFixture(GameTestHelper h, ServerPlayer player, BlockPos pos, double fuelJ) {
        unlockPottery(player);
        h.getLevel().setBlockAndUpdate(pos, PrimitiveContent.HEARTH.get().defaultBlockState());
        var hearth = (ThermalStationEntity) h.getLevel().getBlockEntity(pos);
        environment(hearth, 24000, 101325); move(player, pos);
        EMISSIONS.put(new Site(h.getLevel(), pos), new ArrayList<>());
        use(hearth, player, pot("raw_water", 4));
        use(hearth, player, new ItemStack(PrimitiveContent.item("ceramic_pot")));
        selectBoiling(hearth, player);
        var tag = hearth.saveWithFullMetadata(player.registryAccess());
        tag.putBoolean("lit", true); tag.putDouble("fuel_j", fuelJ);
        tag.putDouble("heating_value", 16_000_000); tag.putDouble("temperature_k", 1000);
        tag.putDouble("water_temperature_k", 373.15); tag.putLong("last_tick", h.getLevel().getGameTime());
        tag.putString("fuel_profile", id("dry_wood").toString());
        h.getLevel().removeBlockEntity(pos);
        hearth = (ThermalStationEntity) BlockEntity.loadStatic(pos, hearth.getBlockState(), tag, player.registryAccess());
        h.getLevel().setBlockEntity(hearth);
        use(hearth, player, ItemStack.EMPTY);
        return hearth;
    }

    private static double retained(ServerLevel level, BlockPos pos) {
        return EMISSIONS.get(new Site(level, pos)).stream().mapToDouble(CombustionEvent::retainedHeatJoules).sum();
    }


    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID, batch = "water_treatment_rebuttal")
    public static void fuelExhaustionHasTheSameWaterAndHeatStateAtBothEvaluationIntervals(GameTestHelper h) {
        var player = player(h); long before = h.getLevel().getGameTime();
        try {
            var whole = boilingFixture(h, player, h.absolutePos(new BlockPos(7, 2, 1)), 30_000);
            var split = boilingFixture(h, player, h.absolutePos(new BlockPos(10, 2, 1)), 30_000);
            long start = h.getLevel().getGameTime();
            advance(h, 20); whole.work();
            h.getLevel().getServer().getWorldData().overworldData().setGameTime(start);
            advance(h, 5); split.work();
            advance(h, 15); split.work();
            var one = whole.saveWithoutMetadata(player.registryAccess());
            var two = split.saveWithoutMetadata(player.registryAccess());
            check(Math.abs(one.getDouble("water_temperature_k") - two.getDouble("water_temperature_k")) < 1e-8,
                    "燃焼0.25秒と非燃焼0.75秒を同じ順で評価する");
            check(Math.abs(retained(h.getLevel(), whole.getBlockPos()) - retained(h.getLevel(), split.getBlockPos())) < 1e-5,
                    "M3へ伝える留保熱も分割で変わらない");
            check(whole.station().save(player.registryAccess()).getDouble("seconds")
                    == split.station().save(player.registryAccess()).getDouble("seconds"), "冷却後の連続保持秒も一致");
            check(whole.waterTank().contents().massKg() == 4 && split.waterTank().contents().massKg() == 4, "未完了の水量は同じ");
            h.succeed();
        } finally { cleanup(h, player, before); }
    }

    @GameTest(template = "pottery_empty", templateNamespace = AshenFrontierPrimitive.MOD_ID, batch = "water_treatment_rebuttal")
    public static void savedFiftyNineSecondsCannotCompleteAfterCoolingAndOneMoreSecond(GameTestHelper h) {
        var player = player(h); long before = h.getLevel().getGameTime();
        try {
            var hearth = boilingFixture(h, player, h.absolutePos(new BlockPos(7, 2, 1)), 64_000_000);
            advance(h, 1180); hearth.work();
            check(hearth.station().save(player.registryAccess()).getDouble("seconds") == 59, "保存前に59秒");
            var pos = hearth.getBlockPos();
            var tag = hearth.saveWithFullMetadata(player.registryAccess());
            tag.putBoolean("lit", false); tag.putDouble("fuel_j", 0);
            h.getLevel().removeBlockEntity(pos);
            advance(h, 24000);
            hearth = (ThermalStationEntity) BlockEntity.loadStatic(pos, hearth.getBlockState(), tag, player.registryAccess());
            h.getLevel().setBlockEntity(hearth);
            PotteryGameTests.finishCooling(hearth,h.getLevel());
            check(hearth.saveWithoutMetadata(player.registryAccess()).getDouble("water_temperature_k") < 300, "未ロードを含め十分に冷える");
            tag = hearth.saveWithFullMetadata(player.registryAccess());
            tag.putBoolean("lit", true); tag.putDouble("fuel_j", 64_000_000); tag.putDouble("temperature_k", 1000);
            // 再予熱後というテスト専用状態。保持だけを1秒追加する。
            tag.putDouble("water_temperature_k", 373.15); tag.putLong("last_tick", h.getLevel().getGameTime());
            h.getLevel().removeBlockEntity(pos);
            hearth = (ThermalStationEntity) BlockEntity.loadStatic(pos, hearth.getBlockState(), tag, player.registryAccess());
            h.getLevel().setBlockEntity(hearth);
            use(hearth, player, ItemStack.EMPTY);
            advance(h, 20); hearth.work();
            check(hearth.station().output(0).isEmpty() && hearth.waterTank().contents().massKg() == 4,
                    "過去の59秒を合算せず、再加熱後1秒では未完成");
            check(hearth.station().save(player.registryAccess()).getDouble("seconds") == 1, "連続保持は1秒からやり直す");
            h.succeed();
        } finally { cleanup(h, player, before); }
    }

    private static void environment(BlockEntity owner, long day, double pressure) {
        if (!registered) {
            NeoForge.EVENT_BUS.addListener((EnvironmentQuery e) -> {
                var climate = CLIMATE.get(new Site(e.level(), e.position()));
                if (climate != null) e.provide(new EnvironmentSample(e.startTick(), e.endTick(), climate.day, 293.15,
                        climate.pressure, 0.5, 0, 0, Optional.empty(), true, true));
            });
            NeoForge.EVENT_BUS.addListener((CombustionEvent e) -> {
                var events = EMISSIONS.get(new Site(e.level(), e.position()));
                if (events != null) events.add(e);
            });
            registered = true;
        }
        CLIMATE.put(new Site((ServerLevel) owner.getLevel(), owner.getBlockPos()), new Climate(day, pressure));
    }

    private static ServerPlayer player(GameTestHelper h) {
        var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "water-treatment-test"));
        p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        h.getLevel().addNewPlayer(p);
        move(p, h.absolutePos(FILTER));
        return p;
    }

    private static void move(ServerPlayer player, BlockPos pos) { player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5); }
    private static void unlockPottery(ServerPlayer p) {
        ResearchManager.report(p, id("primitive_event"), "friction_ignition");
        ResearchManager.report(p, id("primitive_event"), "ceramic_pot");
    }
    private static WaterFilterEntity filter(GameTestHelper h) { return (WaterFilterEntity) h.getLevel().getBlockEntity(h.absolutePos(FILTER)); }
    private static ItemStack pot(String quality, double mass) {
        var pot = new ItemStack(PrimitiveContent.item("ceramic_pot"));
        pot.set(RelicProperties.CONTENT_MASS, mass);
        pot.set(NatureProperties.QUALITY, id(quality));
        return pot;
    }
    private static long mass(ItemStack stack) { return WaterMass.fromKg(stack.getOrDefault(RelicProperties.CONTENT_MASS.get(), 0.0)); }
    private static void fill(WaterFilterEntity filter, ServerPlayer player, String quality, double mass) {
        use(filter, player, pot(quality, mass));
    }
    private static void use(WaterFilterEntity filter, ServerPlayer player, ItemStack held) {
        move(player, filter.getBlockPos());
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        filter.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
    }
    private static void use(ThermalStationEntity hearth, ServerPlayer player, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        hearth.interact(player, InteractionHand.MAIN_HAND, Direction.UP);
    }
    private static void selectBoiling(ThermalStationEntity hearth, ServerPlayer player) {
        for (int i = 0; i < 12 && !hearth.saveWithoutMetadata(player.registryAccess()).getString("selected").contains("boil_water"); i++) {
            player.setShiftKeyDown(true); use(hearth, player, ItemStack.EMPTY); player.setShiftKeyDown(false);
        }
        check(hearth.saveWithoutMetadata(player.registryAccess()).getString("selected").contains("boil_water"), "煮沸製法を選択");
    }
    private static WaterFilterEntity restoreFilter(GameTestHelper h, WaterFilterEntity old, CompoundTag tag) {
        var pos = old.getBlockPos(); var state = old.getBlockState();
        h.getLevel().removeBlockEntity(pos);
        var restored = (WaterFilterEntity) BlockEntity.loadStatic(pos, state, tag, h.getLevel().registryAccess());
        h.getLevel().setBlockEntity(restored);
        return restored;
    }
    private static void advance(GameTestHelper h, int ticks) {
        h.getLevel().getServer().getWorldData().overworldData().setGameTime(h.getLevel().getGameTime() + ticks);
    }
    private static void cleanup(GameTestHelper h, ServerPlayer player, long before) {
        EMISSIONS.keySet().removeIf(s -> s.level == h.getLevel() && s.position.closerThan(h.absolutePos(FILTER), 20));
        CLIMATE.keySet().removeIf(s -> s.level == h.getLevel() && s.position.closerThan(h.absolutePos(FILTER), 20));
        h.getLevel().getServer().getWorldData().overworldData().setGameTime(before);
        player.discard();
    }
    private static ResourceLocation id(String name) { return AshenFrontierCore.id(name); }
    private static void check(boolean value, String message) { if (!value) throw new GameTestAssertException(message); }
}
