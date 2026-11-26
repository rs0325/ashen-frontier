package io.github.rs0325.ashenfrontier.core.water;

import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import io.github.rs0325.ashenfrontier.core.catalog.NatureContent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** 専用容器の空中右クリックをサーバーで再照合する。無接続時に代替供給しない。 */
public final class WaterInteraction {
    private WaterInteraction() {}
    public static void register() { NeoForge.EVENT_BUS.addListener(WaterInteraction::use); }
    private static void use(PlayerInteractEvent.RightClickItem event) {
        if (WaterContainerData.capacity(event.getItemStack()) == null) return;
        var player = event.getEntity();
        var eye = player.getEyePosition();
        var end = eye.add(player.getViewVector(1).scale(player.blockInteractionRange()));
        var hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        var fluid = player.level().getFluidState(hit.getBlockPos());
        if (NatureCatalog.entries().stream().filter(e -> e.kind().equals("water"))
                .noneMatch(e -> fluid.getType() == NatureContent.fluids().get(e.id()).source().get())) return;
        if (player instanceof ServerPlayer serverPlayer) {
            var result = WaterTransfers.sourceToContainer(serverPlayer, hit.getBlockPos(), event.getHand(),
                    player.isShiftKeyDown() ? 4 : 1);
            serverPlayer.sendSystemMessage(result.message());
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
