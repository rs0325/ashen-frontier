package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.ItemPropertyMath;
import io.github.rs0325.ashenfrontier.core.NbtLists;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentRetention;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;

/** 保管者ごとの未評価経路を共有する。評価済みの温度列は残さず、食品ごとのRと分岐の確定点へ畳み込む。 */
final class FoodStorageData extends SavedData {
    private final MinecraftServer server;
    private final LinkedHashMap<UUID,Holder> holders=new LinkedHashMap<>();
    private final LinkedHashMap<UUID,Food> foods=new LinkedHashMap<>();
    private final ArrayDeque<UUID> due=new ArrayDeque<>();
    private CompoundTag failure;
    private static final class Span {
        UUID source;String dimension;BlockPos position;long generation,from,through,to,target=-1;boolean closed;
        ServerLevel live;
        Span(ServerLevel level,BlockPos position,long now) {
            live=level;dimension=level.dimension().location().toString();this.position=position.immutable();from=through=to=now;
        }
    }
    /** C06の終了証明を位置ごとのUUIDで増やさず、dimension内の1所有者の連番へ畳み込む。 */
    private static final class Registrations extends SavedData {
        UUID owner=UUID.randomUUID();long issued;CompoundTag failure;
        static Registrations get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new Factory<>(Registrations::new,(tag,registries)->{
                var data=new Registrations();
                if(tag.getInt("format")!=1||!tag.hasUUID("owner")||!tag.contains("issued",Tag.TAG_LONG)||tag.getLong("issued")<0)data.failure=tag.copy();
                else{data.owner=tag.getUUID("owner");data.issued=tag.getLong("issued");}return data;
            },null),"ashen_frontier_food_registrations");
        }
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
            if(failure!=null)return failure.copy();tag.putInt("format",1);tag.putUUID("owner",owner);tag.putLong("issued",issued);return tag;
        }
    }
    private static final class Holder {
        UUID id;final ArrayDeque<Span> spans=new ArrayDeque<>();final LinkedHashSet<UUID> foods=new LinkedHashSet<>();boolean queued;
        Holder(UUID id) { this.id=id; }
    }
    private static final class Food {
        UUID id,holder,parent;int count;long created,through,cut,query=-1;double reference,answer;boolean answered,failed;
        ItemStack definition;final ArrayList<UUID> children=new ArrayList<>();
    }
    private FoodStorageData(MinecraftServer server) { this.server=server; }
    static FoodStorageData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new Factory<>(
                ()->new FoodStorageData(level.getServer()),(tag,registries)->load(level.getServer(),tag,registries),null),"ashen_frontier_food_storage");
    }
    private ServerLevel level(Span span) {
        return span.live!=null?span.live:server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(span.dimension)));
    }
    private boolean retain(Span span) {
        var level=level(span);if(level==null)return false;
        var registration=Registrations.get(level);if(registration.failure!=null)return false;
        span.source=registration.owner;span.generation=registration.issued+1;
        if(!EnvironmentRetention.request(level,span.source,span.generation,span.position.above(),span.from,level.getGameTime(),0,EnvironmentRetention.Action.RETAIN).orElse(false))return false;
        registration.issued=span.generation;registration.setDirty();return true;
    }
    private void close(Span span) {
        if(span.closed)return;span.closed=true;
        var level=level(span);if(level!=null)EnvironmentRetention.request(level,span.source,span.generation,span.position.above(),span.from,span.to,0,EnvironmentRetention.Action.END);
    }
    private void release(Span span) {
        close(span);var level=level(span);if(level==null)return;
        EnvironmentRetention.request(level,span.source,span.generation,span.position.above(),span.from,span.to,0,EnvironmentRetention.Action.COMMIT);
        EnvironmentRetention.request(level,span.source,span.generation,span.position.above(),span.from,span.to,0,EnvironmentRetention.Action.RELEASE);
    }
    private void queue(Holder holder) {
        if(!holder.queued&&!holder.spans.isEmpty()){holder.queued=true;due.addLast(holder.id);}
    }
    private boolean active(Holder holder) { return holder.foods.stream().map(foods::get).anyMatch(f->f.count>0&&!f.failed); }
    void capture(UUID id,ServerLevel level,BlockPos position) {
        var holder=holders.get(id);if(holder==null||!active(holder))return;
        var last=holder.spans.peekLast();long now=level.getGameTime();
        if(last!=null&&!last.closed&&last.dimension.equals(level.dimension().location().toString())&&last.position.equals(position)) {
            if(now<last.to){fail(holder);return;}last.live=level;last.to=now;
        } else {
            if(last!=null&&!last.closed) {
                var previous=level(last);if(previous==null){fail(holder);return;}
                last.to=previous.getGameTime();close(last);
            }
            var span=new Span(level,position,now);if(!retain(span)){fail(holder);return;}holder.spans.addLast(span);
        }
        setDirty();queue(holder);
    }
    private static ItemStack definition(ItemStack source) {
        var copy=source.copyWithCount(1);copy.remove(FoodProperties.STORAGE);copy.remove(ItemProperties.ROT);return copy;
    }
    private boolean matches(Food food,ItemStack stack) {
        var rot=stack.get(ItemProperties.ROT);
        return rot!=null&&rot.createdAt()==food.created&&ItemStack.isSameItemSameComponents(food.definition,definition(stack));
    }
    void observe(ServerLevel level,UUID owner,BlockPos position,ItemStack stack) {
        if(failure!=null||stack.isEmpty()||!stack.has(ItemProperties.ROT)||!stack.has(FoodProperties.EVIDENCE))return;
        long now=level.getGameTime();var key=stack.get(FoodProperties.STORAGE);Food food;
        if(key==null) {
            var rot=stack.get(ItemProperties.ROT);
            if(rot.lastCalculatedAt()!=now||rot.createdAt()<0||rot.createdAt()>now||!Double.isFinite(rot.referenceSeconds())||rot.referenceSeconds()<0)return;
            food=new Food();food.id=UUID.randomUUID();food.holder=owner;food.count=stack.getCount();food.created=rot.createdAt();
            food.through=now;food.reference=rot.referenceSeconds();food.definition=definition(stack);foods.put(food.id,food);
        } else {
            food=foods.get(key);if(food==null||food.failed||!matches(food,stack))return;
            if(!food.holder.equals(owner)) {
                FoodStorageService.captureKnown(this,food.holder);
                var old=holders.get(food.holder);var end=old.spans.peekLast();if(end==null||end.to!=now||stack.getCount()>food.count)return;
                var child=new Food();child.id=UUID.randomUUID();child.holder=owner;child.parent=food.id;child.cut=child.through=now;
                child.count=stack.getCount();child.created=food.created;child.definition=food.definition.copy();
                food.count-=child.count;food.children.add(child.id);foods.put(child.id,child);
                if(food.through==now&&food.parent==null){child.reference=food.reference;child.parent=null;food.children.remove(child.id);}
                prune(old);
                food=child;
            }
        }
        var holder=holders.computeIfAbsent(owner,Holder::new);holder.foods.add(food.id);
        stack.set(FoodProperties.STORAGE,food.id);capture(owner,level,position);setDirty();
    }
    Optional<ItemStack> query(ItemStack source,long target) {
        if(failure!=null)return Optional.empty();var food=foods.get(source.get(FoodProperties.STORAGE));
        if(food==null||food.failed||!matches(food,source)||source.getCount()>food.count)return Optional.empty();
        if(food.query!=target){food.query=target;food.answered=food.parent==null&&food.through==target;if(food.answered)food.answer=food.reference;setDirty();}
        queue(holders.get(food.holder));work();
        if(!food.answered||food.parent!=null)return Optional.empty();
        var copy=source.copy();copy.set(ItemProperties.ROT,new ItemProperties.Rot(food.created,target,Math.ceil(food.answer/600)*600));return Optional.of(copy);
    }
    boolean missing(ItemStack source) {
        var food=foods.get(source.get(FoodProperties.STORAGE));return failure!=null||food==null||food.failed;
    }
    void reconcile(UUID owner,java.util.Map<UUID,Integer> counts) {
        var holder=holders.get(owner);if(holder==null)return;
        boolean changed=false;
        for(var key:holder.foods) {
            var food=foods.get(key);int actual=counts.getOrDefault(key,0);
            if(actual==food.count)continue;
            changed=true;if(actual>food.count)food.failed=true;else food.count=actual;
        }
        if(changed){prune(holder);setDirty();}
    }
    void consume(ItemStack source,int count) {
        var food=foods.get(source.get(FoodProperties.STORAGE));if(food==null||failure!=null)return;
        if(count>food.count||!matches(food,source))throw new IllegalArgumentException("食品の実消費と保管台帳が一致しません");
        food.count-=count;food.query=-1;food.answered=false;
        var rot=source.get(ItemProperties.ROT);if(rot!=null&&rot.lastCalculatedAt()==food.through)food.reference=Math.max(food.reference,rot.referenceSeconds());
        prune(holders.get(food.holder));setDirty();
    }
    private void fail(Holder holder) {
        var pending=new ArrayDeque<>(holder.foods);var affected=new LinkedHashSet<Holder>();affected.add(holder);
        var visited=new java.util.HashSet<UUID>();
        while(!pending.isEmpty()) {
            var key=pending.removeFirst();if(!visited.add(key))continue;
            var food=foods.get(key);if(food==null)continue;
            food.failed=true;pending.addAll(food.children);affected.add(holders.get(food.holder));
        }
        for(var owner:affected)prune(owner);
        setDirty();
    }
    private void prune(Holder changed) {
        var pending=new ArrayDeque<>(changed.foods);var affected=new LinkedHashSet<Holder>();affected.add(changed);
        while(!pending.isEmpty()) {
            var key=pending.removeFirst();var food=foods.get(key);
            if(food==null||food.count!=0||!food.children.isEmpty())continue;
            var owner=holders.get(food.holder);foods.remove(key);owner.foods.remove(key);affected.add(owner);
            if(food.parent!=null) {
                var parent=foods.get(food.parent);
                if(parent!=null){parent.children.remove(key);pending.addLast(parent.id);affected.add(holders.get(parent.holder));}
            }
        }
        for(var holder:affected) {
            if(holder.foods.stream().map(foods::get).allMatch(f->f.failed||f.count==0&&f.children.isEmpty())) {
                for(var span:holder.spans)release(span);holder.spans.clear();
            }
            if(holder.foods.isEmpty()&&holder.spans.isEmpty())holders.remove(holder.id);
        }
    }
    void work() {
        if(failure!=null)return;long deadline=System.nanoTime()+100_000;
        for(int n=0;n<16&&!due.isEmpty()&&System.nanoTime()<deadline;n++) {
            var holder=holders.get(due.removeFirst());if(holder==null)continue;holder.queued=false;
            var span=holder.spans.peekFirst();if(span==null)continue;
            var level=level(span);if(level==null||EnvironmentBudget.remaining(level)<=0){queue(holder);continue;}
            boolean resolved=false;
            for(var key:holder.foods) {
                var food=foods.get(key);
                if(food.parent==null)for(var childId:java.util.List.copyOf(food.children)) {
                    var child=foods.get(childId);
                    if(food.through==child.cut){child.reference+=food.reference;child.parent=null;food.children.remove(childId);queue(holders.get(child.holder));resolved=true;}
                }
                if(food.parent==null&&food.query==food.through&&!food.answered){food.answer=food.reference;food.answered=true;}
            }
            if(resolved){prune(holder);if(holder.spans.peekFirst()!=span)continue;}
            if(span.through==span.to) {
                if(span.closed){release(span);holder.spans.removeFirst();queue(holder);}continue;
            }
            long to=Math.min(span.to,span.through+600);
            boolean blocked=false;
            for(var key:holder.foods) {
                var food=foods.get(key);
                if(food.failed)continue;
                if(food.parent!=null&&food.through<=span.through)blocked=true;
                if(food.through>span.through)to=Math.min(to,food.through);
                if(food.query>span.through&&!food.answered)to=Math.min(to,food.query);
                for(var childId:food.children){long cut=foods.get(childId).cut;if(cut>span.through)to=Math.min(to,cut);}
            }
            if(blocked){queue(holder);continue;}
            if(span.target<0)span.target=to;else to=Math.min(to,span.target);
            var request=EnvironmentQuery.query(level,span.position,span.through,to);
            if(request.values().isEmpty()) {
                if(request.reason().orElse("").contains("history_missing"))fail(holder);else queue(holder);
                continue;
            }
            var sample=request.values().orElseThrow().getFirst();long start=System.nanoTime();
            for(var key:holder.foods) {
                var food=foods.get(key);if(food.failed||food.through>sample.startTick())continue;
                food.reference=ItemPropertyMath.rotProgress(food.reference,GameTime.gameSeconds(sample.endTick()-sample.startTick(),sample.ticksPerDay()),
                        sample.temperatureK(),ItemProperties.ROT_REFERENCE_K,ItemProperties.ROT_Q10);food.through=sample.endTick();
            }
            span.through=sample.endTick();if(span.through==span.target)span.target=-1;
            setDirty();EnvironmentBudget.charge(level,System.nanoTime()-start);queue(holder);
        }
    }
    static FoodStorageData load(MinecraftServer server,CompoundTag tag,HolderLookup.Provider registries) {
        var data=new FoodStorageData(server);
        try { data.read(tag,registries); }
        catch(RuntimeException failure){data.holders.clear();data.foods.clear();data.due.clear();data.failure=tag.copy();}
        return data;
    }
    private void read(CompoundTag tag,HolderLookup.Provider registries) {
        if(tag.getInt("format")!=2||!tag.contains("holders",Tag.TAG_LIST)||!tag.contains("foods",Tag.TAG_LIST))throw new IllegalArgumentException("食品保管保存が不正です");
        for(var value:NbtLists.read(tag,"holders",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)value;var holder=new Holder(row.getUUID("id"));
            if(holders.putIfAbsent(holder.id,holder)!=null||!row.contains("spans",Tag.TAG_LIST))throw new IllegalArgumentException("保管者が重複または不正です");
            for(var part:NbtLists.read(row,"spans",Tag.TAG_COMPOUND)) {
                var s=(CompoundTag)part;
                if(!s.contains("dimension",Tag.TAG_STRING)||!s.contains("position",Tag.TAG_LONG)||!s.contains("from",Tag.TAG_LONG)
                        ||!s.contains("through",Tag.TAG_LONG)||!s.contains("to",Tag.TAG_LONG)||!s.contains("generation",Tag.TAG_LONG)||!s.contains("target",Tag.TAG_LONG)||!s.contains("closed",Tag.TAG_BYTE))throw new IllegalArgumentException("保管区間が欠落しています");
                String dimension=s.getString("dimension");var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(dimension)));
                var span=new Span(level,BlockPos.of(s.getLong("position")),s.getLong("from"));span.dimension=dimension;span.source=s.getUUID("source");
                span.through=s.getLong("through");span.to=s.getLong("to");span.generation=s.getLong("generation");span.target=s.getLong("target");span.closed=s.getBoolean("closed");
                if(span.generation<1||span.from<0||span.through<span.from||span.to<span.through||span.target!=-1&&(span.target<=span.through||span.target>span.to))throw new IllegalArgumentException("保管区間の時刻が不正です");
                holder.spans.addLast(span);
            }
        }
        var ops=RegistryOps.create(NbtOps.INSTANCE,registries);
        for(var value:NbtLists.read(tag,"foods",Tag.TAG_COMPOUND)) {
            var r=(CompoundTag)value;var food=new Food();food.id=r.getUUID("id");food.holder=r.getUUID("holder");
            if(!r.contains("count",Tag.TAG_INT)||!r.contains("created",Tag.TAG_LONG)||!r.contains("through",Tag.TAG_LONG)||!r.contains("reference",Tag.TAG_DOUBLE)
                    ||!r.contains("query",Tag.TAG_LONG)||!r.contains("answered",Tag.TAG_BYTE)||!r.contains("answer",Tag.TAG_DOUBLE)||!r.contains("failed",Tag.TAG_BYTE))throw new IllegalArgumentException("食品残高が欠落しています");
            food.count=r.getInt("count");food.created=r.getLong("created");food.through=r.getLong("through");food.reference=r.getDouble("reference");
            food.query=r.getLong("query");food.answered=r.getBoolean("answered");food.answer=r.getDouble("answer");food.failed=r.getBoolean("failed");
            food.definition=ItemStack.CODEC.parse(ops,r.get("definition")).getOrThrow();
            if(r.contains("parent")){food.parent=r.getUUID("parent");if(!r.contains("cut",Tag.TAG_LONG))throw new IllegalArgumentException("分岐時刻が欠落しています");food.cut=r.getLong("cut");
                if(food.cut!=food.through||food.cut<food.created||food.reference!=0)throw new IllegalArgumentException("未確定分岐の時刻・残高が不正です");}
            if(food.count<0||food.created<0||food.through<food.created||!Double.isFinite(food.reference)||food.reference<0||!Double.isFinite(food.answer)||food.answer<0
                    ||!holders.containsKey(food.holder)||foods.putIfAbsent(food.id,food)!=null)throw new IllegalArgumentException("食品残高が不正です");
            holders.get(food.holder).foods.add(food.id);
        }
        for(var food:foods.values())if(food.parent!=null) {
            var parent=foods.get(food.parent);if(parent==null||food.cut<parent.through)throw new IllegalArgumentException("分岐の参照が不正です");parent.children.add(food.id);
            var visited=new java.util.HashSet<UUID>();var cursor=food;
            while(cursor.parent!=null){if(!visited.add(cursor.id))throw new IllegalArgumentException("分岐が循環しています");cursor=foods.get(cursor.parent);if(cursor==null)throw new IllegalArgumentException("分岐元が欠落しています");}
        }
        for(var holder:holders.values())queue(holder);
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(failure!=null)return failure.copy();tag.putInt("format",2);var owners=new ListTag();
        for(var holder:holders.values()) {
            var row=new CompoundTag();row.putUUID("id",holder.id);var spans=new ListTag();
            for(var span:holder.spans){var s=new CompoundTag();s.putUUID("source",span.source);s.putLong("generation",span.generation);s.putString("dimension",span.dimension);s.putLong("position",span.position.asLong());
                s.putLong("from",span.from);s.putLong("through",span.through);s.putLong("to",span.to);s.putLong("target",span.target);s.putBoolean("closed",span.closed);spans.add(s);}
            row.put("spans",spans);owners.add(row);
        }
        tag.put("holders",owners);var rows=new ListTag();var ops=RegistryOps.create(NbtOps.INSTANCE,registries);
        for(var food:foods.values()){var r=new CompoundTag();r.putUUID("id",food.id);r.putUUID("holder",food.holder);r.putInt("count",food.count);
            r.putLong("created",food.created);r.putLong("through",food.through);r.putDouble("reference",food.reference);r.putLong("query",food.query);
            r.putBoolean("answered",food.answered);r.putDouble("answer",food.answer);r.putBoolean("failed",food.failed);
            r.put("definition",ItemStack.CODEC.encodeStart(ops,food.definition).getOrThrow());if(food.parent!=null){r.putUUID("parent",food.parent);r.putLong("cut",food.cut);}rows.add(r);}
        tag.put("foods",rows);return tag;
    }
}
