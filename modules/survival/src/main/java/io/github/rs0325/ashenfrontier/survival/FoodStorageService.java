package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.core.food.FoodHistoryQuery;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.core.food.FoodStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.UUID;

/** 携帯・地上アイテムの実観測と、保留中の使用完了を接続する。 */
final class FoodStorageService {
    private static final HashMap<UUID,WeakReference<Entity>> LIVE=new HashMap<>();
    private FoodStorageService() {}
    static void clear() { LIVE.clear(); }
    static void remember(Entity entity) { LIVE.put(entity.getUUID(),new WeakReference<>(entity)); }
    static void detach(ServerPlayer player) {
        FoodStorageData.get(player.serverLevel()).capture(player.getUUID(),player.serverLevel(),player.blockPosition());LIVE.remove(player.getUUID());
    }
    static void captureKnown(FoodStorageData data,UUID id) {
        var ref=LIVE.get(id);var entity=ref==null?null:ref.get();
        if(entity!=null&&!entity.isRemoved()&&entity.level() instanceof ServerLevel level)data.capture(id,level,entity.blockPosition());
    }
    static void observe(FoodStorage event) {
        var data=FoodStorageData.get(event.level);
        if(event.consumed>0)data.consume(event.stack,event.consumed);
        else data.observe(event.level,event.holder,event.position,event.stack);
    }
    static void history(FoodHistoryQuery event) {
        if(!event.source().has(FoodProperties.STORAGE))return;
        var data=FoodStorageData.get(event.player().serverLevel());
        data.query(event.source(),event.through()).ifPresent(event::provide);
        if(data.missing(event.source()))event.reject("rot_history");
    }
    static void player(PlayerTickEvent.Post event) {
        if(event.getEntity() instanceof ServerPlayer player) {
            LIVE.put(player.getUUID(),new WeakReference<>(player));
            var data=FoodStorageData.get(player.serverLevel());data.capture(player.getUUID(),player.serverLevel(),player.blockPosition());
            if(player.serverLevel().getGameTime()%20==0) {
                var counts=new HashMap<UUID,Integer>();
                for(int slot=0;slot<player.getInventory().getContainerSize();slot++) {
                    var stack=player.getInventory().getItem(slot);var key=stack.get(FoodProperties.STORAGE);
                    if(key!=null)counts.merge(key,stack.getCount(),Integer::sum);
                }
                var carried=player.containerMenu.getCarried();var key=carried.get(FoodProperties.STORAGE);
                if(key!=null)counts.merge(key,carried.getCount(),Integer::sum);
                data.reconcile(player.getUUID(),counts);
            }
        }
    }
    static void join(EntityJoinLevelEvent event) {
        if(event.getLevel() instanceof ServerLevel level&&event.getEntity() instanceof ItemEntity item&&item.getItem().has(FoodProperties.EVIDENCE)) {
            LIVE.put(item.getUUID(),new WeakReference<>(item));FoodStorage.observe(level,item.getUUID(),item.blockPosition(),item.getItem());
        }
    }
    static void leave(EntityLeaveLevelEvent event) {
        if(event.getLevel() instanceof ServerLevel level&&event.getEntity() instanceof ItemEntity item) {
            FoodStorageData.get(level).capture(item.getUUID(),level,item.blockPosition());
            if(item.getRemovalReason()!=null&&item.getRemovalReason().shouldDestroy()&&!item.getItem().isEmpty()
                    &&item.getItem().has(FoodProperties.STORAGE))FoodStorage.consume(level,item.getItem(),item.getItem().getCount());
            LIVE.remove(item.getUUID());
        }
    }
    static void pickup(ItemEntityPickupEvent.Post event) {
        if(event.getPlayer() instanceof ServerPlayer player&&event.getOriginalStack().has(FoodProperties.STORAGE)) {
            LIVE.put(player.getUUID(),new WeakReference<>(player));
            for(var stack:player.getInventory().items)if(stack.has(FoodProperties.STORAGE))
                FoodStorage.observe(player.serverLevel(),player.getUUID(),player.blockPosition(),stack);
        }
    }
    static void tick(ServerTickEvent.Pre event) {
        FoodStorageData.get(event.getServer().overworld()).work();IntakeService.retry();
    }
}
