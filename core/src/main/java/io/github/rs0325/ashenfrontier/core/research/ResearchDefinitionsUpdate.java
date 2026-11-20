package io.github.rs0325.ashenfrontier.core.research;

import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** サーバーで有効な研究の表示用定義を前提順で送り、クライアントの一覧を置き換える。 */
public record ResearchDefinitionsUpdate(List<Definition> definitions) implements CustomPacketPayload {
    public static final Type<ResearchDefinitionsUpdate> TYPE =
            new Type<>(AshenFrontierCore.id("research_definitions"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchDefinitionsUpdate> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> buffer.writeCollection(packet.definitions, (buf, definition) -> {
                buf.writeUtf(definition.id());
                buf.writeUtf(definition.name());
                buf.writeUtf(definition.description());
                buf.writeCollection(definition.prerequisites(), (b, value) -> b.writeUtf(value));
                buf.writeCollection(definition.conditions(), (b, condition) -> {
                    b.writeUtf(condition.type());
                    b.writeUtf(condition.id());
                });
            }), buffer -> new ResearchDefinitionsUpdate(buffer.readList(buf -> new Definition(
                    buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readList(b -> b.readUtf()),
                    buf.readList(b -> new ResearchTree.Condition(b.readUtf(), b.readUtf()))))));
    private static List<Definition> clientDefinitions = List.of();

    public ResearchDefinitionsUpdate {
        definitions = List.copyOf(definitions);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, ResearchDefinitionsUpdate::handle);
    }

    private static void handle(ResearchDefinitionsUpdate packet, IPayloadContext context) {
        context.enqueueWork(() -> clientDefinitions = packet.definitions);
    }

    /** 現在接続しているサーバーから受信した研究定義を前提順で返す。 */
    public static List<Definition> clientDefinitions() { return clientDefinitions; }

    /** 受信済みの研究名を返す。定義がないIDはそのまま表示する。 */
    public static Component clientName(ResourceLocation id) {
        return clientDefinitions.stream().filter(definition -> definition.id().equals(id.toString()))
                .findFirst().map(definition -> Component.translatable(definition.name()))
                .orElseGet(() -> Component.literal(id.toString()));
    }

    public record Definition(String id, String name, String description,
                             List<String> prerequisites, List<ResearchTree.Condition> conditions) {
        public Definition {
            prerequisites = List.copyOf(prerequisites);
            conditions = List.copyOf(conditions);
        }

        static Definition from(ResearchTree.Definition definition) {
            return new Definition(definition.id(), definition.name(), definition.description(),
                    definition.prerequisites(), definition.conditions());
        }
    }
}
