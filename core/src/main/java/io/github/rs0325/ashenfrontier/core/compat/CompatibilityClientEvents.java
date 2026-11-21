package io.github.rs0325.ashenfrontier.core.compat;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.GAME)
public final class CompatibilityClientEvents {
    private CompatibilityClientEvents() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen && !ClientCompatibilityState.titleWarningShown
                && !ClientCompatibilityState.unsupported.isEmpty()) {
            showWarning();
            ClientCompatibilityState.titleWarningShown = true;
        }
    }

    @SubscribeEvent
    public static void onWorldJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!ClientCompatibilityState.worldWarningShown && !ClientCompatibilityState.unsupported.isEmpty()) {
            event.getPlayer().displayClientMessage(warning(), false);
            ClientCompatibilityState.worldWarningShown = true;
        }
    }

    private static void showWarning() {
        var minecraft = Minecraft.getInstance();
        minecraft.getToasts().addToast(SystemToast.multiline(minecraft,
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("compatibility.ashen_frontier.title"), warning()));
    }

    private static Component warning() {
        var summary = Component.literal(
                ModCompatibility.displayedMods(ClientCompatibilityState.unsupported));
        int omitted = ModCompatibility.omittedCount(ClientCompatibilityState.unsupported);
        if (omitted > 0) {
            summary.append(Component.literal(", ").append(
                    Component.translatable("compatibility.ashen_frontier.more", omitted)));
        }
        return Component.translatable("compatibility.ashen_frontier.warning",
                summary);
    }
}
