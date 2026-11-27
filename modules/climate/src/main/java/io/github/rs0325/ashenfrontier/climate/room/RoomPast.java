package io.github.rs0325.ashenfrontier.climate.room;

import io.github.rs0325.ashenfrontier.core.NbtLists;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/** 通知の到着時刻ではなく、観測済みの構成境界で古い増分を再生する。 */
final class RoomPast {
    static final int LIMIT = 64;
    private static final RoomPhysics.Amount ZERO = new RoomPhysics.Amount(0, 0, 0, 0);
    record Delta(RoomPhysics.Amount amount, RoomPhysics.Amount exchange) {}
    record Stamp(long tick, int id) implements Comparable<Stamp> {
        @Override public int compareTo(Stamp other) { int time = Long.compare(tick, other.tick); return time != 0 ? time : Integer.compare(id, other.id); }
    }
    static final class Model {
        final int id;
        final long from;
        long until = Long.MAX_VALUE;
        final long[] cells;
        final int walls, openings;
        final int cellsHash;
        final double height;
        double temperature = Double.NaN;
        boolean approximate;
        boolean outdoor;
        boolean sky;
        long[] watch = new long[0];
        boolean published;
        long preparedVersion = -1;
        Model(int id, long from, long[] cells, int walls, int openings, double height) {
            this.id = id; this.from = from; this.cells = cells; this.cellsHash = Arrays.hashCode(cells); this.walls = walls; this.openings = openings; this.height = height;
        }
        private Model(Model first, long until) {
            id = first.id; from = first.from; cells = first.cells; cellsHash = first.cellsHash;
            walls = first.walls; openings = first.openings; height = first.height;
            this.until = until; temperature = first.temperature; published = true;
            approximate = true;
            outdoor = first.outdoor; sky = first.sky; watch = first.watch;
        }
        @Override public int hashCode() { return id * 31 + Long.hashCode(from) * 31 + Long.hashCode(until) + cellsHash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof Model m && id == m.id && from == m.from && until == m.until && walls == m.walls
                    && openings == m.openings && height == m.height && Double.compare(temperature, m.temperature) == 0
                    && approximate == m.approximate && outdoor == m.outdoor && sky == m.sky && Arrays.equals(watch, m.watch) && Arrays.equals(cells, m.cells);
        }
        boolean sameGeometry(Model other) {
            return outdoor == other.outdoor && from == other.from && walls == other.walls && openings == other.openings && height == other.height
                    && Double.compare(temperature, other.temperature) == 0 && Arrays.equals(cells, other.cells);
        }
        boolean contains(long cell) { return Arrays.binarySearch(cells, cell) >= 0; }
    }
    @FunctionalInterface interface Boundaries {
        Optional<List<RoomAir.Boundary>> get(BlockPos pos, long from, long to);
    }
    boolean approximate(long cell,long tick) {
        var model=at(cell,tick);
        return model!=null && tick<model.until && model.approximate;
    }
    Model outdoor(long cell, long tick) {
        var model = at(cell, tick);
        return model != null && model.outdoor && tick < model.until ? model : null;
    }
    List<Model> outdoors(long tick) {
        var result = new ArrayList<Model>();
        for (long cell : cells.keySet()) {
            var model = outdoor(cell, tick);
            if (model != null) result.add(model);
        }
        return result;
    }
    void observeOutdoor(int id, long tick, long cell, long[] watch, boolean sky) {
        var model = new Model(id,tick,new long[]{cell},0,0,0);
        model.outdoor = true; model.sky = sky; model.watch = sky ? model.cells : watch;
        Arrays.sort(model.watch);
        invalidateOutdoor(cell);
        bind(cell,model);
        if (!publish(model)) throw new IllegalStateException("屋外観測の公開版が変わりました");
    }
    private static final class Line {
        final Model owner;
        final TreeMap<Stamp, Model> rows;
        Line before;
        Line(Model owner, TreeMap<Stamp, Model> rows, Line before) {
            this.owner = owner; this.rows = rows; this.before = before;
        }
    }
    private final Map<Long, Line> cells = new HashMap<>();
    private long version;

    long version() { return version; }
    void prepare(Model model) { model.preparedVersion = version; }
    private static TreeMap<Stamp, Model> visible(Line line) {
        while (line != null && line.owner != null && !line.owner.published) line = line.before;
        if (line != null && line.owner != null) line.before = null;
        return line == null ? null : line.rows;
    }
    private TreeMap<Stamp, Model> rows(long cell) { return visible(cells.get(cell)); }
    private static Line without(Line line, Model model) {
        if (line == null) return null;
        if (line.owner == model) return line.before;
        for (var next = line; next.before != null; next = next.before)
            if (next.before.owner == model) { next.before = next.before.before; break; }
        return line;
    }
    /** 1セル分の置換を準備する。公開前は元の索引だけが照会・保存に見える。 */
    void bind(long cell, Model model) {
        if (model.preparedVersion < 0) prepare(model);
        var before = without(cells.get(cell), model);
        if (before != null && (before.owner == null || before.owner.published))
            before = new Line(null, before.rows, null);
        var current = visible(before);
        var prepared = current == null ? new TreeMap<Stamp, Model>() : new TreeMap<>(current);
        prepared.put(new Stamp(model.from, model.id), model);
        while (prepared.size() > LIMIT) {
            var first = prepared.pollFirstEntry().getValue();
            var second = prepared.pollFirstEntry().getValue();
            var coarse = new Model(first, second.until);
            prepared.put(new Stamp(coarse.from, coarse.id), coarse);
        }
        cells.put(cell, new Line(model, prepared, before));
    }
    /** 室内版と同位置の屋外観測が準備中に変わらなければ、可視性だけを原子的に切り替える。 */
    boolean publish(Model model) {
        if (model.preparedVersion != version) return false;
        model.published = true; if (!model.outdoor) version++; return true;
    }
    private void invalidateOutdoor(long cell) {
        for (var line = cells.get(cell); line != null; line = line.before)
            if (line.owner != null && !line.owner.published) line.owner.preparedVersion = -1;
    }
    void end(Model model, long tick) {
        model.until = tick;
        if (model.outdoor) for (long cell : model.cells) invalidateOutdoor(cell);
        else version++;
    }
    void discard(Model model) {
        if (model.published) throw new IllegalStateException("公開済みの構成は取り消せません");
        for (long cell : model.cells) {
            var line = without(cells.get(cell), model);
            if (line == null) cells.remove(cell); else cells.put(cell, line);
        }
    }
    void resolve(Model model) {
        if (!Double.isFinite(model.temperature)) return;
        for (long cell : model.cells) {
            var rows = rows(cell);
            var coarse = rows == null ? null : rows.get(new Stamp(model.from, model.id));
            if (coarse != null && !Double.isFinite(coarse.temperature) && coarse.walls == model.walls
                    && coarse.openings == model.openings && Arrays.equals(coarse.cells, model.cells))
                coarse.temperature = model.temperature;
        }
        version++;
    }
    private Model at(long cell, long tick) {
        var rows = rows(cell);
        if (rows == null) return null;
        var row = rows.floorEntry(new Stamp(tick, Integer.MAX_VALUE));
        while (row != null && !row.getValue().published) row = rows.lowerEntry(row.getKey());
        return row == null ? null : row.getValue();
    }
    void cuts(long cell, long from, long to, TreeSet<Long> cuts) {
        var rows = rows(cell);
        if (rows != null) for (var model : rows.values()) if (model.published) {
            if (model.from > from && model.from < to) cuts.add(model.from);
            if (model.until > from && model.until < to) cuts.add(model.until);
        }
    }

    Optional<Map<Long, Delta>> response(long source, long from, long to, long end,
                                       RoomPhysics.Amount input, Boundaries outside) {
        return response(source, from, to, end, input, -1, outside);
    }
    Optional<Map<Long, Delta>> response(long source, long from, long to, long end,
                                       RoomPhysics.Amount input, int context, Boundaries outside) {
        if(end<from)return Optional.of(Map.of());
        var model=at(source,from);
        if(model!=null&&!model.outdoor&&model.until>=end&&(context<0||context==model.id)
                &&stable(model,from,end))return stableResponse(model,from,to,end,input,outside);
        return responseGeneral(source,from,to,end,input,context,outside);
    }
    private boolean stable(Model model,long from,long end) {
        for(long cell:model.cells) {
            var rows=rows(cell);if(rows==null)return false;
            var first=at(cell,from);if(first==null||!model.sameGeometry(first)||first.until<end)return false;
            for(var candidate:rows.values())if(candidate.published&&candidate.from<=end&&candidate.until>=from
                    &&!model.sameGeometry(candidate))return false;
        }
        return true;
    }
    private static RoomPhysics.Amount roundTrip(RoomPhysics.Amount amount,int members) {
        var part=RoomHistory.scale(amount,1.0/members);var sum=ZERO;
        for(int n=0;n<members;n++)sum=RoomHistory.plus(sum,part);
        return sum;
    }
    private Optional<Map<Long,Delta>> stableResponse(Model model,long from,long to,long end,RoomPhysics.Amount input,Boundaries outside) {
        var amount=from==to?input:ZERO;var exchange=ZERO;
        if(end>from) {
            if(model.openings>0&&!Double.isFinite(model.temperature))return Optional.empty();
            if(from==to)amount=roundTrip(amount,model.cells.length);
            long a=from;
            while(a<end) {
                long b=a<to?Math.min(to,end):end;
                var rows=outside.get(BlockPos.of(model.cells[0]),a,b);if(rows.isEmpty())return Optional.empty();
                var rate=from!=to&&a<to?input.rate(20.0/(to-from)):RoomPhysics.Rate.ZERO;
                for(var row:rows.get()) {
                    double seconds=(row.to()-row.from())/20.0;
                    double volume=model.cells.length,capacity=RoomPhysics.capacity(volume,model.walls);
                    double q=model.openings==0?0:RoomPhysics.flow(model.openings,model.height,model.temperature,row.air());
                    double heat=(q*RoomPhysics.capacity(volume,0)/volume+model.walls)/capacity,gas=q/volume;
                    var after=new RoomPhysics.Amount(RoomPhysics.evolve(amount.heatJ(),0,rate.heatW(),heat,seconds),
                            RoomPhysics.evolve(amount.coMol(),0,rate.coMolS(),gas,seconds),
                            RoomPhysics.evolve(amount.co2Mol(),0,rate.co2MolS(),gas,seconds),
                            RoomPhysics.evolve(amount.oxygenMol(),0,rate.oxygenMolS(),gas,seconds));
                    var added=new RoomPhysics.Amount(rate.heatW()*seconds,rate.coMolS()*seconds,rate.co2MolS()*seconds,rate.oxygenMolS()*seconds);
                    exchange=RoomHistory.plus(exchange,RoomHistory.plus(added,RoomHistory.plus(amount,RoomHistory.scale(after,-1))));
                    amount=after;
                }
                a=b;
                if(a<end){amount=roundTrip(amount,model.cells.length);exchange=roundTrip(exchange,model.cells.length);}
            }
        }
        var result=new HashMap<Long,Delta>();var value=new Delta(RoomHistory.scale(amount,1.0/model.cells.length),RoomHistory.scale(exchange,1.0/model.cells.length));
        for(long cell:model.cells)result.put(cell,value);return Optional.of(result);
    }
    Optional<Map<Long, Delta>> responseGeneral(long source, long from, long to, long end,
                                       RoomPhysics.Amount input, int context, Boundaries outside) {
        if (end < from) return Optional.of(Map.of());
        if (at(source, from) == null) return Optional.empty();
        var reachable = new HashSet<Long>();
        var enqueued = new HashSet<Long>();
        var expandedModels = new HashSet<Model>();
        var queue = new ArrayList<Long>(); queue.add(source); enqueued.add(source);
        var cuts = new TreeSet<Long>(List.of(from, Math.min(to, end), end));
        for (int n = 0; n < queue.size(); n++) {
            long cell = queue.get(n);
            if (!reachable.add(cell)) continue;
            cuts(cell, from, end, cuts);
            var rows = rows(cell);
            if (rows != null) for (var model : rows.values())
                if (model.published && model.from <= end && model.until >= from && expandedModels.add(model))
                    for (long other : model.cells) if (enqueued.add(other)) queue.add(other);
        }
        var values = new HashMap<Long, Delta>();
        var births = new HashSet<Model>();
        for (long cell : reachable) {
            var rows = rows(cell);
            if (rows != null) for (var model : rows.values())
                if (model.published && !model.outdoor && model.from > from && model.from <= end) births.add(model);
        }
        if (from == to) {
            Model owner = null;
            var sourceRows = rows(source);
            if (context >= 0 && sourceRows != null) owner = sourceRows.values().stream().filter(m -> m.published && m.id == context).findFirst().orElse(null);
            if (owner == null) inject(values, source, from, input);
            else {
                for (long cell : owner.cells) values.put(cell, new Delta(RoomHistory.scale(input, 1.0/owner.cells.length), ZERO));
                var later = new HashSet<Model>();
                for (long cell : reachable) { var rows = rows(cell);
                    if (rows != null) for (var m : rows.values()) if (m.published && !m.outdoor && m.from == from && m.id > context) later.add(m); }
                for (var m : later.stream().sorted(java.util.Comparator.comparingInt(m -> m.id)).toList()) mix(values, m);
            }
        }
        var times = new ArrayList<>(cuts);
        for (int n = 0; n + 1 < times.size(); n++) {
            long a = times.get(n), b = times.get(n + 1);
            var active = new ArrayList<Model>();
            var held = new HashSet<Long>();
            var involved = new HashSet<Long>(values.keySet());
            if (from != to && a < to) involved.add(source);
            for (long cell : involved) {
                var model = at(cell, a);
                if (model == null || model.outdoor || a >= model.until) held.add(cell);
                else if (active.stream().noneMatch(model::sameGeometry)) active.add(model);
            }
            var next = new HashMap<Long, Delta>(values);
            for (long cell : held) {
                var prior = values.getOrDefault(cell, new Delta(ZERO, ZERO));
                var addition = cell == source && from != to && a < to
                        ? RoomHistory.scale(input, (double) (b - a) / (to - from)) : ZERO;
                next.put(cell, new Delta(RoomHistory.plus(prior.amount, addition), prior.exchange));
            }
            for (var model : active) {
                if (model.openings > 0 && !Double.isFinite(model.temperature)) return Optional.empty();
                var members = members(model, a);
                var amount = ZERO; var exchanged = ZERO;
                for (long cell : members) {
                    var value = values.getOrDefault(cell, new Delta(ZERO, ZERO));
                    amount = RoomHistory.plus(amount, value.amount); exchanged = RoomHistory.plus(exchanged, value.exchange);
                }
                var rows = outside.get(BlockPos.of(model.cells[0]), a, b);
                if (rows.isEmpty()) return Optional.empty();
                boolean feeds = members.contains(source) && from != to && a < to;
                var rate = feeds ? input.rate(20.0 / (to - from)) : RoomPhysics.Rate.ZERO;
                for (var row : rows.get()) {
                    double seconds = (row.to() - row.from()) / 20.0;
                    double volume = model.cells.length, capacity = RoomPhysics.capacity(volume, model.walls);
                    double q = model.openings == 0 ? 0 : RoomPhysics.flow(model.openings, model.height, model.temperature, row.air());
                    double heatLambda = (q * RoomPhysics.capacity(volume, 0) / volume + model.walls) / capacity;
                    double gasLambda = q / volume;
                    var after = new RoomPhysics.Amount(
                            RoomPhysics.evolve(amount.heatJ(), 0, rate.heatW(), heatLambda, seconds),
                            RoomPhysics.evolve(amount.coMol(), 0, rate.coMolS(), gasLambda, seconds),
                            RoomPhysics.evolve(amount.co2Mol(), 0, rate.co2MolS(), gasLambda, seconds),
                            RoomPhysics.evolve(amount.oxygenMol(), 0, rate.oxygenMolS(), gasLambda, seconds));
                    var added = new RoomPhysics.Amount(rate.heatW() * seconds, rate.coMolS() * seconds,
                            rate.co2MolS() * seconds, rate.oxygenMolS() * seconds);
                    exchanged = RoomHistory.plus(exchanged, RoomHistory.plus(added,
                            RoomHistory.plus(amount, RoomHistory.scale(after, -1))));
                    amount = after;
                }
                var value = new Delta(RoomHistory.scale(amount, 1.0 / members.size()),
                        RoomHistory.scale(exchanged, 1.0 / members.size()));
                for (long cell : members) next.put(cell, value);
            }
            values = next;
            for (var model : births.stream().filter(m -> m.from == b).sorted(java.util.Comparator.comparingInt(m -> m.id)).toList()) mix(values, model);
        }
        return Optional.of(values);
    }
    private void mix(Map<Long, Delta> values, Model model) {
        var amount = ZERO; var exchange = ZERO;
        for (long cell : model.cells) {
            var value = values.getOrDefault(cell, new Delta(ZERO, ZERO));
            amount = RoomHistory.plus(amount, value.amount); exchange = RoomHistory.plus(exchange, value.exchange);
        }
        var value = new Delta(RoomHistory.scale(amount, 1.0/model.cells.length), RoomHistory.scale(exchange, 1.0/model.cells.length));
        for (long cell : model.cells) values.put(cell, value);
    }

    private List<Long> members(Model model, long tick) {
        var result = new ArrayList<Long>();
        for (long cell : model.cells) {
            var owner = at(cell, tick);
            if (owner != null && !owner.outdoor && tick < owner.until && model.sameGeometry(owner)) result.add(cell);
        }
        return result;
    }

    private void inject(Map<Long, Delta> values, long source, long tick, RoomPhysics.Amount amount) {
        var model = at(source, tick);
        if (model.outdoor || tick >= model.until) values.put(source, new Delta(amount, ZERO));
        else {
            var members = members(model, tick);
            for (long cell : members) values.put(cell, new Delta(RoomHistory.scale(amount, 1.0 / members.size()), ZERO));
        }
    }

    record Historical(Model model, RoomPhysics.State state) {}
    boolean known(long cell, long tick) {
        var model = at(cell, tick);
        return model != null && tick < model.until;
    }
    Optional<Historical> historical(long cell, long tick, List<RoomHistory.Entry> inputs, Boundaries outside) {
        var model = at(cell, tick);
        if (model == null || model.outdoor || tick >= model.until) return Optional.empty();
        var base = background(cell, tick, outside);
        if (base.isEmpty()) return Optional.empty();
        var values = base.get();
        for (var input : inputs) {
            if (input.from() > tick || !values.containsKey(input.position())
                    || input.amount().heatJ() == 0 && input.amount().coMol() == 0 && input.amount().co2Mol() == 0 && input.amount().oxygenMol() == 0) continue;
            var delta = response(input.position(), input.from(), input.to(), tick, input.amount(), input.context(), outside);
            if (delta.isEmpty()) return Optional.empty();
            for (var entry : delta.get().entrySet()) {
                var old = values.get(entry.getKey());
                if (old != null) values.put(entry.getKey(), new Delta(RoomHistory.plus(old.amount, entry.getValue().amount),
                        RoomHistory.plus(old.exchange, entry.getValue().exchange)));
            }
        }
        var total = ZERO;
        for (long position : model.cells) {
            var value = values.get(position);
            if (value != null) total = RoomHistory.plus(total, value.amount);
        }
        return Optional.of(new Historical(model, RoomPhysics.state(total, model.cells.length, model.walls)));
    }
    private Optional<Map<Long, Delta>> background(long source, long end, Boundaries outside) {
        var reachable = new HashSet<Long>(); var queue = new ArrayList<Long>(); queue.add(source); reachable.add(source);
        var models = new HashMap<Integer, Model>();
        for (int n = 0; n < queue.size(); n++) {
            var rows = rows(queue.get(n));
            if (rows == null) continue;
            for (var model : rows.values()) if (model.published && !model.outdoor && model.from <= end && models.putIfAbsent(model.id, model) == null)
                for (long cell : model.cells) if (reachable.add(cell)) queue.add(cell);
        }
        var cuts = new TreeSet<Long>(); cuts.add(end);
        for (var model : models.values()) {
            cuts.add(model.from); if (model.until < end) cuts.add(model.until);
        }
        var values = new HashMap<Long, Delta>();
        var ambient = new HashMap<Model, RoomPhysics.Amount>();
        var times = new ArrayList<>(cuts);
        for (int n = 0; n < times.size(); n++) {
            long a = times.get(n);
            for (var model : models.values().stream().filter(m -> m.from == a).sorted(java.util.Comparator.comparingInt(m -> m.id)).toList()) {
                for (long cell : model.cells) if (!values.containsKey(cell)) {
                    var initial = ambient.get(model);
                    if (initial == null) {
                        var rows = outside.get(BlockPos.of(model.cells[0]), a, a);
                        if (rows.isEmpty()) return Optional.empty();
                        initial = RoomHistory.scale(RoomPhysics.amount(RoomPhysics.State.ambient(rows.get().getFirst().air()),
                                model.cells.length, model.walls), 1.0/model.cells.length); ambient.put(model, initial);
                    }
                    values.put(cell, new Delta(initial, RoomHistory.scale(initial, -1)));
                }
                mix(values, model);
            }
            if (n + 1 == times.size()) break;
            long b = times.get(n + 1);
            var active = new ArrayList<Model>();
            for (long cell : values.keySet()) {
                var model = at(cell, a);
                if (model != null && !model.outdoor && a < model.until && active.stream().noneMatch(model::sameGeometry)) active.add(model);
            }
            for (var model : active) {
                if (model.openings > 0 && !Double.isFinite(model.temperature)) return Optional.empty();
                var positions = members(model, a);
                var amount = ZERO; var exchanged = ZERO;
                for (long cell : positions) { var value = values.get(cell);
                    amount = RoomHistory.plus(amount, value.amount); exchanged = RoomHistory.plus(exchanged, value.exchange); }
                var rows = outside.get(BlockPos.of(model.cells[0]), a, b);
                if (rows.isEmpty()) return Optional.empty();
                for (var row : rows.get()) {
                    double volume = model.cells.length, capacity = RoomPhysics.capacity(volume, model.walls);
                    double q = model.openings == 0 ? 0 : RoomPhysics.flow(model.openings, model.height, model.temperature, row.air());
                    double heatLambda = (q * RoomPhysics.capacity(volume, 0)/volume + model.walls)/capacity;
                    double gasLambda = q/volume, seconds = (row.to()-row.from())/20.0, share = (double) positions.size()/volume;
                    var target = RoomHistory.scale(RoomPhysics.amount(RoomPhysics.State.ambient(row.air()), volume, model.walls), share);
                    var after = new RoomPhysics.Amount(
                            RoomPhysics.evolve(amount.heatJ(), target.heatJ(), 0, heatLambda, seconds),
                            RoomPhysics.evolve(amount.coMol(), target.coMol(), 0, gasLambda, seconds),
                            RoomPhysics.evolve(amount.co2Mol(), target.co2Mol(), 0, gasLambda, seconds),
                            RoomPhysics.evolve(amount.oxygenMol(), target.oxygenMol(), 0, gasLambda, seconds));
                    exchanged = RoomHistory.plus(exchanged, RoomHistory.plus(amount, RoomHistory.scale(after, -1))); amount = after;
                }
                var value = new Delta(RoomHistory.scale(amount, 1.0/positions.size()), RoomHistory.scale(exchanged, 1.0/positions.size()));
                for (long cell : positions) values.put(cell, value);
            }
        }
        return Optional.of(values);
    }

    boolean forgettable(long cell) {
        var line=cells.get(cell);
        if(line!=null&&line.owner!=null&&!line.owner.published)return false;
        return !physicalHistory(cell);
    }
    boolean physicalHistory(long cell) {
        var rows=rows(cell);return rows!=null&&rows.values().stream().anyMatch(m->!m.outdoor);
    }
    int references(long cell) { var rows=rows(cell);return rows==null?0:rows.size(); }
    long outdoorBytes(long cell) {
        var rows=rows(cell);long size=78;
        if(rows!=null)for(var model:rows.values())size+=142+(model.sky?0:12+8L*model.watch.length);
        return size;
    }
    void forgetOutdoor(long cell) {
        if(!forgettable(cell))throw new IllegalStateException("物理的な室の履歴は一般整理できません");
        cells.remove(cell);
    }

    void save(CompoundTag tag) {
        var models = new ArrayList<Model>();
        var indices = new HashMap<Model, Integer>();
        var locations = new ListTag();
        cells.forEach((cell, line) -> {
            var rows = visible(line);
            if (rows == null || rows.isEmpty()) return;
            var row = new CompoundTag(); row.putLong("cell", cell); var ids = new ArrayList<Integer>();
            for (var model : rows.values()) if (model.published) {
                ids.add(indices.computeIfAbsent(model, key -> { models.add(key); return models.size() - 1; }));
            }
            row.putIntArray("models", ids); locations.add(row);
        });
        var records = new ListTag();
        for (var model : models) {
            var row = new CompoundTag(); row.putInt("id", model.id); row.putLong("from", model.from); row.putLong("until", model.until);
            row.putBoolean("approximate",model.approximate);
            row.putLongArray("cells", model.cells); row.putInt("walls", model.walls); row.putInt("openings", model.openings);
            row.putDouble("height", model.height); if (!model.outdoor) row.putDouble("temperature", model.temperature);
            if (model.outdoor) { row.putBoolean("outdoor",true); row.putBoolean("sky",model.sky); if (!model.sky) row.putLongArray("watch",model.watch); }
            records.add(row);
        }
        tag.put("past_models", records); tag.put("past_cells", locations);
    }
    static RoomPast load(CompoundTag tag) {
        var result = new RoomPast(); var models = new ArrayList<Model>();
        for (var element : NbtLists.read(tag,"past_models",10)) {
            var row = (CompoundTag) element;
            if (!row.contains("approximate",Tag.TAG_BYTE)) throw new IllegalStateException("精度の印がない試験用の室内履歴は再生成が必要です");
            var model = new Model(row.getInt("id"), row.getLong("from"), row.getLongArray("cells"), row.getInt("walls"),
                    row.getInt("openings"), row.getDouble("height"));
            model.until = row.getLong("until"); model.temperature = row.getDouble("temperature"); model.published = true;
            model.approximate = row.getBoolean("approximate");
            model.outdoor = row.getBoolean("outdoor"); model.sky = row.getBoolean("sky"); model.watch = row.getLongArray("watch");
            if (model.outdoor) { model.temperature = Double.NaN; if (model.sky) model.watch = model.cells; Arrays.sort(model.watch); }
            models.add(model);
        }
        for (var element : NbtLists.read(tag,"past_cells",10)) {
            var row = (CompoundTag) element; var rows = new TreeMap<Stamp, Model>();
            for (int id : row.getIntArray("models")) { var model = models.get(id); rows.put(new Stamp(model.from, model.id), model); }
            if (rows.size() > LIMIT) throw new IllegalArgumentException("過去構成の保持上限を超えています");
            result.cells.put(row.getLong("cell"), new Line(null, rows, null));
        }
        return result;
    }
    Model model(long cell, long from, int id) {
        var rows = rows(cell); return rows == null ? null : rows.get(new Stamp(from, id));
    }
}
