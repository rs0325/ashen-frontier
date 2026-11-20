package io.github.rs0325.ashenfrontier.core.compat;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.config.CoreConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.bus.api.SubscribeEvent;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, value = Dist.CLIENT)
public final class CompatibilityClientSetup {
    private CompatibilityClientSetup() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ClientCompatibilityState.unsupported = ModCompatibility.unsupported(
                ModList.get().getMods().stream().map(IModInfo::getModId).toList(),
                CoreConfig.clientAllowedMods());
        if (!ClientCompatibilityState.unsupported.isEmpty()) {
            AshenFrontierCore.LOGGER.warn("Unsupported mods detected: {}",
                    ClientCompatibilityState.unsupported);
        }
    }
}
