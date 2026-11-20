package io.github.rs0325.ashenfrontier.core.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ワールド内のチームとプレイヤー所属を永続化する。 */
public final class TeamData extends SavedData {
    private static final Codec<Set<UUID>> UUID_SET_CODEC = UUIDUtil.CODEC.listOf()
            .xmap(HashSet::new, ArrayList::new);
    private static final Codec<List<Membership>> MEMBERSHIPS_CODEC = Membership.CODEC.listOf();
    private static final Codec<TeamData> CODEC = RecordCodecBuilder.create(i -> i.group(
            MEMBERSHIPS_CODEC.fieldOf("memberships").forGetter(TeamData::memberships),
            Codec.unboundedMap(Codec.STRING, Team.CODEC).fieldOf("teams").forGetter(d -> d.teams)
    ).apply(i, TeamData::new));
    private static final Factory<TeamData> FACTORY = new Factory<>(
            TeamData::new, TeamData::load, DataFixTypes.LEVEL);
    private final Map<UUID, String> memberships;
    private final Map<String, Team> teams;

    private TeamData() {
        this(List.of(), Map.of());
    }

    private TeamData(List<Membership> memberships, Map<String, Team> teams) {
        this.memberships = new HashMap<>();
        memberships.forEach(entry -> this.memberships.put(entry.player(), entry.team()));
        this.teams = new HashMap<>(teams);
    }

    private List<Membership> memberships() {
        return memberships.entrySet().stream()
                .map(entry -> new Membership(entry.getKey(), entry.getValue())).toList();
    }

    public static TeamData load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                get(player.getServer()).ensurePersonalTeam(player);
            }
        });
    }

    public static TeamData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "ashen_frontier_teams");
    }

    public Optional<Team> teamFor(UUID player) {
        return Optional.ofNullable(memberships.get(player)).map(teams::get);
    }

    public Team ensurePersonalTeam(ServerPlayer player) {
        return teamFor(player.getUUID()).orElseGet(
                () -> create(player.getUUID(), player.getGameProfile().getName()));
    }

    public Team create(UUID owner, String name) {
        if (teamFor(owner).isPresent()) throw new IllegalStateException("Player already belongs to a team");
        Team team = new Team(UUID.randomUUID().toString(), name, Set.of(owner), Set.of());
        teams.put(team.id(), team);
        memberships.put(owner, team.id());
        setDirty();
        return team;
    }

    public boolean invite(UUID actor, UUID target) {
        Team team = teamFor(actor).orElse(null);
        if (team == null || !team.members().contains(actor) || memberships.containsKey(target)) return false;
        if (team.invites().add(target)) setDirty();
        return true;
    }

    public boolean accept(UUID player) {
        Team team = teams.values().stream().filter(t -> t.invites().contains(player)).findFirst().orElse(null);
        if (team == null || memberships.containsKey(player)) return false;
        team.invites().remove(player);
        team.members().add(player);
        memberships.put(player, team.id());
        setDirty();
        return true;
    }

    /** 脱退者の個人チームを作り、空になった旧チームIDを返す。 */
    public Optional<String> leave(UUID player, String name) {
        Team team = teamFor(player).orElse(null);
        if (team == null) return Optional.empty();
        team.members().remove(player);
        memberships.remove(player);
        Optional<String> disbanded = Optional.empty();
        if (team.members().isEmpty()) {
            teams.remove(team.id());
            disbanded = Optional.of(team.id());
        }
        create(player, name);
        setDirty();
        return disbanded;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, this).getOrThrow();
    }

    private record Membership(UUID player, String team) {
        private static final Codec<Membership> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Membership::player),
                Codec.STRING.fieldOf("team").forGetter(Membership::team)
        ).apply(i, Membership::new));
    }

    public record Team(String id, String name, Set<UUID> members, Set<UUID> invites) {
        public Team {
            members = new HashSet<>(members);
            invites = new HashSet<>(invites);
        }

        private static final Codec<Team> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Team::id),
                Codec.STRING.fieldOf("name").forGetter(Team::name),
                UUID_SET_CODEC.fieldOf("members").forGetter(Team::members),
                UUID_SET_CODEC.fieldOf("invites").forGetter(Team::invites)
        ).apply(i, Team::new));
    }
}
