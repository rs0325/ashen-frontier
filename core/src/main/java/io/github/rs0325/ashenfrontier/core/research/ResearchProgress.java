package io.github.rs0325.ashenfrontier.core.research;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.util.datafix.DataFixTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** チームごとの達成条件、解放研究、資料知識を保存する。 */
public final class ResearchProgress extends SavedData {
    private static final Codec<ResearchProgress> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf())
                    .fieldOf("conditions").forGetter(d -> lists(d.conditions)),
            Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf())
                    .fieldOf("unlocked").forGetter(d -> lists(d.unlocked)),
            Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf())
                    .fieldOf("knowledge").forGetter(d -> lists(d.knowledge))
    ).apply(i, (conditions, unlocked, knowledge) -> new ResearchProgress(conditions, unlocked, knowledge)));
    private static final Factory<ResearchProgress> FACTORY = new Factory<>(
            ResearchProgress::new, ResearchProgress::load, DataFixTypes.LEVEL);
    private final Map<String, Set<String>> conditions = new HashMap<>(), unlocked = new HashMap<>(),
            knowledge = new HashMap<>();
    private ResearchProgress() {}
    private ResearchProgress(Map<String, List<String>> c, Map<String, List<String>> u,
                             Map<String, List<String>> k) {
        copy(c, conditions); copy(u, unlocked); copy(k, knowledge);
    }
    public static ResearchProgress load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }
    private static Map<String, List<String>> lists(Map<String, Set<String>> values) {
        Map<String, List<String>> result = new HashMap<>();
        values.forEach((team, items) -> result.put(team, List.copyOf(items)));
        return result;
    }
    private static void copy(Map<String, List<String>> source, Map<String, Set<String>> target) {
        source.forEach((team, items) -> target.put(team, new HashSet<>(items)));
    }
    public static ResearchProgress get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "ashen_frontier_research");
    }
    public boolean hasCondition(String team, String value) {
        return conditions.getOrDefault(team, Set.of()).contains(value);
    }
    public boolean isUnlocked(String team, String research) {
        return unlocked.getOrDefault(team, Set.of()).contains(research);
    }
    public Set<String> conditions(String team) { return Set.copyOf(conditions.getOrDefault(team, Set.of())); }
    public Set<String> unlocked(String team) { return Set.copyOf(unlocked.getOrDefault(team, Set.of())); }
    public Set<String> knowledge(String team) { return Set.copyOf(knowledge.getOrDefault(team, Set.of())); }
    public boolean know(String team, String document) {
        boolean changed = knowledge.computeIfAbsent(team, key -> new HashSet<>()).add(document);
        if (changed) setDirty();
        return changed;
    }
    public void removeTeam(String team) {
        boolean changed = conditions.remove(team) != null;
        changed |= unlocked.remove(team) != null;
        changed |= knowledge.remove(team) != null;
        if (changed) setDirty();
    }
    public boolean record(String team, String condition) {
        boolean changed = conditions.computeIfAbsent(team, key -> new HashSet<>()).add(condition);
        if (changed) setDirty();
        return changed;
    }
    public boolean unlock(String team, String research) {
        boolean changed = unlocked.computeIfAbsent(team, key -> new HashSet<>()).add(research);
        if (changed) setDirty();
        return changed;
    }
    public List<String> recordCondition(String team, String condition, ResearchTree.Resolution tree) {
        if (!record(team, condition)) return List.of();
        List<String> newlyUnlocked = new ArrayList<>();
        for (String id : tree.order()) {
            ResearchTree.Definition definition = tree.definitions().get(id);
            if (isUnlocked(team, id)
                    || definition.prerequisites().stream().anyMatch(p -> !isUnlocked(team, p))) continue;
            if (definition.conditions().stream().allMatch(c -> hasCondition(team, c.type() + ":" + c.id()))
                    && unlock(team, id)) newlyUnlocked.add(id);
        }
        return List.copyOf(newlyUnlocked);
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, this).getOrThrow();
    }
}
