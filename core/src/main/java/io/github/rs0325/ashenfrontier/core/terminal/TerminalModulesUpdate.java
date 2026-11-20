package io.github.rs0325.ashenfrontier.core.terminal;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public record TerminalModulesUpdate(List<ResourceLocation> modules) implements CustomPacketPayload {
    public static final Type<TerminalModulesUpdate> TYPE = new Type<>(AshenFrontierCore.id("terminal_modules"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalModulesUpdate> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.fromCodec(ResourceLocation.CODEC.listOf()),
                    TerminalModulesUpdate::modules, TerminalModulesUpdate::new);
    private static final Set<ResourceLocation> CLIENT_MODULES = ConcurrentHashMap.newKeySet();

    public TerminalModulesUpdate {
        modules = List.copyOf(modules);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, TerminalModulesUpdate::handle);
    }

    public static boolean isInstalled(ResourceLocation id) {
        return CLIENT_MODULES.contains(id);
    }

    public static Set<ResourceLocation> installedModules() {
        return Set.copyOf(CLIENT_MODULES);
    }

    private static void handle(TerminalModulesUpdate packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            CLIENT_MODULES.clear();
            CLIENT_MODULES.addAll(new LinkedHashSet<>(packet.modules()));
        });
    }
}
