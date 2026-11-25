package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.MaterialData;
import io.github.rs0325.ashenfrontier.core.catalog.FuelData;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe;
import io.github.rs0325.ashenfrontier.core.recipe.ProcessingStation;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/** 加工の所有は共通基盤、着火操作と燃料・余熱だけを炉が保持する。M3入力なしでは停止する。 */
public final class ThermalStationEntity extends BlockEntity {
    private ProcessingStation station = new ProcessingStation(1, 2, 0.000001);
    private final HearthHeat heat = new HearthHeat();
    private ItemStack fuel = ItemStack.EMPTY;
    private ItemStack tinder = ItemStack.EMPTY;
    private ItemStack ignitionTool = ItemStack.EMPTY;
    private ResourceLocation selected;
    private ResourceLocation fuelProfile;
    private UUID worker;
    private long workTick;
    private long lastTick = -1;
    private double frictionSeconds;
    private boolean igniting;
    private boolean lit;

    public ThermalStationEntity(BlockPos pos, BlockState state) {
        super(PrimitiveContent.THERMAL_ENTITY.get(), pos, state);
        selected = AshenFrontierCore.id(state.is(PrimitiveContent.HEARTH.get()) ? "primitive/fire_pot" : "primitive/dry_pot");
    }

    ProcessingStation station() { return station; }
    HearthHeat heat() { return heat; }
    boolean lit() { return lit; }
    private boolean hearth() { return getBlockState().is(PrimitiveContent.HEARTH.get()); }
    private ResourceLocation facility() { return AshenFrontierCore.id(hearth() ? "hearth" : "drying_rack"); }
    private int interval() { return hearth() ? 20 : 200; }
    private static boolean dry(ItemStack stack) { return stack.getOrDefault(NatureProperties.MOISTURE.get(), 0.0) == 0; }

    public void interact(ServerPlayer player, InteractionHand hand, Direction face) {
        if (worker != null && !worker.equals(player.getUUID()) && (station.active() || igniting)) {
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.busy"));
            return;
        }
        work();
        var held = player.getItemInHand(hand);
        if (held.isEmpty() && player.isShiftKeyDown()) {
            if (face == Direction.DOWN) {
                station.cancel(player);
                igniting = false;
                frictionSeconds = 0;
                setLit(false);
            } else if (station.active() || igniting) {
                station.cancel(player);
                igniting = false;
                frictionSeconds = 0;
            } else if (face == Direction.UP) {
                var choices = recipes();
                int current = -1;
                for (int i = 0; i < choices.size(); i++) if (choices.get(i).id().equals(selected)) current = i;
                if (!choices.isEmpty()) selected = choices.get((current + 1) % choices.size()).id();
            } else {
                give(player, station.input(0));
                station.setInput(player, 0, ItemStack.EMPTY);
                give(player, fuel); fuel = ItemStack.EMPTY;
                give(player, tinder); tinder = ItemStack.EMPTY;
                collect(player);
            }
        } else if (hearth() && held.is(PrimitiveContent.item("fire_drill")) && hand == InteractionHand.MAIN_HAND && !lit) {
            var environment = EnvironmentQuery.sample(player.serverLevel(), worldPosition, level.getGameTime(), level.getGameTime());
            if (!tinder.isEmpty() && dry(tinder) && dry(held) && PrimitiveToolItem.usable(held)
                    && (!fuel.isEmpty() || heat.fuelJoules() > 0) && environment.filter(e -> e.covered() && e.ventilated()).isPresent()) {
                igniting = true;
                ignitionTool = held.copy();
                resume(player);
            } else {
                player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.fire_requirements"));
            }
        } else if (!held.isEmpty() && !igniting) {
            if (hearth() && held.is(PrimitiveContent.item("tinder"))) {
                tinder = insert(player, held, tinder);
            } else if (hearth() && fuelData(held).isPresent()) {
                if (dry(held)) fuel = insert(player, held, fuel);
                else player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.fire_requirements"));
            } else if (!station.active()) {
                station.setInput(player, 0, insert(player, held, station.input(0)));
            }
        } else if (held.isEmpty()) {
            if (!station.output(0).isEmpty() || !station.output(1).isEmpty()) collect(player);
            else {
                long now = level.getGameTime();
                var environment = EnvironmentQuery.sample((ServerLevel) level, worldPosition, now, now);
                if (station.active() || suitable(environment) && (station.start(player, selected, facility(), environment, temperature()) || lit)) resume(player);
                else player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.environment_missing"));
            }
        }
        describe(player);
        setChanged();
    }

    private void resume(ServerPlayer player) {
        worker = player.getUUID();
        workTick = level.getGameTime();
        station.advance(player, facility(), Optional.empty(), 0, temperature(), 0);
        level.scheduleTick(worldPosition, getBlockState().getBlock(), interval());
    }

    private static ItemStack insert(ServerPlayer player, ItemStack held, ItemStack stored) {
        if (!stored.isEmpty() && !ItemStack.isSameItemSameComponents(held, stored)) return stored;
        int moved = Math.min(player.isShiftKeyDown() ? held.getCount() : 1, held.getMaxStackSize() - stored.getCount());
        if (moved <= 0) return stored;
        if (stored.isEmpty()) stored = held.copyWithCount(moved); else stored.grow(moved);
        if (!player.isCreative()) held.shrink(moved);
        return stored;
    }

    private static Optional<FuelData> fuelData(ItemStack stack) {
        return Optional.ofNullable(stack.getItem().builtInRegistryHolder().getData(FuelData.ITEM))
                .filter(f -> f.profile().equals(AshenFrontierCore.id("dry_wood")))
                .filter(f -> f.heatingValueJKg().filter(v -> Double.isFinite(v) && v > 0).isPresent())
                .filter(f -> MaterialData.get(stack.getItem()).filter(m -> m.massKg() > 0).isPresent());
    }

    private OptionalDouble temperature() {
        return hearth() && Double.isFinite(heat.temperatureK()) ? OptionalDouble.of(heat.temperatureK()) : OptionalDouble.empty();
    }

    private boolean suitable(Optional<EnvironmentSample> environment) {
        return environment.filter(e -> e.covered() && e.ventilated()
                && (hearth() || e.temperatureK() > 273.15 && e.relativeHumidity() <= 0.8)).isPresent();
    }

    public void work() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (worker == null) { coolTo(serverLevel, level.getGameTime()); return; }
        var player = serverLevel.getPlayerByUUID(worker);
        if (!(player instanceof ServerPlayer serverPlayer) || !player.isAlive()) { worker = null; coolTo(serverLevel, level.getGameTime()); return; }
        long now = level.getGameTime();
        long from = workTick;
        var environment = coolTo(serverLevel, from)
                ? EnvironmentQuery.samples(serverLevel, worldPosition, from, now) : Optional.<List<EnvironmentSample>>empty();
        if (environment.isEmpty()) {
            station.advance(serverPlayer, facility(), Optional.empty(), 0, temperature(), 0);
            worker = null;
            serverPlayer.sendSystemMessage(Component.translatable("primitive.ashen_frontier.environment_missing"));
            setChanged();
            return;
        }
        workTick = now;
        for (var sample : environment.get()) workInterval(serverLevel, serverPlayer, sample);
        if (station.active() || igniting || lit) level.scheduleTick(worldPosition, getBlockState().getBlock(), interval());
        else worker = null;
        setChanged();
    }

    private void workInterval(ServerLevel serverLevel, ServerPlayer serverPlayer, EnvironmentSample sample) {
        long from = sample.startTick();
        long now = sample.endTick();
        double seconds = GameTime.realSeconds(now - from);
        var environment = Optional.of(sample);
        boolean newlyLit = false;
        if (igniting) {
            var held = serverPlayer.getMainHandItem();
            if (serverPlayer.distanceToSqr(worldPosition.getCenter()) > 16 || !suitable(environment)
                    || !ItemStack.isSameItemSameComponents(held, ignitionTool) || !dry(held)
                    || !dry(tinder) || tinder.isEmpty()) {
                igniting = false;
            } else {
                frictionSeconds += seconds;
                if (frictionSeconds >= 30) {
                    tinder.shrink(1);
                    var wear = held.get(ItemProperties.WEAR);
                    held.set(ItemProperties.WEAR, ItemProperties.Wear.of(wear.value() + 1, wear.maximum()));
                    frictionSeconds = 0;
                    igniting = false;
                    setLit(true);
                    newlyLit = true;
                    ResearchManager.report(serverPlayer, AshenFrontierCore.id("primitive_event"), "friction_ignition");
                }
            }
        }
        double processJ = 0;
        double activeSeconds = 0;
        double suppliedProcessJ = 0;
        HearthHeat.Step evaluatedHeat = null;
        if (hearth()) {
            while (!newlyLit && lit && suitable(environment) && heat.fuelJoules() < HearthHeat.POWER_W * seconds && !fuel.isEmpty()) {
                var properties = fuelData(fuel);
                if (properties.isEmpty() || heat.fuelJoules() > 0
                        && heat.heatingValueJKg() != properties.get().heatingValueJKg().orElseThrow()) break;
                fuelProfile = properties.get().profile();
                heat.addFuel(MaterialData.get(fuel.getItem()).orElseThrow().massKg(), properties.get().heatingValueJKg().orElseThrow());
                fuel.shrink(1);
            }
            var step = heat.advance(newlyLit ? 0 : seconds, GameTime.gameSeconds(Math.max(0, now - from), sample.ticksPerDay()),
                    sample.temperatureK(), !newlyLit && lit && suitable(environment), station.active());
            lastTick = now;
            evaluatedHeat = step;
            processJ = step.processJoules();
            activeSeconds = step.processSeconds();
            if (lit && heat.fuelJoules() == 0 && fuel.isEmpty()) setLit(false);
        }
        if (station.active()) {
            if (suitable(environment) && (!hearth() || activeSeconds > 0)) {
                if (station.advanceInterval(serverPlayer, facility(), sample, processJ, temperature(),
                        hearth() ? activeSeconds : Double.MAX_VALUE)) suppliedProcessJ = processJ;
                if (station.finish(serverPlayer, facility(), environment, temperature())) {
                    if (station.output(0).is(PrimitiveContent.item("ceramic_pot"))) {
                        ResearchManager.report(serverPlayer, AshenFrontierCore.id("primitive_event"), "ceramic_pot");
                    }
                    serverPlayer.sendSystemMessage(Component.translatable("primitive.ashen_frontier.finished"));
                }
            } else station.advanceInterval(serverPlayer, facility(), sample, 0, temperature(), 0);
        }
        if (evaluatedHeat != null) notifyHeat(serverLevel, from, now, evaluatedHeat, suppliedProcessJ);
    }

    /** 作業・燃焼を進めず、未ロード区間も含む余熱だけを遅延評価する。未接続では時刻を進めない。 */
    private boolean coolTo(ServerLevel serverLevel, long now) {
        if (!hearth()) return true;
        if (lastTick < 0 || !Double.isFinite(heat.temperatureK())) { lastTick = now; return true; }
        if (now <= lastTick) return true;
        var environment = EnvironmentQuery.samples(serverLevel, worldPosition, lastTick, now);
        if (environment.isEmpty()) return false;
        for (var sample : environment.get()) {
            var step = heat.advance(0, GameTime.gameSeconds(sample.endTick() - sample.startTick(), sample.ticksPerDay()),
                    sample.temperatureK(), false, false);
            notifyHeat(serverLevel, sample.startTick(), sample.endTick(), step, 0);
            lastTick = sample.endTick();
        }
        setChanged();
        return true;
    }

    private void notifyHeat(ServerLevel serverLevel, long from, long to, HearthHeat.Step step, double suppliedProcessJ) {
        if (fuelProfile != null && (step.chemicalJoules() > 0 || step.storedJoules() != 0)) {
            NeoForge.EVENT_BUS.post(new CombustionEvent(serverLevel, worldPosition, from, to, fuelProfile,
                    step.burnedKg(), step.chemicalJoules(), step.storedJoules() + suppliedProcessJ));
        }
    }

    private void setLit(boolean value) {
        lit = value;
        if (level != null && getBlockState().getValue(BlockStateProperties.LIT) != value) {
            level.setBlock(worldPosition, getBlockState().setValue(BlockStateProperties.LIT, value), 3);
        }
    }

    private List<RecipeHolder<CommonRecipe>> recipes() {
        return level.getRecipeManager().getRecipes().stream()
                .filter(h -> h.value() instanceof CommonRecipe r && r.conditions().facility().filter(facility()::equals).isPresent())
                .map(h -> new RecipeHolder<>(h.id(), (CommonRecipe) h.value()))
                .sorted(Comparator.comparing(h -> h.id().toString())).toList();
    }

    private void describe(ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.thermal_controls"));
        var environment = EnvironmentQuery.sample((ServerLevel) level, worldPosition, level.getGameTime(), level.getGameTime());
        if (environment.isEmpty()) player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.environment_missing"));
        else if (!suitable(environment)) player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.unsuitable_environment"));
        if (hearth()) {
            String state = !Double.isFinite(heat.temperatureK()) ? "unknown"
                    : heat.temperatureK() < 973 ? "cold" : heat.temperatureK() > 1123 ? "hot" : "suitable";
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.heat_" + state));
        } else player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.drying_conditions"));

        recipes().stream().filter(h -> h.id().equals(selected)).findFirst().ifPresent(h -> {
            var output = h.value().outputs().getFirst();
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.selected", output.getHoverName()));
            if (output.has(PotteryProperties.FORM)) player.sendSystemMessage(Component.translatable("tooltip.ashen_frontier.pottery_form",
                    Component.translatable("pottery_form.ashen_frontier." + output.get(PotteryProperties.FORM))));
            h.value().inputs().forEach(input -> player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.input", input.getItems()[0].getHoverName())));
            h.value().serverDisplayLines(player).forEach(player::sendSystemMessage);
        });
        player.sendSystemMessage(Component.translatable(igniting ? "primitive.ashen_frontier.igniting"
                : lit ? "primitive.ashen_frontier.lit" : "primitive.ashen_frontier.ready"));
    }

    private void collect(ServerPlayer player) {
        for (int i = 0; i < 2; i++) give(player, station.takeOutput(player, i, 64));
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!stack.isEmpty() && !player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** 通常lootは空。設備アイテム1個だけに入力・出力・燃料・余熱を保存して回収する。 */
    public void dropStored() {
        if (level.isClientSide) return;
        coolTo((ServerLevel) level, level.getGameTime());
        var stack = new ItemStack(getBlockState().getBlock());
        saveToItem(stack, level.registryAccess());
        Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            setLit(lit);
            if (hearth()) level.scheduleTick(worldPosition, getBlockState().getBlock(), interval());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        tag.put("processing", station.save(registries));
        tag.put("fuel", ItemStack.OPTIONAL_CODEC.encodeStart(ops, fuel).getOrThrow());
        tag.put("tinder", ItemStack.OPTIONAL_CODEC.encodeStart(ops, tinder).getOrThrow());
        tag.putString("selected", selected.toString());
        tag.putDouble("fuel_j", heat.fuelJoules());
        tag.putDouble("heating_value", heat.heatingValueJKg());
        if (Double.isFinite(heat.temperatureK())) tag.putDouble("temperature_k", heat.temperatureK());
        if (fuelProfile != null) tag.putString("fuel_profile", fuelProfile.toString());
        tag.putBoolean("lit", lit);
        tag.putDouble("friction_seconds", frictionSeconds);
        tag.putLong("last_tick", lastTick);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        if (tag.contains("processing")) station = ProcessingStation.load(tag.getCompound("processing"), registries);
        fuel = ItemStack.OPTIONAL_CODEC.parse(ops, tag.get("fuel")).getOrThrow();
        tinder = ItemStack.OPTIONAL_CODEC.parse(ops, tag.get("tinder")).getOrThrow();
        selected = ResourceLocation.parse(tag.getString("selected"));
        heat.restore(tag.getDouble("fuel_j"), tag.getDouble("heating_value"),
                tag.contains("temperature_k") ? tag.getDouble("temperature_k") : Double.NaN);
        fuelProfile = tag.contains("fuel_profile") ? ResourceLocation.parse(tag.getString("fuel_profile")) : null;
        lit = tag.getBoolean("lit");
        frictionSeconds = tag.getDouble("friction_seconds");
        lastTick = tag.getLong("last_tick");
        if (level != null && !level.isClientSide) setLit(lit);
        worker = null;
        igniting = false;
    }
}
