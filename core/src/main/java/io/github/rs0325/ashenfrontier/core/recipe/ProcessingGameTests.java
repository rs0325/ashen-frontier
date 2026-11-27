package io.github.rs0325.ashenfrontier.core.recipe;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.catalog.NatureTooltips;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import io.github.rs0325.ashenfrontier.core.food.FoodIntake;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.core.food.NutritionData;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.CraftingInput;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public class ProcessingGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierCore.MOD_ID)
    public static void onlyAmountBearingInputsRejectRepeatedInheritance(GameTestHelper helper) {
        var duplicated=new ProcessingData(List.of(new ProcessingData.Input(0,2,false)),Optional.empty(),0,0,
                List.of(new ProcessingData.Inheritance(0,0),new ProcessingData.Inheritance(0,1)),false);
        duplicated.validate(1,2);
        var player=player(helper);
        var food=new ItemStack(RelicContent.item("canned_food"),2);
        var candidates=new ArrayList<ItemStack>();candidates.add(food);
        var chemicals=new ItemStack(Items.STICK,2);chemicals.set(ItemProperties.CONTAMINATION,new ItemProperties.Contamination(Map.of("lead",.00001)));candidates.add(chemicals);
        var radiation=new ItemStack(Items.STICK,2);radiation.set(ItemProperties.RADIOACTIVITY,new ItemProperties.Radioactivity("ashen_frontier:cs137",2,0));candidates.add(radiation);
        var water=new ItemStack(Items.STICK,2);water.set(RelicProperties.CONTENT_MASS,1.0);candidates.add(water);
        for(var source:candidates) {
            var recipe=new CommonRecipe(TYPE,List.of(Ingredient.of(source.getItem())),List.of(new ItemStack(Items.STRING),new ItemStack(Items.STRING)),NO_CONDITIONS,duplicated);
            var station=new ProcessingStation(1,2,1);station.setInput(player,0,source);
            check(!station.start(player,install(helper,recipe),FACILITY,Optional.empty()),"量を持つ実原料の重複継承だけを拒否");
            check(station.input(0).getCount()==2&&station.output(0).isEmpty()&&station.output(1).isEmpty(),"拒否時に消費・量の複製をしない");
        }
        var plainRecipe=new CommonRecipe(TYPE,List.of(Ingredient.of(Items.STICK)),List.of(new ItemStack(Items.STRING),new ItemStack(Items.FEATHER)),NO_CONDITIONS,duplicated);
        var station=new ProcessingStation(1,2,1);var plain=new ItemStack(Items.STICK,2);
        plain.set(ItemProperties.ROT,new ItemProperties.Rot(0,0,1200));station.setInput(player,0,plain);
        check(station.start(player,install(helper,plainRecipe),FACILITY,Optional.empty())&&station.finish(player,FACILITY,Optional.empty()),"量でない属性の複数出力継承は維持");
        check(station.output(0).get(ItemProperties.ROT).equals(plain.get(ItemProperties.ROT))&&station.output(1).get(ItemProperties.ROT).equals(plain.get(ItemProperties.ROT)),"両出力へ同じ属性を継承");
        new ProcessingData(List.of(),Optional.empty(),0,0,
                List.of(new ProcessingData.Inheritance(0,0),new ProcessingData.Inheritance(1,1)),false).validate(2,2);
        helper.succeed();
    }
    private static final ResourceLocation TYPE = AshenFrontierCore.id("game_test_processing");
    private static final ResourceLocation FACILITY = AshenFrontierCore.id("test_processing_surface");
    private static final ResourceLocation RAW = AshenFrontierCore.id("raw_water");
    private static final ResourceLocation CLEAN = AshenFrontierCore.id("clean_water");
    private static final ResourceLocation RESEARCH = AshenFrontierCore.id("game_test_survey");
    private static final RecipeConditions NO_CONDITIONS = new RecipeConditions(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void containerTooltipUsesTheSameMilligramsAsTransfer(GameTestHelper helper) {
        var player = player(helper);
        double[] saved = {0.0000004, 0.0000006};
        double[] expected = {0, 0.000001};
        for (int i = 0; i < saved.length; i++) {
            var container = RelicProperties.container("sealed_water_container", RAW, saved[i]);
            var tooltip = new ArrayList<Component>();
            NatureTooltips.tooltip(new ItemTooltipEvent(container, player, tooltip, TooltipFlag.NORMAL, Item.TooltipContext.EMPTY));
            var line = expected[i] == 0 ? Component.translatable("tooltip.ashen_frontier.content_empty")
                    : Component.translatable("tooltip.ashen_frontier.content_mass", expected[i]);
            check(tooltip.contains(line), "処理と同じmgで内容量・空を表示");
            check(!tooltip.contains(Component.translatable("tooltip.ashen_frontier.content_mass", saved[i])), "生のdoubleを表示しない");
            check(container.get(RelicProperties.CONTENT_MASS) == saved[i], "表示では保存値を変更しない");
            var tank = new WaterTank(1);
            check(tank.fromContainer(player, container, 0.000001) == expected[i], "表示量と実移送量が一致");
            check(tank.contents().massKg() == expected[i], "処理後の槽量も一致");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void repeatedTwentyFiveGramsExhaustsStoredTank(GameTestHelper helper) {
        repeatedConsumption(helper, 0.025, 5760);
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void repeatedHundredGramsExhaustsStoredTank(GameTestHelper helper) {
        repeatedConsumption(helper, 0.1, 1440);
    }

    private static void repeatedConsumption(GameTestHelper helper, double amount, int batches) {
        var player = player(helper);
        var tank = new WaterTank(144);
        tank.fill(player, RAW, 144);
        for (int i = 0; i < batches; i++) {
            check(tank.contains(RAW, amount), "反復消費の水量不足: " + i);
            tank.consume(RAW, amount);
            if (i == batches / 2) {
                var ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
                var saved = WaterTank.Contents.CODEC.encodeStart(ops, tank.contents()).getOrThrow();
                tank = new WaterTank(WaterTank.Contents.CODEC.parse(ops, saved).getOrThrow());
            }
        }
        check(tank.contents().massKg() == 0 && tank.contents().quality().isEmpty(), "反復消費後は正確に空");
        check(tank.fill(player, CLEAN, 1) == 1, "空の槽へ異品質を補充");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void transfersPreserveKgQualityAndEmptyContainer(GameTestHelper helper) {
        var player = player(helper);
        var tank = new WaterTank(4);
        var container = RelicProperties.container("sealed_water_container", RAW, 3);
        check(tank.fromContainer(player, container, 1) == 1, "部分移送量");
        check(container.get(RelicProperties.CONTENT_MASS) == 2 && container.get(NatureProperties.QUALITY).equals(RAW), "部分移送の品質");
        var other = RelicProperties.container("sealed_water_container", CLEAN, 1);
        check(tank.fromContainer(player, other, 1) == 0 && other.get(RelicProperties.CONTENT_MASS) == 1, "異品質拒否");
        check(tank.fromContainer(player, container, 9) == 2 && container.get(RelicProperties.CONTENT_MASS) == 0, "全量と空容器");
        check(container.is(RelicContent.item("sealed_water_container")), "空容器ID維持");
        container.set(NatureProperties.QUALITY, CLEAN);
        check(tank.toContainer(player, container, 2, 9) == 2 && container.get(NatureProperties.QUALITY).equals(RAW), "空容器再利用");
        check(tank.contents().massKg() == 1 && container.get(RelicProperties.CONTENT_MASS) == 2, "量保存");
        container.setCount(2);
        check(tank.fromContainer(player, container, 1) == 0, "未分割スタックを変更しない");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void researchEnvironmentAndQuantityAreEnforced(GameTestHelper helper) {
        var player = player(helper);
        var id = install(helper, recipe(true));
        var station = station(player);
        check(!station.start(player, id, FACILITY, environment(player)), "未研究拒否");
        unlock(player);
        check(!station.start(player, id, FACILITY, Optional.empty()), "気象未提供拒否");
        station.setInput(player, 0, seed("soybean", 2));
        check(!station.start(player, id, FACILITY, environment(player)), "成分違い拒否");
        station.setInput(player, 0, seed("barley", 1));
        check(!station.start(player, id, FACILITY, environment(player)), "個数不足拒否");
        station.setInput(player, 0, seed("barley", 2));
        check(!station.start(player, id, AshenFrontierCore.id("wrong_facility"), environment(player)), "設備違い拒否");
        check(station.start(player, id, FACILITY, environment(player)), "条件充足で開始");
        check(!station.finish(player, FACILITY, environment(player)), "熱量不足では未消費");
        check(station.water().contents().massKg() == 2, "開始と失敗で水を引かない");
        station.cancel(player);
        check(station.water().contents().massKg() == 2 && station.input(0).getCount() == 2, "取消で保持");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void completionIsAtomicWithFullOutputAndCompetingPlayers(GameTestHelper helper) {
        var player = player(helper);
        var other = player(helper);
        var id = install(helper, recipe(false));
        var station = station(player);
        var input = seed("barley", 2);
        input.set(ItemProperties.ROT, ItemProperties.Rot.of(0, 0, 1200));
        input.set(ItemProperties.CONTAMINATION, new ItemProperties.Contamination(Map.of("lead", 0.00001)));
        input.set(ItemProperties.RADIOACTIVITY, ItemProperties.Radioactivity.of("Cs-137", 1, 0));
        input.set(FoodProperties.NUTRITION, new NutritionData(100, 0.01, 0.005, 0.1, Set.of("c")));
        station.setInput(player, 0, input);
        check(station.start(player, id, FACILITY, environment(player)), "開始");
        check(!station.start(other, id, FACILITY, environment(other)), "別プレイヤーの重複開始拒否");
        station.advance(player, FACILITY, environment(player), 10);
        check(station.finish(player, FACILITY, environment(player)), "全出力確定");
        check(!station.finish(other, FACILITY, environment(other)), "二重確定拒否");
        check(station.water().contents().massKg() == 1.5 && station.input(0).isEmpty(), "確定時だけ消費");
        check(station.input(1).getCount() == 1 && station.tool().get(ItemProperties.WEAR).value() == 1, "保持素材と工具摩耗");
        check(station.output(0).getCount() == 64 && station.output(1).getCount() == 1, "複数出力");
        check(station.output(1).get(ItemProperties.ROT).referenceSeconds() == 1200, "腐敗非初期化");
        check(FoodIntake.from(station.output(1)).orElseThrow().nutrition().energyJoules() == 200, "消費2個分の栄養継承");
        check(station.output(1).has(ItemProperties.CONTAMINATION) && station.output(1).has(ItemProperties.RADIOACTIVITY), "汚染保持");
        station.setInput(player, 0, seed("barley", 2));
        check(station.start(player, id, FACILITY, environment(player)), "次バッチ");
        station.advance(player, FACILITY, environment(player), 10);
        check(!station.finish(player, FACILITY, environment(player)), "満杯で停止");
        check(station.water().contents().massKg() == 1.5 && station.input(0).getCount() == 2, "満杯時の量保存");
        station.takeOutput(player, 0, 64);
        station.takeOutput(player, 1, 64);
        check(station.finish(player, FACILITY, environment(player)) && station.water().contents().massKg() == 1, "空き確保後に一度だけ確定");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void savedStateResumesAndChangedRecipeCancelsWithoutConsumption(GameTestHelper helper) {
        var player = player(helper);
        var id = install(helper, recipe(false));
        var station = station(player);
        var foodSeed = seed("barley", 2);
        foodSeed.set(FoodProperties.NUTRITION, new NutritionData(100, 0.01, 0.005, 0.1, Set.of("c", "fat_soluble")));
        station.setInput(player, 0, foodSeed);
        check(station.start(player, id, FACILITY, environment(player)), "保存前開始");
        station.advance(player, FACILITY, environment(player), 10);
        var restored = ProcessingStation.load(station.save(player.registryAccess()), player.registryAccess());
        check(restored.water().contents().massKg() == 2 && restored.active(), "未消費水と進捗の復元");
        check(restored.finish(player, FACILITY, environment(player)), "復元後確定");
        check(restored.output(1).get(FoodProperties.NUTRITION).vitaminGroups().equals(Set.of("c", "fat_soluble")), "栄養成分の保存");
        var completed = ProcessingStation.load(restored.save(player.registryAccess()), player.registryAccess());
        check(!completed.finish(player, FACILITY, environment(player)) && completed.water().contents().massKg() == 1.5, "完了後復元で重複しない");
        var pending = station(player);
        check(pending.start(player, id, FACILITY, environment(player)), "定義変更前開始");
        pending.advance(player, FACILITY, environment(player), 10);
        var changed = recipe(false);
        replace(helper, id, new CommonRecipe(TYPE, changed.inputs(), List.of(new ItemStack(Items.DIAMOND)),
                changed.conditions(), ProcessingData.EMPTY));
        check(!pending.finish(player, FACILITY, environment(player)) && !pending.active(), "reload差を検出");
        check(pending.water().contents().massKg() == 2 && pending.input(0).getCount() == 2, "reload取消で未消費保持");
        var beforeTypeChange = recipe(false);
        replace(helper, id, beforeTypeChange);
        check(pending.start(player, id, FACILITY, environment(player)), "加工型変更前開始");
        replace(helper, id, new CommonRecipe(AshenFrontierCore.id("game_test"), beforeTypeChange.inputs(),
                beforeTypeChange.outputs(), beforeTypeChange.conditions(), beforeTypeChange.processing()));
        check(!pending.finish(player, FACILITY, environment(player)) && !pending.active(), "加工型変更でも取消");
        replace(helper, id, beforeTypeChange);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void codecNetworkAndMetadataReferencesAreValidated(GameTestHelper helper) {
        var recipe = recipe(false);
        var serializer = RecipeTypeRegistry.serializer(TYPE);
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var json = serializer.codec().codec().encodeStart(ops, recipe).getOrThrow();
        var decoded = serializer.codec().codec().parse(ops, json).getOrThrow();
        check(decoded.processing().equals(recipe.processing()), "加工データCodec");
        check(decoded.matches(CraftingInput.of(2, 1,
                List.of(seed("barley", 2), new ItemStack(Items.STICK))), helper.getLevel()), "Ingredientの成分Codec");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            serializer.streamCodec().encode(buffer, recipe);
            check(serializer.streamCodec().decode(buffer).processing().equals(recipe.processing()), "加工同期");
            buffer.clear();
            var item = seed("barley", 1);
            item.set(FoodProperties.NUTRITION, new NutritionData(100, 0.01, 0.005, 0.1, Set.of("c", "fat_soluble")));
            ItemStack.STREAM_CODEC.encode(buffer, item);
            check(ItemStack.isSameItemSameComponents(item, ItemStack.STREAM_CODEC.decode(buffer)), "種別と栄養成分の同期");
        } finally { buffer.release(); }
        var invalid = json.deepCopy().getAsJsonObject();
        invalid.getAsJsonObject("processing").getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("count", -1);
        check(serializer.codec().codec().parse(ops, invalid).error().isPresent(), "負の個数を拒否");
        var offGrid = json.deepCopy().getAsJsonObject();
        offGrid.getAsJsonObject("processing").getAsJsonObject("water").addProperty("mass_kg", 0.0250001);
        check(serializer.codec().codec().parse(ops, offGrid).error().isPresent(), "格子外の制作水量を読込エラーにする");
        try {
            var output = RelicProperties.container("sealed_water_container", RAW, 0.0250001);
            new CommonRecipe(TYPE, List.of(), List.of(output), NO_CONDITIONS);
            throw new AssertionError("格子外の水入り出力を受理");
        } catch (IllegalArgumentException expected) { }
        try {
            new CommonRecipe(TYPE, recipe.inputs(), recipe.outputs(), recipe.conditions(), new ProcessingData(
                    List.of(new ProcessingData.Input(0, 1, false), new ProcessingData.Input(0, 1, true)),
                    Optional.empty(), 0, 0, List.of(), false));
            throw new AssertionError("入力役割重複を受理");
        } catch (IllegalArgumentException expected) { }
        try {
            new WaterTank.Contents(4, Double.NaN, Optional.of(RAW));
            throw new AssertionError("NaNを受理");
        } catch (IllegalArgumentException expected) { }
        check(NutritionData.get(new ItemStack(Items.STONE)).isEmpty(), "未定義品へ栄養を補わない");
        check(NutritionData.get(new ItemStack(RelicContent.item("preserved_rations"))).orElseThrow().energyJoules() == 4500000,
                "固定栄養データマップ");
        check(new ProcessingData(List.of(), Optional.empty(), 0, 0.1, List.of(), false).toolWear() == 1,
                "刻み未満の摩耗を切り捨てて無料にしない");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void emptyLeadingSlotConsumesAndInheritsFromOriginalSlot(GameTestHelper helper) {
        var player = player(helper);
        var recipe = new CommonRecipe(TYPE, List.of(Ingredient.of(Items.STICK)), List.of(new ItemStack(Items.STRING)),
                NO_CONDITIONS, new ProcessingData(List.of(), Optional.empty(), 0, 0,
                List.of(new ProcessingData.Inheritance(0, 0)), false));
        var id = install(helper, recipe);
        var station = new ProcessingStation(2, 1, 1);
        var source = new ItemStack(Items.STICK);
        source.set(FoodProperties.NUTRITION, new NutritionData(100, 0, 0, 0, Set.of()));
        station.setInput(player, 1, source);
        check(station.start(player, id, FACILITY, Optional.empty()), "先頭空きの入力を照合");
        check(station.finish(player, FACILITY, Optional.empty()), "加工確定");
        check(station.input(1).isEmpty(), "元スロットの材料を消費");
        check(station.output(0).get(FoodProperties.NUTRITION).energyJoules() == 100, "元スロットから属性継承");
        check(!station.start(player, id, FACILITY, Optional.empty()), "材料なしで再生産しない");
        station.setInput(player, 0, new ItemStack(Items.STICK));
        station.setInput(player, 1, new ItemStack(Items.STICK));
        check(!station.start(player, id, FACILITY, Optional.empty()), "余分な材料スロットを無視しない");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void fractionalWaterConsumptionEmptiesTank(GameTestHelper helper) {
        var player = player(helper);
        var id = install(helper, new CommonRecipe(TYPE, List.of(Ingredient.of(Items.STICK)),
                List.of(new ItemStack(Items.STRING)), NO_CONDITIONS,
                new ProcessingData(List.of(), Optional.of(new ProcessingData.Water(RAW, 0.1)), 0, 0, List.of(), false)));
        var station = new ProcessingStation(1, 1, 1);
        station.water().fill(player, RAW, 0.3);
        for (int batch = 0; batch < 3; batch++) {
            station.setInput(player, 0, new ItemStack(Items.STICK));
            check(station.start(player, id, FACILITY, Optional.empty()), "端数水のバッチ開始 " + batch);
            check(station.finish(player, FACILITY, Optional.empty()), "端数水のバッチ確定 " + batch);
        }
        check(station.water().contents().massKg() == 0 && station.water().contents().quality().isEmpty(), "残差を空に正規化");
        check(station.water().fill(player, CLEAN, 0.2) == 0.2, "空の槽は別品質を受け入れる");
        check(!station.water().contains(CLEAN, 0.200001), "実際の水不足は許容しない");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void fractionalContainerTransfersLeaveReusableEmptyContainer(GameTestHelper helper) {
        var player = player(helper);
        var tank = new WaterTank(144);
        var container = RelicProperties.container("sealed_water_container", RAW, 144);
        for (int i = 0; i < 5760; i++) {
            check(tank.fromContainer(player, container, 0.025) == 0.025, "容器からの反復移送 " + i);
        }
        check(container.get(RelicProperties.CONTENT_MASS) == 0 && tank.contents().massKg() == 144, "全量移送で空容器");
        for (int i = 0; i < 5760; i++) {
            check(tank.toContainer(player, container, 144, 0.025) == 0.025, "容器への反復移送 " + i);
        }
        check(tank.contents().massKg() == 0 && container.get(RelicProperties.CONTENT_MASS) == 144, "往復で量保存");
        check(tank.fill(player, CLEAN, 1) == 1, "空槽へ別品質を補充");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID)
    public static void blockEntityRestoresProcessingWithoutPlayer(GameTestHelper helper) {
        var player = player(helper);
        var original = station(player);
        var id = install(helper, recipe(false));
        check(original.start(player, id, FACILITY, environment(player)), "保存前開始");
        original.advance(player, FACILITY, environment(player), 10);
        var tag = new CompoundTag();
        tag.put("processing", original.save(helper.getLevel().registryAccess()));
        var entity = new SavedStation();
        entity.loadWithComponents(tag, helper.getLevel().registryAccess());
        check(entity.station.active() && entity.station.water().contents().massKg() == 2, "Level未設定のBEから復元");
        helper.runAfterDelay(5, () -> {
            check(entity.station.advance(player, FACILITY, environment(player), 0), "最初の操作で時計を再開");
            check(entity.station.save(helper.getLevel().registryAccess()).getDouble("seconds") == 0,
                    "復元から最初の操作までの時間は加算しない");
            check(entity.station.finish(player, FACILITY, environment(player)), "後から操作するプレイヤーで確定");
            check(entity.station.water().contents().massKg() == 1.5, "保存復元で水を一度だけ消費");
            helper.succeed();
        });
    }

    private static final class SavedStation extends BlockEntity {
        private ProcessingStation station;

        private SavedStation() {
            super(BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            station = ProcessingStation.load(tag.getCompound("processing"), registries);
        }
    }

    private static CommonRecipe recipe(boolean locked) {
        var ingredient = DataComponentIngredient.of(false, seed("barley", 1));
        return new CommonRecipe(TYPE, List.of(ingredient, Ingredient.of(Items.STICK)),
                List.of(new ItemStack(Items.IRON_NUGGET, 64), new ItemStack(Items.STRING)),
                new RecipeConditions(Optional.of(new RecipeConditions.Range(300, 310)),
                        Optional.of(new RecipeConditions.Range(90000, 110000)), Optional.empty(), Optional.of(10.0),
                        Optional.of(new ItemStack(Items.FLINT)), Optional.of(FACILITY), locked ? Optional.of(RESEARCH) : Optional.empty()),
                new ProcessingData(List.of(new ProcessingData.Input(0, 2, false), new ProcessingData.Input(1, 1, true)),
                        Optional.of(new ProcessingData.Water(RAW, 0.5)), 0.1, 1,
                        List.of(new ProcessingData.Inheritance(0, 1)), true));
    }

    private static ItemStack seed(String species, int count) {
        var stack = new ItemStack(NatureContent.item("preserved_seed"), count);
        // 成分付き入力の試験用。実際の作物種や食品レシピは追加しない。
        stack.set(FoodProperties.CROP_SPECIES, AshenFrontierCore.id(species));
        return stack;
    }

    private static ProcessingStation station(ServerPlayer player) {
        var station = new ProcessingStation(2, 2, 4);
        station.setInput(player, 0, seed("barley", 2));
        station.setInput(player, 1, new ItemStack(Items.STICK));
        var tool = new ItemStack(Items.FLINT);
        tool.set(ItemProperties.WEAR, ItemProperties.Wear.of(0, 5));
        station.setTool(player, tool);
        station.water().fill(player, RAW, 2);
        return station;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "processing-test"));
    }

    private static Optional<EnvironmentSample> environment(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        return Optional.of(new EnvironmentSample(now, now, 24000, 305, 100000, 0.5, 0, 0, Optional.empty(), true, true));
    }

    private static void unlock(ServerPlayer player) {
        ResearchManager.registerConditionType(AshenFrontierCore.id("game_test"), (p, condition) -> condition.equals("surveyed"));
        ResearchManager.report(player, AshenFrontierCore.id("game_test"), "surveyed");
        ResearchManager.reportKnowledge(player, AshenFrontierCore.id("game_test_document"));
    }

    private static ResourceLocation install(GameTestHelper helper, CommonRecipe recipe) {
        var id = AshenFrontierCore.id("test_processing/" + UUID.randomUUID());
        replace(helper, id, recipe);
        return id;
    }

    private static void replace(GameTestHelper helper, ResourceLocation id, CommonRecipe recipe) {
        var manager = helper.getLevel().getRecipeManager();
        var all = new ArrayList<>(manager.getRecipes().stream().filter(h -> !h.id().equals(id)).toList());
        all.add(new RecipeHolder<>(id, recipe));
        manager.replaceRecipes(all);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
