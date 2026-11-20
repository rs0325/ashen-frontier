package io.github.rs0325.ashenfrontier.core.research;

import com.mojang.authlib.GameProfile;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.team.TeamData;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(AshenFrontierCore.MOD_ID)
@PrefixGameTestTemplate(false)
public class ResearchGameTests {
    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void newUnlockNotifiesTeamOnlyOnce(GameTestHelper helper) throws Exception {
        try (var owner = new RecordingPlayer(helper, "unlock-owner");
             var member = new RecordingPlayer(helper, "unlock-member")) {
            var server = helper.getLevel().getServer();
            var data = TeamData.get(server);
            data.ensurePersonalTeam(owner.player);
            data.ensurePersonalTeam(member.player);
            data.invite(owner.player.getUUID(), member.player.getUUID());
            ResearchManager.acceptTeam(server, member.player.getUUID());
            ResourceLocation type = ResourceLocation.parse("ashen_frontier:game_test");
            ResearchManager.registerConditionType(type, (player, id) -> id.equals("surveyed"));
            ResearchManager.reportKnowledge(owner.player, ResourceLocation.parse("ashen_frontier:game_test_document"));
            owner.packets.clear();
            member.packets.clear();
            ResearchManager.report(owner.player, type, "surveyed");
            for (var player : List.of(owner, member)) {
                helper.assertTrue(player.updates().size() == 1 && !player.updates().getFirst().replace()
                        && player.updates().getFirst().unlocked().contains("ashen_frontier:game_test_survey"),
                        "新規解放の差分がチーム全員へ届きません");
                helper.assertTrue(player.packets.stream().filter(ClientboundSystemChatPacket.class::isInstance)
                        .count() == 1, "新規解放が一度だけ通知されません");
                player.packets.clear();
            }
            ResearchManager.report(owner.player, type, "surveyed");
            helper.assertTrue(owner.packets.isEmpty() && member.packets.isEmpty(), "同じ解放を再通知しました");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void loginSyncSkipsMissingDefinitionsWithoutNotifications(GameTestHelper helper) throws Exception {
        try (var player = new RecordingPlayer(helper, "研究同期")) {
            var server = helper.getLevel().getServer();
            var team = TeamData.get(server).ensurePersonalTeam(player.player);
            var progress = ResearchProgress.get(server);
            String valid = ResearchManager.tree().order().getFirst();
            progress.unlock(team.id(), valid);
            progress.unlock(team.id(), "test:removed_research");
            progress.record(team.id(), "test:condition");
            progress.know(team.id(), "test:document");
            for (int i = 0; i < 2; i++) {
                player.packets.clear();
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player.player));
                var updates = player.updates();
                helper.assertTrue(updates.size() == 1, "ログインで全量同期が送られません");
                var update = updates.getFirst();
                helper.assertTrue(update.replace() && update.teamId().equals(team.id())
                        && update.unlocked().equals(List.of(valid))
                        && update.conditions().contains("test:condition")
                        && update.knowledge().contains("test:document"), "全量同期の内容が不正です");
                helper.assertTrue(player.packets.stream().allMatch(ClientboundCustomPayloadPacket.class::isInstance),
                        "全量同期で解放通知が再送されました");
                helper.assertTrue(progress.isUnlocked(team.id(), "test:removed_research"),
                        "同期によって定義のない研究が保存データから消えました");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void repairSyncsOnlyOnlineTeamMembers(GameTestHelper helper) throws Exception {
        try (var owner = new RecordingPlayer(helper, "repair-owner");
             var member = new RecordingPlayer(helper, "repair-member");
             var outsider = new RecordingPlayer(helper, "repair-outsider")) {
            var server = helper.getLevel().getServer();
            var data = TeamData.get(server);
            var team = data.ensurePersonalTeam(owner.player);
            data.ensurePersonalTeam(member.player);
            data.ensurePersonalTeam(outsider.player);
            data.invite(owner.player.getUUID(), member.player.getUUID());
            ResearchManager.acceptTeam(server, member.player.getUUID());
            UUID offline = UUID.randomUUID();
            data.invite(owner.player.getUUID(), offline);
            data.accept(offline);
            var progress = ResearchProgress.get(server);
            var definition = ResearchManager.tree().definitions().get(ResearchManager.tree().order().getFirst());
            for (var condition : definition.conditions()) {
                progress.record(team.id(), condition.type() + ":" + condition.id());
            }
            helper.assertTrue(!progress.isUnlocked(team.id(), definition.id()), "修復前から解放されています");
            int result = server.getCommands().getDispatcher().execute(
                    "ashenfrontier rescue repair " + owner.player.getGameProfile().getName(),
                    server.createCommandSourceStack().withPermission(2));
            helper.assertTrue(result == 1 && progress.isUnlocked(team.id(), definition.id()), "救済修復に失敗しました");
            helper.assertTrue(owner.updates().size() == 1 && member.updates().size() == 1,
                    "オンラインのチーム全員に全量同期されません");
            var update = owner.updates().getFirst();
            helper.assertTrue(update.equals(member.updates().getFirst()) && update.replace()
                    && update.unlocked().contains(definition.id()), "修復後の同期内容が一致しません");
            helper.assertTrue(outsider.updates().isEmpty(), "別チームに研究が同期されました");
            helper.assertTrue(owner.packets.size() == 1 && member.packets.size() == 1,
                    "修復の全量同期で解放通知が送られました");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = AshenFrontierCore.MOD_ID, timeoutTicks = 100)
    public static void definitionsSyncOnJoinAndReload(GameTestHelper helper) throws Exception {
        try (var first = new RecordingPlayer(helper, "定義受信1");
             var second = new RecordingPlayer(helper, "定義受信2")) {
            var players = helper.getLevel().getServer().getPlayerList();
            NeoForge.EVENT_BUS.post(new OnDatapackSyncEvent(players, first.player));
            helper.assertTrue(first.definitions().size() == 1 && second.definitions().isEmpty(),
                    "参加時の定義同期先が不正です");
            var packet = first.definitions().getFirst();
            helper.assertTrue(packet.definitions().stream().map(ResearchDefinitionsUpdate.Definition::id).toList()
                    .equals(ResearchManager.tree().order()), "有効な研究が前提順に同期されません");
            for (var definition : packet.definitions()) {
                var expected = ResearchManager.tree().definitions().get(definition.id());
                helper.assertTrue(definition.equals(ResearchDefinitionsUpdate.Definition.from(expected)),
                        "研究の表示情報が欠落しています");
            }
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            try {
                ResearchDefinitionsUpdate.STREAM_CODEC.encode(buffer, packet);
                helper.assertTrue(packet.equals(ResearchDefinitionsUpdate.STREAM_CODEC.decode(buffer)),
                        "研究定義が通信形式で往復しません");
            } finally {
                buffer.release();
            }
            first.packets.clear();
            NeoForge.EVENT_BUS.post(new OnDatapackSyncEvent(players, null));
            helper.assertTrue(first.definitions().equals(List.of(packet))
                    && second.definitions().equals(List.of(packet)), "再読み込みで全員へ同期されません");
            helper.assertTrue(first.updates().size() == 1 && second.updates().size() == 1,
                    "再読み込みで研究進捗が更新されません");
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
            // 通信対象だけを登録し、実際の接続やワールドへのプレイヤー追加は行わない。
            Field onlineField = PlayerList.class.getDeclaredField("playersByUUID");
            onlineField.setAccessible(true);
            online = (Map<UUID, ServerPlayer>) onlineField.get(list);
            Field playersField = PlayerList.class.getDeclaredField("players");
            playersField.setAccessible(true);
            players = (List<ServerPlayer>) playersField.get(list);
            online.put(player.getUUID(), player);
            players.add(player);
        }

        List<ResearchProgressUpdate> updates() {
            return packets.stream().filter(ClientboundCustomPayloadPacket.class::isInstance)
                    .map(ClientboundCustomPayloadPacket.class::cast).map(ClientboundCustomPayloadPacket::payload)
                    .filter(ResearchProgressUpdate.class::isInstance).map(ResearchProgressUpdate.class::cast).toList();
        }

        List<ResearchDefinitionsUpdate> definitions() {
            return packets.stream().filter(ClientboundCustomPayloadPacket.class::isInstance)
                    .map(ClientboundCustomPayloadPacket.class::cast).map(ClientboundCustomPayloadPacket::payload)
                    .filter(ResearchDefinitionsUpdate.class::isInstance).map(ResearchDefinitionsUpdate.class::cast).toList();
        }

        @Override public void close() {
            online.remove(player.getUUID());
            players.remove(player);
        }
    }
}
