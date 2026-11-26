package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.climate.ClimateQuery;
import io.github.rs0325.ashenfrontier.climate.EnvironmentProvider;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentRetention;
import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import io.github.rs0325.ashenfrontier.survival.PhysiologyData.Frame;
import io.github.rs0325.ashenfrontier.survival.PhysiologyData.Kind;
import io.github.rs0325.ashenfrontier.survival.PhysiologyData.Lease;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/** プレイヤーのイベント順と評価cursorを保つ。未観測入力を既定の空気で埋めない。 */
final class SurvivalRuntime {
    private static final ResourceLocation STRAIN=AshenFrontierSurvival.id("strain");
    record Observation(long from,long to,long day,Physiology.Air air) {}
    interface Inputs {
        Optional<Observation> sample(ServerLevel level,BlockPos position,long from,long to,boolean submerged);
        Optional<DayLengthHistory> clock(ServerLevel level);
    }
    static final Inputs REAL=new Inputs() {
        public Optional<Observation> sample(ServerLevel level,BlockPos pos,long from,long to,boolean submerged) {
            if(ClimateQuery.mapVersion(level).isEmpty())return Optional.empty();
            return EnvironmentProvider.get(level).exposure(pos,from,to).map(value->{
                var e=value.sample();double seconds=GameTime.gameSeconds(e.endTick()-e.startTick(),e.ticksPerDay());
                return new Observation(e.startTick(),e.endTick(),e.ticksPerDay(),
                        new Physiology.Air(e.temperatureK(),e.relativeHumidity(),value.windMps(),
                                seconds==0?0:e.solarJoulesM2()/seconds,e.pressurePa(),value.oxygenPa(),
                                e.precipitationKgM2(),e.covered(),submerged));
            });
        }
        public Optional<DayLengthHistory> clock(ServerLevel level) { return ClimateQuery.dayLengthHistory(level); }
    };
    // 制御入力のGameTestだけが差し替える。実環境の未接続を補う値は持たない。
    static final Map<ServerLevel,Inputs> INPUTS=new IdentityHashMap<>();
    final ServerPlayer player;
    final PhysiologyData data;
    final Inputs input;
    long scheduled=-1,heavyUntil=-1,probeTick=-1,lastSent=-1;
    private PhysiologyUpdate sent;
    private double appliedWorkFactor=Double.NaN;
    private Observation current;
    private net.minecraft.world.phys.Vec3 movementPosition;
    private String movementDimension;
    private long movementTick=-1;
    private BlockPos currentPosition;
    boolean pending=true;
    SurvivalRuntime(ServerPlayer player) {
        this.player=player;movementPosition=player.position();movementDimension=player.level().dimension().location().toString();
        data=player.getData(AshenFrontierSurvival.DATA);input=INPUTS.getOrDefault(player.serverLevel(),REAL);
    }
    private ServerLevel level(Lease lease) {
        if(lease.dimension.equals(player.level().dimension().location().toString()))return player.serverLevel();
        return player.server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(lease.dimension)));
    }
    void login() {
        long now=player.serverLevel().getGameTime();
        if(data.observed>now)throw new IllegalStateException("生理保存の時刻がワールドより先です");
        if(data.currentLease!=0)closeLease(data.observed);
        if(data.observed<0) { data.observed=now;data.cursor=now; }
        else if(data.observed<now) {
            data.addFrame(new Frame(Kind.OFFLINE,0,data.observed,now,Activity.REST,false,0,0,0,0,false,false,0));
            data.observed=now;
        }
        // 切断で失った継続就寝を、新しい100 tick成立として再利用しない。
        data.online=true;data.sleeping=false;data.effects=AshenFrontierSurvival.EFFECTS.get();
        openLease(now);data.activity=activity();data.submerged=player.isInWater();
        schedule(1);
    }
    void logout() {
        capture();closeLease(data.observed);data.online=false;data.sleeping=false;
        if(data.pendingSleep==0)data.consumedSleep=data.sleepGeneration;
        AshenFrontierSurvival.unschedule(this);
    }
    private void openLease(long now) {
        long id=++data.leaseSerial;data.currentLease=id;
        var lease=new Lease(id,now,player.blockPosition(),player.level().dimension().location().toString());
        lease.registration=data.dimensionSerials.merge(lease.dimension,1L,Math::addExact);
        data.leases.put(id,lease);retain(lease);probeTick=now;
        current=null;currentPosition=null;
    }
    private void closeLease(long now) {
        if(data.currentLease!=0) { data.leases.get(data.currentLease).end=now;data.currentLease=0; }
    }
    private void retain(Lease lease) {
        var level=level(lease);if(level==null)return;
        var answer=EnvironmentRetention.request(level,data.owner,lease.registration,lease.pos.above(),lease.from,
                level.getGameTime(),0,EnvironmentRetention.Action.RETAIN);
        if(answer.isPresent()&&!answer.get())throw new IllegalStateException("保存した生理保持参照が解放済みです");
    }
    private Activity activity() {
        var position=player.position();String dimension=player.level().dimension().location().toString();
        if(!dimension.equals(movementDimension))movementTick=-1;
        else if(position.subtract(movementPosition).horizontalDistanceSqr()>.0001
                &&player.getKnownMovement().horizontalDistanceSqr()>.0001)movementTick=player.level().getGameTime();
        movementPosition=position;movementDimension=dimension;
        if(player.isSleeping())return Activity.SLEEP;
        if(player.isSprinting())return Activity.RUN;
        if(player.level().getGameTime()<heavyUntil)return Activity.HEAVY;
        if(movementTick==player.level().getGameTime())return Activity.WALK;
        return player.isUsingItem()?Activity.LIGHT:Activity.REST;
    }
    /** 観測tickまでの旧係数を閉じ、新しい位置/活動はその後へ適用する。 */
    void capture() {
        if(!data.online)return;
        long now=player.serverLevel().getGameTime();
        data.span(now);
        var lease=data.leases.get(data.currentLease);
        if(lease==null||!lease.pos.equals(player.blockPosition())
                ||!lease.dimension.equals(player.level().dimension().location().toString())) {
            closeLease(now);openLease(now);
        }
        Activity next=activity();
        if(next==Activity.SLEEP&&!data.sleeping) {
            data.sleepGeneration++;data.sleeping=true;
        } else if(next!=Activity.SLEEP&&data.sleeping) {
            data.sleeping=false;
            if(data.pendingSleep==0)data.consumedSleep=data.sleepGeneration;
        }
        data.effects=AshenFrontierSurvival.EFFECTS.get();data.activity=next;data.submerged=player.isInWater();
    }
    void postTick() {
        capture();
        if(player.isSleepingLongEnough()&&data.sleeping&&data.pendingSleep==0
                &&data.sleepGeneration>data.consumedSleep) {
            data.pendingSleep=data.sleepGeneration;
            enqueue(Kind.SLEEP,0,0,0,0,false,false,data.sleepGeneration);
        }
    }
    void heavy() { capture();heavyUntil=player.level().getGameTime()+1;data.activity=Activity.HEAVY;schedule(1); }
    void hit(double hp) { capture();enqueue(Kind.HIT,hp,0,0,0,false,false,0); }
    void jump() { capture();enqueue(Kind.JUMP,0,0,0,0,false,false,0); }
    void intake(double water,double energy,double protein,double fat,boolean vc,boolean vf) {
        capture();enqueue(Kind.INTAKE,water,energy,protein,fat,vc,vf,0);
    }
    private void enqueue(Kind kind,double a,double b,double c,double d,boolean vc,boolean vf,long generation) {
        data.addFrame(new Frame(kind,data.currentLease,data.observed,data.observed,data.activity,
                data.submerged,a,b,c,d,vc,vf,generation));schedule(1);
    }
    void schedule(int ticks) { AshenFrontierSurvival.schedule(this,ticks); }
    private void probe() {
        if(data.currentLease==0)return;
        var lease=data.leases.get(data.currentLease);var level=level(lease);
        if(level==null)return;
        if(probeTick<lease.from)probeTick=lease.from;
        var result=input.sample(level,lease.pos,probeTick,probeTick,data.submerged);
        if(result.isPresent()) {
            current=result.get();currentPosition=lease.pos;
            if(probeTick<level.getGameTime())probeTick=level.getGameTime();
        }
    }
    boolean sleepReady() {
        if(data.pendingSleep!=0&&!data.sleeping)return false;
        if(current==null||currentPosition==null||!currentPosition.equals(player.blockPosition())
                ||player.level().getGameTime()-current.to>20)return false;
        return current.air.oxygenPa()>=16000;
    }
    /** deadlineまで有限に処理し、未完了の末尾を現在時刻へ飛ばさない。 */
    int work(long deadline,int maximumSteps) {
        if(System.nanoTime()>=deadline) {
            pending=true;if(data.online)schedule(1);return 0;
        }
        if(data.online)capture();
        int steps=0;
        while(steps<maximumSteps&&System.nanoTime()<deadline&&!data.frames.isEmpty()) {
            var frame=data.frames.getFirst();
            if(frame.kind==Kind.OFFLINE) { data.cursor=frame.to;data.frames.removeFirst();steps++;continue; }
            var lease=data.leases.get(frame.lease);var level=level(lease);
            if(level==null)break;
            if(frame.kind==Kind.SPAN) {
                long to=Math.min(frame.to,frame.from+20);
                var result=input.sample(level,lease.pos,frame.from,to,frame.submerged);
                if(result.isEmpty())break;
                var observation=result.get();
                if(observation.from!=frame.from||observation.to<=frame.from||observation.to>to)
                    throw new IllegalStateException("生理環境の区間列が不連続です");
                boolean validSleep=frame.activity!=Activity.SLEEP||observation.air.oxygenPa()>=16000;
                if(!validSleep)data.invalidSleep=Math.max(data.invalidSleep,frame.generation);
                Physiology.advance(data.body,observation.air,validSleep?frame.activity:Activity.REST,
                        GameTime.realSeconds(observation.to-observation.from),
                        GameTime.gameSeconds(observation.to-observation.from,observation.day));
                if(!frame.effects)data.body.damage=0;
                frame.from=observation.to;data.cursor=observation.to;lease.through=observation.to;
                EnvironmentRetention.request(level,data.owner,lease.registration,lease.pos.above(),lease.from,lease.through,0,EnvironmentRetention.Action.COMMIT);
                if(lease.id==data.currentLease) { current=observation;currentPosition=lease.pos; }
                if(frame.from<frame.to) { steps++;continue; }
            } else {
                switch(frame.kind) {
                    case HIT -> Physiology.wound(data.body,frame.a);
                    case JUMP -> Physiology.jump(data.body);
                    case INTAKE -> Physiology.ingest(data.body,frame.a,frame.b,frame.c,frame.d,frame.vitaminC,frame.vitaminFat);
                    case SLEEP -> {
                        var clock=input.clock(level);if(clock.isEmpty())return steps;
                        double elapsed=data.lastCredit<0?Double.MAX_VALUE:GameTime.gameSeconds(data.lastCredit,frame.to,clock.get());
                        data.credit(frame.generation,frame.to,elapsed,frame.generation>data.invalidSleep);
                    }
                    default -> throw new IllegalStateException("生理イベントの種類が不正です");
                }
                data.cursor=frame.to;
            }
            data.frames.removeFirst();lease.queued--;steps++;
        }
        retire(deadline);
        if(data.online&&System.nanoTime()<deadline&&(current==null||player.level().getGameTime()-current.to>=20))probe();
        pending=!data.frames.isEmpty()||current==null;
        if(data.online) {
            applyEffects();send();
            schedule(pending?1:20);
        }
        return steps;
    }
    private void retire(long deadline) {
        int count=0;
        var iterator=data.leases.values().iterator();
        while(iterator.hasNext()) {
            var lease=iterator.next();
            if(count++>=8||System.nanoTime()>=deadline)break;
            if(lease.end<0||lease.queued!=0||lease.through<lease.end)continue;
            var level=level(lease);if(level==null)continue;
            EnvironmentRetention.request(level,data.owner,lease.registration,lease.pos.above(),lease.from,lease.end,0,EnvironmentRetention.Action.END);
            lease.releasing=true;
            if(EnvironmentRetention.request(level,data.owner,lease.registration,lease.pos.above(),lease.from,lease.end,0,
                    EnvironmentRetention.Action.RELEASE).orElse(true))iterator.remove();
        }
    }
    private void send() {
        var value=PhysiologyUpdate.snapshot(data.body,pending,AshenFrontierSurvival.EFFECTS.get());
        long now=player.level().getGameTime();
        if(!value.equals(sent)||now-lastSent>=20) {
            PhysiologyUpdate.send(player,value);sent=value;lastSent=now;
        }
    }
    private void applyEffects() {
        var body=data.body;boolean enabled=AshenFrontierSurvival.EFFECTS.get();
        double factor=enabled?Math.max(.5,body.workFactor()):1;
        if(factor!=appliedWorkFactor) {
            var attribute=player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            attribute.removeModifier(STRAIN);
            if(factor<1)attribute.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    STRAIN,factor-1,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            appliedWorkFactor=factor;
        }
        if(enabled&&body.exhausted)player.setSprinting(false);
        player.getFoodData().setFoodLevel((int)Math.clamp(Math.round(body.energy/1_000_000),0,20));
        player.getFoodData().setSaturation(0);player.getFoodData().setExhaustion(0);
        if(!enabled) { body.damage=0;return; }
        double due=Math.floor(body.damage*2)/2;body.damage-=due;
        if(enabled&&due>0) {
            AshenFrontierSurvival.APPLYING_DAMAGE=true;
            try { player.hurt(player.damageSources().generic(),(float)due); }
            finally { AshenFrontierSurvival.APPLYING_DAMAGE=false; }
        }
    }
}
