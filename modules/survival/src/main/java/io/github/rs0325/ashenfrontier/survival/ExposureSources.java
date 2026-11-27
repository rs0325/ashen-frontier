package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.SoilContamination;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.world.BlockContamination;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 観測中の位置だけへ線源の変更を通知する。持物の全走査は既存menuの差分通知へ委ねる。 */
final class ExposureSources {
    private static final Map<ServerLevel,Map<Long,Set<RadiationField.Point>>> POINTS=new IdentityHashMap<>();
    private static final Map<ServerPlayer,Set<RadiationField.Point>> OWNED=new IdentityHashMap<>();
    private static final Map<ServerPlayer,PlayerWatch> PLAYERS=new IdentityHashMap<>();
    private static final Map<ItemEntity,ItemStamp> ITEMS=new java.util.WeakHashMap<>();
    private record ItemStamp(Vec3 position,int count,ItemProperties.Radioactivity activity,double width,SoilContamination.Stages soil) {
        boolean sameAmount(ItemStamp other) { return count==other.count&&java.util.Objects.equals(activity,other.activity)
                &&width==other.width&&java.util.Objects.equals(soil,other.soil); }
    }
    private ExposureSources() {}
    static void install() {
        NeoForge.EVENT_BUS.addListener((BlockContamination.Changed e)->{
            var pos=Vec3.atCenterOf(e.position);for(var point:List.copyOf(nearby(e.level,pos)))
                if(point.receiver().distanceToSqr(pos)<=100)point.groundChanged();
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e)->{
            if(e.getEntity() instanceof ServerPlayer p)watch(p);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e)->{
            if(e.getEntity() instanceof ServerPlayer p)unwatch(p);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.Clone e)->{
            if(e.getOriginal() instanceof ServerPlayer p)unwatch(p);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e)->{
            if(e.getEntity() instanceof ServerPlayer p)watch(p);
        });
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post e)->{
            if(e.getEntity() instanceof ServerPlayer p){var watch=PLAYERS.get(p);if(watch!=null)watch.tick();}
        });
        NeoForge.EVENT_BUS.addListener((EntityTickEvent.Post e)->{if(e.getEntity() instanceof ItemEntity item)tick(item);});
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e)->{if(e.getEntity() instanceof ItemEntity item)tick(item);});
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Load e)->chunkChanged(e));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload e)->chunkChanged(e));
        NeoForge.EVENT_BUS.addListener((EntityLeaveLevelEvent e)->{
            if(e.getEntity() instanceof ItemEntity item&&e.getLevel() instanceof ServerLevel level) {
                var prior=ITEMS.remove(item);if(prior!=null)changed(level,prior.position);else if(relevant(item.getItem()))changed(level,item.position());
            }
        });
    }
    private static void watch(ServerPlayer player) {
        unwatch(player);PLAYERS.put(player,new PlayerWatch(player));
    }
    private static void unwatch(ServerPlayer player) {
        var old=PLAYERS.remove(player);if(old!=null)old.close();
    }
    private static void chunkChanged(net.neoforged.neoforge.event.level.ChunkEvent event) {
        if(!(event.getLevel() instanceof ServerLevel level))return;
        var cells=POINTS.get(level);if(cells==null)return;
        for(var point:List.copyOf(cells.getOrDefault(event.getChunk().getPos().toLong(),Set.of())))point.groundChanged();
    }
    static void add(ServerPlayer owner,RadiationField.Point point) {
        var level=owner.serverLevel();var cells=POINTS.computeIfAbsent(level,k->new HashMap<>());
        var pos=owner.blockPosition();
        for(int x=(pos.getX()-9)>>4;x<=(pos.getX()+9)>>4;x++)for(int z=(pos.getZ()-9)>>4;z<=(pos.getZ()+9)>>4;z++)
            cells.computeIfAbsent(ChunkPos.asLong(x,z),k->new HashSet<>()).add(point);
        OWNED.computeIfAbsent(owner,k->new HashSet<>()).add(point);
    }
    static void remove(ServerPlayer owner,ServerLevel level,BlockPos position,RadiationField.Point point) {
        var cells=POINTS.get(level);
        if(cells!=null) {
            for(int x=(position.getX()-9)>>4;x<=(position.getX()+9)>>4;x++)for(int z=(position.getZ()-9)>>4;z<=(position.getZ()+9)>>4;z++) {
                long key=ChunkPos.asLong(x,z);var rows=cells.get(key);
                if(rows!=null){rows.remove(point);if(rows.isEmpty())cells.remove(key);}
            }
            if(cells.isEmpty())POINTS.remove(level);
        }
        var owned=OWNED.get(owner);if(owned!=null){owned.remove(point);if(owned.isEmpty())OWNED.remove(owner);}
    }
    private static Set<RadiationField.Point> nearby(ServerLevel level,Vec3 position) {
        var cells=POINTS.get(level);if(cells==null)return Set.of();
        return cells.getOrDefault(ChunkPos.asLong(((int)Math.floor(position.x))>>4,((int)Math.floor(position.z))>>4),Set.of());
    }
    static void changedSource(ServerPlayer source) { changed(source.serverLevel(),source.getEyePosition()); }
    private static void changed(ServerLevel level,Vec3 position) { changed(level,position,false); }
    private static void changed(ServerLevel level,Vec3 position,boolean motion) {
        for(var point:List.copyOf(nearby(level,position)))if(point.receiver().distanceToSqr(position)<=81) {
            if(motion)point.moved();else point.end(level.getGameTime());
        }
    }
    static void observed(ItemEntity item) { ITEMS.putIfAbsent(item,stamp(item)); }
    static boolean relevant(ItemStack stack) {
        return !stack.isEmpty()&&(stack.has(ItemProperties.RADIOACTIVITY)||stack.has(ItemProperties.SOIL_CONTAMINATION)||BlockContamination.isTarget(Block.byItem(stack.getItem()).defaultBlockState()));
    }
    private static ItemStamp stamp(ItemEntity item) {
        var stack=item.getItem();return new ItemStamp(item.position(),stack.getCount(),stack.get(ItemProperties.RADIOACTIVITY),
                stack.getOrDefault(FoodProperties.ACTIVITY_UNCERTAINTY,ItemProperties.ACTIVITY_STEP_BQ),stack.get(ItemProperties.SOIL_CONTAMINATION));
    }
    private static void tick(ItemEntity item) {
        if(!(item.level() instanceof ServerLevel level))return;
        var old=ITEMS.get(item);
        if(nearby(level,item.position()).isEmpty()&&(old==null||nearby(level,old.position).isEmpty())){ITEMS.remove(item);return;}
        if(!relevant(item.getItem())){if(old!=null){ITEMS.remove(item);changed(level,old.position);}return;}
        var current=stamp(item);
        if(!current.equals(old)) {
            boolean motion=old!=null&&current.sameAmount(old);
            if(old!=null)changed(level,old.position,motion);changed(level,current.position,motion);ITEMS.put(item,current);
        }
    }
    private static final class PlayerWatch implements ContainerListener,AutoCloseable {
        final ServerPlayer player;
        final Map<AbstractContainerMenu,Set<Integer>> slots=new IdentityHashMap<>();
        AbstractContainerMenu active;
        ServerLevel level;
        Vec3 position;
        ItemStack carried=ItemStack.EMPTY;
        PlayerWatch(ServerPlayer player) {
            this.player=player;level=player.serverLevel();position=player.getEyePosition();
            attach(player.inventoryMenu);active=player.containerMenu;attach(active);carried=active.getCarried().copy();
        }
        private void attach(AbstractContainerMenu menu) {
            if(!slots.containsKey(menu)){slots.put(menu,new HashSet<>());menu.addSlotListener(this);}
        }
        @Override public void slotChanged(AbstractContainerMenu menu,int index,ItemStack stack) {
            if(menu.getSlot(index).container!=player.getInventory())return;
            var set=slots.get(menu);if(set==null)return;
            boolean before=set.contains(index),after=relevant(stack);
            if(after)set.add(index);else set.remove(index);
            if(before||after)changed(player.serverLevel(),player.getEyePosition());
        }
        @Override public void dataChanged(AbstractContainerMenu menu,int index,int value) {}
        void tick() {
            if(active!=player.containerMenu) {
                if(active!=player.inventoryMenu){active.removeSlotListener(this);slots.remove(active);}
                active=player.containerMenu;attach(active);
            }
            var cursor=active.getCarried();
            if(cursor.getCount()!=carried.getCount()||!ItemStack.isSameItemSameComponents(cursor,carried)) {
                if(relevant(cursor)||relevant(carried))changed(player.serverLevel(),player.getEyePosition());
                carried=cursor.copy();
            }
            var now=player.getEyePosition();
            if(level!=player.serverLevel()||!position.equals(now)) {
                boolean source=relevant(carried)||slots.values().stream().anyMatch(s->!s.isEmpty());
                if(source){changed(level,position,true);changed(player.serverLevel(),now,true);}
                for(var point:List.copyOf(OWNED.getOrDefault(player,Set.of())))point.moved();
                level=player.serverLevel();position=now;
            }
        }
        @Override public void close() {
            for(var menu:slots.keySet())menu.removeSlotListener(this);slots.clear();
            changed(level,position);
        }
    }
    static void clear() {
        for(var player:List.copyOf(PLAYERS.values()))player.close();PLAYERS.clear();ITEMS.clear();POINTS.clear();OWNED.clear();
    }
}
