package io.github.rs0325.ashenfrontier.core.research;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** チームに追加された研究進捗だけをクライアントへ送る。 */
public record ResearchProgressUpdate(String teamId, List<String> conditions, List<String> unlocked,
                                     List<String> knowledge, boolean replace)
        implements CustomPacketPayload {
    public static final Type<ResearchProgressUpdate> TYPE = new Type<>(AshenFrontierCore.id("research_progress"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchProgressUpdate> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeUtf(packet.teamId);
                writeList(buffer, packet.conditions);
                writeList(buffer, packet.unlocked);
                writeList(buffer, packet.knowledge);
                buffer.writeBoolean(packet.replace);
            }, buffer -> new ResearchProgressUpdate(buffer.readUtf(), readList(buffer), readList(buffer),
                    readList(buffer), buffer.readBoolean()));
    private static final Set<String> CLIENT_UNLOCKED = ConcurrentHashMap.newKeySet();
    private static final Set<String> CLIENT_CONDITIONS = ConcurrentHashMap.newKeySet();
    private static final Set<String> CLIENT_KNOWLEDGE = ConcurrentHashMap.newKeySet();

    public ResearchProgressUpdate {
        conditions = List.copyOf(conditions);
        unlocked = List.copyOf(unlocked);
        knowledge = List.copyOf(knowledge);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, ResearchProgressUpdate::handle);
    }
    private static void handle(ResearchProgressUpdate packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (packet.replace) {
                CLIENT_CONDITIONS.clear();
                CLIENT_UNLOCKED.clear();
                CLIENT_KNOWLEDGE.clear();
            }
            CLIENT_CONDITIONS.addAll(packet.conditions);
            CLIENT_UNLOCKED.addAll(packet.unlocked);
            CLIENT_KNOWLEDGE.addAll(packet.knowledge);
        });
    }
    public static boolean isClientUnlocked(ResourceLocation research) {
        return CLIENT_UNLOCKED.contains(research.toString());
    }
    public static boolean isClientConditionComplete(ResourceLocation type, String id) {
        return CLIENT_CONDITIONS.contains(type + ":" + id);
    }
    public static boolean isClientKnowledgeKnown(ResourceLocation document) {
        return CLIENT_KNOWLEDGE.contains(document.toString());
    }
    private static void writeList(RegistryFriendlyByteBuf buffer, List<String> values) {
        buffer.writeVarInt(values.size());
        values.forEach(buffer::writeUtf);
    }
    private static List<String> readList(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) values.add(buffer.readUtf());
        return values;
    }
}
