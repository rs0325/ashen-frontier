package io.github.rs0325.ashenfrontier.core;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, value = Dist.CLIENT)
public final class MiningClientEvents {
    private MiningClientEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide || !event.isCanceled()
                || event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.ABORT) return;
        var minecraft = Minecraft.getInstance();
        if (event.getEntity() == minecraft.player && minecraft.gameMode != null) {
            // キャンセル時はバニラの初期化まで進まないため、ローカルの進行とヒビも中断する。
            minecraft.gameMode.stopDestroyBlock();
        }
    }
}
