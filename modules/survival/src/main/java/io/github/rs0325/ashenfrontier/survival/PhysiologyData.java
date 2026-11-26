package io.github.rs0325.ashenfrontier.survival;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import io.github.rs0325.ashenfrontier.core.NbtLists;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.UUID;

/** 返済/受領印/未確定のオンライン区間を同じAttachmentで保存する。 */
public final class PhysiologyData {
    public static final Codec<PhysiologyData> CODEC=Codec.PASSTHROUGH.comapFlatMap(value->{
        try {
            var tag=value.convert(NbtOps.INSTANCE).getValue();
            if(!(tag instanceof CompoundTag compound))return DataResult.error(()->"生理保存がcompoundではありません");
            return DataResult.success(load(compound));
        } catch(RuntimeException e) { return DataResult.error(()->"生理保存が不正です: "+e.getMessage()); }
    },value->new Dynamic<>(NbtOps.INSTANCE,value.save()));
    public Body body;
    Tag failure;
    boolean blocked() { return failure!=null; }
    static PhysiologyData failed(Tag raw) {
        var state=new PhysiologyData(null,null);state.failure=raw.copy();return state;
    }
    final UUID owner;
    long observed=-1,cursor=-1,leaseSerial,currentLease;
    boolean online,effects=true;
    final LinkedHashMap<String,Long> dimensionSerials=new LinkedHashMap<>();
    Activity activity=Activity.REST;
    boolean submerged;
    long sleepGeneration,consumedSleep,lastCredit=-1,pendingSleep;
    boolean sleeping;
    long invalidSleep;
    final LinkedHashMap<Long,Lease> leases=new LinkedHashMap<>();
    final ArrayDeque<Frame> frames=new ArrayDeque<>();

    static final class Lease {
        final long id,from;final BlockPos pos;final String dimension;
        long registration;
        long through,end=-1;
        boolean releasing;int queued;
        Lease(long id,long from,BlockPos pos,String dimension) {
            this.id=id;registration=id;this.from=from;through=from;this.pos=pos.immutable();this.dimension=dimension;
        }
    }
    enum Kind { SPAN, HIT, JUMP, INTAKE, SLEEP, OFFLINE }
    static final class Frame {
        final Kind kind;final long lease;long from,to;
        final Activity activity;final boolean submerged;boolean effects=true;
        final double a,b,c,d;final boolean vitaminC,vitaminFat;final long generation;
        Frame(Kind kind,long lease,long from,long to,Activity activity,boolean submerged,
              double a,double b,double c,double d,boolean vc,boolean vf,long generation) {
            if(lease<0||kind==Kind.OFFLINE&&lease!=0||kind==Kind.SPAN&&from==to||from<0||to<from||!Double.isFinite(a+b+c+d)||a<0||b<0||c<0||d<0||generation<0)
                throw new IllegalArgumentException("生理イベント区間が不正です");
            if(kind!=Kind.SPAN&&kind!=Kind.OFFLINE&&to!=from)throw new IllegalArgumentException("単発イベントの時刻が不正です");
            this.kind=kind;this.lease=lease;this.from=from;this.to=to;this.activity=activity;this.submerged=submerged;
            this.a=a;this.b=b;this.c=c;this.d=d;vitaminC=vc;vitaminFat=vf;this.generation=generation;
        }
        static Frame span(long lease,long from,long to,Activity activity,boolean submerged,long generation) {
            return new Frame(Kind.SPAN,lease,from,to,activity,submerged,0,0,0,0,false,false,generation);
        }
    }
    public PhysiologyData() { this(UUID.randomUUID()); }
    private PhysiologyData(UUID owner) { this(owner,new Body()); }
    private PhysiologyData(UUID owner,Body body) { this.owner=owner;this.body=body; }

    /** 実際の睡眠分を確定した後だけ呼ぶ。intervalはC01の日長履歴で計算する。 */
    boolean credit(long generation,long tick,double sinceLastCredit,boolean valid) {
        if(generation<=consumedSleep)return false;
        if(generation!=consumedSleep+1&&pendingSleep!=generation)
            throw new IllegalStateException("未確定の就寝を追い越せません");
        var result=Physiology.creditSleep(body,generation,consumedSleep,tick,lastCredit,sinceLastCredit,valid);
        consumedSleep=result.consumed();lastCredit=result.lastCredit();
        if(pendingSleep==generation)pendingSleep=0;
        return result.applied();
    }
    void span(long to) {
        if(to<observed)throw new IllegalStateException("生理の時計が逆転しました");
        if(to==observed)return;
        long generation=activity==Activity.SLEEP?sleepGeneration:0;
        var last=frames.peekLast();
        if(last!=null&&last.kind==Kind.SPAN&&last.lease==currentLease&&last.to==observed
                &&last.activity==activity&&last.submerged==submerged&&last.effects==effects&&last.generation==generation) {
            last.to=to;
        } else {
            var frame=Frame.span(currentLease,observed,to,activity,submerged,generation);
            frame.effects=effects;addFrame(frame);
        }
        observed=to;
    }
    void addFrame(Frame frame) { frames.addLast(frame);if(frame.lease!=0)leases.get(frame.lease).queued++; }
    public CompoundTag save() {
        if(blocked())throw new IllegalStateException("破損状態はAttachment serializerで原文保存してください");
        var tag=new CompoundTag();tag.putInt("format",2);tag.putUUID("owner",owner);
        var values=new ListTag();for(double value:body.values())values.add(DoubleTag.valueOf(value));
        tag.put("body",values);tag.putIntArray("levels",body.levels);tag.putBoolean("exhausted",body.exhausted);
        tag.putLong("observed",observed);tag.putLong("cursor",cursor);tag.putLong("lease_serial",leaseSerial);
        tag.putLong("current_lease",currentLease);tag.putBoolean("online",online);
        tag.putInt("activity",activity.ordinal());tag.putBoolean("submerged",submerged);
        tag.putLong("sleep_generation",sleepGeneration);tag.putLong("consumed_sleep",consumedSleep);
        tag.putLong("last_credit",lastCredit);tag.putLong("pending_sleep",pendingSleep);tag.putBoolean("sleeping",sleeping);tag.putLong("invalid_sleep",invalidSleep);
        var registrations=new ListTag();
        for(var lease:leases.values()) {
            var row=new CompoundTag();row.putLong("id",lease.id);row.putLong("registration",lease.registration);row.putLong("from",lease.from);row.putLong("pos",lease.pos.asLong());
            row.putString("dimension",lease.dimension);row.putLong("through",lease.through);row.putLong("end",lease.end);
            row.putBoolean("releasing",lease.releasing);registrations.add(row);
        }
        tag.put("leases",registrations);
        var serials=new ListTag();
        dimensionSerials.forEach((dimension,serial)->{
            var row=new CompoundTag();row.putString("dimension",dimension);row.putLong("serial",serial);serials.add(row);
        });
        tag.put("dimension_serials",serials);tag.putBoolean("effects",effects);
        var periods=new ListTag();
        for(var f:frames) {
            var row=new CompoundTag();row.putInt("kind",f.kind.ordinal());row.putLong("lease",f.lease);
            row.putLong("from",f.from);row.putLong("to",f.to);row.putInt("activity",f.activity.ordinal());
            row.putBoolean("submerged",f.submerged);row.putDouble("a",f.a);row.putDouble("b",f.b);
            row.putDouble("c",f.c);row.putDouble("d",f.d);row.putBoolean("vc",f.vitaminC);row.putBoolean("vf",f.vitaminFat);
            row.putLong("generation",f.generation);row.putBoolean("effects",f.effects);periods.add(row);
        }
        tag.put("frames",periods);return tag;
    }
    private static void require(CompoundTag t,String name,int type) {
        if(!t.contains(name,type))throw new IllegalArgumentException("生理保存の必須項目がありません: "+name);
    }
    public static PhysiologyData load(CompoundTag tag) {
        require(tag,"format",Tag.TAG_INT);require(tag,"owner",Tag.TAG_INT_ARRAY);
        if(tag.getInt("format")!=2)throw new IllegalArgumentException("未知の生理保存形式です");
        var data=new PhysiologyData(tag.getUUID("owner"));
        require(tag,"body",Tag.TAG_LIST);var values=NbtLists.read(tag,"body",Tag.TAG_DOUBLE);var numbers=new double[values.size()];
        for(int i=0;i<numbers.length;i++)numbers[i]=values.getDouble(i);
        data.body.values(numbers);
        require(tag,"levels",Tag.TAG_INT_ARRAY);var levels=tag.getIntArray("levels");
        if(levels.length!=data.body.levels.length)throw new IllegalArgumentException("症状列の長さが不正です");
        for(int i=0;i<levels.length;i++) {
            if(levels[i]<0||levels[i]>3)throw new IllegalArgumentException("症状段階が不正です");
            data.body.levels[i]=levels[i];
        }
        for(String key:new String[]{"observed","cursor","lease_serial","current_lease","sleep_generation",
                "consumed_sleep","last_credit","pending_sleep","invalid_sleep"})require(tag,key,Tag.TAG_LONG);
        for(String key:new String[]{"online","submerged","sleeping","exhausted"})require(tag,key,Tag.TAG_BYTE);
        require(tag,"activity",Tag.TAG_INT);
        data.observed=tag.getLong("observed");data.cursor=tag.getLong("cursor");data.leaseSerial=tag.getLong("lease_serial");
        data.currentLease=tag.getLong("current_lease");data.online=tag.getBoolean("online");
        data.activity=Activity.values()[tag.getInt("activity")];data.submerged=tag.getBoolean("submerged");
        data.sleepGeneration=tag.getLong("sleep_generation");data.consumedSleep=tag.getLong("consumed_sleep");
        data.lastCredit=tag.getLong("last_credit");data.pendingSleep=tag.getLong("pending_sleep");
        data.invalidSleep=tag.getLong("invalid_sleep");data.sleeping=tag.getBoolean("sleeping");data.body.exhausted=tag.getBoolean("exhausted");
        if((data.observed==-1)!=(data.cursor==-1)||data.observed< -1||data.cursor< -1||data.cursor>data.observed||data.lastCredit>data.observed
                ||data.lastCredit< -1||data.leaseSerial<0||data.currentLease<0||data.sleepGeneration<0
                ||data.consumedSleep<0||data.consumedSleep>data.sleepGeneration||data.pendingSleep<0
                ||data.invalidSleep<0||data.invalidSleep>data.sleepGeneration||data.pendingSleep>data.sleepGeneration||data.pendingSleep!=0&&data.pendingSleep<=data.consumedSleep)
            throw new IllegalArgumentException("生理の保存cursorが不正です");
        require(tag,"effects",Tag.TAG_BYTE);data.effects=tag.getBoolean("effects");
        require(tag,"dimension_serials",Tag.TAG_LIST);
        for(var element:NbtLists.read(tag,"dimension_serials",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)element;require(row,"dimension",Tag.TAG_STRING);require(row,"serial",Tag.TAG_LONG);
            String dimension=row.getString("dimension");long serial=row.getLong("serial");
            if(net.minecraft.resources.ResourceLocation.tryParse(dimension)==null||serial<1||data.dimensionSerials.put(dimension,serial)!=null)
                throw new IllegalArgumentException("dimension別の保持世代が不正です");
        }
        var registrations=new java.util.HashSet<String>();
        require(tag,"leases",Tag.TAG_LIST);
        for(var element:NbtLists.read(tag,"leases",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)element;
            for(String key:new String[]{"id","registration","from","pos","through","end"})require(row,key,Tag.TAG_LONG);
            require(row,"dimension",Tag.TAG_STRING);require(row,"releasing",Tag.TAG_BYTE);
            var lease=new Lease(row.getLong("id"),row.getLong("from"),BlockPos.of(row.getLong("pos")),row.getString("dimension"));
            lease.registration=row.getLong("registration");lease.through=row.getLong("through");lease.end=row.getLong("end");lease.releasing=row.getBoolean("releasing");
            if(lease.registration<1||lease.registration>data.dimensionSerials.getOrDefault(lease.dimension,0L)
                    ||!registrations.add(lease.dimension+"#"+lease.registration)||lease.id<=0||lease.id>data.leaseSerial||lease.from<0||lease.through<lease.from
                    ||lease.through>data.observed||lease.end< -1||lease.end>=0&&(lease.end<lease.through||lease.end>data.observed)
                    ||lease.releasing&&(lease.end<0||lease.through!=lease.end)||data.leases.put(lease.id,lease)!=null)
                throw new IllegalArgumentException("生理の保持参照が不正です");
        }
        if(data.currentLease!=0&&!data.leases.containsKey(data.currentLease))
            throw new IllegalArgumentException("現在位置の保持参照がありません");
        require(tag,"frames",Tag.TAG_LIST);long prior=data.cursor;int candidates=0;
        for(var element:NbtLists.read(tag,"frames",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)element;
            for(String key:new String[]{"lease","from","to","generation"})require(row,key,Tag.TAG_LONG);
            for(String key:new String[]{"kind","activity"})require(row,key,Tag.TAG_INT);
            for(String key:new String[]{"a","b","c","d"})require(row,key,Tag.TAG_DOUBLE);
            for(String key:new String[]{"submerged","vc","vf","effects"})require(row,key,Tag.TAG_BYTE);
            var f=new Frame(Kind.values()[row.getInt("kind")],row.getLong("lease"),row.getLong("from"),row.getLong("to"),
                    Activity.values()[row.getInt("activity")],row.getBoolean("submerged"),row.getDouble("a"),row.getDouble("b"),
                    row.getDouble("c"),row.getDouble("d"),row.getBoolean("vc"),row.getBoolean("vf"),row.getLong("generation"));
            f.effects=row.getBoolean("effects");
            if(f.from!=prior||f.to>data.observed||f.kind!=Kind.OFFLINE&&!data.leases.containsKey(f.lease))
                throw new IllegalArgumentException("生理区間に欠落があります");
            if(f.kind==Kind.SLEEP) {
                if(f.generation!=data.pendingSleep)throw new IllegalArgumentException("就寝候補の受領印が不正です");
                candidates++;
            }
            data.addFrame(f);prior=f.to;
        }
        if(data.cursor>=0&&prior!=data.observed||candidates!=(data.pendingSleep==0?0:1))
            throw new IllegalArgumentException("未評価の生理区間が欠落しています");
        return data;
    }
}
