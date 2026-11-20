package io.github.rs0325.ashenfrontier.core.terminal;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, value = Dist.CLIENT)
public final class TerminalClient {
    public static final KeyMapping OPEN = new KeyMapping("key.ashen_frontier.terminal",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.ashen_frontier");

    private TerminalClient() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    @EventBusSubscriber(modid = AshenFrontierCore.MOD_ID, value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (OPEN.consumeClick() && Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new TerminalScreen());
            }
        }
    }
}
