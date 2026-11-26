package io.github.rs0325.ashenfrontier.climate.room;

import io.github.rs0325.ashenfrontier.core.NbtLists;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** format=10の保持所有権。派生計数は保存行から復元する。 */
final class RoomRetentionCodec {
    private RoomRetentionCodec() {}
    static CompoundTag save(RoomRetention book) {
        var tag=new CompoundTag();var leases=new ListTag();
        for(var lease:book.leases.values()) {
            var row=new CompoundTag();row.putUUID("owner",lease.key.owner());row.putLong("generation",lease.key.generation());
            row.putLong("position",lease.position);row.putLong("retain_from",lease.from);row.putLong("committed",lease.committed);
            row.putLong("until",lease.until);row.putLong("sequence",lease.sequence);row.putBoolean("releasing",lease.releasing);leases.add(row);
        }
        tag.put("leases",leases);var heads=new ListTag();
        book.heads.forEach((owner,head)->{
            var row=new CompoundTag();row.putUUID("owner",owner);row.putLong("issued",head.issued);row.putLong("released",head.released);
            row.putLongArray("ended",head.ended.entrySet().stream().flatMapToLong(e->java.util.stream.LongStream.of(e.getKey(),e.getValue())).toArray());heads.add(row);
        });
        tag.put("heads",heads);
        tag.putLongArray("physical",book.physical.stream().mapToLong(Long::longValue).toArray());
        var general=new ListTag();
        book.general.forEach((cell,value)->{
            var row=new CompoundTag();row.putLong("cell",cell);row.putDouble("last_seen_game_seconds",value.seen());general.add(row);
        });
        tag.put("general",general);return tag;
    }
    private static void require(CompoundTag row,int type,String... fields) {
        for(String field:fields)if(!row.contains(field,type))throw new IllegalArgumentException("保持の保存項目がありません: "+field);
    }
    static RoomRetention load(CompoundTag tag,java.util.function.LongToIntFunction references,java.util.function.LongUnaryOperator encodedBytes) {
        for(String field:new String[]{"heads","leases","general"})
            if(!tag.contains(field,Tag.TAG_LIST))throw new IllegalArgumentException("保持の保存列がありません: "+field);
        if(!tag.contains("physical",Tag.TAG_LONG_ARRAY))throw new IllegalArgumentException("物理参照の保存列がありません");
        var book=new RoomRetention();
        for(var item:NbtLists.read(tag,"heads",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)item;
            if(!row.hasUUID("owner"))throw new IllegalArgumentException("保持所有者が不正です");
            require(row,Tag.TAG_LONG,"issued","released");require(row,Tag.TAG_LONG_ARRAY,"ended");
            var head=new RoomRetention.Head();head.issued=row.getLong("issued");head.released=row.getLong("released");
            if(head.released<0||head.issued<head.released)throw new IllegalArgumentException("保持世代が不正です");
            long[] ended=row.getLongArray("ended");
            if(ended.length%2!=0)throw new IllegalArgumentException("保持終了証明が不正です");
            long previous=head.released;
            for(int i=0;i<ended.length;i+=2) {
                if(ended[i]<=previous||ended[i+1]<ended[i]||ended[i+1]>head.issued)
                    throw new IllegalArgumentException("保持終了証明が不正です");
                head.ended.put(ended[i],ended[i+1]);previous=ended[i+1];
            }
            if(book.heads.put(row.getUUID("owner"),head)!=null)throw new IllegalArgumentException("保持所有者が重複しています");
        }
        for(var item:NbtLists.read(tag,"leases",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)item;
            if(!row.hasUUID("owner"))throw new IllegalArgumentException("保持所有者が不正です");
            require(row,Tag.TAG_LONG,"generation","position","retain_from","committed","until","sequence");
            var key=new RoomRetention.Key(row.getUUID("owner"),row.getLong("generation"));
            var head=book.heads.get(key.owner());
            if(head==null||key.generation()<=head.released||key.generation()>head.issued||head.ended(key.generation()))
                throw new IllegalArgumentException("保持登録の世代が不正です");
            var value=new RoomRetention.Lease(key,row.getLong("position"),row.getLong("retain_from"));
            value.committed=row.getLong("committed");value.until=row.getLong("until");value.sequence=row.getLong("sequence");value.releasing=row.getBoolean("releasing");
            if(!row.contains("releasing",Tag.TAG_BYTE)||value.releasing&&(value.until==Long.MAX_VALUE||value.committed<value.until))
                throw new IllegalArgumentException("保持解放の準備状態が不正です");
            if(value.from<0||value.committed<value.from||value.until<value.committed||value.sequence<0||book.leases.put(key,value)!=null)
                throw new IllegalArgumentException("保持登録の保存区間が不正です");
            book.owners.merge(value.position,1,Integer::sum);
        }
        var activeCounts=new java.util.HashMap<java.util.UUID,Long>();
        book.leases.keySet().forEach(k->activeCounts.merge(k.owner(),1L,Long::sum));
        for(var entry:book.heads.entrySet()) {
            var head=entry.getValue();long count=activeCounts.getOrDefault(entry.getKey(),0L);
            for(var range:head.ended.entrySet())count=Math.addExact(count,range.getValue()-range.getKey()+1);
            if(count!=head.issued-head.released)throw new IllegalArgumentException("保持世代の登録または終了証明が欠落しています");
        }
        for(long position:tag.getLongArray("physical"))if(!book.physical.add(position))throw new IllegalArgumentException("物理参照が重複しています");
        double last=-1;
        for(var item:NbtLists.read(tag,"general",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)item;require(row,Tag.TAG_LONG,"cell");require(row,Tag.TAG_DOUBLE,"last_seen_game_seconds");long cell=row.getLong("cell");double seen=row.getDouble("last_seen_game_seconds");
            int count=references.applyAsInt(cell);long bytes=encodedBytes.applyAsLong(cell);
            if(!Double.isFinite(seen)||seen<last||count<=0||bytes<=0||book.protectedPosition(cell)||book.general.containsKey(cell))
                throw new IllegalArgumentException("一般観測の保存が不正です");
            book.general.put(cell,new RoomRetention.Observation(seen,count,bytes));book.references+=count;book.bytes+=bytes;last=seen;
        }
        return book;
    }
}
