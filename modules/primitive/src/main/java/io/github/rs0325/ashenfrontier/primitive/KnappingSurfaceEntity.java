package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.catalog.RelicContent;
import io.github.rs0325.ashenfrontier.core.recipe.CommonRecipe;
import io.github.rs0325.ashenfrontier.core.recipe.ProcessingStation;
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

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 台石の操作アダプタ。入力・工具・全出力・進捗の正本はProcessingStation。
 * 原料1スロット、工具1個、出力2スロット。液体の入出力は提供しない。
 * 所持品から実際に移した物だけを、サーバーの操作内で共通基盤へ渡す。
 */
public final class KnappingSurfaceEntity extends BlockEntity {
    private static final ResourceLocation FACILITY = AshenFrontierCore.id("knapping_surface");
    private ProcessingStation station = new ProcessingStation(1, 2, 0.000001);
    private ResourceLocation selected = AshenFrontierCore.id("primitive/chopper");
    private UUID worker;

    public KnappingSurfaceEntity(BlockPos pos, BlockState state) { super(PrimitiveContent.SURFACE_ENTITY.get(), pos, state); }

    ProcessingStation station() { return station; }

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
        } else if (!held.isEmpty() && !station.active()) {
            if (held.is(RelicContent.item("stone_hammer")) || held.is(PrimitiveContent.item("stone_flake"))
                    || held.is(PrimitiveContent.item("biface"))) {
                give(player, station.tool());
                ItemStack moved = player.isCreative() ? held.copyWithCount(1) : held.split(1);
                if (!moved.has(ItemProperties.WEAR)) moved.set(ItemProperties.WEAR, ItemProperties.Wear.of(0, 64));
                station.setTool(player, moved);
            } else {
                ItemStack input = station.input(0);
                if (!input.isEmpty() && !ItemStack.isSameItemSameComponents(input, held)) {
                    describe(player);
                    return;
                }
                int count = Math.min(player.isShiftKeyDown() ? held.getCount() : 1, held.getMaxStackSize() - input.getCount());
                if (count > 0) {
                    if (input.isEmpty()) input = held.copyWithCount(count); else input.grow(count);
                    station.setInput(player, 0, input);
                    if (!player.isCreative()) held.shrink(count);
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
        recipes().stream().filter(h -> h.id().equals(selected)).findFirst().ifPresent(holder -> {
            var recipe = holder.value();
            player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.selected", recipe.outputs().getFirst().getHoverName()));
            recipe.inputs().forEach(input -> player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.input", input.getItems()[0].getHoverName())));
            recipe.displayLines().forEach(player::sendSystemMessage);
        });
        player.sendSystemMessage(Component.translatable(station.active() ? "primitive.ashen_frontier.working" : "primitive.ashen_frontier.ready"));
    }

    private void withdraw(ServerPlayer player) {
        give(player, station.input(0));
        station.setInput(player, 0, ItemStack.EMPTY);
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
        for (ItemStack stack : List.of(station.input(0), station.tool(), station.output(0), station.output(1))) {
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
