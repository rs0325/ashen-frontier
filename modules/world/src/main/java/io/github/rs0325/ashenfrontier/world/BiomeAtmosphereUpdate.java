package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;

/** サーバーの大気定義全体でクライアントの定義を置き換える。 */
record BiomeAtmosphereUpdate(Map<ResourceLocation, BiomeAtmosphere.Values> values) implements CustomPacketPayload {
    static final Type<BiomeAtmosphereUpdate> TYPE = new Type<>(AshenFrontierCore.id("biome_atmosphere"));
    static final StreamCodec<RegistryFriendlyByteBuf, BiomeAtmosphereUpdate> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> buffer.writeMap(packet.values, (buf, id) -> buf.writeResourceLocation(id),
                    (buf, value) -> { buf.writeDouble(value.density()); buf.writeInt(value.dustColor()); }),
            buffer -> new BiomeAtmosphereUpdate(buffer.readMap(buf -> buf.readResourceLocation(),
                    buf -> new BiomeAtmosphere.Values(buf.readDouble(), buf.readInt()))));
    private static Map<ResourceLocation, BiomeAtmosphere.Values> clientValues = Map.of();

    BiomeAtmosphereUpdate { values = Map.copyOf(values); }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, BiomeAtmosphereUpdate::handle);
    }

    private static void handle(BiomeAtmosphereUpdate packet, IPayloadContext context) {
        context.enqueueWork(() -> clientValues = packet.values);
    }

    static Map<ResourceLocation, BiomeAtmosphere.Values> clientValues() { return clientValues; }

    static void clearClientValues() { clientValues = Map.of(); }
}
