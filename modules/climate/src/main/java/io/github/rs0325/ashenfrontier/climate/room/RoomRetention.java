package io.github.rs0325.ashenfrontier.climate.room;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 位置の保持所有権。幾何と物理量は持たず、保護位置には期限/受付上限を設けない。 */
final class RoomRetention {
    static final int POSITION_LIMIT=4096, REFERENCE_LIMIT=16384;
    static final long BYTE_LIMIT=8*1024*1024;
    static final double LIFETIME=86400;
    record Key(UUID owner,long generation) {}
    static final class Lease {
        final Key key;
        final long position,from;
        long committed,until=Long.MAX_VALUE,sequence;
        boolean releasing;
        Lease(Key key,long position,long from) { this.key=key;this.position=position;this.from=from;committed=from; }
    }
    static final class Head {
        long issued,released;
        final TreeMap<Long,Long> ended=new TreeMap<>();
        boolean ended(long generation) { var row=ended.floorEntry(generation);return row!=null&&row.getValue()>=generation; }
    }
    record Observation(double seen,int references,long bytes) {}
    final Map<Key,Lease> leases=new HashMap<>();
    final Map<UUID,Head> heads=new HashMap<>();
    final Set<Long> physical=new HashSet<>();
    final Map<Long,Integer> owners=new HashMap<>();
    final LinkedHashMap<Long,Observation> general=new LinkedHashMap<>();
    long bytes,references;
    int positions=POSITION_LIMIT,referenceLimit=REFERENCE_LIMIT;
    long byteLimit=BYTE_LIMIT;
    double lifetime=LIFETIME;
    private boolean pressure;

    Lease retain(UUID owner,long generation,long position,long from) {
        if(generation<=0||from<0)throw new IllegalArgumentException("保持登録の世代/時刻が不正です");
        var key=new Key(owner,generation);
        var existing=leases.get(key);
        if(existing!=null) {
            if(existing.position!=position||existing.from!=from)throw new IllegalArgumentException("保持登録の再送内容が異なります");
            return existing;
        }
        var head=heads.computeIfAbsent(owner,k->new Head());
        if(generation!=head.issued+1)throw new IllegalArgumentException("保持登録の発行世代が連続していません");
        head.issued=generation;
        var value=new Lease(key,position,from);leases.put(key,value);
        owners.merge(position,1,Integer::sum);removeGeneral(position);return value;
    }
    boolean released(Key key) {
        var head=heads.get(key.owner());
        return head!=null&&(key.generation()<=head.released||head.ended(key.generation()));
    }
    Lease require(Key key) {
        var value=leases.get(key);
        if(value==null)throw new IllegalArgumentException("保持登録がありません");
        return value;
    }
    void commit(Key key,long tick,long sequence) {
        if(released(key))return;
        var value=require(key);
        if(tick<value.committed||tick>value.until||sequence<value.sequence)
            throw new IllegalArgumentException("保持確定の時刻/通知番号が逆転しています");
        value.committed=tick;value.sequence=sequence;
    }
    void end(Key key,long tick,long sequence) {
        if(released(key))return;
        var value=require(key);
        if(tick<value.from||tick<value.committed||sequence<value.sequence
                ||value.until!=Long.MAX_VALUE&&value.until!=tick)
            throw new IllegalArgumentException("保持終了の境界が不正です");
        value.until=tick;value.sequence=sequence;
    }
    boolean release(Key key) {
        if(released(key))return true;
        var value=require(key);
        if(value.until==Long.MAX_VALUE||value.committed<value.until)return false;
        leases.remove(key);
        owners.compute(value.position,(p,n)->n==1?null:n-1);
        var head=heads.get(key.owner());long a=key.generation(),b=a;
        var left=head.ended.floorEntry(a);
        if(left!=null&&left.getValue()+1==a) { a=left.getKey();head.ended.remove(left.getKey()); }
        var right=head.ended.ceilingEntry(a);
        if(right!=null&&right.getKey()==b+1) { b=right.getValue();head.ended.remove(right.getKey()); }
        head.ended.put(a,b);
        var first=head.ended.firstEntry();
        if(first.getKey()==head.released+1) { head.released=first.getValue();head.ended.pollFirstEntry(); }
        return true;
    }
    boolean protectedPosition(long position) { return owners.containsKey(position)||physical.contains(position); }
    void physical(long position) { physical.add(position);removeGeneral(position); }
    private void removeGeneral(long position) {
        var old=general.remove(position);
        if(old!=null) { references-=old.references();bytes-=old.bytes(); }
    }
    boolean observe(long position,double now,int count,long size) {
        if(protectedPosition(position))return true;
        var old=general.get(position);
        long nextReferences=references-(old==null?0:old.references())+count;
        long nextBytes=bytes-(old==null?0:old.bytes())+size;
        if((old==null&&general.size()>=positions)||nextReferences>referenceLimit||nextBytes+56>byteLimit) {
            pressure=true;return false;
        }
        removeGeneral(position);
        general.put(position,new Observation(now,count,size));references+=count;bytes+=size;return true;
    }
    /** 最古候補だけを返す。幾何/監視索引を削除し終わってからforgetで計数を解放する。 */
    Long candidate(double now) { return candidate(now,Set.of()); }
    Long candidate(double now,Set<Long> skipped) {
        if(general.isEmpty()) { pressure=false;return null; }
        boolean excess=general.size()>positions||references>referenceLimit||bytes+56>byteLimit;
        int visited=0;
        for(var row:general.entrySet()) {
            if(visited++>=64)return null;
            if(skipped.contains(row.getKey()))continue;
            return pressure||excess||now-row.getValue().seen()>=lifetime?row.getKey():null;
        }
        return null;
    }
    void forget(long position) { removeGeneral(position);pressure=false; }
    void requestCollection() { pressure=true; }
    long minimum(long position) {
        return leases.values().stream().filter(l->l.position==position).mapToLong(l->l.committed).min().orElse(Long.MAX_VALUE);
    }
}
