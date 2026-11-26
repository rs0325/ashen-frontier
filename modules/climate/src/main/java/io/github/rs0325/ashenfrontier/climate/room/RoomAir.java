package io.github.rs0325.ashenfrontier.climate.room;

import io.github.rs0325.ashenfrontier.core.NbtLists;
import io.github.rs0325.ashenfrontier.climate.ClimateQuery;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentRetention;
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
        GENERAL_POSITIONS=b.defineInRange("generalPositions",4096,0,65536);
        GENERAL_REFERENCES=b.defineInRange("generalReferences",16384,0,1048576);
        GENERAL_BYTES=b.defineInRange("generalEncodedBytes",8388608L,0L,1073741824L);
        GENERAL_DAYS=b.defineInRange("generalRetentionGameDays",1.0,0.0,3650.0);
        SPEC = b.build();
    }
    private record Fuel(double carbon, double hydrogen, double oxygen, double coKgPerKg, String source) {}
    private static final Map<String, Fuel> FUELS = loadFuels();
    private final ServerLevel level;
    private Outside outside;
    private java.util.function.LongSupplier outsideRevision;
    private long sampleTick=-1,sampleOutsideRevision=-1;
    private final Map<String,Snapshot> sampleMemo=new HashMap<>();
    private record SampleKey(long position,long tick) {}
    private final Map<SampleKey,Sample> sampleCache=new HashMap<>();
    private void clearSamples() { sampleMemo.clear();sampleCache.clear(); }
    private RoomRetention retention=new RoomRetention();
    private Long forgetting;
    private Outdoor forgettingOutdoor;
    private java.util.Iterator<ChunkPos> forgettingChunks;
    private static final ModConfigSpec.IntValue GENERAL_POSITIONS,GENERAL_REFERENCES;
    private static final ModConfigSpec.LongValue GENERAL_BYTES;
    private static final ModConfigSpec.DoubleValue GENERAL_DAYS;

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
    private record FailedSearch(long origin,Status status) {}
    private FailedSearch lastFailedSearch;
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
    private boolean flattening;
    private long compactingAt=-1,compactingBoundary=-1;
    private final Map<Long,Long> generalSearches=new HashMap<>();
    private long preparingBytes;
    private final java.util.ArrayDeque<RoomRetention.Key> releases=new java.util.ArrayDeque<>();
    private RoomRetention.Key forgettingRelease;
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
        final Set<Long> retainedPositions = new HashSet<>();
        final Set<ChunkPos> chunks;
        final NavigableMap<Long, Snapshot> checkpoints = new TreeMap<>();
        final long[] portals;
        boolean ready;
        RoomPast.Model pastModel;
        Map.Entry<Long, Snapshot> committed;
        long inputRevision=-1,archiveStart;
        List<RoomHistory.Entry> localInputs=List.of();
        Region(int id, long from, long[] cells, int walls, int openings, double height, Map<Integer, Integer> parents,
               Set<ChunkPos> chunks, long[] portals) {
            this.id = id; this.from = from; this.replayFrom = from; this.cells = cells; this.walls = walls; this.openings = openings;
            this.height = height; this.parents = parents; this.observedFrom = from;
            this.chunks = chunks; this.portals = portals;
            this.pastModel = new RoomPast.Model(id, from, cells, walls, openings, height);
        }
        boolean contains(long cell) { return Arrays.binarySearch(cells, cell) >= 0; }
        boolean receives(long position) { return weight(position) > 0; }
        double weight(long position) { var value=groups.get(position);return value!=null?value:contains(position)?1.0:0.0; }
    }

    private RoomAir(ServerLevel level) { this.level = level; }
    public static RoomAir get(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("室内操作はサーバースレッド専用です");
        return level.getDataStorage().computeIfAbsent(new Factory<>(() -> new RoomAir(level),
                (tag, registries) -> load(level, tag), null), ID);
    }
    public static void install(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC, "ashen_frontier_rooms-server.toml");
        NeoForge.EVENT_BUS.addListener((EnvironmentRetention event)->{
            get(event.level()).retention(event);
        });
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
                if (storage != null && server.getGameTime() % 5 == 0) {
                    long start=System.nanoTime(),remaining=EnvironmentBudget.remaining(server);
                    if(remaining>0)storage.work(start+Math.min(1_000_000,remaining));
                    EnvironmentBudget.charge(server,System.nanoTime()-start);
                }
            }
        });
    }

    /** #135が実屋外履歴を接続する。nullは切断。既定の気温・気圧・ガスは持たない。 */
    public void outside(Outside provider) {
        outside(provider,null);
    }

    /** 確定済み屋外応答の破棄・変更で進む版がある場合だけ、同tickの純再生を再利用する。追記は既存値を変えない。 */
    public void outside(Outside provider,java.util.function.LongSupplier revision) {
        clearSamples();outsideRevision=revision;
        if (outside != null) regions.forEach(r -> r.checkpoints.clear());
        outside = provider;
        if (provider != null) { refreshPast(); flushPending(); }
    }

    private double retentionTime() {
        return ClimateQuery.elapsedGameSeconds(level,level.getGameTime()).orElse(0);
    }

    private void retention(EnvironmentRetention event) {
        var key=new RoomRetention.Key(event.owner(),event.generation());
        if(event.generation()<=0||event.from()<0||event.tick()<0||event.tick()>level.getGameTime()||event.sequence()<0)
            throw new IllegalArgumentException("保持通知の時刻/番号が不正です");
        if(retention.released(key)) {
            event.provide(event.action()!=EnvironmentRetention.Action.RETAIN);return;
        }
        switch(event.action()) {
            case RETAIN -> {
                var prior=retention.leases.get(key);
                if(prior!=null&&prior.releasing) { event.provide(false);return; }
                if(!retention.leases.containsKey(key)&&event.from()<level.getGameTime()&&!past.known(event.position().asLong(),event.from()))
                    throw new IllegalArgumentException("未観測の過去へ保持登録できません");
                boolean created=!retention.leases.containsKey(key);
                retention.retain(event.owner(),event.generation(),event.position().asLong(),event.from());
                removeGeneralSearch(event.position().asLong());
                if(created&&retention.leases.size()==16385)com.mojang.logging.LogUtils.getLogger().warn("C06の保持登録が注意目安16384件を超えました。受付は継続します");
                if(created) {
                    sample(event.position(),level.getGameTime());
                    var region=latest(event.position().asLong());
                    if(region!=null&&region.to==Long.MAX_VALUE)region.retainedPositions.add(event.position().asLong());
                }
                event.provide(true);
            }
            case COMMIT -> {
                if(event.sequence()>receipts.lastSequence(event.owner()))
                    throw new IllegalArgumentException("未受領の熱通知を保持確定できません");
                retention.commit(key,event.tick(),event.sequence());event.provide(true);
            }
            case END -> { retention.end(key,event.tick(),event.sequence());event.provide(true); }
            case RELEASE -> {
                var lease=retention.require(key);
                if(lease.until==Long.MAX_VALUE||lease.committed<lease.until)event.provide(false);
                else if(retention.owners.get(lease.position)>1||retention.physical.contains(lease.position)
                        ||past.physicalHistory(lease.position)||past.references(lease.position)==0)
                    event.provide(releaseLease(key));
                else {
                    if(!lease.releasing) { lease.releasing=true;releases.addLast(key); }
                    event.provide(false);
                }
            }
        }
        setDirty();
    }

    private boolean releaseLease(RoomRetention.Key key) {
        long cell=retention.require(key).position;
        boolean released=retention.release(key);
        if(released&&!retention.protectedPosition(cell)&&past.references(cell)==0) {
            jobs.remove(cell);
            var registration=registrations.remove(cell);if(registration!=null)registration.discard();
            removeGeneralSearch(cell);
        }
        return released;
    }

    public record RetentionUsage(int owners,int registeredPositions,int physicalPositions,
                                 int generalPositions,long references,long encodedBytes) {}
    /** 計数済みの保持量を返す。物理参照と消費側位置は重複しうる。 */
    public RetentionUsage retentionUsage() {
        return new RetentionUsage(retention.leases.size(),retention.owners.size(),retention.physical.size(),
                retention.general.size()+generalSearches.size(),retention.references+generalSearches.size(),
                retention.general.isEmpty()&&generalSearches.isEmpty()?0:retention.bytes+preparingBytes+56);
    }

    private void reserveGeneralSearch(long cell,long bytes) {
        Long old=generalSearches.put(cell,bytes);preparingBytes+=bytes-(old==null?0:old);
    }
    private void removeGeneralSearch(long cell) {
        Long old=generalSearches.remove(cell);if(old!=null)preparingBytes-=old;
        if(jobs.isEmpty())changedChunks.clear();
    }
    private void generalLimits(int reserved,long bytes) {
        retention.positions=Math.max(0,GENERAL_POSITIONS.get()-reserved);
        retention.referenceLimit=Math.max(0,GENERAL_REFERENCES.get()-reserved);
        retention.byteLimit=Math.max(0,GENERAL_BYTES.get()-bytes);
        retention.lifetime=GENERAL_DAYS.get()*86400;
    }

    private void collectGeneral(long deadline) {
        generalLimits(generalSearches.size(),preparingBytes);
        var skipped=new HashSet<Long>();
        for(int operations=0;operations<64&&System.nanoTime()<deadline;operations++) {
            if(forgetting==null) {
                Long cell=null;
                if(!releases.isEmpty()) {
                    var key=releases.removeFirst();var lease=retention.leases.get(key);
                    if(lease==null)continue;
                    if(retention.owners.getOrDefault(lease.position,0)>1||retention.physical.contains(lease.position)
                            ||past.physicalHistory(lease.position)||past.references(lease.position)==0) {
                        releaseLease(key);setDirty();continue;
                    }
                    if(!past.forgettable(lease.position)) { releases.addLast(key);skipped.add(lease.position); }
                    else { cell=lease.position;forgettingRelease=key; }
                }
                if(cell==null) {
                    cell=retention.candidate(retentionTime(),skipped);
                    if(cell==null)return;
                    if(retention.protectedPosition(cell)||past.physicalHistory(cell)) { retention.physical(cell);continue; }
                    if(!past.forgettable(cell)) { skipped.add(cell);continue; }
                }
                forgetting=cell;forgettingOutdoor=outdoors.remove(cell);
                if(forgettingOutdoor!=null)past.end(forgettingOutdoor.model,level.getGameTime());
                forgettingChunks=forgettingOutdoor==null?java.util.Collections.emptyIterator():forgettingOutdoor.chunks.iterator();
            }
            if(forgettingChunks.hasNext()) {
                var chunk=forgettingChunks.next();var records=outdoorChunks.get(chunk.toLong());
                if(records!=null) { records.remove(forgettingOutdoor);if(records.isEmpty())outdoorChunks.remove(chunk.toLong()); }
            } else {
                boolean releasedOnly=forgettingRelease!=null&&retention.leases.containsKey(forgettingRelease)
                        &&retention.owners.getOrDefault(forgetting,0)==1&&!retention.physical.contains(forgetting)
                        &&!past.physicalHistory(forgetting);
                if((!retention.protectedPosition(forgetting)||releasedOnly)&&past.forgettable(forgetting)) {
                    past.forgetOutdoor(forgetting);
                    if(retention.general.containsKey(forgetting))retention.forget(forgetting);
                } else if(past.physicalHistory(forgetting))retention.physical(forgetting);
                if(forgettingRelease!=null&&retention.leases.containsKey(forgettingRelease))releaseLease(forgettingRelease);
                forgetting=null;forgettingOutdoor=null;forgettingChunks=null;forgettingRelease=null;setDirty();
            }
        }
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
        if(record.model.until!=Long.MAX_VALUE)return;
        past.end(record.model,tick);
        outdoors.remove(record.model.cells[0]);
        for (var chunk : record.chunks) {
            var records = outdoorChunks.get(chunk.toLong());
            if (records != null) { records.remove(record); if (records.isEmpty()) outdoorChunks.remove(chunk.toLong()); }
        }
        setDirty();
    }
    private boolean observeOutdoor(RoomSearch search,long tick) {
        long cell = search.origin.asLong();
        var model = past.outdoor(cell,tick);
        if(past.physicalHistory(cell))retention.physical(cell);
        if(!retention.protectedPosition(cell)&&forgetting!=null&&forgetting==cell)return false;
        double seen=retentionTime();
        int count=past.references(cell);
        long size=past.outdoorBytes(cell);
        if(model==null) { if(count<64) { count++;size+=142; } if(!RoomSearch.exposed(level,search.origin))size+=12+8L*search.visited.size(); }
        long reserved=generalSearches.getOrDefault(cell,0L);
        generalLimits(generalSearches.size()-(reserved>0?1:0),preparingBytes-reserved);
        if(!retention.observe(cell,seen,Math.max(1,count),size))return false;
        if (model == null) {
            past.observeOutdoor(nextRegionId++,tick,cell,search.visited.toLongArray(),RoomSearch.exposed(level,search.origin));
            model = past.outdoor(cell,tick); setDirty();
        }
        if (!outdoors.containsKey(cell)) watchOutdoor(model);
        var registration = registrations.remove(cell);
        if (registration != null) registration.discard();
        jobs.remove(cell);removeGeneralSearch(cell);return true;
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
                if(retention.owners.containsKey(origin.asLong()))sample(origin,tick);
            }
        }
    }

    /** 部屋探索を要求し、現在または過去の状態を返す。airPositionは設備直上/生理の目位置の空間。 */
    public Sample sample(BlockPos airPosition, long tick) {
        flushPending();
        if (tick < 0 || tick > level.getGameTime()) throw new IllegalArgumentException("室内照会時刻が不正です");
        if (!ENABLED.get()) return unknown(Status.DISABLED);
        Status previousFailure=null;
        if(tick==level.getGameTime()&&lastFailedSearch!=null&&lastFailedSearch.origin==airPosition.asLong()) {
            previousFailure=lastFailedSearch.status;lastFailedSearch=null;
        }
        if (tick == level.getGameTime() && !level.hasChunkAt(airPosition)) return unknown(Status.UNLOADED);
        Region region = byChunk.getOrDefault(chunk(airPosition.asLong()),List.of()).stream().filter(r -> r.ready && r.from <= tick && tick < r.to && r.contains(airPosition.asLong()))
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
            else {
                retention.observe(airPosition.asLong(),retentionTime(),past.references(airPosition.asLong()),past.outdoorBytes(airPosition.asLong()));
                setDirty();
                return outdoorSample(airPosition,tick);
            }
        }
        if (region == null && tick == level.getGameTime() && level.hasChunkAt(airPosition)
                && RoomSearch.passable(level, airPosition) && RoomSearch.exposed(level, airPosition)) {
            var observation = new RoomSearch(airPosition,tick);
            observation.status = RoomSearch.Status.OUTDOOR; observation.visited.add(airPosition.asLong());
            if(!observeOutdoor(observation,tick))return unknown(Status.SCANNING);
            return outdoorSample(airPosition,tick);
        }
        if (region == null) {
            if (tick < level.getGameTime() && !jobs.containsKey(airPosition.asLong())) return unknown(Status.HISTORY_MISSING);
            var priorJob = jobs.get(airPosition.asLong());
            if (priorJob != null && priorJob.status == RoomSearch.Status.OUTDOOR && tick == level.getGameTime()) {
                if (!level.hasChunkAt(airPosition)) return unknown(Status.UNLOADED);
                if (!RoomSearch.exposed(level, airPosition)) { jobs.remove(airPosition.asLong());removeGeneralSearch(airPosition.asLong()); }
            }
            if(!jobs.containsKey(airPosition.asLong())&&!retention.protectedPosition(airPosition.asLong())
                    &&(generalSearches.size()>=GENERAL_POSITIONS.get()-retention.general.size()
                    ||retention.references+generalSearches.size()+1>GENERAL_REFERENCES.get()
                    ||retention.bytes+preparingBytes+220+56>GENERAL_BYTES.get())) {
                retention.requestCollection();return unknown(Status.SCANNING);
            }
            var search = jobs.computeIfAbsent(airPosition.asLong(), p -> {
                if(!retention.protectedPosition(p))reserveGeneralSearch(p,220);
                return new RoomSearch(airPosition,tick);
            });
            if(previousFailure!=null)return unknown(previousFailure);
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
        Map<String,Snapshot> memo;
        if(outsideRevision==null)memo=new HashMap<>();
        else {
            long version=outsideRevision.getAsLong();
            if(sampleTick!=level.getGameTime()||sampleOutsideRevision!=version||sampleMemo.size()+sampleCache.size()>=512) {
                clearSamples();sampleTick=level.getGameTime();sampleOutsideRevision=version;
            }
            memo=sampleMemo;
        }
        var key=new SampleKey(airPosition.asLong(),tick);
        if(outsideRevision!=null) {
            var cached=sampleCache.get(key);if(cached!=null)return cached;
        }
        var state = evaluate(region, tick, memo);
        if(memo==sampleMemo&&sampleMemo.size()+sampleCache.size()>=512)clearSamples();
        if (state.isEmpty()) return unknown(Status.MISSING_BOUNDARY);
        var out = boundary(airPosition, tick, tick);
        if (out.isEmpty()) return unknown(Status.MISSING_BOUNDARY);
        double q = exchange(region, state.get(), out.get().getFirst().air);
        var result=new Sample(Status.INDOOR, state, true, region.openings > 0, region.cells.length, q, accuracy(airPosition,tick));
        if(outsideRevision!=null)sampleCache.put(key,result);
        return result;
    }

    /** C04が日長/気象境界と併合する室構成・発生源の境界。端点を含む昇順列。 */
    public List<Long> boundaries(BlockPos airPosition, long from, long to) {
        var points = new TreeSet<Long>(List.of(from, to));
        past.cuts(airPosition.asLong(),from,to,points);
        var localRooms=byChunk.getOrDefault(chunk(airPosition.asLong()),List.of()).stream()
                .filter(r->r.contains(airPosition.asLong())).toList();
        for (var r : localRooms) {
            if (r.from > from && r.from < to) points.add(r.from);
            if (r.to > from && r.to < to) points.add(r.to);
        }
        for(var r:localRooms)for (var p : localInputs(r)) if (p.from() < to && p.to() >= from
                && r.replayFrom <= p.to() && p.from() < r.to) {
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
            clearSamples();
            if(amount.heatJ()!=0||amount.coMol()!=0||amount.co2Mol()!=0||amount.oxygenMol()!=0)
                retention.physical(pos.asLong());
            var owner = latest(pos.asLong());
            int context = from == to && to == level.getGameTime() && owner != null && owner.to == Long.MAX_VALUE ? owner.id : -1;
            inputs.add(new RoomHistory.Entry(pos.asLong(), from, to, amount, context));
            route(pos, from, to, amount);
            setDirty();
        }
        return result;
    }

    private void route(BlockPos pos, long from, long to, RoomPhysics.Amount amount) {
        var localRooms=byChunk.getOrDefault(chunk(pos.asLong()),List.of()).stream().filter(r->r.ready).toList();
        var cuts = new TreeSet<Long>(List.of(from, to));
        for (var room : localRooms) if (room.contains(pos.asLong())) {
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
            var owner = localRooms.stream().filter(r -> r.from <= a && a < r.to && r.contains(pos.asLong()))
                    .max(Comparator.comparingInt(r -> r.id)).orElse(null);
            if (owner == null) {
                owner = localRooms.stream().filter(r -> r.observedFrom <= a && r.from >= b && r.contains(pos.asLong()))
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
            else if (a < history.checkpoint() || localRooms.stream().anyMatch(r -> r.committed != null
                    && r.contains(pos.asLong()) && (a < r.committed.getKey() || a == b && a == r.committed.getKey()))) {
                if (projectPast(pos, a, b, part)) history.archiveInput(part);
                else pending.add(new RoomHistory.Entry(pos.asLong(), a, b, part));
            } else {
                history.append(new RoomHistory.Entry(owner.anchor, a, b, part));
                if (part.heatJ() != 0 || part.coMol() != 0 || part.co2Mol() != 0 || part.oxygenMol() != 0) for (var r : regions)
                    if (r.receives(owner.anchor)) r.checkpoints.tailMap(a, a==b).clear();
                if (history.retainedCount() > RoomHistory.LIMIT) {
                    if(compactingAt<0)compactingAt=level.getGameTime();
                    compactingBoundary=history.compactionBoundary();
                    var positions=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                    for(var entry:history.entries())positions.add(entry.position());
                    var affectedRooms=regions.stream().filter(r->r.from<=retentionFrontier(r)
                            &&positions.longStream().anyMatch(r::receives)).toList();
                    var observed = new HashMap<Integer, Snapshot>();
                    var memo = new HashMap<String, Snapshot>();
                    for (var r : affectedRooms)
                        simulate(r,retentionFrontier(r),memo).ifPresent(value->observed.put(r.id,value));
                    if(observed.size()!=affectedRooms.size())continue;
                    history.compactIfNeeded();
                    for (var r : regions) {
                        r.checkpoints.clear();
                        var value = observed.get(r.id);
                        if (value != null) commit(r,retentionFrontier(r),value);
                    }
                    compactingAt=-1;compactingBoundary=-1;
                }
            }
        }
    }

    private long retentionFrontier(Region room) {
        long through=Math.min(compactingAt>=0?compactingAt:level.getGameTime(),room.to);
        for(long cell:room.retainedPositions)through=Math.min(through,retention.minimum(cell));
        long compacted=Math.min(compactingBoundary,Math.min(level.getGameTime(),room.to));
        return Math.max(room.from,Math.max(through,compacted));
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
        clearSamples();
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
        RoomPhysics.State state;
        RoomPhysics.Amount exchanged;
        if(checkpoint!=null) {
            state=checkpoint.getValue().state;exchanged=checkpoint.getValue().exchange;
        } else {
            var ambient = RoomPhysics.State.ambient(rows.get().getFirst().air);
            var quantity = ZERO;
            exchanged = ZERO;
            int known = r.recoveredCells;
            for (var parent : r.parents.entrySet()) {
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
            state = RoomPhysics.state(quantity, r.cells.length, r.walls);
        }
        var relevant = localInputs(r);
        var cuts = new TreeSet<Long>(List.of(base, tick));
        var deltas = new TreeMap<Long, RoomPhysics.Rate>();
        var instants = new HashMap<Long, RoomPhysics.Amount>();
        if (r.from >= base && r.from <= tick && !(checkpoint != null && r.from == base)) {
            cuts.add(r.from);
            instants.put(r.from, RoomHistory.plus(r.recovered, r.adjustment));
        }
        for (var row : rows.get()) { cuts.add(row.from); cuts.add(row.to); }
        for (var pulse : relevant) {
            if(pulse.to()<base||pulse.from()>tick)continue;
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
            if(!flattening&&i>=128&&rows.get().size()>128
                    &&EnvironmentBudget.remaining(level)!=Long.MAX_VALUE)return Optional.empty();
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
        localInputs(room);
        long start = room.archiveStart;
        long end = history.checkpoint();
        if (end <= start) return;
        long stride = Math.max(1, (end - start + 127) / 128);
        for (int n = 1; n < 128; n++) {
            long tick = start + n * stride;
            if (tick > from && tick < to && tick < end) cuts.add(tick);
        }
    }

    private List<RoomHistory.Entry> localInputs(Region room) {
        if(room.inputRevision!=history.revision()) {
            room.localInputs=history.matching(room::receives);
            room.archiveStart=history.cumulative().stream().filter(p->room.receives(p.position()))
                    .mapToLong(RoomHistory.Entry::from).min().orElse(history.checkpoint());
            room.inputRevision=room.to==Long.MAX_VALUE?history.revision():-1;
        }
        var result=room.localInputs;
        if(room.to!=Long.MAX_VALUE)room.localInputs=List.of();
        return result;
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
        boolean step(int budget) { return step(budget,System.nanoTime()+1_000_000); }
        boolean step(int budget,long deadline) {
            var active = latest(job.origin.asLong());
            if (active != null && active.to == Long.MAX_VALUE) { discard(); return true; }
            for (int used = 0; used < budget; used++) {
                if (used > 0 && System.nanoTime()>=deadline) return false;
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
                        long cell=cells[pastCursor++];
                        past.bind(cell, room.pastModel);
                        if(retention.owners.containsKey(cell))room.retainedPositions.add(cell);
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
            room.inputRevision=-1;
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
    public void work() { work(System.nanoTime()+1_000_000); }
    private void work(long deadline) {
        clearSamples();
        try {
            if (!ENABLED.get()) return;
            collectGeneral(deadline);
            if(jobs.isEmpty()||System.nanoTime()>=deadline)return;
            var job = jobs.values().stream().filter(j -> j.status == RoomSearch.Status.SCANNING || j.status==RoomSearch.Status.OUTDOOR
                    || registrations.containsKey(j.origin.asLong())).findFirst().orElse(null);
            if (job == null) return;
            if(job.status==RoomSearch.Status.OUTDOOR) { observeOutdoor(job,level.getGameTime());return; }
            if (job.status == RoomSearch.Status.SCANNING) {
                int visits=VISITS.get();
                if(generalSearches.containsKey(job.origin.asLong())) {
                    long available=GENERAL_BYTES.get()-retention.bytes-preparingBytes-56;
                    if(available<8) { retention.requestCollection();return; }
                    visits=(int)Math.min(visits,available/8);
                }
                job.work(level,visits,4096,deadline);
                if(generalSearches.containsKey(job.origin.asLong()))
                    reserveGeneralSearch(job.origin.asLong(),220+8L*job.visited.size());
                if (job.status == RoomSearch.Status.OUTDOOR) { observeOutdoor(job,level.getGameTime()); return; }
                if (job.status != RoomSearch.Status.INDOOR) {
                    if(job.status!=RoomSearch.Status.SCANNING) {
                        lastFailedSearch=new FailedSearch(job.origin.asLong(),Status.valueOf(job.status.name()));
                        jobs.remove(job.origin.asLong());
                        removeGeneralSearch(job.origin.asLong());
                    }
                    return;
                }
                var existing = latest(job.origin.asLong());
                if (existing != null && existing.to == Long.MAX_VALUE) { jobs.remove(job.origin.asLong());removeGeneralSearch(job.origin.asLong()); return; }
                registrations.put(job.origin.asLong(), new Registration(job));
                return;
            }
            var registration = registrations.get(job.origin.asLong());
            if (registration.step(VISITS.get(),deadline)) {
                registrations.remove(job.origin.asLong()); jobs.remove(job.origin.asLong());removeGeneralSearch(job.origin.asLong());
                if (jobs.isEmpty()) changedChunks.clear();
                setDirty();
            } else if (job.status == RoomSearch.Status.HISTORY_MISSING) {
                registration.discard(); registrations.remove(job.origin.asLong()); jobs.remove(job.origin.asLong());removeGeneralSearch(job.origin.asLong());
            }
        } finally { clearSamples(); }
    }

    /** 構成の変更時刻まで旧幾何を維持し、その時刻を新しい探索の境界にする。 */
    public void changed(BlockPos pos) {
        clearSamples();
        lastFailedSearch=null;
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
                removeGeneralSearch(entry.getKey());iterator.remove();
            }
        }
        if(jobs.isEmpty())changedChunks.clear();
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
        var observe=new HashSet<Long>();
        for (var r : affected) if (r.to == Long.MAX_VALUE) {
            for(long cell:r.retainedPositions)if(retention.owners.containsKey(cell))observe.add(cell);
            retire(r, tick);
        }
        for(long cell:observe)sample(BlockPos.of(cell),tick);
    }

    private void retire(Region r, long tick) {
        if (r.to != Long.MAX_VALUE) return;
        simulate(r, tick, new HashMap<>()).ifPresent(value -> commit(r, tick, value));
        while (r.checkpoints.size() > 2) r.checkpoints.pollFirstEntry();
        r.to = tick; past.end(r.pastModel, tick);
        r.inputRevision=-1;r.localInputs=List.of();
        if (!Double.isFinite(r.pastModel.temperature)) simulate(r, r.from, new HashMap<>())
                .ifPresent(value -> r.pastModel.temperature = value.state.temperatureK());
        setDirty();
    }

    private boolean prepareFlattening(Region child) {
        long beginning=child.replayFrom;
        for(int parent:child.parents.keySet())beginning=Math.min(beginning,regionIds.get(parent).replayFrom);
        return boundary(BlockPos.of(child.cells[0]),beginning,child.from).isPresent();
    }

    private boolean canTrim(Region candidate) {
        if (regions.stream().filter(r -> r.to != Long.MAX_VALUE).count() <= 64) return true;
        var old = regions.stream().filter(r -> r.to != Long.MAX_VALUE).findFirst().orElseThrow();
        var memo = new HashMap<String, Snapshot>();
        for(var child:regions)if(child!=old&&child.groups.containsKey(old.anchor))
            if(!prepareFlattening(child))return false;
        if(candidate.groups.containsKey(old.anchor)&&!prepareFlattening(candidate))return false;
        if (evaluate(old, old.to, memo).isEmpty()) return false;
        for (var child : regions) if (child != old && child.groups.containsKey(old.anchor)
                && evaluate(child, child.from, memo).isEmpty()) return false;
        return !candidate.groups.containsKey(old.anchor) || evaluate(candidate, candidate.from, memo).isPresent();
    }

    private void trimGenerations() {
        flattening=true;
        try {
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
            for (var retained : regions) { retained.groups.remove(old.anchor); retained.inputRevision=-1;retained.checkpoints.clear(); }
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
                if (value != null) commit(r,Math.min(level.getGameTime(),r.to),value);
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
        } finally { flattening=false; }
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
        clearSamples();
        tag.putInt("format", 10);tag.put("retention",RoomRetentionCodec.save(retention));
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

    /** SavedDataが読取失敗を新規データへ置換する前に呼ぶ。試験保存の移行はしない。 */
    private static void verifyLists(CompoundTag tag) {
        for(String key:new String[]{"receipts","geometries","regions","displaced"})
            NbtLists.read(tag,key,Tag.TAG_COMPOUND);
        for(String key:new String[]{"cumulative","emissions","input_cumulative","input_recent","pending_cumulative","pending_recent"})
            NbtLists.read(tag,key,Tag.TAG_LONG_ARRAY);
        for(var item:NbtLists.read(tag,"regions",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)item;
            for(String key:new String[]{"parents","groups","checkpoints"})NbtLists.read(row,key,Tag.TAG_COMPOUND);
        }
    }

    public static void verify(CompoundTag tag) {
        if(tag.getInt("format")!=10||!tag.contains("retention",Tag.TAG_COMPOUND))
            throw new IllegalStateException("C06 format=10以外の試験保存は保全し、再生成してください");
        verifyLists(tag);
        var past=RoomPast.load(tag);
        RoomRetentionCodec.load(tag.getCompound("retention"),past::references,past::outdoorBytes);
    }

    static RoomAir load(ServerLevel level, CompoundTag tag) {
        if (tag.getInt("format") != 10) throw new IllegalStateException("室内の保存形式が不正です");
        verifyLists(tag);
        var data = new RoomAir(level);
        if(!tag.contains("retention",net.minecraft.nbt.Tag.TAG_COMPOUND))throw new IllegalStateException("室内の保持登録がありません");

        data.past = RoomPast.load(tag);
        data.retention=RoomRetentionCodec.load(tag.getCompound("retention"),data.past::references,data.past::outdoorBytes);
        for(var lease:data.retention.leases.values())if(lease.releasing)data.releases.addLast(lease.key);
        data.nextRegionId = tag.getInt("next_region");
        for (var item : NbtLists.read(tag,"receipts",Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) item;
            data.receipts.restore(row.getUUID("source"), new SourceReceipts.Last(row.getLong("sequence"), row.getString("payload")));
        }
        var shapes = NbtLists.read(tag,"geometries",Tag.TAG_COMPOUND);
        for (var item : NbtLists.read(tag,"regions",Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) item;
            int shape = row.getInt("geometry");
            if (shape < 0 || shape >= shapes.size()) throw new IllegalArgumentException("室幾何の参照が不正です");
            var g = shapes.getCompound(shape); var cells = g.getLongArray("cells");
            if (cells.length == 0 || cells.length > 4096) throw new IllegalArgumentException("室の容積が不正です");
            var parents = new HashMap<Integer, Integer>();
            for (var parent : NbtLists.read(row,"parents",Tag.TAG_COMPOUND)) {
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
            for (var itemGroup : NbtLists.read(row,"groups",Tag.TAG_COMPOUND)) {
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
            for (var itemState : NbtLists.read(row,"checkpoints",Tag.TAG_COMPOUND)) {
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
        for (var item : NbtLists.read(tag,"displaced",Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) item; data.displaced.put(row.getLong("position"), new Stored(loadAmount(row), loadAmount(row.getCompound("exchange")),
                    loadOrigin(row.getCompound("origin"))));
        }
        data.history.restore(tag.getLong("checkpoint_tick"), loadEntries(NbtLists.read(tag,"cumulative",Tag.TAG_LONG_ARRAY)),
                loadEntries(NbtLists.read(tag,"emissions",Tag.TAG_LONG_ARRAY)));
        data.inputs.restore(tag.getLong("input_checkpoint"), loadEntries(NbtLists.read(tag,"input_cumulative",Tag.TAG_LONG_ARRAY)),
                loadEntries(NbtLists.read(tag,"input_recent",Tag.TAG_LONG_ARRAY)));
        data.pending.restore(tag.getLong("pending_checkpoint"), loadEntries(NbtLists.read(tag,"pending_cumulative",Tag.TAG_LONG_ARRAY)),
                loadEntries(NbtLists.read(tag,"pending_recent",Tag.TAG_LONG_ARRAY)));
        data.history.restoreArchivedInput(new RoomPhysics.Amount(tag.getDouble("archived_heat"), tag.getDouble("archived_co"),
                tag.getDouble("archived_co2"), tag.getDouble("archived_oxygen")));
        for(long cell:data.retention.owners.keySet()) {
            var region=data.latest(cell);
            if(region!=null&&region.to==Long.MAX_VALUE)region.retainedPositions.add(cell);
        }
        for (var model : data.past.outdoors(level.getGameTime())) data.watchOutdoor(model);
        return data;
    }
}
