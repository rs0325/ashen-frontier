package io.github.rs0325.ashenfrontier.core.compat;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.config.CoreConfig;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.server.level.ServerPlayer;
import java.util.Set;
import java.util.List;

public final class CompatibilityChecker {
    private CompatibilityChecker() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CompatibilityChecker::serverStarted);
        NeoForge.EVENT_BUS.addListener(CompatibilityChecker::playerLoggedIn);
    }

    private static void serverStarted(ServerStartedEvent event) {
        Set<String> unsupported = check(CoreConfig.serverAllowedMods());
        if (!unsupported.isEmpty()) {
            AshenFrontierCore.LOGGER.warn("Unsupported mods detected: {}", unsupported);
        }
    }

    private static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getServer().isDedicatedServer()) {
            Set<String> unsupported = check(CoreConfig.serverAllowedMods());
            if (!unsupported.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "compatibility.ashen_frontier.warning", warningSummary(unsupported)));
            }
        }
    }

    static Set<String> check(List<String> configuredAllowed) {
        return ModCompatibility.unsupported(ModList.get().getMods().stream()
                .map(IModInfo::getModId).toList(), configuredAllowed);
    }

    private static Component warningSummary(Set<String> unsupported) {
        var summary = Component.literal(ModCompatibility.displayedMods(unsupported));
        int omitted = ModCompatibility.omittedCount(unsupported);
        if (omitted > 0) {
            summary.append(Component.literal(", ").append(
                    Component.translatable("compatibility.ashen_frontier.more", omitted)));
        }
        return summary;
    }
}
