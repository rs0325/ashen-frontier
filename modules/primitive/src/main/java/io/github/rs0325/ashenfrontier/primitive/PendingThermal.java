package io.github.rs0325.ashenfrontier.primitive;

import io.github.rs0325.ashenfrontier.core.NbtLists;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentRetention;
import io.github.rs0325.ashenfrontier.core.environment.CombustionSourceQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.LinkedHashMap;
import java.util.UUID;

/** 消滅した設備アイテムの未精算状態。幾何履歴はC06のLeaseだけを参照する。 */
final class PendingThermal extends SavedData {
    static final String ID="ashen_frontier_pending_thermal";
    private record Pending(long until,CompoundTag state) {}
    private static final java.util.Set<ServerLevel> VERIFIED=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final ServerLevel level;
    private final LinkedHashMap<UUID,Pending> jobs=new LinkedHashMap<>();
    private PendingThermal(ServerLevel level) { this.level=level; }
    static PendingThermal get(ServerLevel level) {
        verify(level);
        return level.getDataStorage().computeIfAbsent(new Factory<>(()->new PendingThermal(level),
                (tag,registries)->load(level,tag),null),ID);
    }
    private static void verify(ServerLevel level) {
        if(VERIFIED.contains(level))return;
        var root=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        var path=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),root).resolve("data/"+ID+".dat");
        if(java.nio.file.Files.exists(path))try {
            load(level,net.minecraft.nbt.NbtIo.readCompressed(path,net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getCompound("data"));
        } catch(java.io.IOException|RuntimeException e) { throw new IllegalStateException("未精算の熱設備の保存を読めません",e); }
        VERIFIED.add(level);
    }
    static void install() {
        NeoForge.EVENT_BUS.addListener((EntityLeaveLevelEvent event)->{
            if(event.getLevel() instanceof ServerLevel server&&event.getEntity() instanceof ItemEntity item
                    &&!item.getItem().isEmpty()&&(item.getRemovalReason()==Entity.RemovalReason.KILLED
                    ||item.getRemovalReason()==Entity.RemovalReason.DISCARDED))enqueue(server,item);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW,(ServerTickEvent.Pre event)->{
            long deadline=System.nanoTime()+100_000;
            for(var server:event.getServer().getAllLevels()) {
                verify(server);
                var data=server.getDataStorage().get(new Factory<>(()->new PendingThermal(server),
                        (tag,registries)->load(server,tag),null),ID);
                if(System.nanoTime()>=deadline)break;
                if(data!=null) {
                    long start=System.nanoTime(),remaining=EnvironmentBudget.remaining(server);
                    if(remaining>0)data.work(Math.min(deadline,start+remaining));
                    EnvironmentBudget.charge(server,System.nanoTime()-start);
                }
            }
        });
    }
    private static void enqueue(ServerLevel server,ItemEntity item) {
        var data=item.getItem().get(DataComponents.BLOCK_ENTITY_DATA);
        if(data==null)return;
        var tag=data.copyTag();
        if(!tag.hasUUID("source_id")||tag.getLong("lease_generation")<=0)return;
        if(!tag.contains("station_position"))throw new IllegalArgumentException("回収設備の作用位置がありません");
        var probe=EnvironmentQuery.query(server,
                BlockPos.of(tag.getLong("heat_reference")),server.getGameTime(),server.getGameTime());
        if(probe.values().isEmpty()&&probe.reason().isEmpty())return;
        var owner=tag.getUUID("source_id");
        if(CombustionSourceQuery.lastSequence(server,owner)>tag.getLong("notification_sequence")
                ||ThermalStationEntity.sourceInUse(server,owner))return;
        if(EnvironmentRetention.request(server,owner,
                tag.getLong("lease_generation"),BlockPos.of(tag.getLong("heat_reference")).above(),
                tag.getLong("lease_from"),server.getGameTime(),tag.getLong("notification_sequence"),
                EnvironmentRetention.Action.RETAIN).isEmpty())return;
        var pending=get(server);
        pending.jobs.putIfAbsent(owner,new Pending(server.getGameTime(),tag));
        pending.setDirty();
    }
    void work(long deadline) {
        if(jobs.isEmpty())return;
        var entry=jobs.entrySet().iterator().next();var pending=entry.getValue();
        if(ThermalStationEntity.sourceInUse(level,entry.getKey())) { jobs.remove(entry.getKey());setDirty();return; }
        var tag=pending.state();var pos=BlockPos.of(tag.getLong("station_position"));
        var closed=EnvironmentRetention.request(level,entry.getKey(),
                tag.getLong("lease_generation"),BlockPos.of(tag.getLong("heat_reference")).above(),tag.getLong("lease_from"),
                pending.until(),tag.getLong("notification_sequence"),
                EnvironmentRetention.Action.RELEASE);
        if(closed.isPresent()&&closed.get()) { jobs.remove(entry.getKey());setDirty();return; }
        if(tag.getBoolean("discard_closed"))return;
        var entity=new ThermalStationEntity(pos,(tag.getBoolean("hearth")?PrimitiveContent.HEARTH:PrimitiveContent.DRYING_RACK).get().defaultBlockState());
        entity.loadWithComponents(tag,level.registryAccess());entity.setLevel(level);
        boolean complete;
        try { complete=entity.settleDiscarded(level,pending.until()); }
        finally { entity.setRemoved(); }
        if(complete)jobs.remove(entry.getKey());
        else {
            jobs.remove(entry.getKey());
            jobs.put(entry.getKey(),new Pending(pending.until(),entity.saveWithoutMetadata(level.registryAccess())));
        }
        setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        tag.putInt("format",1);var rows=new ListTag();
        jobs.forEach((owner,pending)->{
            var row=new CompoundTag();row.putUUID("owner",owner);row.putLong("until",pending.until());row.put("state",pending.state());rows.add(row);
        });
        tag.put("jobs",rows);return tag;
    }
    private static PendingThermal load(ServerLevel level,CompoundTag tag) {
        if(!tag.contains("jobs",Tag.TAG_LIST))throw new IllegalArgumentException("未精算の保存列がありません");
        if(tag.getInt("format")!=1)throw new IllegalArgumentException("未精算の熱設備の保存形式が不正です");
        var data=new PendingThermal(level);
        for(var item:NbtLists.read(tag,"jobs",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)item;
            if(!row.hasUUID("owner")||row.getLong("until")<0||!row.contains("state",Tag.TAG_COMPOUND))
                throw new IllegalArgumentException("未精算の熱設備の保存が不正です");
            var state=row.getCompound("state");
            if(!state.hasUUID("source_id")||!state.getUUID("source_id").equals(row.getUUID("owner"))
                    ||state.getLong("lease_generation")<=0||!state.contains("last_tick",Tag.TAG_LONG)
                    ||!state.contains("heat_reference",Tag.TAG_LONG)||!state.contains("station_position",Tag.TAG_LONG)
                    ||row.getLong("until")<state.getLong("last_tick")||row.getLong("until")>level.getGameTime())
                throw new IllegalArgumentException("未精算の保持参照が不正です");
            if(data.jobs.put(row.getUUID("owner"),new Pending(row.getLong("until"),state))!=null)
                throw new IllegalArgumentException("未精算の発生源が重複しています");
        }
        return data;
    }
}
