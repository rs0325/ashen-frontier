package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.catalog.NatureProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicProperties;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.core.recipe.ProcessingStation;
import io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe;
import io.github.rs0325.ashenfrontier.core.recipe.WaterMass;
import io.github.rs0325.ashenfrontier.core.recipe.WaterTank;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import io.github.rs0325.ashenfrontier.core.water.WaterTankOwner;
import io.github.rs0325.ashenfrontier.core.water.WaterTransfers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/** 静置とろ過を順に評価する。保持水は使用中の布の自由水として設備内に残す。 */
public final class WaterFilterEntity extends BlockEntity implements WaterTankOwner {
    private ProcessingStation station = new ProcessingStation(1, 1, 4);
    private UUID worker;
    private long lastTick;
    private double settlingSeconds;
    private boolean clockPending;

    public WaterFilterEntity(BlockPos pos, BlockState state) { super(PrimitiveContent.FILTER_ENTITY.get(), pos, state); }

    ProcessingStation station() { return station; }
    @Override
    public WaterTank waterTank() { return station.water(); }

    public void interact(ServerPlayer player, InteractionHand hand, Direction face) {
        if (station.active() && worker != null && !worker.equals(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.busy"));
            return;
        }
        work();
        var held = player.getItemInHand(hand);
        var storedKg = held.get(RelicProperties.CONTENT_MASS.get());
        if (storedKg != null && storedKg >= 0 && storedKg <= WaterMass.MAX_KG)
            held.set(RelicProperties.CONTENT_MASS, WaterMass.toKg(WaterMass.fromKg(storedKg)));
        if (held.isEmpty() && player.isShiftKeyDown()) {
            station.cancel(player);
            settlingSeconds = 0;
            worker = null;
            if (face != Direction.UP) {
                give(player, station.input(0)); station.setInput(player, 0, ItemStack.EMPTY);
                give(player, station.tool()); station.setTool(player, ItemStack.EMPTY);
                give(player, station.takeOutput(player, 0, 1));
            }
        } else if (held.has(RelicProperties.CONTENT_MASS.get()) && (!held.is(PrimitiveContent.item("ceramic_pot"))
                || held.getOrDefault(RelicProperties.CONTENT_MASS.get(), 0.0) > 0 || player.isShiftKeyDown())) {
            var result = player.isShiftKeyDown()
                    ? WaterTransfers.tankToContainer(player, hand, this, 4)
                    : WaterTransfers.containerToTank(player, hand, this, 4);
            if (result.movedMg() > 0) { station.cancel(player); settlingSeconds = 0; worker = null; }
            player.sendSystemMessage(result.message());
        } else if (!station.active() && held.is(PrimitiveContent.item("fiber_cloth"))) {
            give(player, station.tool());
            var cloth = held.split(1);
            if (!cloth.has(ItemProperties.WEAR.get())) cloth.set(ItemProperties.WEAR, ItemProperties.Wear.of(0, 10));
            station.setTool(player, cloth);
            settlingSeconds = 0;
        } else if (!station.active() && held.is(PrimitiveContent.item("ceramic_pot"))
                && station.input(0).isEmpty()) {
            station.setInput(player, 0, held.split(1));
        } else if (held.isEmpty()) {
            if (!station.output(0).isEmpty()) give(player, station.takeOutput(player, 0, 1));
            else {
                long now = level.getGameTime();
                var environment = EnvironmentQuery.sample((ServerLevel) level, worldPosition, now, now);
                if (station.active() || station.start(player, AshenFrontierCore.id("primitive/filter_water"),
                        AshenFrontierCore.id("water_filter"), environment)) {
                    worker = player.getUUID();
                    lastTick = now;
                    clockPending = false;
                    station.advance(player, AshenFrontierCore.id("water_filter"), environment, 0,
                            OptionalDouble.empty(), 0);
                    level.scheduleTick(worldPosition, getBlockState().getBlock(), 200);
                }
            }
        }
        var water = station.water().contents();
        player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.stored_water", water.massKg(),
                water.quality().<Component>map(q -> Component.translatable("fluid_type." + q.getNamespace() + "." + q.getPath()))
                        .orElseGet(() -> Component.literal("-"))));
        player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.filter_controls"));
        player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.filter_progress",
                settlingSeconds, station.tool().getOrDefault(NatureProperties.MOISTURE.get(), 0.0)));
        var now = level.getGameTime();
        if (EnvironmentQuery.sample((ServerLevel) level, worldPosition, now, now).isEmpty())
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.environment_missing"));
        level.getRecipeManager().byKey(AshenFrontierCore.id("primitive/filter_water")).ifPresent(h -> {
            if (h.value() instanceof CommonRecipe recipe)
                recipe.serverDisplayLines(player).forEach(player::sendSystemMessage);
        });
        setChanged();
    }

    public void work() {
        if (!(level instanceof ServerLevel server) || worker == null || !station.active()) return;
        var entity = server.getPlayerByUUID(worker);
        if (!(entity instanceof ServerPlayer player)) { worker = null; return; }
        long now = server.getGameTime();
        if (clockPending) { lastTick = now; clockPending = false; }
        var environment = EnvironmentQuery.samples(server, worldPosition, lastTick, now);
        if (environment.isEmpty()) { worker = null; return; }
        for (var sample : environment.get()) {
            double elapsed = GameTime.gameSeconds(sample.endTick() - sample.startTick(), sample.ticksPerDay());
            double needed = Math.max(0, 43200 - settlingSeconds);
            double fraction = elapsed == 0 ? 0 : Math.max(0, elapsed - needed) / elapsed;
            settlingSeconds = Math.min(43200, settlingSeconds + elapsed);
            station.advanceInterval(player, AshenFrontierCore.id("water_filter"), sample, 0, OptionalDouble.empty(),
                    GameTime.realSeconds(sample.endTick() - sample.startTick()) * fraction);
            if (station.finish(player, AshenFrontierCore.id("water_filter"), Optional.of(sample))) {
                var cloth = station.tool();
                cloth.set(NatureProperties.MOISTURE, WaterMass.toKg(WaterMass.fromKg(cloth.getOrDefault(NatureProperties.MOISTURE.get(), 0.0)) + 400_000));
                cloth.set(NatureProperties.QUALITY, AshenFrontierCore.id("turbid_water"));
                station.setTool(player, cloth);
                settlingSeconds = 0;
                worker = null;
                ResearchManager.report(player, AshenFrontierCore.id("water_event"), "filtered_water");
                break;
            }
        }
        lastTick = now;
        if (station.active()) level.scheduleTick(worldPosition, getBlockState().getBlock(), 200);
        setChanged();
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!stack.isEmpty() && !player.getInventory().add(stack)) player.drop(stack, false);
    }

    public void dropStored() {
        if (level.isClientSide) return;
        var stack = new ItemStack(getBlockState().getBlock());
        saveToItem(stack, level.registryAccess());
        Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("processing", station.save(registries));
        tag.putDouble("settling_seconds", settlingSeconds);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("processing")) station = ProcessingStation.load(tag.getCompound("processing"), registries);
        settlingSeconds = tag.getDouble("settling_seconds");
        worker = null;
        clockPending = true;
    }
}
