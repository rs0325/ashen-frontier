package io.github.rs0325.ashenfrontier.climate.room;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 古い通知を位置ごとのcheckpoint区間へ集約する。量は残し、時刻分布だけを均す。 */
final class RoomHistory {
    static final int LIMIT = 1024;
    record Entry(long position, long from, long to, RoomPhysics.Amount amount, int context) {
        Entry(long position, long from, long to, RoomPhysics.Amount amount) { this(position, from, to, amount, -1); }
    }
    private final List<Entry> recent = new ArrayList<>();
    private record Key(long position, int context) {}
    private final Map<Key, Entry> cumulative = new HashMap<>();
    private long checkpoint = -1;
    private int maximum;
    private long revision;
    private List<Entry> entries,totals;
    private Map<Long,int[]> positions;
    private RoomPhysics.Amount archivedInput = new RoomPhysics.Amount(0, 0, 0, 0);

    long checkpoint() { return checkpoint; }
    long revision() { return revision; }
    private void changed() { revision++;entries=null;totals=null;positions=null; }
    int retainedCount() { return recent.size(); }
    int cumulativeCount() { return cumulative.size(); }
    int maximumRetainedCount() { return maximum; }
    Entry cumulativeAt(long position) { return cumulative.get(new Key(position, -1)); }

    /** trueなら旧checkpointの状態を再構成する必要がある。 */
    boolean add(Entry entry) {
        boolean reconstructed = append(entry);
        return compactIfNeeded() || reconstructed;
    }

    /** 呼出側が観測済みcheckpointを確定してから履歴を圧縮できるよう、追加を分ける。 */
    boolean append(Entry entry) {
        var index=positions;
        changed();
        boolean reconstructed = false;
        if (checkpoint >= 0 && entry.from < checkpoint) {
            if (entry.to <= checkpoint) {
                accumulate(entry.position, entry.from, entry.to, entry.amount, entry.context);
                return true;
            }
            double fraction = (double) (checkpoint - entry.from) / (entry.to - entry.from);
            var old = scale(entry.amount, fraction);
            accumulate(entry.position, entry.from, checkpoint, old, entry.context);
            entry = new Entry(entry.position, checkpoint, entry.to, subtract(entry.amount, old));
            reconstructed = true;
        }
        recent.add(entry);
        if(!reconstructed&&index!=null) {
            positions=index;
            var indices=positions.computeIfAbsent(entry.position,p->new int[8]);
            if(indices[0]+1==indices.length) {
                indices=java.util.Arrays.copyOf(indices,indices.length*2);
                positions.put(entry.position,indices);
            }
            indices[++indices[0]]=cumulative.size()+recent.size()-1;
        }
        maximum = Math.max(maximum, Math.min(LIMIT, recent.size()));
        return reconstructed;
    }

    long compactionBoundary() {
        return recent.size()>LIMIT?recent.stream().mapToLong(Entry::to).sorted().skip(LIMIT/2).findFirst().orElseThrow():checkpoint;
    }
    boolean compactIfNeeded() {
        boolean reconstructed = false;
        if (recent.size() > LIMIT) {
            long cut=compactionBoundary();
            compact(cut);
            reconstructed = true;
        }
        maximum = Math.max(maximum, recent.size());
        return reconstructed;
    }

    private void compact(long cut) {
        changed();
        var retained = new ArrayList<Entry>();
        for (var entry : recent) {
            if (entry.to <= cut) accumulate(entry.position, entry.from, entry.to, entry.amount, entry.context);
            else if (entry.from < cut) {
                double fraction = (double) (cut - entry.from) / (entry.to - entry.from);
                var old = scale(entry.amount, fraction);
                accumulate(entry.position, entry.from, cut, old, entry.context);
                retained.add(new Entry(entry.position, cut, entry.to, subtract(entry.amount, old)));
            } else retained.add(entry);
        }
        recent.clear(); recent.addAll(retained);
        checkpoint = cut;
    }

    private void accumulate(long pos, long from, long to, RoomPhysics.Amount amount) {
        accumulate(pos, from, to, amount, -1);
    }
    private void accumulate(long pos, long from, long to, RoomPhysics.Amount amount, int context) {
        changed();
        var key = new Key(pos, context);
        var prior = cumulative.get(key);
        cumulative.put(key, prior == null ? new Entry(pos, from, to, amount, context)
                : new Entry(pos, Math.min(prior.from, from), Math.max(prior.to, to), plus(prior.amount, amount), context));
        if (context >= 0 && prior == null) {
            var contexts = cumulative.keySet().stream().filter(k -> k.position == pos && k.context >= 0)
                    .sorted(Comparator.comparingInt(Key::context)).toList();
            if (contexts.size() > 64) {
                var old = cumulative.remove(contexts.getFirst());
                accumulate(pos, old.from, old.to, old.amount, -1);
            }
        }
    }

    List<Entry> entries() {
        if(entries==null) {
            var result = new ArrayList<>(cumulative.values());result.addAll(recent);
            entries=List.copyOf(result);
        }
        return entries;
    }

    List<Entry> cumulative() { if(totals==null)totals=List.copyOf(cumulative.values());return totals; }
    /** 位置の照合回数を通知数から発生位置数へ減らし、加算は元のentries順を保持する。 */
    List<Entry> matching(java.util.function.LongPredicate predicate) {
        var all=entries();
        if(positions==null) {
            var counts=new HashMap<Long,Integer>();
            for(var entry:all)counts.merge(entry.position,1,Integer::sum);
            positions=new HashMap<>();counts.forEach((position,count)->positions.put(position,new int[count+1]));
            for(int n=0;n<all.size();n++) {
                var indices=positions.get(all.get(n).position);indices[++indices[0]]=n;
            }
        }
        var selected=new ArrayList<int[]>();int count=0;
        for(var group:positions.entrySet())if(predicate.test(group.getKey())) {
            selected.add(group.getValue());count+=group.getValue()[0];
        }
        if(selected.size()==1) {
            var group=selected.getFirst();var result=new ArrayList<Entry>(count);
            for(int n=1;n<=group[0];n++)result.add(all.get(group[n]));
            return List.copyOf(result);
        }
        var indices=new int[count];int offset=0;
        for(var group:selected){System.arraycopy(group,1,indices,offset,group[0]);offset+=group[0];}
        if(selected.size()>1)java.util.Arrays.sort(indices);
        var result=new ArrayList<Entry>(count);for(int index:indices)result.add(all.get(index));
        return List.copyOf(result);
    }
    List<Entry> recent() { return List.copyOf(recent); }

    void restore(long checkpoint, List<Entry> totals, List<Entry> tail) {
        changed();
        if (tail.size() > LIMIT) throw new IllegalArgumentException("室内通知の保持上限を超えています");
        this.checkpoint = checkpoint;
        for (var e : totals) {
            if (cumulative.put(new Key(e.position, e.context), e) != null) throw new IllegalArgumentException("室内累積位置が重複しています");
        }
        recent.addAll(tail); maximum = Math.max(maximum, recent.size());
    }

    RoomPhysics.Amount archivedInput() { return archivedInput; }
    void restoreArchivedInput(RoomPhysics.Amount amount) { archivedInput = amount; }

    void archiveInput(RoomPhysics.Amount amount) { archivedInput = plus(archivedInput, amount); }

    /** 破棄する世代の入力量を子へ一度だけ配る。非配分分は元の入力の持分で、新しい放熱通知ではない。 */
    boolean transfer(long source, Map<Long, Double> recipients) {
        var selected = entries().stream().filter(e -> e.position == source).toList();
        if (selected.isEmpty()) return false;
        var amount = new RoomPhysics.Amount(0, 0, 0, 0);
        long from = Long.MAX_VALUE, to = 0;
        for (var e : selected) { amount = plus(amount, e.amount); from = Math.min(from, e.from); to = Math.max(to, e.to); }
        recent.removeIf(e -> e.position == source); cumulative.keySet().removeIf(k -> k.position == source);
        changed();
        double assigned = 0;
        // 子がなければ残留量の正本はdisplacedに移っている。実座標の入力として再生しない。
        if (recipients.isEmpty()) archiveInput(amount);
        else {
            for (var target : recipients.entrySet()) {
                assigned += target.getValue();
                accumulate(target.getKey(), from, to, scale(amount, target.getValue()));
            }
            archivedInput = plus(archivedInput, scale(amount, Math.max(0, 1 - assigned)));
        }
        checkpoint = Math.max(checkpoint, to);
        return true;
    }

    static RoomPhysics.Amount plus(RoomPhysics.Amount a, RoomPhysics.Amount b) {
        return new RoomPhysics.Amount(a.heatJ() + b.heatJ(), a.coMol() + b.coMol(), a.co2Mol() + b.co2Mol(), a.oxygenMol() + b.oxygenMol());
    }
    static RoomPhysics.Amount scale(RoomPhysics.Amount a, double f) {
        return new RoomPhysics.Amount(a.heatJ() * f, a.coMol() * f, a.co2Mol() * f, a.oxygenMol() * f);
    }
    private static RoomPhysics.Amount subtract(RoomPhysics.Amount a, RoomPhysics.Amount b) {
        return plus(a, scale(b, -1));
    }
}
