package io.github.rs0325.ashenfrontier.core.recipe;

import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.ItemPropertyMath;
import io.github.rs0325.ashenfrontier.core.MaterialData;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * ブロックに依存しない加工状態。設備はこの状態全体を同じBlockEntityの保存に含める。
 * setInput/setToolは呼出し元が移送済みのアイテムだけを渡す。外部ケイパビリティは公開しない。
 * 入力/水の予約・事前消費はせず、finishだけが入力・槽・全出力を同じサーバー操作で変更する。
 * サーバースレッド外の変更はIllegalStateException。不正な構造/供給熱量はIllegalArgumentException。
 */
public final class ProcessingStation {
    private final List<ItemStack> inputs;
    private final List<ItemStack> outputs;
    private WaterTank water;
    private ItemStack tool = ItemStack.EMPTY;
    private Optional<ResourceLocation> recipeId = Optional.empty();
    private Optional<Tag> definition = Optional.empty();
    private List<ItemStack> expectedInputs = List.of();
    private ItemStack expectedTool = ItemStack.EMPTY;
    private double seconds;
    private double energy;
    private long lastTick;
    private boolean clockPending;

    public ProcessingStation(int inputSlots, int outputSlots, double waterCapacityKg) {
        if (inputSlots < 0 || outputSlots < 1) throw new IllegalArgumentException("加工スロット数が不正です");
        inputs = new ArrayList<>(Collections.nCopies(inputSlots, ItemStack.EMPTY));
        outputs = new ArrayList<>(Collections.nCopies(outputSlots, ItemStack.EMPTY));
        water = new WaterTank(waterCapacityKg);
    }

    public WaterTank water() { return water; }
    public int inputSlots() { return inputs.size(); }
    public ItemStack input(int slot) { return inputs.get(slot).copy(); }
    public ItemStack output(int slot) { return outputs.get(slot).copy(); }
    public ItemStack tool() { return tool.copy(); }
    public boolean active() { return recipeId.isPresent(); }

    public void setInput(ServerPlayer player, int slot, ItemStack stack) {
        WaterTank.requireServer(player);
        cancel(player);
        inputs.set(slot, stack.copy());
    }

    public void setTool(ServerPlayer player, ItemStack stack) {
        WaterTank.requireServer(player);
        cancel(player);
        tool = stack.copy();
    }

    /** 所有設備の出力を取り出す。呼出し元は返った物だけを受取先へ移す。 */
    public ItemStack takeOutput(ServerPlayer player, int slot, int count) {
        WaterTank.requireServer(player);
        if (count <= 0) throw new IllegalArgumentException("取出数は正値が必要です");
        return outputs.get(slot).split(count);
    }

    /** 現在のRecipeManagerから開始する。条件不足ではfalseで入力/水を変更しない。 */
    public boolean start(ServerPlayer player, ResourceLocation id, ResourceLocation facility,
                         Optional<EnvironmentSample> environment) {
        return start(player, id, facility, environment, OptionalDouble.empty());
    }

    /** 炉内温度を気候の気温と分けて照合する。未指定時は従来どおり気温を用いる。 */
    public boolean start(ServerPlayer player, ResourceLocation id, ResourceLocation facility,
                         Optional<EnvironmentSample> environment, OptionalDouble processTemperatureK) {
        WaterTank.requireServer(player);
        if (active()) return false;
        var holder = current(player, id);
        if (holder.isEmpty()) return false;
        CommonRecipe recipe = holder.get().value();
        if (!available(recipe) || !conditions(player, recipe, facility, environment, Double.MAX_VALUE, Double.MAX_VALUE, processTemperatureK)) return false;
        recipeId = Optional.of(id);
        definition = Optional.of(signature(recipe, player.registryAccess()));
        expectedInputs = inputs.stream().map(ItemStack::copy).toList();
        expectedTool = tool.copy();
        seconds = 0;
        energy = 0;
        lastTick = player.serverLevel().getGameTime();
        clockPending = false;
        return true;
    }

    /**
     * 経過gameTimeを型の時計へ変換する。energyJoulesはこの呼出し区間に設備が供給した実エネルギー。
     * 環境条件がある型は区間を覆うclimate入力が必要で、未提供/条件外の時間は進めない。
     */
    public boolean advance(ServerPlayer player, ResourceLocation facility,
                           Optional<EnvironmentSample> environment, double energyJoules) {
        return advance(player, facility, environment, energyJoules, OptionalDouble.empty(), Double.MAX_VALUE);
    }

    /**
     * 設備が確認した適合区間だけを進める。availableSecondsはレシピの時計（REAL/GAME）での上限秒。
     * 実際の経過秒を超えて加算せず、燃料切れ等で短い区間の熱だけが供給された場合に用いる。
     * 気候サンプルの気温は書き換えず、processTemperatureKで炉内温度を別に渡す。
     */
    public boolean advance(ServerPlayer player, ResourceLocation facility,
                           Optional<EnvironmentSample> environment, double energyJoules,
                           OptionalDouble processTemperatureK, double availableSeconds) {
        return advanceUntil(player, facility, environment, energyJoules, processTemperatureK,
                availableSeconds, player.serverLevel().getGameTime());
    }

    /**
     * 区間列の1区間を評価する。startTickが設備の評価済み時刻に一致する場合だけ進める。
     * 重複/欠落/未来の区間はfalseで時計も進捗も変えない。復元直後は現在時刻に合わせ、未ロードの作業を加算しない。
     * 不適合時間を飛ばす場合も同じ区間を渡し、availableSecondsを0にする。
     */
    public boolean advanceInterval(ServerPlayer player, ResourceLocation facility, EnvironmentSample environment,
                                   double energyJoules, OptionalDouble processTemperatureK, double availableSeconds) {
        WaterTank.requireServer(player);
        if (clockPending) {
            lastTick = player.serverLevel().getGameTime();
            clockPending = false;
        }
        if (environment.startTick() == environment.endTick() && energyJoules != 0) {
            throw new IllegalArgumentException("ゼロ長区間へ熱量は供給できません");
        }
        if (environment.startTick() != lastTick || environment.endTick() > player.serverLevel().getGameTime()) return false;
        return advanceUntil(player, facility, Optional.of(environment), energyJoules, processTemperatureK,
                availableSeconds, environment.endTick());
    }

    private boolean advanceUntil(ServerPlayer player, ResourceLocation facility, Optional<EnvironmentSample> environment,
                                 double energyJoules, OptionalDouble processTemperatureK, double availableSeconds, long now) {
        WaterTank.requireServer(player);
        if (!Double.isFinite(availableSeconds) || availableSeconds < 0) throw new IllegalArgumentException("適合区間秒数が不正です");
        if (!Double.isFinite(energyJoules) || energyJoules < 0) throw new IllegalArgumentException("供給熱量が不正です");
        var recipe = unchanged(player);
        if (recipe.isEmpty()) return false;
        if (clockPending) {
            lastTick = now;
            clockPending = false;
        }
        long from = lastTick;
        if (now < from) return false;
        lastTick = now;
        if (!conditions(player, recipe.get(), facility, environment, Double.MAX_VALUE, Double.MAX_VALUE, processTemperatureK)
                || environment.filter(e -> e.startTick() > from || e.endTick() < now).isPresent()) return false;
        TimeBasis basis = RecipeTypeRegistry.timeBasis(recipe.get().typeId());
        seconds += Math.min(availableSeconds, basis.seconds(now - from, environment.map(EnvironmentSample::ticksPerDay).orElse(GameTime.TICKS_PER_DAY)));
        energy += energyJoules;
        return true;
    }

    /**
     * 再検査後、入力・槽・全出力を一度だけ確定する。満杯ではfalseで進捗と水を保持する。
     * reloadで定義が変わった場合は加工だけ取り消し、未消費の入力/水をそのまま残す。
     */
    public boolean finish(ServerPlayer player, ResourceLocation facility, Optional<EnvironmentSample> environment) {
        return finish(player, facility, environment, OptionalDouble.empty());
    }

    /** 製品温度は環境区間の末尾の温度として記録する。環境なしの即時加工では現在時刻を使う。 */
    public boolean finish(ServerPlayer player, ResourceLocation facility, Optional<EnvironmentSample> environment,
                          OptionalDouble processTemperatureK) {
        WaterTank.requireServer(player);
        var current = unchanged(player);
        if (current.isEmpty()) return false;
        CommonRecipe recipe = current.get();
        if (!sameInputs(inputs, expectedInputs) || !same(tool, expectedTool) || !available(recipe)
                || !conditions(player, recipe, facility, environment, seconds, energy, processTemperatureK)) return false;
        var slots = recipe.ingredientSlots(recipeInput()).orElseThrow();
        var products = recipe.outputs().stream().map(ItemStack::copy).toList();
        for (var inheritance : recipe.processing().inheritance()) {
            inheritProperties(inputs.get(slots[inheritance.ingredient()]), products.get(inheritance.output()));
        }
        double moistureKg = 0;
        for (int ingredient = 0; ingredient < slots.length; ingredient++) {
            var use = recipe.processing().input(ingredient);
            if (!use.retained()) moistureKg += inputs.get(slots[ingredient]).getOrDefault(NatureProperties.MOISTURE.get(), 0.0) * use.count();
        }
        inheritMoisture(moistureKg, products);
        if (processTemperatureK.isPresent()) {
            long completedAt = environment.map(EnvironmentSample::endTick).orElse(player.serverLevel().getGameTime());
            double kelvin = ItemPropertyMath.roundDown(processTemperatureK.getAsDouble(), ItemProperties.TEMPERATURE_STEP_K);
            for (var product : products) product.set(ItemProperties.TEMPERATURE, new ItemProperties.Temperature(kelvin, completedAt));
        }
        var plannedOutputs = outputs.stream().map(ItemStack::copy).collect(Collectors.toCollection(ArrayList::new));
        for (ItemStack product : products) if (!insert(plannedOutputs, product.copy())) return false;
        ItemStack wornTool = tool.copy();
        if (recipe.processing().toolWear() > 0) {
            var wear = wornTool.get(ItemProperties.WEAR);
            wornTool.set(ItemProperties.WEAR, ItemProperties.Wear.of(wear.value() + recipe.processing().toolWear(), wear.maximum()));
        }
        for (int ingredient = 0; ingredient < slots.length; ingredient++) {
            var rule = recipe.processing().input(ingredient);
            if (!rule.retained()) inputs.get(slots[ingredient]).shrink(rule.count());
        }
        recipe.processing().water().ifPresent(v -> water.consume(v.quality(), v.massKg()));
        for (int slot = 0; slot < outputs.size(); slot++) outputs.set(slot, plannedOutputs.get(slot));
        tool = wornTool;
        cancel(player);
        return true;
    }

    /**
     * 連続した条件を失った加工の時間・熱量だけを0へ戻す。レシピ・材料・槽・評価済み時刻は維持する。
     * 所有設備のServerLevelを渡す。サーバースレッド外の呼出しはIllegalStateException。
     */
    public void resetProgress(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("加工進捗の変更はサーバースレッド専用です");
        seconds = 0;
        energy = 0;
    }

    /** 加工だけ停止する。未消費の水とアイテムは槽/入力へ残り、世界へは戻さない。 */
    public void cancel(ServerPlayer player) {
        WaterTank.requireServer(player);
        recipeId = Optional.empty();
        definition = Optional.empty();
        expectedInputs = List.of();
        expectedTool = ItemStack.EMPTY;
        seconds = 0;
        energy = 0;
    }

    private Optional<CommonRecipe> unchanged(ServerPlayer player) {
        var holder = recipeId.flatMap(id -> current(player, id));
        if (holder.isEmpty()) {
            cancel(player);
            return Optional.empty();
        }
        CommonRecipe recipe = holder.get().value();
        if (definition.isEmpty() || !signature(recipe, player.registryAccess()).equals(definition.get())) {
            cancel(player);
            return Optional.empty();
        }
        return Optional.of(recipe);
    }

    private boolean available(CommonRecipe recipe) {
        if (recipe.ingredientSlots(recipeInput()).isEmpty()) return false;
        if (recipe.processing().water().filter(v -> !water.contains(v.quality(), v.massKg())).isPresent()) return false;
        if (recipe.conditions().catalyst().filter(v -> !DataComponentIngredient.of(false, v).test(tool)
                || tool.getCount() < v.getCount()).isPresent()) return false;
        if (recipe.processing().toolWear() > 0) {
            var wear = tool.get(ItemProperties.WEAR);
            if (wear == null || wear.value() + recipe.processing().toolWear() > wear.maximum()) return false;
        }
        return true;
    }

    private RecipeInput recipeInput() {
        return new RecipeInput() {
            @Override public ItemStack getItem(int slot) { return inputs.get(slot); }
            @Override public int size() { return inputs.size(); }
        };
    }

    private boolean conditions(ServerPlayer player, CommonRecipe recipe, ResourceLocation facility,
                               Optional<EnvironmentSample> environment, double elapsed, double joules,
                               OptionalDouble processTemperatureK) {
        if (processTemperatureK.isPresent() && (!Double.isFinite(processTemperatureK.getAsDouble()) || processTemperatureK.getAsDouble() <= 0)) {
            throw new IllegalArgumentException("加工温度は有限の正のKが必要です");
        }
        var conditions = recipe.conditions();
        if (environment.isEmpty() && (recipe.processing().requiresEnvironment() || conditions.temperatureK().isPresent()
                || conditions.pressurePa().isPresent() || RecipeTypeRegistry.timeBasis(recipe.typeId()) == TimeBasis.GAME)) return false;
        return conditions.matches(processTemperatureK.orElse(environment.map(EnvironmentSample::temperatureK).orElse(Double.NaN)),
                environment.map(EnvironmentSample::pressurePa).orElse(Double.NaN), elapsed, joules,
                conditions.catalyst().isEmpty() || !tool.isEmpty(),
                conditions.facility().filter(facility::equals).isPresent(),
                conditions.research().map(id -> ResearchManager.isUnlocked(player, id)).orElse(true));
    }

    private static Optional<RecipeHolder<CommonRecipe>> current(ServerPlayer player, ResourceLocation id) {
        return player.serverLevel().getRecipeManager().byKey(id).filter(h -> h.value() instanceof CommonRecipe)
                .map(h -> new RecipeHolder<>(h.id(), (CommonRecipe) h.value()));
    }

    private static Tag signature(CommonRecipe recipe, HolderLookup.Provider registries) {
        var signature = new CompoundTag();
        signature.putString("type", recipe.typeId().toString());
        signature.put("recipe", RecipeTypeRegistry.serializer(recipe.typeId()).codec().codec()
                .encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), recipe).getOrThrow());
        return signature;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }

    private static boolean sameInputs(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
        return true;
    }

    private static boolean insert(List<ItemStack> inventory, ItemStack product) {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, product)) {
                int count = Math.min(product.getCount(), stack.getMaxStackSize() - stack.getCount());
                stack.grow(count);
                product.shrink(count);
            }
        }
        for (int i = 0; i < inventory.size() && !product.isEmpty(); i++) {
            if (inventory.get(i).isEmpty()) inventory.set(i, product.split(Math.min(product.getCount(), product.getMaxStackSize())));
        }
        return product.isEmpty();
    }

    /**
     * 単純組立で原料1個分の属性・追加自由水を出力へ保持する。数量・摩耗は変えない。
     * targetは空でない完成品。湿った原料の出力はMaterialDataの正の単位質量を必要とする。
     * 加工バッチではfinishが全消費原料の自由水を全製品の質量へ配分する。
     */
    public static void inherit(ItemStack source, ItemStack target) {
        inheritProperties(source, target);
        inheritMoisture(source.getOrDefault(NatureProperties.MOISTURE.get(), 0.0), List.of(target));
    }

    private static void inheritMoisture(double moistureKg, List<ItemStack> products) {
        if (moistureKg == 0) return;
        double totalMass = products.stream()
                .mapToDouble(p -> MaterialData.get(p.getItem()).orElseThrow().massKg() * p.getCount()).sum();
        for (var product : products) {
            double mass = MaterialData.get(product.getItem()).orElseThrow().massKg();
            product.set(NatureProperties.MOISTURE, moistureKg * (mass / totalMass));
        }
    }

    private static void inheritProperties(ItemStack source, ItemStack target) {
        copy(source, target, ItemProperties.ROT.get());
        copy(source, target, ItemProperties.CONTAMINATION.get());
        copy(source, target, ItemProperties.RADIOACTIVITY.get());
        copy(source, target, FoodProperties.NUTRITION.get());
    }

    private static <T> void copy(ItemStack source, ItemStack target, DataComponentType<T> type) {
        if (source.has(type)) target.set(type, source.get(type));
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        var tag = new CompoundTag();
        tag.put("inputs", ItemStack.OPTIONAL_CODEC.listOf().encodeStart(ops, inputs).getOrThrow());
        tag.put("outputs", ItemStack.OPTIONAL_CODEC.listOf().encodeStart(ops, outputs).getOrThrow());
        tag.put("tool", ItemStack.OPTIONAL_CODEC.encodeStart(ops, tool).getOrThrow());
        tag.put("water", WaterTank.Contents.CODEC.encodeStart(ops, water.contents()).getOrThrow());
        recipeId.ifPresent(id -> {
            tag.putString("recipe", id.toString());
            tag.put("definition", definition.orElseThrow());
            tag.put("expected_inputs", ItemStack.OPTIONAL_CODEC.listOf().encodeStart(ops, expectedInputs).getOrThrow());
            tag.put("expected_tool", ItemStack.OPTIONAL_CODEC.encodeStart(ops, expectedTool).getOrThrow());
            tag.putDouble("seconds", seconds);
            tag.putDouble("energy", energy);
        });
        return tag;
    }

    /**
     * BlockEntity.loadAdditionalのレジストリで保存状態を復元する。プレイヤーやLevelは不要。
     * 元の状態は変更しない。最初のadvanceで時計を合わせ、未ロード時間は加算しない。
     * 次回確定時に現在のレシピ/研究を再検査する。不正な保存値は例外にする。
     */
    public static ProcessingStation load(CompoundTag tag, HolderLookup.Provider registries) {
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        var in = ItemStack.OPTIONAL_CODEC.listOf().parse(ops, tag.get("inputs")).getOrThrow();
        var out = ItemStack.OPTIONAL_CODEC.listOf().parse(ops, tag.get("outputs")).getOrThrow();
        var water = WaterTank.Contents.CODEC.parse(ops, tag.get("water")).getOrThrow();
        var station = new ProcessingStation(in.size(), out.size(), water.capacityKg());
        station.inputs.clear(); station.inputs.addAll(in);
        station.outputs.clear(); station.outputs.addAll(out);
        station.tool = ItemStack.OPTIONAL_CODEC.parse(ops, tag.get("tool")).getOrThrow();
        station.water = new WaterTank(water);
        station.clockPending = true;
        if (tag.contains("recipe")) {
            station.recipeId = Optional.of(ResourceLocation.parse(tag.getString("recipe")));
            station.definition = Optional.of(tag.get("definition").copy());
            station.expectedInputs = ItemStack.OPTIONAL_CODEC.listOf().parse(ops, tag.get("expected_inputs")).getOrThrow();
            station.expectedTool = ItemStack.OPTIONAL_CODEC.parse(ops, tag.get("expected_tool")).getOrThrow();
            station.seconds = tag.getDouble("seconds");
            station.energy = tag.getDouble("energy");
            if (!Double.isFinite(station.seconds) || station.seconds < 0 || !Double.isFinite(station.energy) || station.energy < 0) {
                throw new IllegalArgumentException("保存された加工進捗が不正です");
            }
        }
        return station;
    }
}
