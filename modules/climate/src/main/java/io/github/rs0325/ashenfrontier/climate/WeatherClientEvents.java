package io.github.rs0325.ashenfrontier.climate;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid=AshenFrontierClimate.MOD_ID,value=Dist.CLIENT)
final class WeatherClientEvents {
    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) { WeatherClientState.clear(); }
}
