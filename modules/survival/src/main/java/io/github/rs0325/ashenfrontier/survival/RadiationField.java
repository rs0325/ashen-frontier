package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.climate.ClimateQuery;
import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.core.ItemProperties;
import io.github.rs0325.ashenfrontier.core.NbtLists;
import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import io.github.rs0325.ashenfrontier.core.food.FoodProperties;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import io.github.rs0325.ashenfrontier.world.BlockContamination;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 現在に観測した局所線源を固定する。未ロードや失った観測を、後日の幾何で埋めない。 */
final class RadiationField {
    private static final List<BlockPos> FLOOR;
    private static final Set<String> MINERALS;
    static {
        var cells=new ArrayList<BlockPos>();
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)if(x*x+z*z<=64)cells.add(new BlockPos(x,-1,z));
        FLOOR=List.copyOf(cells);
        var blocks=new HashSet<String>();
        for(String id:List.of("stone","granite","diorite","andesite","deepslate","cobblestone",
                "cobbled_deepslate","stone_bricks","dirt","coarse_dirt","rooted_dirt","packed_mud"))blocks.add("minecraft:"+id);
        NatureCatalog.entries().stream().filter(e->e.kind().equals("rock")).forEach(e->blocks.add("ashen_frontier:"+e.id()));
        MINERALS=Set.copyOf(blocks);
    }
    static boolean mineral(ServerLevel level,BlockPos pos,BlockState state) {
        return MINERALS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())&&state.isCollisionShapeFullBlock(level,pos);
    }
    record Rate(String nuclide,double lower,double upper) {}
    private record Source(Vec3 position,String nuclide,double lower,double upper,boolean own) {}
    static final class Point implements AutoCloseable {
        final long tick;
        long until=Long.MAX_VALUE;
        double ground;
        boolean known=true,done;
        private boolean groundKnown=true,closed,groundDirty;
        private final ServerPlayer owner;
        final List<Rate> rates=new ArrayList<>();
        private final ServerLevel level;
        private final BlockPos feet;
        private final Vec3 receiver;
        private final RoomAir.ExposureGeometry geometry;

        private final List<Source> sources;
        private int groundIndex,sourceIndex,holderIndex,slotIndex,entityIndex;
        private List<ServerPlayer> holders;
        private List<ItemEntity> entities;
        private boolean captured;
        private long clockTick;
        private double elapsedGame,clockCompensation;
        Point(ServerPlayer player) { this(player,null); }
        Point(ServerPlayer player,Point prior) {
            owner=player;level=player.serverLevel();tick=level.getGameTime();clockTick=tick;feet=player.blockPosition().immutable();receiver=player.getEyePosition();
            geometry=RoomAir.get(level).observeExposureGeometry(feet);
            sources=new ArrayList<>();
            ExposureSources.add(player,this);
            if(prior!=null&&!prior.closed&&prior.level==level&&prior.groundIndex==FLOOR.size()&&prior.receiver.equals(receiver)
                    &&prior.geometry.changedAt()==Long.MAX_VALUE&&!prior.groundDirty) {
                ground=prior.ground;groundKnown=prior.groundKnown;groundIndex=FLOOR.size();known&=groundKnown;
            }

        }
        private Point(long tick,long until,double ground,boolean known,List<Rate> rates) {
            this.tick=tick;clockTick=tick;this.until=until;this.ground=ground;this.known=known;this.rates.addAll(rates);done=true;
            owner=null;level=null;feet=null;receiver=null;geometry=null;sources=List.of();captured=true;
        }
        private boolean capture(long deadline) {
            if(holders==null) {
                holders=new ArrayList<>();holders.add(owner);
                for(var player:level.players())if(player!=owner&&player.position().distanceToSqr(owner.position())<=64)holders.add(player);
            }
            while(System.nanoTime()<deadline&&holderIndex<holders.size()) {
                var holder=holders.get(holderIndex);int size=holder.getInventory().getContainerSize();
                if(slotIndex<size) {
                    var stack=holder.getInventory().getItem(slotIndex++);
                    if(IntakeService.unresolvedSource(holder,stack)&&stack.has(ItemProperties.RADIOACTIVITY))known=false;
                    else add(stack,holder.getEyePosition(),holder==owner);
                } else {
                    var stack=holder.containerMenu.getCarried();
                    if(IntakeService.unresolvedSource(holder,stack)&&stack.has(ItemProperties.RADIOACTIVITY))known=false;
                    else add(stack,holder.getEyePosition(),holder==owner);
                    holderIndex++;slotIndex=0;
                }
            }
            if(holderIndex<holders.size()||System.nanoTime()>=deadline)return false;
            if(entities==null)entities=level.getEntitiesOfClass(ItemEntity.class,new AABB(receiver,receiver).inflate(8));
            while(System.nanoTime()<deadline&&entityIndex<entities.size()) {
                var entity=entities.get(entityIndex++);
                if(entity.position().distanceToSqr(receiver)<=64){ExposureSources.observed(entity);add(entity.getItem(),entity.position(),false);}
            }
            if(entityIndex<entities.size())return false;
            holders=null;entities=null;return true;
        }
        private void add(ItemStack stack,Vec3 pos,boolean own) {
            if(stack.isEmpty())return;
            var activity=stack.get(ItemProperties.RADIOACTIVITY);
            if(activity==null) {
                var block=Block.byItem(stack.getItem());
                if(stack.has(ItemProperties.SOIL_CONTAMINATION)||BlockContamination.isTarget(block.defaultBlockState()))known=false;
                return;
            }
            var definition=Nuclides.get(activity.nuclide());var history=ClimateQuery.dayLengthHistory(level);
            double width=stack.getOrDefault(FoodProperties.ACTIVITY_UNCERTAINTY,ItemProperties.ACTIVITY_STEP_BQ);
            if(definition.isEmpty()||history.isEmpty()||activity.gameTime()<0||activity.gameTime()>tick
                    ||!Double.isFinite(activity.becquerels()+width)||activity.becquerels()<0||width<0) { known=false;return; }
            double factor=stack.getCount()*Math.exp(-Math.log(2)*GameTime.gameSeconds(activity.gameTime(),tick,history.get())/definition.get().halfLifeGameSeconds());
            sources.add(new Source(pos,activity.nuclide(),activity.becquerels()*factor,(activity.becquerels()+width)*factor,own));
        }
        double ageAt(long from,io.github.rs0325.ashenfrontier.core.time.DayLengthHistory history) {
            return from==clockTick?elapsedGame:GameTime.gameSeconds(tick,from,history);
        }
        void ageThrough(long from,long to,double base,double game) {
            if(clockTick!=from){elapsedGame=base;clockCompensation=0;}
            double adjusted=game-clockCompensation,total=elapsedGame+adjusted;
            clockCompensation=(total-elapsedGame)-adjusted;elapsedGame=total;clockTick=to;
        }
        long validUntil() {
            if(geometry==null)return until;
            return Math.min(until,geometry.changedAt());
        }
        boolean changed() { return geometry!=null&&(geometry.changedAt()!=Long.MAX_VALUE||groundDirty); }
        /** 1セルまたは1射線ごとに協調期限を確認する。線源数を黙って切り捨てない。 */
        void work(long deadline) {
            if(done)return;
            if(changed()) { ground=0;rates.clear();known=false;done=true;return; }
            if(!captured) { captured=capture(deadline);if(!captured)return; }
            while(System.nanoTime()<deadline&&groundIndex<FLOOR.size()) {
                var pos=feet.offset(FLOOR.get(groundIndex++));
                if(!level.hasChunkAt(pos)) { known=false;groundKnown=false;continue; }
                var soil=BlockContamination.get(level,pos);if(soil.isEmpty())continue;
                var definition=Nuclides.get("ashen_frontier:cs137");if(definition.isEmpty()) { known=false;groundKnown=false;continue; }
                var source=new Vec3(pos.getX()+.5,pos.getY()+1,pos.getZ()+.5);
                double material=shield(level,source,receiver);
                if(material<0) { known=false;groundKnown=false;continue; }
                ground+=Exposure.pointRate(soil.get().caesium137()*80,source.distanceTo(receiver),material,definition.get().gammaSvM2SecondBq());
            }
            while(System.nanoTime()<deadline&&groundIndex==FLOOR.size()&&sourceIndex<sources.size()) {
                var source=sources.get(sourceIndex++);double material=source.own?0:shield(level,source.position,receiver);
                if(material<0) { known=false;continue; }
                var definition=Nuclides.get(source.nuclide);if(definition.isEmpty()) { known=false;continue; }
                double factor=Exposure.pointRate(1,source.own?.5:source.position.distanceTo(receiver),material,definition.get().gammaSvM2SecondBq());
                rates.add(new Rate(source.nuclide,source.lower*factor,source.upper*factor));
            }
            done=groundIndex==FLOOR.size()&&sourceIndex==sources.size();
        }
        void end(long tick) {
            until=Math.min(until,tick);if(!done){done=true;known=false;}close();
            var runtime=owner==null?null:AshenFrontierSurvival.ACTIVE.get(owner);if(runtime!=null&&runtime.data.online)runtime.schedule(1);
        }
        BlockPos position() { return feet; }
        Vec3 receiver() { return receiver; }
        void expire() { end(level.getGameTime()); }
        void moved() {
            var runtime=owner==null?null:AshenFrontierSurvival.ACTIVE.get(owner);if(runtime!=null)runtime.radiationMoved();
            expire();
        }
        void groundChanged() { groundDirty=true;expire(); }
        @Override public void close() {
            if(closed)return;closed=true;
            if(geometry!=null){geometry.close();ExposureSources.remove(owner,level,feet,this);}
        }
        CompoundTag save(long observed) {
            var t=new CompoundTag();t.putLong("tick",tick);t.putLong("until",Math.min(observed,validUntil()));
            t.putDouble("ground",ground);t.putBoolean("known",known&&done);var list=new ListTag();
            for(var rate:rates) { var r=new CompoundTag();r.putString("nuclide",rate.nuclide);r.putDouble("lower",rate.lower);r.putDouble("upper",rate.upper);list.add(r); }
            t.put("rates",list);return t;
        }
        static Point load(CompoundTag t) {
            ExposureCodec.require(t,"tick",Tag.TAG_LONG);ExposureCodec.require(t,"until",Tag.TAG_LONG);
            ExposureCodec.require(t,"ground",Tag.TAG_DOUBLE);ExposureCodec.require(t,"known",Tag.TAG_BYTE);ExposureCodec.require(t,"rates",Tag.TAG_LIST);
            var rates=new ArrayList<Rate>();
            for(var entry:NbtLists.read(t,"rates",Tag.TAG_COMPOUND)) {
                var row=(CompoundTag)entry;ExposureCodec.require(row,"nuclide",Tag.TAG_STRING);
                ExposureCodec.require(row,"lower",Tag.TAG_DOUBLE);ExposureCodec.require(row,"upper",Tag.TAG_DOUBLE);
                String id=row.getString("nuclide");double lower=row.getDouble("lower"),upper=row.getDouble("upper");
                if(net.minecraft.resources.ResourceLocation.tryParse(id)==null||lower<0||upper<lower||!Double.isFinite(lower+upper))
                    throw new IllegalArgumentException("局所線源の係数が不正です");
                rates.add(new Rate(id,lower,upper));
            }
            long from=t.getLong("tick"),to=t.getLong("until");double ground=t.getDouble("ground");
            if(from<0||to<from||!Double.isFinite(ground)||ground<0)throw new IllegalArgumentException("局所線源の保存が不正です");
            return new Point(from,to,ground,t.getBoolean("known"),rates);
        }
    }
    /** 最大32 voxel区間の実長。未知chunkは遮蔽なしではなく未観測。源自身の自己吸収は省く。 */
    static double shield(ServerLevel level,Vec3 from,Vec3 to) {
        var delta=to.subtract(from);double length=delta.length();if(length==0)return 0;
        int x=(int)Math.floor(from.x),y=(int)Math.floor(from.y),z=(int)Math.floor(from.z);
        double t=0,total=0;
        for(int visit=0;visit<32&&t<1;visit++) {
            double tx=next(from.x,delta.x,x),ty=next(from.y,delta.y,y),tz=next(from.z,delta.z,z);
            double end=Math.min(1,Math.min(tx,Math.min(ty,tz)));
            var pos=new BlockPos(x,y,z);if(!level.hasChunkAt(pos))return -1;
            if(visit>0&&mineral(level,pos,level.getBlockState(pos)))total+=(end-t)*length;
            if(end>=1)return total;
            if(tx==end)x+=delta.x>0?1:-1;if(ty==end)y+=delta.y>0?1:-1;if(tz==end)z+=delta.z>0?1:-1;t=end;
        }
        return t>=1?total:-1;
    }
    private static double next(double origin,double direction,int cell) {
        return direction==0?Double.POSITIVE_INFINITY:((direction>0?cell+1:cell)-origin)/direction;
    }
    private RadiationField() {}
}
