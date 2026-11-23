package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.ContaminationTools;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import java.util.Locale;

final class ContaminationDisplay {
    private ContaminationDisplay() {}

    static void register(IEventBus bus) {
        NeoForge.EVENT_BUS.addListener(ContaminationDisplay::measure);
        NeoForge.EVENT_BUS.addListener(ContaminationDisplay::layer);
    }

    private static void measure(PlayerInteractEvent.RightClickBlock event) {
        var level = event.getLevel();
        var pos = event.getPos();
        boolean sign = level.getBlockState(pos).is(BuiltInRegistries.BLOCK.get(AshenFrontierCore.id("damaged_warning_sign")));
        if (!sign && !event.getItemStack().is(ContaminationTools.METER.get())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (!(level instanceof ServerLevel server)) return;
        if (sign) pos = pos.below();
        var value = BlockContamination.get(server, pos);
        if (value.isEmpty()) {
            event.getEntity().sendSystemMessage(Component.translatable("contamination.ashen_frontier.no_sample"));
            return;
        }
        event.getEntity().sendSystemMessage(Component.translatable("contamination.ashen_frontier.position", pos.getX(), pos.getY(), pos.getZ()));
        for (var kind : SoilContamination.Kind.values()) {
            event.getEntity().sendSystemMessage(Component.translatable("contamination.ashen_frontier.result",
                    Component.translatable("contamination.ashen_frontier." + kind.key),
                    Component.translatable("contamination.ashen_frontier.stage." + value.get().stage(kind)),
                    String.format(Locale.ROOT, "%.6g", value.get().value(kind) * kind.displayFactor), kind.unit));
        }
    }

    private static void layer(UseItemOnBlockEvent event) {
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK
                || !(event.getLevel() instanceof ServerLevel level) || !(event.getItemStack().getItem() instanceof BlockItem item)) return;
        var pos = new BlockPlaceContext(event.getUseOnContext()).getClickedPos();
        var state = level.getBlockState(pos);
        if (state.is(item.getBlock()) && state.hasProperty(BlockStateProperties.LAYERS)
                && BlockContamination.isTarget(state)
                && !BlockContamination.get(level, pos).orElseThrow().stages().equals(BlockContamination.readItem(event.getItemStack()).stages())) {
            event.cancelWithResult(ItemInteractionResult.FAIL);
            if (event.getPlayer() != null)
                event.getPlayer().sendSystemMessage(Component.translatable("contamination.ashen_frontier.different_layers"));
        }
    }

    static void wither(ServerLevel level, BlockPos pos, SoilContamination value) {
        if (!value.severe()) return;
        var plant = level.getBlockState(pos.above());
        if (plant.is(Blocks.SHORT_GRASS) || plant.is(Blocks.FERN))
            level.setBlockAndUpdate(pos.above(), NatureContent.block("dead_grass").defaultBlockState());
    }
}
