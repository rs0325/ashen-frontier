package io.github.rs0325.ashenfrontier.climate.room;

import com.google.gson.Gson;
import io.github.rs0325.ashenfrontier.core.environment.CombustionEvent;
import io.github.rs0325.ashenfrontier.core.environment.CombustionSourceQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;

/** C06の保存所有者。屋外入力は#135から明示的に接続する。M4のEnvironmentQueryを再照会しない。 */
public final class RoomAir extends SavedData {
    public static final String ID = "ashen_frontier_room_air";
    public enum Status { INDOOR, OUTDOOR, SCANNING, UNLOADED, LIMIT, BLOCKED, MISSING_BOUNDARY, HISTORY_MISSING, DISABLED }
    /** 局所の幾何履歴の精度。物理モデル全体の数値誤差を表す値ではない。 */
    public enum Accuracy { OBSERVED, APPROXIMATE, UNKNOWN }
    public record Boundary(long from, long to, RoomPhysics.Air air) {}
    /** 要求全域を過不足なく覆う、一定係数の半開区間列を提供する。空は未接続/履歴不足。 */
    @FunctionalInterface public interface Outside {
        Optional<List<Boundary>> intervals(ServerLevel level, BlockPos airPosition, long from, long to);
    }
    /** APPROXIMATEも計算可能な分類であり、未確認とは区別する。精度は局所幾何履歴に対する値。 */
    public record Sample(Status status, Optional<RoomPhysics.State> state, boolean covered, boolean ventilated,
                         double volumeM3, double flowM3S, Accuracy accuracy) {
        public Component reason() { return Component.translatable("room_status.ashen_frontier_climate." + status.name().toLowerCase(Locale.ROOT)); }
        public boolean confirmed() { return status == Status.INDOOR || status == Status.OUTDOOR; }
    }
    private static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.IntValue VISITS;
    static {
        var b = new ModConfigSpec.Builder();
        ENABLED = b.comment("室内モデルを無効にしても換気済み/安全とは判定しない。").define("enabled", true);
        VISITS = b.defineInRange("visitsPerWork", 256, 1, 4096);
        SPEC = b.build();
    }
    private record Fuel(double carbon, double hydrogen, double oxygen, double coKgPerKg, String source) {}
    private static final Map<String, Fuel> FUELS = loadFuels();
    private final ServerLevel level;
    private Outside outside;
    private final SourceReceipts receipts = new SourceReceipts();
    private final List<Region> regions = new ArrayList<>();
    private final Map<Long, List<Region>> byChunk = new HashMap<>();
    private final Map<Long, Set<Region>> skyColumns = new HashMap<>();
    private static final class Outdoor {
        final RoomPast.Model model;
        final Set<ChunkPos> chunks;
        Outdoor(RoomPast.Model model,Set<ChunkPos> chunks) { this.model=model;this.chunks=chunks; }
    }
    private final Map<Long, Outdoor> outdoors = new HashMap<>();
    private final Map<Long, Set<Outdoor>> outdoorChunks = new HashMap<>();
    private final Map<Long, Registration> registrations = new HashMap<>();
    private final Map<Long, RoomSearch> jobs = new LinkedHashMap<>();
    private final RoomHistory history = new RoomHistory();
    private RoomPast past = new RoomPast();
    private final RoomHistory inputs = new RoomHistory();
    private final Map<Integer, Region> regionIds = new HashMap<>();
    private int nextRegionId;
    private static final RoomPhysics.Amount ZERO = new RoomPhysics.Amount(0, 0, 0, 0);
    private record Snapshot(RoomPhysics.State state, RoomPhysics.Amount exchange) {}
    private record Origin(long from, long until, double volume, int walls, int openings, double height, double temperature) {}
    private record Stored(RoomPhysics.Amount amount, RoomPhysics.Amount exchange, Origin origin) {}
    private final Map<Long, Stored> displaced = new HashMap<>();
    private RoomHistory pending = new RoomHistory();
    private final Map<Long, Long> changedChunks = new HashMap<>();

    private static final class Region {
        final int id;
        final long from;
        long replayFrom;
        long to = Long.MAX_VALUE;
        final long[] cells;
        final int walls;
        final int openings;
        final double height;
        final Map<Integer, Integer> parents;
        final Map<Long, Double> groups = new HashMap<>();
        long anchor;
        int recoveredCells;
        RoomPhysics.Amount recovered = ZERO;
        RoomPhysics.Amount recoveredExchange = ZERO;
        RoomPhysics.Amount adjustment = ZERO;
        RoomPhysics.Amount adjustmentExchange = ZERO;
        long observedFrom;
        final Set<ChunkPos> chunks;
        final NavigableMap<Long, Snapshot> checkpoints = new TreeMap<>();
        final long[] portals;
        boolean ready;
        RoomPast.Model pastModel;
        Map.Entry<Long, Snapshot> committed;
        Region(int id, long from, long[] cells, int walls, int openings, double height, Map<Integer, Integer> parents,
               Set<ChunkPos> chunks, long[] portals) {
            this.id = id; this.from = from; this.replayFrom = from; this.cells = cells; this.walls = walls; this.openings = openings;
            this.height = height; this.parents = parents; this.observedFrom = from;
            this.chunks = chunks; this.portals = portals;
            this.pastModel = new RoomPast.Model(id, from, cells, walls, openings, height);
        }
        boolean contains(long cell) { return Arrays.binarySearch(cells, cell) >= 0; }
        boolean receives(long position) { return weight(position) > 0; }
        double weight(long position) { return groups.getOrDefault(position, contains(position) ? 1.0 : 0.0); }
    }

    private RoomAir(ServerLevel level) { this.level = level; }
    public static RoomAir get(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("室内操作はサーバースレッド専用です");
        return level.getDataStorage().computeIfAbsent(new Factory<>(() -> new RoomAir(level),
                (tag, registries) -> load(level, tag), null), ID);
    }
    public static void install(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC, "ashen_frontier_rooms-server.toml");
        NeoForge.EVENT_BUS.addListener((CombustionSourceQuery query) -> {
            query.provide(get(query.level()).receipts.lastSequence(query.sourceId()));
            for (var serverLevel : query.level().getServer().getAllLevels())
                if (serverLevel != query.level()) query.provide(get(serverLevel).receipts.lastSequence(query.sourceId()));
        });
        NeoForge.EVENT_BUS.addListener((CombustionEvent event) -> {
            if (get(event.level()).receive(event) == SourceReceipts.Result.CONFLICT)
                throw new IllegalArgumentException("同じ発生源・通知番号の内容が異なります");
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.NeighborNotifyEvent e) -> {
            if (e.getLevel() instanceof ServerLevel server) get(server).changed(e.getPos());
        });
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post e) -> {
            if (e.getLevel() instanceof ServerLevel server) {
                var storage = server.getDataStorage().get(new Factory<>(() -> new RoomAir(server),
                        (tag, registries) -> load(server, tag), null), ID);
                if (storage != null && server.getGameTime() % 5 == 0) storage.work();
            }
        });
    }

    /** #135が実屋外履歴を接続する。nullは切断。既定の気温・気圧・ガスは持たない。 */
    public void outside(Outside provider) {
        if (outside != null) regions.forEach(r -> r.checkpoints.clear());
        outside = provider;
        if (provider != null) { refreshPast(); flushPending(); }
    }

    private Accuracy accuracy(BlockPos pos,long tick) {
        return past.approximate(pos.asLong(),tick) ? Accuracy.APPROXIMATE : Accuracy.OBSERVED;
    }
    private Sample outdoorSample(BlockPos pos,long tick) {
        return boundary(pos,tick,tick).map(rows -> new Sample(Status.OUTDOOR,
                Optional.of(RoomPhysics.State.ambient(rows.getFirst().air)),false,true,0,0,accuracy(pos,tick)))
                .orElseGet(() -> unknown(Status.MISSING_BOUNDARY));
    }
    private void watchOutdoor(RoomPast.Model model) {
        var chunks = new HashSet<ChunkPos>();
        for (long cell : model.watch) chunks.add(new ChunkPos(BlockPos.of(cell)));
        var record = new Outdoor(model,chunks);
        outdoors.put(model.cells[0],record);
        for (var chunk : chunks) outdoorChunks.computeIfAbsent(chunk.toLong(),p -> new HashSet<>()).add(record);
    }
    private void closeOutdoor(Outdoor record,long tick) {
        past.end(record.model,tick);
        outdoors.remove(record.model.cells[0]);
        for (var chunk : record.chunks) {
            var records = outdoorChunks.get(chunk.toLong());
            if (records != null) { records.remove(record); if (records.isEmpty()) outdoorChunks.remove(chunk.toLong()); }
        }
        setDirty();
    }
    private void observeOutdoor(RoomSearch search,long tick) {
        long cell = search.origin.asLong();
        var model = past.outdoor(cell,tick);
        if (model == null) {
            past.observeOutdoor(nextRegionId++,tick,cell,search.visited.toLongArray(),RoomSearch.exposed(level,search.origin));
            model = past.outdoor(cell,tick); setDirty();
        }
        if (!outdoors.containsKey(cell)) watchOutdoor(model);
        var registration = registrations.remove(cell);
        if (registration != null) registration.discard();
        jobs.remove(cell);
    }
    private void changedOutdoor(BlockPos pos,long tick) {
        var records = outdoorChunks.get(chunk(pos.asLong()));
        if (records == null) return;
        for (var record : List.copyOf(records)) {
            var origin = BlockPos.of(record.model.cells[0]);
            boolean above = origin.getX()==pos.getX() && origin.getZ()==pos.getZ() && pos.getY()>=origin.getY();
            if (above || Arrays.binarySearch(record.model.watch,pos.asLong())>=0) {
                if (record.model.sky && level.hasChunkAt(origin) && RoomSearch.passable(level,origin) && RoomSearch.exposed(level,origin)) continue;
                closeOutdoor(record,tick);
            }
        }
    }

    /** 部屋探索を要求し、現在または過去の状態を返す。airPositionは設備直上/生理の目位置の空間。 */
    public Sample sample(BlockPos airPosition, long tick) {
        flushPending();
        if (tick < 0 || tick > level.getGameTime()) throw new IllegalArgumentException("室内照会時刻が不正です");
        if (!ENABLED.get()) return unknown(Status.DISABLED);
        if (tick == level.getGameTime() && !level.hasChunkAt(airPosition)) return unknown(Status.UNLOADED);
        Region region = regions.stream().filter(r -> r.ready && r.from <= tick && tick < r.to && r.contains(airPosition.asLong()))
                .max(Comparator.comparingInt(r -> r.id)).orElse(null);
        if (region == null && tick < level.getGameTime()) {
            if (past.outdoor(airPosition.asLong(),tick) != null) return outdoorSample(airPosition,tick);
            var historical = past.historical(airPosition.asLong(), tick, inputs.entries(), this::boundary);
            if (historical.isPresent()) {
                var value = historical.get();
                var out = boundary(airPosition, tick, tick);
                if (out.isEmpty()) return unknown(Status.MISSING_BOUNDARY);
                return new Sample(Status.INDOOR, Optional.of(value.state()), true, value.model().openings > 0,
                        value.model().cells.length, RoomPhysics.flow(value.model().openings, value.model().height,
                        value.state().temperatureK(), out.get().getFirst().air),accuracy(airPosition,tick));
            }
            return unknown(past.known(airPosition.asLong(), tick) ? Status.MISSING_BOUNDARY : Status.HISTORY_MISSING);
        }
        var observed = outdoors.get(airPosition.asLong());
        if (region == null && tick == level.getGameTime() && observed != null) {
            if (observed.chunks.stream().anyMatch(c -> !level.hasChunk(c.x,c.z))) return unknown(Status.UNLOADED);
            if (!RoomSearch.passable(level,airPosition) || observed.model.sky && !RoomSearch.exposed(level,airPosition))
                closeOutdoor(observed,tick);
            else return outdoorSample(airPosition,tick);
        }
        if (region == null && tick == level.getGameTime() && level.hasChunkAt(airPosition)
                && RoomSearch.passable(level, airPosition) && RoomSearch.exposed(level, airPosition)) {
            var observation = new RoomSearch(airPosition,tick);
            observation.status = RoomSearch.Status.OUTDOOR; observation.visited.add(airPosition.asLong());
            observeOutdoor(observation,tick);
            return outdoorSample(airPosition,tick);
        }
        if (region == null) {
            if (tick < level.getGameTime() && !jobs.containsKey(airPosition.asLong())) return unknown(Status.HISTORY_MISSING);
            var priorJob = jobs.get(airPosition.asLong());
            if (priorJob != null && priorJob.status == RoomSearch.Status.OUTDOOR && tick == level.getGameTime()) {
                if (!level.hasChunkAt(airPosition)) return unknown(Status.UNLOADED);
                if (!RoomSearch.exposed(level, airPosition)) jobs.remove(airPosition.asLong());
            }
            if (priorJob != null && priorJob.status == RoomSearch.Status.UNLOADED
                    && priorJob.visited.stream().allMatch(cell -> level.hasChunkAt(BlockPos.of(cell)))) jobs.remove(airPosition.asLong());
            var search = jobs.computeIfAbsent(airPosition.asLong(), p -> new RoomSearch(airPosition, tick));
            if (search.status == RoomSearch.Status.OUTDOOR) {
                observeOutdoor(search,tick); return outdoorSample(airPosition,tick);
            }
            var registration = registrations.get(airPosition.asLong());
            if (registration != null) return unknown(registration.boundaryMissing ? Status.MISSING_BOUNDARY : Status.SCANNING);
            if (search.status != RoomSearch.Status.INDOOR) return unknown(Status.valueOf(search.status.name()));
            region = latest(airPosition.asLong());
        }
        if (region == null) return unknown(Status.HISTORY_MISSING);
        if (tick == level.getGameTime() && region.chunks.stream().anyMatch(c -> !level.hasChunk(c.x, c.z))) return unknown(Status.UNLOADED);
        var memo = new HashMap<String, Snapshot>();
        var state = evaluate(region, tick, memo);
        if (state.isEmpty()) return unknown(Status.MISSING_BOUNDARY);
        var out = boundary(airPosition, tick, tick);
        if (out.isEmpty()) return unknown(Status.MISSING_BOUNDARY);
        double q = exchange(region, state.get(), out.get().getFirst().air);
        return new Sample(Status.INDOOR, state, true, region.openings > 0, region.cells.length, q, accuracy(airPosition,tick));
    }

    /** C04が日長/気象境界と併合する室構成・発生源の境界。端点を含む昇順列。 */
    public List<Long> boundaries(BlockPos airPosition, long from, long to) {
        var points = new TreeSet<Long>(List.of(from, to));
        past.cuts(airPosition.asLong(),from,to,points);
        for (var r : regions) if (r.contains(airPosition.asLong())) {
            if (r.from > from && r.from < to) points.add(r.from);
            if (r.to > from && r.to < to) points.add(r.to);
        }
        for (var p : history.entries()) if (p.from() < to && p.to() >= from
                && regions.stream().anyMatch(r -> r.contains(airPosition.asLong()) && r.receives(p.position()) && r.replayFrom <= p.to() && p.from() < r.to)) {
            if (p.from() > from) points.add(p.from()); if (p.to() < to) points.add(p.to());
        }
        var representative = latest(airPosition.asLong());
        if (representative != null) addArchiveCuts(points, representative, from, to);
        return List.copyOf(points);
    }

    public SourceReceipts.Result receive(CombustionEvent event) {
        var f = FUELS.get(event.fuelProfile().toString());
        if (f == null) throw new IllegalArgumentException("室内排出係数のない燃料profileです");
        double co = event.burnedKg() * f.coKgPerKg / 0.028;
        double carbon = event.burnedKg() * f.carbon / 0.012;
        double co2 = carbon - co;
        double oxygen = co2 + co / 2 + event.burnedKg() * (f.hydrogen / 0.001 / 4 - f.oxygen / 0.016 / 2);
        String payload = event.position().asLong() + "/" + event.startTick() + "/" + event.endTick() + "/"
                + event.fuelProfile() + "/" + Double.toHexString(event.burnedKg()) + "/"
                + Double.toHexString(event.energyJoules()) + "/" + Double.toHexString(event.retainedHeatJoules());
        return emit(event.sourceId(), event.sequence(), payload, event.position().above(), event.startTick(), event.endTick(),
                new RoomPhysics.Amount(event.energyJoules() - event.retainedHeatJoules(), co, co2, -oxygen));
    }

    /** S03が実際の呼吸量を渡す。酸素消費・CO2排出は正のmol、熱はJ。健康や代謝率を代行しない。 */
    public SourceReceipts.Result respiration(UUID source, long sequence, BlockPos airPosition, long from, long to,
                                            double oxygenMol, double co2Mol, double heatJ) {
        if (oxygenMol < 0 || co2Mol < 0) throw new IllegalArgumentException("呼吸量は非負です");
        String payload = "respiration/" + airPosition.asLong() + "/" + from + "/" + to + "/"
                + Double.toHexString(oxygenMol) + "/" + Double.toHexString(co2Mol) + "/" + Double.toHexString(heatJ);
        return emit(source, sequence, payload, airPosition, from, to, new RoomPhysics.Amount(heatJ, 0, co2Mol, -oxygenMol));
    }

    private SourceReceipts.Result emit(UUID source, long sequence, String payload, BlockPos pos, long from, long to, RoomPhysics.Amount amount) {
        if (from < 0 || to < from || to > level.getGameTime() || !Double.isFinite(amount.heatJ())
                || !Double.isFinite(amount.coMol()) || !Double.isFinite(amount.co2Mol()) || !Double.isFinite(amount.oxygenMol()))
            throw new IllegalArgumentException("室内通知の区間/発生量が不正です");
        var result = receipts.accept(source, sequence, payload);
        if (result == SourceReceipts.Result.ACCEPTED) {
            var owner = latest(pos.asLong());
            int context = from == to && to == level.getGameTime() && owner != null && owner.to == Long.MAX_VALUE ? owner.id : -1;
            inputs.add(new RoomHistory.Entry(pos.asLong(), from, to, amount, context));
            route(pos, from, to, amount);
            setDirty();
        }
        return result;
    }

    private void route(BlockPos pos, long from, long to, RoomPhysics.Amount amount) {
        var cuts = new TreeSet<Long>(List.of(from, to));
        for (var room : regions) if (room.contains(pos.asLong())) {
            if (room.from > from && room.from < to) cuts.add(room.from);
            if (room.to > from && room.to < to) cuts.add(room.to);
        }
        past.cuts(pos.asLong(), from, to, cuts);
        var stored = displaced.get(pos.asLong());
        if (stored != null && stored.origin.until > from && stored.origin.until < to) cuts.add(stored.origin.until);
        var times = new ArrayList<>(cuts);
        var remaining = amount;
        for (int i = 0; i < Math.max(1, times.size() - 1); i++) {
            long a = times.get(i), b = times.size() == 1 ? a : times.get(i + 1);
            var part = i == times.size() - 2 || times.size() == 1 ? remaining
                    : RoomHistory.scale(amount, (double) (b - a) / (to - from));
            remaining = RoomHistory.plus(remaining, RoomHistory.scale(part, -1));
            if (stored != null) {
                if (projectPast(pos, a, b, part)) history.archiveInput(part);
                else pending.add(new RoomHistory.Entry(pos.asLong(), a, b, part));
                continue;
            }
            var owner = regions.stream().filter(r -> r.ready && r.from <= a && a < r.to && r.contains(pos.asLong()))
                    .max(Comparator.comparingInt(r -> r.id)).orElse(null);
            if (owner == null) {
                owner = regions.stream().filter(r -> r.ready && r.observedFrom <= a && r.from >= b && r.contains(pos.asLong()))
                        .min(Comparator.comparingInt(r -> r.id)).orElse(null);
                if (owner != null && a < owner.replayFrom) {
                    if (projectPast(pos, a, b, part)) history.archiveInput(part);
                    else pending.add(new RoomHistory.Entry(pos.asLong(), a, b, part));
                    continue;
                }
            }
            if (owner == null) {
                if (projectPast(pos, a, b, part)) history.archiveInput(part);
                else pending.add(new RoomHistory.Entry(pos.asLong(), a, b, part));
            }
            else if (a < history.checkpoint() || regions.stream().anyMatch(r -> r.committed != null
                    && r.contains(pos.asLong()) && (a < r.committed.getKey() || a == b && a == r.committed.getKey()))) {
                if (projectPast(pos, a, b, part)) history.archiveInput(part);
                else pending.add(new RoomHistory.Entry(pos.asLong(), a, b, part));
            } else {
                history.append(new RoomHistory.Entry(owner.anchor, a, b, part));
                if (part.heatJ() != 0 || part.coMol() != 0 || part.co2Mol() != 0 || part.oxygenMol() != 0) for (var r : regions)
                    if (r.receives(owner.anchor)) r.checkpoints.tailMap(a, true).clear();
                if (history.retainedCount() > RoomHistory.LIMIT) {
                    var observed = new HashMap<Integer, Snapshot>();
                    var memo = new HashMap<String, Snapshot>();
                    for (var r : regions) if (r.from <= Math.min(level.getGameTime(), r.to))
                        simulate(r, Math.min(level.getGameTime(), r.to), memo)
                            .ifPresent(value -> observed.put(r.id, value));
                    history.compactIfNeeded();
                    for (var r : regions) {
                        r.checkpoints.clear();
                        var value = observed.get(r.id);
                        if (value != null) commit(r, Math.min(level.getGameTime(), r.to), value);
                    }
                }
            }
        }
    }

    private boolean projectPast(BlockPos pos, long from, long to, RoomPhysics.Amount input) {
        long now = level.getGameTime();
        if (input.heatJ() == 0 && input.coMol() == 0 && input.co2Mol() == 0 && input.oxygenMol() == 0) {
            return true;
        }
        refreshPast();
        var owner = latest(pos.asLong());
        if (from == now && to == now && owner != null && owner.to == Long.MAX_VALUE && !displaced.containsKey(pos.asLong())) {
            var state = simulate(owner, now, new HashMap<>());
            if (state.isEmpty()) return false;
            var amount = RoomHistory.plus(RoomPhysics.amount(state.get().state, owner.cells.length, owner.walls), input);
            commit(owner, now, new Snapshot(RoomPhysics.state(amount, owner.cells.length, owner.walls), state.get().exchange));
            return true;
        }
        var response = past.response(pos.asLong(), from, to, now, input, this::boundary);
        if (response.isEmpty()) return false;
        var snapshots = new HashMap<Region, Snapshot>();
        var held = new HashMap<Long, Stored>();
        var memo = new HashMap<String, Snapshot>();
        for (var entry : response.get().entrySet()) {
            if (displaced.containsKey(entry.getKey())) continue;
            var holder = latest(entry.getKey());
            if (holder == null) return false;
            if (holder.to == Long.MAX_VALUE) {
                var state = simulate(holder, now, memo);
                if (state.isEmpty()) return false;
                snapshots.put(holder, state.get());
            } else {
                var state = simulate(holder, holder.to, memo);
                if (state.isEmpty()) return false;
                var origin = new Origin(holder.observedFrom, holder.to, holder.cells.length, holder.walls,
                        holder.openings, holder.height, state.get().state.temperatureK());
                for (long cell : holder.cells) if (latest(cell) == holder && !displaced.containsKey(cell))
                    held.put(cell, new Stored(RoomHistory.scale(RoomPhysics.amount(state.get().state,
                            holder.cells.length, holder.walls), 1.0 / holder.cells.length),
                            RoomHistory.scale(state.get().exchange, 1.0 / holder.cells.length), origin));
            }
        }
        for (var entry : snapshots.entrySet()) {
            var r = entry.getKey(); var snapshot = entry.getValue();
            var amount = RoomPhysics.amount(snapshot.state, r.cells.length, r.walls);
            var exchanged = snapshot.exchange;
            for (long cell : r.cells) {
                var delta = response.get().get(cell);
                if (delta != null) { amount = RoomHistory.plus(amount, delta.amount());
                    exchanged = RoomHistory.plus(exchanged, delta.exchange()); }
            }
            commit(r, now, new Snapshot(RoomPhysics.state(amount, r.cells.length, r.walls), exchanged));
        }
        held.forEach(this::storeDisplaced);
        for (var entry : response.get().entrySet()) {
            var stored = displaced.get(entry.getKey());
            if (stored != null) displaced.put(entry.getKey(), new Stored(
                    RoomHistory.plus(stored.amount, entry.getValue().amount()),
                    RoomHistory.plus(stored.exchange, entry.getValue().exchange()), stored.origin));
        }
        return true;
    }

    private void storeDisplaced(long cell, Stored value) {
        boolean added = displaced.put(cell, value) == null;
        if (added) for (var registration : registrations.values()) registration.recover(cell);
    }

    private void commit(Region r, long tick, Snapshot snapshot) {
        r.committed = Map.entry(tick, snapshot);
        r.checkpoints.clear();
    }

    private void refreshPast() {
        for (var r : regions) if (r.from <= level.getGameTime() && !Double.isFinite(r.pastModel.temperature))
            simulate(r, r.from, new HashMap<>()).ifPresent(value -> {
                r.pastModel.temperature = value.state.temperatureK(); past.resolve(r.pastModel);
            });
    }

    private void flushPending() {
        if (outside == null || pending.entries().isEmpty()) return;
        var entries = pending.entries();
        pending = new RoomHistory();
        for (var entry : entries) route(BlockPos.of(entry.position()), entry.from(), entry.to(), entry.amount());
        setDirty();
    }

    private Optional<List<Boundary>> boundary(BlockPos pos, long from, long to) {
        if (outside == null) return Optional.empty();
        var response = outside.intervals(level, pos, from, to);
        if (response.isEmpty()) return response;
        long next = from;
        if (from == to && response.get().size() != 1) throw new IllegalArgumentException("瞬時の屋外応答は1行です");
        if (response.get().isEmpty()) throw new IllegalArgumentException("屋外区間が空です");
        for (var row : response.get()) {
            if (row.from != next || row.to < row.from || row.to > to || (from < to && row.to == row.from))
                throw new IllegalArgumentException("屋外区間に欠落・重複があります: request=" + from + "/" + to + " row=" + row.from + "/" + row.to + " next=" + next);
            next = row.to;
        }
        if (next != to) throw new IllegalArgumentException("屋外区間が要求を満たしません");
        return response;
    }
    private static Sample unknown(Status status) { return new Sample(status, Optional.empty(), false, false, 0, 0, Accuracy.UNKNOWN); }

    private Optional<RoomPhysics.State> evaluate(Region r, long tick, Map<String, Snapshot> memo) {
        return simulate(r, tick, memo).map(Snapshot::state);
    }

    private Optional<Snapshot> simulate(Region r, long tick, Map<String, Snapshot> memo) {
        String key = r.id + "/" + tick;
        if (memo.containsKey(key)) return Optional.of(memo.get(key));
        var checkpoint = r.checkpoints.floorEntry(tick);
        if (r.committed != null && r.committed.getKey() <= tick
                && (checkpoint == null || checkpoint.getKey() <= r.committed.getKey())) checkpoint = r.committed;
        long base = checkpoint == null ? r.replayFrom : checkpoint.getKey();
        var rows = boundary(BlockPos.of(r.cells[0]), base, tick);
        if (rows.isEmpty()) return Optional.empty();
        var ambient = RoomPhysics.State.ambient(rows.get().getFirst().air);
        var quantity = ZERO;
        var exchanged = ZERO;
        int known = r.recoveredCells;
        for (var parent : r.parents.entrySet()) {
            if (checkpoint != null) break;
            var old = regionIds.get(parent.getKey());
            var prior = simulate(old, Math.min(r.from, old.to), memo);
            if (prior.isEmpty()) return Optional.empty();
            double weight = (double) parent.getValue() / old.cells.length;
            known += parent.getValue();
            quantity = RoomHistory.plus(quantity, RoomHistory.scale(
                    RoomPhysics.amount(prior.get().state, old.cells.length, old.walls), weight));
            exchanged = RoomHistory.plus(exchanged, RoomHistory.scale(prior.get().exchange, weight));
        }
        double fresh = 1 - (double) known / r.cells.length;
        var imported = RoomHistory.scale(RoomPhysics.amount(ambient, r.cells.length, r.walls), fresh);
        quantity = RoomHistory.plus(quantity, imported);
        exchanged = RoomHistory.plus(exchanged, RoomHistory.scale(imported, -1));
        var state = RoomPhysics.state(quantity, r.cells.length, r.walls);
        if (checkpoint != null) {
            state = checkpoint.getValue().state;
            exchanged = checkpoint.getValue().exchange;
        }
        var relevant = history.entries().stream().filter(p -> p.to() >= base && p.from() <= tick && r.receives(p.position())).toList();
        var cuts = new TreeSet<Long>(List.of(base, tick));
        var deltas = new TreeMap<Long, RoomPhysics.Rate>();
        var instants = new HashMap<Long, RoomPhysics.Amount>();
        if (r.from >= base && r.from <= tick && !(checkpoint != null && r.from == base)) {
            cuts.add(r.from);
            instants.put(r.from, RoomHistory.plus(r.recovered, r.adjustment));
        }
        for (var row : rows.get()) { cuts.add(row.from); cuts.add(row.to); }
        for (var pulse : relevant) {
            if (pulse.from() == pulse.to()) {
                if (pulse.from() >= base && (pulse.from() < r.to || pulse.from() == r.to && pulse.position() == r.anchor)
                        && !(checkpoint != null && pulse.from() == base)
                        && !(pulse.from() == r.from && pulse.position() != r.anchor))
                    instants.merge(pulse.from(), RoomHistory.scale(pulse.amount(), r.weight(pulse.position())), RoomHistory::plus);
            } else {
                long a = Math.max(base, pulse.from()), b = Math.min(tick, pulse.to());
                if (a < b) {
                    var rate = pulse.amount().rate(r.weight(pulse.position()) * 20.0 / (pulse.to() - pulse.from()));
                    deltas.merge(a, rate, RoomPhysics.Rate::plus);
                    deltas.merge(b, rate.scale(-1), RoomPhysics.Rate::plus);
                }
            }
            if (pulse.from() > base && pulse.from() < tick) cuts.add(pulse.from());
            if (pulse.to() > base && pulse.to() < tick) cuts.add(pulse.to());
        }
        addArchiveCuts(cuts, r, base, tick);
        var times = new ArrayList<>(cuts);
        var source = RoomPhysics.Rate.ZERO;
        int outsideIndex = 0;
        for (int i = 0; i < times.size(); i++) {
            long a = times.get(i);
            while (outsideIndex + 1 < rows.get().size() && rows.get().get(outsideIndex).to <= a) outsideIndex++;
            var out = rows.get().get(outsideIndex).air;
            var instant = instants.get(a);
            if (instant != null) state = RoomPhysics.inject(state, out, r.cells.length, r.walls, instant);
            if (a == r.from && !(checkpoint != null && a == base))
                exchanged = RoomHistory.plus(exchanged, RoomHistory.plus(r.recoveredExchange, r.adjustmentExchange));
            if (i + 1 == times.size()) break;
            r.checkpoints.put(a, new Snapshot(state, exchanged));
            while (r.checkpoints.size() > 2) r.checkpoints.pollFirstEntry();
            source = source.plus(deltas.getOrDefault(a, RoomPhysics.Rate.ZERO));
            long b = times.get(i + 1);
            double seconds = (b - a) / 20.0;
            var before = RoomPhysics.amount(state, r.cells.length, r.walls);
            state = RoomPhysics.advance(state, out, r.cells.length, r.walls, exchange(r, state, out), source, seconds);
            var input = new RoomPhysics.Amount(source.heatW() * seconds, source.coMolS() * seconds,
                    source.co2MolS() * seconds, source.oxygenMolS() * seconds);
            exchanged = RoomHistory.plus(exchanged, RoomHistory.plus(input,
                    RoomHistory.plus(before, RoomHistory.scale(RoomPhysics.amount(state, r.cells.length, r.walls), -1))));
        }
        var result = new Snapshot(state, exchanged);
        memo.put(key, result);
        return Optional.of(result);
    }
    private void addArchiveCuts(Set<Long> cuts, Region room, long from, long to) {
        long start = history.cumulative().stream().filter(p -> room.receives(p.position()))
                .mapToLong(RoomHistory.Entry::from).min().orElse(history.checkpoint());
        long end = history.checkpoint();
        if (end <= start) return;
        long stride = Math.max(1, (end - start + 127) / 128);
        for (int n = 1; n < 128; n++) {
            long tick = start + n * stride;
            if (tick > from && tick < to && tick < end) cuts.add(tick);
        }
    }

    private static double exchange(Region r, RoomPhysics.State state, RoomPhysics.Air outside) {
        return RoomPhysics.flow(r.openings, r.height, state.temperatureK(), outside);
    }

    private static long chunk(long cell) {
        var p = BlockPos.of(cell);
        return ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
    }

    private Region latest(long cell) {
        var candidates = byChunk.get(chunk(cell));
        if (candidates == null) return null;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            var r = candidates.get(i);
            if (r.ready && r.contains(cell)) return r;
        }
        return null;
    }

    private static long column(BlockPos p) { return BlockPos.asLong(p.getX(), 0, p.getZ()); }

    private final class Registration {
        final RoomSearch job;
        LongIterator validation;
        LongIterator copying;
        LongIterator openings;
        Iterator<ChunkPos> indexing;
        final long[] cells;
        final long[] portals;
        final Map<Integer, Integer> parents = new HashMap<>();
        final Map<Long, Integer> parentCells = new HashMap<>();
        boolean groupsDirty;
        final Set<ChunkPos> chunks = new HashSet<>();
        final Set<Long> inheritedGroups = new HashSet<>();
        final Set<Long> recovered = new HashSet<>();
        boolean boundaryMissing;
        long inheritedFrom;
        Region room;
        int cellCursor;
        int portalCursor;
        int pastCursor;
        long pastVersion = -1;
        Registration(RoomSearch job) {
            this.job = job; inheritedFrom = job.epoch; validation = job.visited.iterator(); copying = job.cells.iterator();
            openings = job.portals.iterator();
            cells = new long[job.cells.size()]; portals = new long[job.portals.size()];
        }
        boolean step(int budget) {
            var active = latest(job.origin.asLong());
            if (active != null && active.to == Long.MAX_VALUE) { discard(); return true; }
            long started = System.nanoTime();
            for (int used = 0; used < budget; used++) {
                if (used > 0 && System.nanoTime()-started >= 1_000_000) return false;
                if (validation.hasNext()) {
                    long cell = validation.nextLong();
                    if (changedChunks.getOrDefault(chunk(cell), 0L) > job.epoch) {
                        job.status = RoomSearch.Status.HISTORY_MISSING; return false;
                    }
                } else if (copying.hasNext()) {
                    long cell = copying.nextLong();
                    cells[cellCursor++] = cell;
                    chunks.add(new ChunkPos(BlockPos.of(cell)));
                    var cumulative = history.cumulativeAt(cell);
                    if (cumulative != null) {
                        inheritedGroups.add(cell); inheritedFrom = Math.min(inheritedFrom, cumulative.from());
                    }
                    if (displaced.containsKey(cell)) recovered.add(cell);
                    else {
                        var old = latest(cell);
                        if (old != null && old.to <= job.epoch) {
                            parents.merge(old.id, 1, Integer::sum); parentCells.put(cell, old.id);
                        }
                    }
                } else if (openings.hasNext()) {
                    portals[portalCursor++] = openings.nextLong();
                } else {
                    if (room == null) {
                        room = new Region(nextRegionId++, job.epoch, cells, job.walls, job.openings,
                                job.openingHeight(), parents, chunks, portals);
                        if (parents.isEmpty() && recovered.isEmpty()) room.replayFrom = inheritedFrom;
                        // BlockPosの通常の世界範囲外を予約し、世代ごとの書込先と実座標を区別する。
                        room.anchor = Long.MIN_VALUE + room.id;
                        inheritGroups();
                        indexing = chunks.iterator();
                    }
                    if (pastVersion != past.version() || room.pastModel.preparedVersion != pastVersion) {
                        pastVersion = past.version(); pastCursor = 0; past.prepare(room.pastModel);
                    }
                    if (pastCursor < cells.length) {
                        past.bind(cells[pastCursor++], room.pastModel);
                    } else if (indexing.hasNext()) {
                        var c = indexing.next();
                        byChunk.computeIfAbsent(c.toLong(), key -> new ArrayList<>()).add(room);
                    } else if (portalCursor > 0) {
                        var p = BlockPos.of(portals[--portalCursor]);
                        skyColumns.computeIfAbsent(column(p), key -> new HashSet<>()).add(room);
                    } else {
                        boundaryMissing = false;
                        if (groupsDirty) inheritGroups();
                        for (long cell : recovered) {
                            var stored = displaced.get(cell);
                            room.recovered = RoomHistory.plus(room.recovered, stored.amount);
                            room.recoveredExchange = RoomHistory.plus(room.recoveredExchange, stored.exchange);
                            room.observedFrom = Math.min(room.observedFrom, stored.origin.from);
                        }
                        room.recoveredCells = recovered.size();
                        if (!parents.isEmpty()) {
                            for (var entry : history.entries()) {
                                if (!room.contains(entry.position()) || recovered.contains(entry.position())) continue;
                                var prior = latest(entry.position());
                                long after = prior == null ? 0 : prior.to;
                                long from = Math.max(entry.from(), after), to = Math.min(entry.to(), room.from);
                                double share = entry.from() == entry.to()
                                        ? (entry.from() >= after && entry.from() < room.from ? 1 : 0)
                                        : (double) Math.max(0, to - from) / (entry.to() - entry.from());
                                room.recovered = RoomHistory.plus(room.recovered, RoomHistory.scale(entry.amount(), share));
                            }
                        }
                        if (!canTrim(room)) { room.recovered = ZERO; room.recoveredExchange = ZERO; boundaryMissing = true; return false; }
                        if (!past.publish(room.pastModel)) {
                            room.recovered = ZERO; room.recoveredExchange = ZERO; return false;
                        }
                        for (long cell : recovered) displaced.remove(cell);
                        room.ready = true;
                        regions.add(room); regionIds.put(room.id, room);
                        simulate(room, room.from, new HashMap<>()).ifPresent(value -> room.pastModel.temperature = value.state.temperatureK());
                        trimGenerations(); return true;
                    }
                }
            }
            return false;
        }
        void recover(long cell) {
            if (!job.cells.contains(cell)) return;
            recovered.add(cell);
            var parent = parentCells.remove(cell);
            if (parent != null) parents.compute(parent, (key, count) -> count == 1 ? null : count - 1);
            groupsDirty = room != null;
        }
        private void inheritGroups() {
            room.groups.clear();
            for (var parent : parents.entrySet()) {
                var old = regionIds.get(parent.getKey());
                room.observedFrom = Math.min(room.observedFrom, old.observedFrom);
                double share = (double) parent.getValue() / old.cells.length;
                old.groups.forEach((key, weight) -> room.groups.merge(key, weight * share, Double::sum));
            }
            for (long group : inheritedGroups) room.groups.putIfAbsent(group, 1.0);
            room.groups.put(room.anchor, 1.0); groupsDirty = false;
        }
        void discard() {
            if (room == null) return;
            past.discard(room.pastModel);
            for (var c : chunks) {
                var entries = byChunk.get(c.toLong());
                if (entries != null) entries.remove(room);
            }
            for (long cell : portals) {
                var entries = skyColumns.get(column(BlockPos.of(cell)));
                if (entries != null) entries.remove(room);
            }
        }
    }

    /** 探索だけでなく、検証・親照合・配列化・索引登録も同じ作業予算で分割する。 */
    public void work() {
        if (!ENABLED.get() || jobs.isEmpty()) return;
        var job = jobs.values().stream().filter(j -> j.status == RoomSearch.Status.SCANNING
                || registrations.containsKey(j.origin.asLong())).findFirst().orElse(null);
        if (job == null) return;
        if (job.status == RoomSearch.Status.SCANNING) {
            job.work(level, VISITS.get(), 4096);
            if (job.status == RoomSearch.Status.OUTDOOR) { observeOutdoor(job,level.getGameTime()); return; }
            if (job.status != RoomSearch.Status.INDOOR) return;
            var existing = latest(job.origin.asLong());
            if (existing != null && existing.to == Long.MAX_VALUE) { jobs.remove(job.origin.asLong()); return; }
            registrations.put(job.origin.asLong(), new Registration(job));
            return;
        }
        var registration = registrations.get(job.origin.asLong());
        if (registration.step(VISITS.get())) {
            registrations.remove(job.origin.asLong()); jobs.remove(job.origin.asLong());
            if (jobs.isEmpty()) changedChunks.clear();
            setDirty();
        } else if (job.status == RoomSearch.Status.HISTORY_MISSING) {
            registration.discard(); registrations.remove(job.origin.asLong()); jobs.remove(job.origin.asLong());
        }
    }

    /** 構成の変更時刻まで旧幾何を維持し、その時刻を新しい探索の境界にする。 */
    public void changed(BlockPos pos) {
        long tick = level.getGameTime();
        changedOutdoor(pos,tick);
        if (!jobs.isEmpty()) changedChunks.put(chunk(pos.asLong()), tick);
        var iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var origin = entry.getValue().origin;
            boolean aboveOrigin = origin.getX() == pos.getX() && origin.getZ() == pos.getZ() && pos.getY() >= origin.getY();
            if (entry.getValue().visited.contains(pos.asLong()) || aboveOrigin) {
                var registration = registrations.remove(entry.getKey());
                if (registration != null) registration.discard();
                iterator.remove();
            }
        }
        var affected = new HashSet<Region>();
        var center = latest(pos.asLong()); if (center != null) affected.add(center);
        for (Direction d : Direction.values()) { var r = latest(pos.relative(d).asLong()); if (r != null) affected.add(r); }
        var columnRooms = skyColumns.get(column(pos));
        boolean changesSky = false;
        if (columnRooms != null) for (var r : columnRooms) if (r.ready && r.to == Long.MAX_VALUE) {
            for (long cell : r.portals) {
                var opening = BlockPos.of(cell);
                if (column(opening) == column(pos) && pos.getY() >= opening.getY()) {
                    affected.add(r); changesSky = true; break;
                }
            }
        }
        boolean wasAir = center != null && center.to == Long.MAX_VALUE;
        if (!changesSky && level.hasChunkAt(pos) && wasAir == RoomSearch.passable(level, pos)) return;
        for (var r : affected) if (r.to == Long.MAX_VALUE) retire(r, tick);
    }

    private void retire(Region r, long tick) {
        if (r.to != Long.MAX_VALUE) return;
        simulate(r, tick, new HashMap<>()).ifPresent(value -> commit(r, tick, value));
        while (r.checkpoints.size() > 2) r.checkpoints.pollFirstEntry();
        r.to = tick; past.end(r.pastModel, tick);
        if (!Double.isFinite(r.pastModel.temperature)) simulate(r, r.from, new HashMap<>())
                .ifPresent(value -> r.pastModel.temperature = value.state.temperatureK());
        setDirty();
    }

    private boolean canTrim(Region candidate) {
        if (regions.stream().filter(r -> r.to != Long.MAX_VALUE).count() <= 64) return true;
        var old = regions.stream().filter(r -> r.to != Long.MAX_VALUE).findFirst().orElseThrow();
        var memo = new HashMap<String, Snapshot>();
        if (evaluate(old, old.to, memo).isEmpty()) return false;
        for (var child : regions) if (child != old && child.groups.containsKey(old.anchor)
                && evaluate(child, child.from, memo).isEmpty()) return false;
        return !candidate.groups.containsKey(old.anchor) || evaluate(candidate, candidate.from, memo).isPresent();
    }

    private void trimGenerations() {
        while (regions.stream().filter(r -> r.to != Long.MAX_VALUE).count() > 64) {
            var old = regions.stream().filter(r -> r.to != Long.MAX_VALUE).findFirst().orElseThrow();
            var memo = new HashMap<String, Snapshot>();
            var observed = new HashMap<Integer, Snapshot>();
            for (var r : regions) if (r.from <= Math.min(level.getGameTime(), r.to))
                        simulate(r, Math.min(level.getGameTime(), r.to), memo)
                    .ifPresent(value -> observed.put(r.id, value));
            var finalState = simulate(old, old.to, memo).orElseThrow();
            var finalQuantity = RoomPhysics.amount(finalState.state, old.cells.length, old.walls);
            var origin = new Origin(old.observedFrom, old.to, old.cells.length, old.walls, old.openings, old.height, finalState.state.temperatureK());
            for (long cell : old.cells) if (latest(cell) == old && !displaced.containsKey(cell))
                storeDisplaced(cell, new Stored(RoomHistory.scale(finalQuantity, 1.0 / old.cells.length),
                        RoomHistory.scale(finalState.exchange, 1.0 / old.cells.length), origin));
            var children = regions.stream().filter(r -> r.parents.containsKey(old.id)).toList();
            var affected = regions.stream().filter(r -> r != old && r.groups.containsKey(old.anchor)).toList();
            var targets = new HashMap<Integer, Snapshot>();
            for (var r : affected)
                targets.put(r.id, simulate(r, r.from, memo).orElseThrow());
            var recipients = new HashMap<Long, Double>();
            for (var child : children) recipients.put(child.anchor, (double) child.parents.get(old.id) / old.cells.length);
            history.transfer(old.anchor, recipients);
            for (var retained : regions) { retained.groups.remove(old.anchor); retained.checkpoints.clear(); }
            for (var child : children) {
                long beginning = child.replayFrom;
                for (int parent : child.parents.keySet()) beginning = Math.min(beginning, regionIds.get(parent).replayFrom);
                child.parents.clear(); child.replayFrom = beginning;
            }
            // 親リンクの整理後も系列を共有している室を含め、開始時の実残留量を保存する。
            for (var r : affected) {
                var replay = simulate(r, r.from, new HashMap<>()).orElseThrow();
                var target = targets.get(r.id);
                r.adjustment = RoomHistory.plus(r.adjustment, RoomHistory.plus(
                        RoomPhysics.amount(target.state, r.cells.length, r.walls),
                        RoomHistory.scale(RoomPhysics.amount(replay.state, r.cells.length, r.walls), -1)));
                r.adjustmentExchange = RoomHistory.plus(r.adjustmentExchange,
                        RoomHistory.plus(target.exchange, RoomHistory.scale(replay.exchange, -1)));
                for (var retained : regions) retained.checkpoints.clear();
            }
            regions.remove(old); regionIds.remove(old.id);
            for (var r : regions) {
                var value = observed.get(r.id);
                if (value != null) commit(r, Math.min(level.getGameTime(), r.to), value);
                while (r.checkpoints.size() > 2) r.checkpoints.pollFirstEntry();
            }
            for (var c : old.chunks) {
                var entries = byChunk.get(c.toLong()); entries.remove(old);
                if (entries.isEmpty()) byChunk.remove(c.toLong());
            }
            for (long cell : old.portals) {
                long key = column(BlockPos.of(cell));
                var entries = skyColumns.get(key);
                if (entries != null) { entries.remove(old); if (entries.isEmpty()) skyColumns.remove(key); }
            }
        }
    }

    /** 収支検証用の現在の残留量。未配分セルを含め、同じ空間を一度だけ数える。 */
    record Balance(RoomPhysics.Amount retained, RoomPhysics.Amount exchange) {}
    Optional<RoomPhysics.Amount> quantity() { return balance().map(Balance::retained); }
    Optional<Balance> balance() {
        flushPending();
        var sum = ZERO;
        var exchanged = ZERO;
        var memo = new HashMap<String, Snapshot>();
        for (var room : regions) {
            int owned = 0;
            for (long cell : room.cells) if (latest(cell) == room && !displaced.containsKey(cell)) owned++;
            if (owned == 0) continue;
            var state = simulate(room, Math.min(level.getGameTime(), room.to), memo);
            if (state.isEmpty()) return Optional.empty();
            double share = (double) owned / room.cells.length;
            sum = RoomHistory.plus(sum, RoomHistory.scale(RoomPhysics.amount(state.get().state, room.cells.length, room.walls), share));
            exchanged = RoomHistory.plus(exchanged, RoomHistory.scale(state.get().exchange, share));
        }
        for (var stored : displaced.values()) {
            sum = RoomHistory.plus(sum, stored.amount); exchanged = RoomHistory.plus(exchanged, stored.exchange);
        }
        for (var entry : pending.entries()) sum = RoomHistory.plus(sum, entry.amount());
        return Optional.of(new Balance(sum, exchanged));
    }

    /** 集約した区間を明示する。過去の入力はここへ均等配分して再構成する。量はJ/mol。 */
    public record Retention(int recent, int maximumRecent, int cumulative, int retiredGenerations) {}
    public Retention retention() {
        return new Retention(history.retainedCount(), history.maximumRetainedCount(), history.cumulativeCount(),
                (int) regions.stream().filter(r -> r.to != Long.MAX_VALUE).count());
    }
    public record Reconstruction(long startTick, long endTick, RoomPhysics.Amount amount) {}

    public List<Reconstruction> reconstruction(BlockPos airPosition) {
        var room = latest(airPosition.asLong());
        return history.cumulative().stream().filter(p -> room != null && room.receives(p.position()))
                .map(p -> new Reconstruction(p.from(), p.to(), RoomHistory.scale(p.amount(), room.weight(p.position())))).toList();
    }

    private static Map<String, Fuel> loadFuels() {
        var type = new TypeToken<Map<String, Fuel>>() {}.getType();
        try (var stream = RoomAir.class.getResourceAsStream("/data/ashen_frontier_climate/indoor/fuels.json")) {
            if (stream == null) throw new IllegalStateException("室内燃料係数がありません");
            return Map.copyOf(new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), type));
        } catch (IOException e) { throw new IllegalStateException("室内燃料係数を読めません", e); }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", 9);
        past.save(tag);
        tag.putInt("next_region", nextRegionId);
        tag.putLong("checkpoint_tick", history.checkpoint());
        var receiptRows = new ListTag();
        receipts.entries().forEach((id, last) -> {
            var row = new CompoundTag(); row.putUUID("source", id); row.putLong("sequence", last.sequence());
            row.putString("payload", last.payload()); receiptRows.add(row);
        });
        tag.put("receipts", receiptRows);
        var geometryRows = new ListTag();
        var geometryIds = new HashMap<String, Integer>();
        var regionRows = new ListTag();
        var checkpointMemo = new HashMap<String, Snapshot>();
        for (var r : regions) {
            long cut = Math.min(history.checkpoint(), r.to);
            if (outside != null && cut >= r.replayFrom && cut >= r.from) {
                var state = simulate(r, cut, checkpointMemo);
                state.ifPresent(value -> r.checkpoints.put(cut, value));
                while (r.checkpoints.size() > 2) r.checkpoints.pollFirstEntry();
            }
            String key = Arrays.toString(r.cells) + "/" + Arrays.toString(r.portals) + "/" + r.walls + "/" + r.openings + "/" + r.height;
            int id = geometryIds.computeIfAbsent(key, k -> {
                var geometry = new CompoundTag(); geometry.putLongArray("cells", r.cells);
                geometry.putLongArray("portals", r.portals); geometry.putInt("walls", r.walls); geometry.putInt("openings", r.openings); geometry.putDouble("height", r.height);
                geometryRows.add(geometry); return geometryRows.size() - 1;
            });
            var row = new CompoundTag(); row.putInt("id", r.id); row.putLong("replay_from", r.replayFrom); row.putLong("anchor", r.anchor);
            var groups = new ListTag();
            r.groups.forEach((groupKey, share) -> { var group = new CompoundTag(); group.putLong("key", groupKey); group.putDouble("share", share); groups.add(group); });
            row.put("groups", groups);
            row.putInt("recovered_cells", r.recoveredCells);
            row.putLong("observed_from", r.observedFrom);
            row.put("recovered_exchange", saveAmount(r.recoveredExchange));
            row.put("adjustment_exchange", saveAmount(r.adjustmentExchange));
            row.put("recovered", saveAmount(r.recovered));
            row.put("adjustment", saveAmount(r.adjustment));
            row.putInt("geometry", id); row.putLong("from", r.from); row.putLong("to", r.to);
            var parents = new ListTag();
            r.parents.forEach((parent, count) -> { var entry = new CompoundTag(); entry.putInt("id", parent); entry.putInt("count", count); parents.add(entry); });
            row.put("parents", parents);
            var checkpoints = new ListTag();
            r.checkpoints.forEach((tick, snapshot) -> {
                var state = snapshot.state;
                var entry = new CompoundTag(); entry.putLong("tick", tick);
                entry.putDouble("temperature", state.temperatureK()); entry.putDouble("co", state.co());
                entry.putDouble("co2", state.co2()); entry.putDouble("oxygen", state.oxygen());
                entry.put("exchange", saveAmount(snapshot.exchange)); checkpoints.add(entry);
            });
            row.put("checkpoints", checkpoints);
            if (r.committed != null) {
                var point = new CompoundTag(); var state = r.committed.getValue().state;
                point.putLong("tick", r.committed.getKey()); point.putDouble("temperature", state.temperatureK());
                point.putDouble("co", state.co()); point.putDouble("co2", state.co2()); point.putDouble("oxygen", state.oxygen());
                point.put("exchange", saveAmount(r.committed.getValue().exchange)); row.put("committed", point);
            }
            regionRows.add(row);
        }
        tag.put("geometries", geometryRows); tag.put("regions", regionRows);
        var displacedRows = new ListTag();
        displaced.forEach((cell, stored) -> {
            var row = saveAmount(stored.amount); row.putLong("position", cell);
            row.put("exchange", saveAmount(stored.exchange)); row.put("origin", saveOrigin(stored.origin)); displacedRows.add(row);
        });
        tag.put("displaced", displacedRows);
        tag.put("emissions", saveEntries(history.recent()));
        tag.put("cumulative", saveEntries(history.cumulative()));
        tag.putLong("input_checkpoint", inputs.checkpoint());
        tag.put("input_recent", saveEntries(inputs.recent()));
        tag.put("input_cumulative", saveEntries(inputs.cumulative()));
        tag.putLong("pending_checkpoint", pending.checkpoint());
        tag.put("pending_recent", saveEntries(pending.recent()));
        tag.put("pending_cumulative", saveEntries(pending.cumulative()));
        var archivedInput = history.archivedInput();
        tag.putDouble("archived_heat", archivedInput.heatJ()); tag.putDouble("archived_co", archivedInput.coMol());
        tag.putDouble("archived_co2", archivedInput.co2Mol()); tag.putDouble("archived_oxygen", archivedInput.oxygenMol());
        return tag;
    }

    private static CompoundTag saveOrigin(Origin origin) {
        var row = new CompoundTag(); row.putLong("from", origin.from); row.putLong("until", origin.until);
        row.putDouble("volume", origin.volume); row.putInt("walls", origin.walls);
        row.putInt("openings", origin.openings); row.putDouble("height", origin.height);
        row.putDouble("temperature", origin.temperature); return row;
    }
    private static Origin loadOrigin(CompoundTag row) {
        return new Origin(row.getLong("from"), row.getLong("until"), row.getDouble("volume"),
                row.getInt("walls"), row.getInt("openings"), row.getDouble("height"), row.getDouble("temperature"));
    }

    private static CompoundTag saveAmount(RoomPhysics.Amount amount) {
        var row = new CompoundTag();
        row.putDouble("heat", amount.heatJ()); row.putDouble("co", amount.coMol());
        row.putDouble("co2", amount.co2Mol()); row.putDouble("oxygen", amount.oxygenMol()); return row;
    }
    private static RoomPhysics.Amount loadAmount(CompoundTag row) {
        return new RoomPhysics.Amount(row.getDouble("heat"), row.getDouble("co"), row.getDouble("co2"), row.getDouble("oxygen"));
    }

    private static ListTag saveEntries(List<RoomHistory.Entry> entries) {
        var rows = new ListTag();
        for (var p : entries) rows.add(new LongArrayTag(new long[]{
                p.position(), p.from(), p.to(), p.context(),
                Double.doubleToRawLongBits(p.amount().heatJ()), Double.doubleToRawLongBits(p.amount().coMol()),
                Double.doubleToRawLongBits(p.amount().co2Mol()), Double.doubleToRawLongBits(p.amount().oxygenMol())}));
        return rows;
    }
    static List<RoomHistory.Entry> loadEntries(ListTag rows) {
        var entries = new ArrayList<RoomHistory.Entry>();
        for (var item : rows) {
            var row = ((LongArrayTag) item).getAsLongArray();
            if (row.length != 8) throw new IllegalArgumentException("室内通知の保存行が不正です");
            entries.add(new RoomHistory.Entry(row[0], row[1], row[2],
                    new RoomPhysics.Amount(Double.longBitsToDouble(row[4]), Double.longBitsToDouble(row[5]),
                            Double.longBitsToDouble(row[6]), Double.longBitsToDouble(row[7])), Math.toIntExact(row[3])));
        }
        return entries;
    }

    static RoomAir load(ServerLevel level, CompoundTag tag) {
        if (tag.getInt("format") != 9) throw new IllegalStateException("室内の保存形式が不正です");
        var data = new RoomAir(level);
        data.past = RoomPast.load(tag);
        data.nextRegionId = tag.getInt("next_region");
        for (var item : tag.getList("receipts", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) item;
            data.receipts.restore(row.getUUID("source"), new SourceReceipts.Last(row.getLong("sequence"), row.getString("payload")));
        }
        var shapes = tag.getList("geometries", Tag.TAG_COMPOUND);
        for (var item : tag.getList("regions", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) item;
            int shape = row.getInt("geometry");
            if (shape < 0 || shape >= shapes.size()) throw new IllegalArgumentException("室幾何の参照が不正です");
            var g = shapes.getCompound(shape); var cells = g.getLongArray("cells");
            if (cells.length == 0 || cells.length > 4096) throw new IllegalArgumentException("室の容積が不正です");
            var parents = new HashMap<Integer, Integer>();
            for (var parent : row.getList("parents", Tag.TAG_COMPOUND)) {
                var entry = (CompoundTag) parent;
                if (entry.getInt("id") < 0 || !data.regionIds.containsKey(entry.getInt("id")) || entry.getInt("count") <= 0)
                    throw new IllegalArgumentException("室履歴の親が不正です");
                parents.put(entry.getInt("id"), entry.getInt("count"));
            }
            var chunks = new HashSet<ChunkPos>();
            for (long cell : cells) chunks.add(new ChunkPos(BlockPos.of(cell)));
            var r = new Region(row.getInt("id"), row.getLong("from"), cells, g.getInt("walls"), g.getInt("openings"), g.getDouble("height"), parents, chunks, g.getLongArray("portals"));
            var model = data.past.model(cells[0], r.from, r.id);
            if (model != null) r.pastModel = model;
            r.ready = true;
            r.replayFrom = row.getLong("replay_from");
            for (var itemGroup : row.getList("groups", Tag.TAG_COMPOUND)) {
                var group = (CompoundTag) itemGroup;
                r.groups.put(group.getLong("key"), group.getDouble("share"));
            }
            r.anchor = row.getLong("anchor");
            r.recoveredCells = row.getInt("recovered_cells");
            r.observedFrom = row.getLong("observed_from");
            r.recoveredExchange = loadAmount(row.getCompound("recovered_exchange"));
            r.adjustmentExchange = loadAmount(row.getCompound("adjustment_exchange"));
            r.recovered = loadAmount(row.getCompound("recovered"));
            r.adjustment = loadAmount(row.getCompound("adjustment"));
            for (var itemState : row.getList("checkpoints", Tag.TAG_COMPOUND)) {
                var state = (CompoundTag) itemState;
                r.checkpoints.put(state.getLong("tick"), new Snapshot(new RoomPhysics.State(state.getDouble("temperature"), state.getDouble("co"),
                        state.getDouble("co2"), state.getDouble("oxygen")), loadAmount(state.getCompound("exchange"))));
            }
            if (row.contains("committed")) {
                var point = row.getCompound("committed");
                r.committed = Map.entry(point.getLong("tick"), new Snapshot(new RoomPhysics.State(point.getDouble("temperature"),
                        point.getDouble("co"), point.getDouble("co2"), point.getDouble("oxygen")), loadAmount(point.getCompound("exchange"))));
            }
            data.regionIds.put(r.id, r);
            r.to = row.getLong("to"); data.regions.add(r);
            for (var c : chunks) data.byChunk.computeIfAbsent(c.toLong(), key -> new ArrayList<>()).add(r);
            for (long cell : r.portals) data.skyColumns.computeIfAbsent(column(BlockPos.of(cell)), key -> new HashSet<>()).add(r);
        }
        for (var item : tag.getList("displaced", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) item; data.displaced.put(row.getLong("position"), new Stored(loadAmount(row), loadAmount(row.getCompound("exchange")),
                    loadOrigin(row.getCompound("origin"))));
        }
        data.history.restore(tag.getLong("checkpoint_tick"), loadEntries(tag.getList("cumulative", Tag.TAG_LONG_ARRAY)),
                loadEntries(tag.getList("emissions", Tag.TAG_LONG_ARRAY)));
        data.inputs.restore(tag.getLong("input_checkpoint"), loadEntries(tag.getList("input_cumulative", Tag.TAG_LONG_ARRAY)),
                loadEntries(tag.getList("input_recent", Tag.TAG_LONG_ARRAY)));
        data.pending.restore(tag.getLong("pending_checkpoint"), loadEntries(tag.getList("pending_cumulative", Tag.TAG_LONG_ARRAY)),
                loadEntries(tag.getList("pending_recent", Tag.TAG_LONG_ARRAY)));
        data.history.restoreArchivedInput(new RoomPhysics.Amount(tag.getDouble("archived_heat"), tag.getDouble("archived_co"),
                tag.getDouble("archived_co2"), tag.getDouble("archived_oxygen")));
        for (var model : data.past.outdoors(level.getGameTime())) data.watchOutdoor(model);
        return data;
    }
}
