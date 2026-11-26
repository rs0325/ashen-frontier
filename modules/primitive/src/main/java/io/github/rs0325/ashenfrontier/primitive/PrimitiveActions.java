package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.MiningRules;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import io.github.rs0325.ashenfrontier.core.research.ResearchManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Set;

/** サーバーで観察・台石配置・Bの採掘適性を強制する。 */
public final class PrimitiveActions {
    public static final TagKey<Block> SOILS = TagKey.create(Registries.BLOCK, AshenFrontierCore.id("primitive_soils"));

    private PrimitiveActions() {}

    public static void register() {
        ResearchManager.registerConditionType(AshenFrontierCore.id("primitive_observation"),
                (player, id) -> id.equals("pebble") && player.getMainHandItem().is(NatureContent.item("pebble"))
                        || id.equals("fiber") && player.getMainHandItem().is(NatureContent.item("plant_fiber")));
        ResearchManager.registerConditionType(AshenFrontierCore.id("primitive_crafting"),
                (player, id) -> Set.of("chopper", "cordage").contains(id));
        NeoForge.EVENT_BUS.addListener(PrimitiveActions::observe);
        NeoForge.EVENT_BUS.addListener(PrimitiveActions::place);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.BreakSpeed event) -> {
            if (!canMine(event.getState(), event.getEntity().getMainHandItem())) event.setNewSpeed(0);
        });
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickBlock event) -> {
            if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.ABORT && !event.getEntity().isCreative() && !canMine(event.getLevel().getBlockState(event.getPos()), event.getItemStack())) event.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent event) -> {
            if (!event.getPlayer().isCreative() && !canMine(event.getState(), event.getPlayer().getMainHandItem())) event.setCanceled(true);
        });
    }

    /** 土・砂・粘土の掘削を土用工具へ限定し、他はcoreの適性規則を使う。 */
    public static boolean canMine(BlockState state, ItemStack tool) {
        if (state.is(SOILS) && !tool.is(MiningRules.SOIL_TOOLS)) return false;
        return PrimitiveToolItem.usable(tool) && MiningRules.canMine(state, tool);
    }

    private static void observe(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String id = event.getItemStack().is(NatureContent.item("pebble")) ? "pebble"
                : event.getItemStack().is(NatureContent.item("plant_fiber")) ? "fiber" : "";
        if (id.isEmpty()) return;
        ResearchManager.report(player, AshenFrontierCore.id("primitive_observation"), id);
        player.sendSystemMessage(Component.translatable("primitive.ashen_frontier.observe_" + id));
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static void place(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().getBlockState(event.getPos()).is(PrimitiveContent.SURFACE.get())) {
            event.setUseBlock(TriState.TRUE);
            return;
        }
        if (!event.getEntity().isShiftKeyDown() || !event.getItemStack().is(NatureContent.item("pebble"))
                || event.getLevel().getBlockState(event.getPos()).is(PrimitiveContent.SURFACE.get())) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            var surface = new ItemStack(PrimitiveContent.item("knapping_surface"));
            var context = new BlockPlaceContext(player, event.getHand(), surface, event.getHitVec());
            var result = ((BlockItem) surface.getItem()).place(context);
            if (result.consumesAction() && !player.isCreative()) event.getItemStack().shrink(1);
            event.setCancellationResult(result);
        } else {
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
        event.setCanceled(true);
    }
}
