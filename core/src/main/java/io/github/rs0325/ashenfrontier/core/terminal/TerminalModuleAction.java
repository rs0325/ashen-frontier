package io.github.rs0325.ashenfrontier.core.terminal;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.List;

public record TerminalModuleAction(ResourceLocation module, boolean insert) implements CustomPacketPayload {
    public static final Type<TerminalModuleAction> TYPE = new Type<>(AshenFrontierCore.id("terminal_module_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalModuleAction> STREAM_CODEC =
            StreamCodec.of((buffer, action) -> {
                buffer.writeResourceLocation(action.module());
                buffer.writeBoolean(action.insert());
            }, buffer -> new TerminalModuleAction(buffer.readResourceLocation(), buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, STREAM_CODEC, TerminalModuleAction::handle);
    }

    private static void handle(TerminalModuleAction action, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            boolean changed = action.insert() ? TerminalModules.insert(player, action.module())
                    : TerminalModules.remove(player, action.module());
            if (changed) PacketDistributor.sendToPlayer(player, new TerminalModulesUpdate(
                    List.copyOf(player.getData(TerminalAttachments.MODULES))));
        });
    }
}
