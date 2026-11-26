package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe;
import io.github.rs0325.ashenfrontier.core.recipe.ProcessingStation;
import io.github.rs0325.ashenfrontier.core.recipe.WaterTank;
import io.github.rs0325.ashenfrontier.core.water.WaterTankOwner;
import io.github.rs0325.ashenfrontier.core.water.WaterTransfers;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 台石の操作アダプタ。入力・工具・全出力・進捗の正本はProcessingStation。
 * 原料4スロット、工具1個、出力2スロット。成形水は共通の槽へ容器から実移送する。
 * 所持品から実際に移した物だけを、サーバーの操作内で共通基盤へ渡す。
 */
public final class KnappingSurfaceEntity extends BlockEntity implements WaterTankOwner {
    private static final ResourceLocation FACILITY = AshenFrontierCore.id("knapping_surface");
    private ProcessingStation station = new ProcessingStation(4, 2, 4);
    private ResourceLocation selected = AshenFrontierCore.id("primitive/chopper");
    private UUID worker;

    public KnappingSurfaceEntity(BlockPos pos, BlockState state) { super(PrimitiveContent.SURFACE_ENTITY.get(), pos, state); }

    ProcessingStation station() { return station; }
    @Override
    public WaterTank waterTank() { return station.water(); }
    boolean storesWater() { return station.water().contents().massKg() > 0; }

    /** ブロックの右クリックを処理する。上面のShift操作は選択、側面は回収。作業中のShift操作は取消。 */
    public void interact(ServerPlayer player, InteractionHand hand, Direction face) {
        if (worker != null && !worker.equals(player.getUUID()) && station.active()) {
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.busy"));
            return;
        }
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty() && player.isShiftKeyDown()) {
            if (station.active()) {
                station.cancel(player);
                worker = null;
            } else if (face != Direction.UP) {
                withdraw(player);
            } else {
                var choices = recipes();
                int current = -1;
                for (int i = 0; i < choices.size(); i++) if (choices.get(i).id().equals(selected)) current = i;
                if (!choices.isEmpty()) selected = choices.get((current + 1) % choices.size()).id();
            }
            describe(player);
        } else if ((held.is(RelicContent.item("sealed_water_container")) || held.is(PrimitiveContent.item("ceramic_pot"))) && !station.active()) {
            if (player.isShiftKeyDown() && held.is(PrimitiveContent.item("ceramic_pot"))) {
                player.sendSystemMessage(WaterTransfers.tankToContainer(player, hand, this, 4).message());
            } else player.sendSystemMessage(WaterTransfers.containerToTank(player, hand, this, player.isShiftKeyDown() ? 4 : 1).message());
        } else if (!held.isEmpty() && !station.active()) {
            if (held.is(RelicContent.item("stone_hammer")) || held.is(PrimitiveContent.item("stone_flake"))
                    || held.is(PrimitiveContent.item("biface"))) {
                give(player, station.tool());
                ItemStack moved = player.isCreative() ? held.copyWithCount(1) : held.split(1);
                if (!moved.has(ItemProperties.WEAR)) moved.set(ItemProperties.WEAR, ItemProperties.Wear.of(0, 64));
                station.setTool(player, moved);
            } else {
                for (int slot = 0; slot < station.inputSlots(); slot++) {
                    ItemStack input = station.input(slot);
                    if (!input.isEmpty() && !ItemStack.isSameItemSameComponents(input, held)) continue;
                    int count = Math.min(player.isShiftKeyDown() ? held.getCount() : 1, held.getMaxStackSize() - input.getCount());
                    if (count <= 0) continue;
                    if (input.isEmpty()) input = held.copyWithCount(count); else input.grow(count);
                    station.setInput(player, slot, input);
                    if (!player.isCreative()) held.shrink(count);
                    break;
                }
            }
            describe(player);
        } else if (held.isEmpty()) {
            if (!station.output(0).isEmpty() || !station.output(1).isEmpty()) {
                for (int i = 0; i < 2; i++) give(player, station.takeOutput(player, i, 64));
            } else if (station.active() || station.start(player, selected, FACILITY, Optional.empty())) {
                if (worker == null) {
                    station.advance(player, FACILITY, Optional.empty(), 0);
                    worker = player.getUUID();
                    level.scheduleTick(worldPosition, getBlockState().getBlock(), 20);
                }
            }
            describe(player);
        }
        setChanged();
    }

    /** 稼働中のscheduled tickから呼ぶ。離脱・退出時は未評価区間を進めず、再操作待ちにする。 */
    public void work() {
        if (!station.active() || worker == null) return;
        var player = level.getPlayerByUUID(worker);
        if (!(player instanceof ServerPlayer serverPlayer) || !player.isAlive()
                || player.distanceToSqr(worldPosition.getCenter()) > 16) {
            station = ProcessingStation.load(station.save(level.registryAccess()), level.registryAccess());
            worker = null;
        } else {
            station.advance(serverPlayer, FACILITY, Optional.empty(), 0);
            if (station.finish(serverPlayer, FACILITY, Optional.empty())) {
                String output = BuiltInRegistries.ITEM.getKey(station.output(0).getItem()).getPath();
                ResearchManager.report(serverPlayer, AshenFrontierCore.id("primitive_crafting"), output);
                worker = null;
                serverPlayer.sendSystemMessage(Component.translatable("primitive.ashen_frontier.finished"));
            } else if (station.active()) {
                level.scheduleTick(worldPosition, getBlockState().getBlock(), 20);
            } else {
                worker = null;
            }
        }
        setChanged();
    }

    private List<RecipeHolder<CommonRecipe>> recipes() {
        return level.getRecipeManager().getRecipes().stream()
                .filter(h -> h.value() instanceof CommonRecipe r && r.conditions().facility().filter(FACILITY::equals).isPresent()
                        && (r.typeId().equals(AshenFrontierCore.id("knapping")) || r.typeId().equals(AshenFrontierCore.id("fiberworking"))))
                .map(h -> new RecipeHolder<>(h.id(), (CommonRecipe) h.value())).sorted(Comparator.comparing(h -> h.id().toString())).toList();
    }

    private void describe(ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.controls"));
        var water = station.water().contents();
        player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.stored_water", water.massKg(),
                water.quality().<Component>map(q -> Component.translatable("fluid_type." + q.getNamespace() + "." + q.getPath()))
                        .orElseGet(() -> Component.literal("-"))));

        recipes().stream().filter(h -> h.id().equals(selected)).findFirst().ifPresent(holder -> {
            var recipe = holder.value();
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.selected", recipe.outputs().getFirst().getHoverName()));
            var output = recipe.outputs().getFirst();
            if (output.has(PotteryProperties.FORM)) player.sendSystemMessage(Component.translatable(
                    "tooltip.ashen_frontier.pottery_form",
                    Component.translatable("pottery_form.ashen_frontier." + output.get(PotteryProperties.FORM))));
            recipe.inputs().forEach(input -> player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.input", input.getItems()[0].getHoverName())));
            recipe.serverDisplayLines(player).forEach(player::sendSystemMessage);
        });
        player.sendSystemMessage(Component.translatable(station.active() ? "primitive.ashen_frontier.working" : "primitive.ashen_frontier.ready"));
    }

    private void withdraw(ServerPlayer player) {
        for (int i = 0; i < station.inputSlots(); i++) {
            give(player, station.input(i));
            station.setInput(player, i, ItemStack.EMPTY);
        }
        give(player, station.tool());
        station.setTool(player, ItemStack.EMPTY);
        for (int i = 0; i < 2; i++) give(player, station.takeOutput(player, i, 64));
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!stack.isEmpty() && !player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** 破壊時だけ所有物をドロップする。設備自体の回収は通常のlootへ委ねる。 */
    public void dropContents() {
        if (level.isClientSide) return;
        if (storesWater()) {
            var stack = new ItemStack(getBlockState().getBlock());
            saveToItem(stack, level.registryAccess());
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack);
            return;
        }
        var contents = new ArrayList<ItemStack>();
        for (int i = 0; i < station.inputSlots(); i++) contents.add(station.input(i));
        contents.addAll(List.of(station.tool(), station.output(0), station.output(1)));
        for (ItemStack stack : contents) {
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("processing", station.save(registries));
        tag.putString("selected", selected.toString());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("processing")) station = ProcessingStation.load(tag.getCompound("processing"), registries);
        if (tag.contains("selected")) selected = ResourceLocation.parse(tag.getString("selected"));
        worker = null;
    }
}
