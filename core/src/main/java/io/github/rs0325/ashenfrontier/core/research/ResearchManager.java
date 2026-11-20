package io.github.rs0325.ashenfrontier.core.research;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.team.TeamData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.function.BiPredicate;

/** データパックの研究定義を読み込み、条件報告と解放を管理する。 */
public final class ResearchManager {
    private static final Map<ResourceLocation, BiPredicate<ServerPlayer, String>> CONDITION_TYPES = new HashMap<>();
    private static ResearchTree.Resolution tree = ResearchTree.resolve(Map.of());
    private static final ResourceLocation DOCUMENT_CONDITION = AshenFrontierCore.id("document");
    private ResearchManager() {}
    public static void register(IEventBus modBus) {
        modBus.addListener(ResearchProgressUpdate::register);
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(new Loader()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) sync(player);
        });
    }
    /** 条件種別の達成判定を登録する。条件IDはイベント発生側が報告する。 */
    public static void registerConditionType(ResourceLocation type, BiPredicate<ServerPlayer, String> validator) {
        CONDITION_TYPES.put(type, validator);
    }
    public static ResearchTree.Resolution tree() { return tree; }
    public static void sync(ServerPlayer player) {
        TeamData.get(player.getServer()).teamFor(player.getUUID()).ifPresentOrElse(team -> {
            ResearchProgress progress = ResearchProgress.get(player.getServer());
            sendToTeam(player.getServer(), team, List.copyOf(progress.conditions(team.id())),
                    List.copyOf(progress.unlocked(team.id())), List.copyOf(progress.knowledge(team.id())),
                    true, player.getUUID());
        }, () -> PacketDistributor.sendToPlayer(player,
                new ResearchProgressUpdate("", List.of(), List.of(), List.of(), true)));
    }
    /** プレイヤー所属チームの研究解放状態を返す。サーバー側から呼び出す。 */
    public static boolean isUnlocked(ServerPlayer player, ResourceLocation research) {
        return TeamData.get(player.getServer()).teamFor(player.getUUID())
                .map(team -> ResearchProgress.get(player.getServer()).isUnlocked(team.id(), research.toString()))
                .orElse(false);
    }
    /** 指定チームの研究解放状態を返す。 */
    public static boolean isUnlocked(TeamData.Team team, ResourceLocation research, MinecraftServer server) {
        return ResearchProgress.get(server).isUnlocked(team.id(), research.toString());
    }
    public static void report(ServerPlayer player, ResourceLocation type, String conditionId) {
        BiPredicate<ServerPlayer, String> validator = CONDITION_TYPES.get(type);
        if (validator == null || !validator.test(player, conditionId)) return;
        TeamData.Team team = TeamData.get(player.getServer()).ensurePersonalTeam(player);
        ResearchProgress progress = ResearchProgress.get(player.getServer());
        recordAndShare(player.getServer(), team, progress, type, conditionId, null);
    }
    public static void reportKnowledge(ServerPlayer player, ResourceLocation document) {
        TeamData.Team team = TeamData.get(player.getServer()).ensurePersonalTeam(player);
        ResearchProgress progress = ResearchProgress.get(player.getServer());
        boolean newKnowledge = progress.know(team.id(), document.toString());
        recordAndShare(player.getServer(), team, progress, DOCUMENT_CONDITION,
                document.toString(), newKnowledge ? document.toString() : null);
    }

    /** チームを脱退させ、空になった旧チームの研究進捗も削除する。 */
    public static boolean leaveTeam(ServerPlayer player) {
        return leaveTeam(player.getServer(), player.getUUID(), player.getGameProfile().getName());
    }

    /** オフラインプレイヤーも含め、プレイヤーを個人チームへ移す。 */
    public static boolean leaveTeam(MinecraftServer server, UUID player, String name) {
        TeamData data = TeamData.get(server);
        if (data.teamFor(player).isEmpty()) return false;
        var disbanded = data.leave(player, name);
        disbanded.ifPresent(id -> ResearchProgress.get(server).removeTeam(id));
        return true;
    }

    private static void recordAndShare(MinecraftServer server, TeamData.Team team,
                                       ResearchProgress progress, ResourceLocation type,
                                       String conditionId, String knowledge) {
        String key = type + ":" + conditionId;
        boolean newCondition = !progress.hasCondition(team.id(), key);
        List<String> unlocked = progress.recordCondition(team.id(), key, tree);
        if (newCondition || !unlocked.isEmpty() || knowledge != null) {
            sendToTeam(server, team, newCondition ? List.of(key) : List.of(), unlocked,
                    knowledge == null ? List.of() : List.of(knowledge), false, null);
        }
    }

    private static void sendToTeam(MinecraftServer server, TeamData.Team team,
                                   Set<String> conditions, Set<String> unlocked,
                                   Set<String> knowledge, boolean replace) {
        sendToTeam(server, team, List.copyOf(conditions), List.copyOf(unlocked),
                List.copyOf(knowledge), replace, null);
    }

    private static void sendToTeam(MinecraftServer server, TeamData.Team team,
                                   List<String> conditions, List<String> unlocked,
                                   List<String> knowledge, boolean replace, UUID onlyMember) {
        ResearchProgressUpdate update = new ResearchProgressUpdate(team.id(), conditions, unlocked,
                knowledge, replace);
        for (UUID member : team.members()) {
            if (onlyMember != null && !onlyMember.equals(member)) continue;
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online == null) continue;
            PacketDistributor.sendToPlayer(online, update);
            for (String id : unlocked) {
                ResearchTree.Definition definition = tree.definitions().get(id);
                online.sendSystemMessage(Component.translatable("research.ashen_frontier.unlocked",
                        Component.translatable(definition.name())));
            }
        }
    }
    private static final class Loader extends SimpleJsonResourceReloadListener {
        Loader() { super(new Gson(), "ashen_frontier/research"); }
        @Override protected void apply(Map<ResourceLocation, JsonElement> json,
                                       ResourceManager resources, ProfilerFiller profiler) {
            Map<String, ResearchTree.Definition> definitions = new LinkedHashMap<>();
            json.forEach((id, element) -> {
                try {
                    JsonObject value = element.getAsJsonObject();
                    String researchId = id.toString();
                    List<String> prerequisites = locations(value.getAsJsonArray("prerequisites"));
                    List<ResearchTree.Condition> conditions = new ArrayList<>();
                    for (JsonElement condition : value.getAsJsonArray("conditions")) {
                        JsonObject c = condition.getAsJsonObject();
                        String conditionType = ResourceLocation.parse(c.get("type").getAsString()).toString();
                        conditions.add(new ResearchTree.Condition(conditionType, c.get("id").getAsString()));
                    }
                    definitions.put(researchId, new ResearchTree.Definition(researchId, value.get("name").getAsString(),
                            value.get("description").getAsString(), prerequisites, conditions,
                            locations(value.getAsJsonArray("unlocks"))));
                } catch (RuntimeException exception) {
                    AshenFrontierCore.LOGGER.error("研究定義 {} を読み込めません", id, exception);
                }
            });
            tree = ResearchTree.resolve(definitions);
            tree.invalid().forEach(id -> AshenFrontierCore.LOGGER.error("前提が不正な研究 {} を無効化しました", id));
        }
        private static List<String> locations(JsonArray array) {
            List<String> result = new ArrayList<>();
            if (array != null) {
                for (JsonElement element : array) {
                    result.add(ResourceLocation.parse(element.getAsString()).toString());
                }
            }
            return result;
        }
    }
}
