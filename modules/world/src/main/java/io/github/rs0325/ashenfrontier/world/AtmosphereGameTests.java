package io.github.rs0325.ashenfrontier.world;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.profiling.InactiveProfiler;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(AshenFrontierWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AtmosphereGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierWorld.MOD_ID)
    public static void reloadAndSyncAtmosphere(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var resources = server.getResourceManager();
        Map<ResourceLocation, JsonElement> bundled = new HashMap<>();
        SimpleJsonResourceReloadListener.scanDirectory(resources, "biome_atmosphere", new Gson(), bundled);
        var loader = new BiomeAtmosphere.Loader();
        try (var first = new RecordingPlayer(helper, "大気受信1");
             var second = new RecordingPlayer(helper, "大気受信2")) {
            helper.assertTrue(bundled.size() == 12 && BiomeAtmosphere.values().size() == 12,
                    "実リロードで12件の大気定義が読み込まれる");
            var id = AshenFrontierCore.id("polluted_ocean");
            var override = JsonParser.parseString("{\"density\":0.25,\"dust_color\":1193046}");
            loader.apply(Map.of(id, override), resources, InactiveProfiler.INSTANCE);
            helper.assertTrue(BiomeAtmosphere.values().equals(Map.of(id, new BiomeAtmosphere.Values(0.25, 0x123456))),
                    "データパックの上書き値へ置き換え、削除された定義を残さない");
            NeoForge.EVENT_BUS.post(new OnDatapackSyncEvent(server.getPlayerList(), first.player));
            helper.assertTrue(first.updates().size() == 1 && second.updates().isEmpty(),
                    "参加者だけに大気定義を同期する");
            var packet = first.updates().getFirst();
            helper.assertTrue(packet.values().equals(BiomeAtmosphere.values()), "上書きした値を同期する");
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                BiomeAtmosphereUpdate.STREAM_CODEC.encode(buffer, packet);
                helper.assertTrue(packet.equals(BiomeAtmosphereUpdate.STREAM_CODEC.decode(buffer)),
                        "ID・濃度・砂塵色が通信形式で往復する");
            } finally {
                buffer.release();
            }
            first.packets.clear();
            loader.apply(bundled, resources, InactiveProfiler.INSTANCE);
            NeoForge.EVENT_BUS.post(new OnDatapackSyncEvent(server.getPlayerList(), null));
            var expected = new BiomeAtmosphereUpdate(BiomeAtmosphere.values());
            helper.assertTrue(first.updates().equals(List.of(expected)) && second.updates().equals(List.of(expected)),
                    "リロード後は全員へ最新の定義を同期する");
        } finally {
            loader.apply(bundled, resources, InactiveProfiler.INSTANCE);
        }
        helper.succeed();
    }

    private static final class RecordingPlayer implements AutoCloseable {
        private final FakePlayer player;
        private final List<Packet<?>> packets = new ArrayList<>();
        private final Map<UUID, ServerPlayer> online;
        private final List<ServerPlayer> players;

        @SuppressWarnings("unchecked")
        RecordingPlayer(GameTestHelper helper, String name) throws ReflectiveOperationException {
            player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
            new ServerGamePacketListenerImpl(helper.getLevel().getServer(), new Connection(PacketFlow.SERVERBOUND),
                    player, CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { packets.add(packet); }
                @Override public void send(Packet<?> packet, PacketSendListener listener) { packets.add(packet); }
                @Override public boolean hasChannel(ResourceLocation id) { return false; }
            };
            PlayerList list = helper.getLevel().getServer().getPlayerList();
            Field onlineField = PlayerList.class.getDeclaredField("playersByUUID");
            onlineField.setAccessible(true);
            online = (Map<UUID, ServerPlayer>) onlineField.get(list);
            Field playersField = PlayerList.class.getDeclaredField("players");
            playersField.setAccessible(true);
            players = (List<ServerPlayer>) playersField.get(list);
            online.put(player.getUUID(), player);
            players.add(player);
        }

        List<BiomeAtmosphereUpdate> updates() {
            return packets.stream().filter(ClientboundCustomPayloadPacket.class::isInstance)
                    .map(ClientboundCustomPayloadPacket.class::cast).map(ClientboundCustomPayloadPacket::payload)
                    .filter(BiomeAtmosphereUpdate.class::isInstance).map(BiomeAtmosphereUpdate.class::cast).toList();
        }

        @Override public void close() {
            online.remove(player.getUUID());
            players.remove(player);
        }
    }
}
