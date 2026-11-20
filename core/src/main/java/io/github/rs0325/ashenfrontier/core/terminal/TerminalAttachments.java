package io.github.rs0325.ashenfrontier.core.terminal;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public final class TerminalAttachments {
    public static final Codec<Set<ResourceLocation>> MODULES_CODEC = Codec.list(ResourceLocation.CODEC).xmap(
            (List<ResourceLocation> values) -> new LinkedHashSet<>(values),
            (Set<ResourceLocation> values) -> List.copyOf(values));
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "ashen_frontier_core");
    public static final Supplier<AttachmentType<Set<ResourceLocation>>> MODULES = TYPES.register("terminal_modules",
            () -> AttachmentType.<Set<ResourceLocation>>builder(
                    (Supplier<Set<ResourceLocation>>) HashSet::new)
                    .serialize(MODULES_CODEC)
                    .copyOnDeath().build());

    private TerminalAttachments() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        bus.addListener(TerminalModulesUpdate::register);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player,
                        new TerminalModulesUpdate(List.copyOf(player.getData(MODULES))));
            }
        });
    }
}
