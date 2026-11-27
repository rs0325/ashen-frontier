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
    record Observation(long from,long to,long day,Physiology.Air air,Exposure.Air exposure,boolean approximate,boolean untypedPollution) {}
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
                                e.precipitationKgM2(),e.covered(),submerged),
                        new Exposure.Air(value.co(),value.co2(),value.oxygenPa(),value.dustKgM3(),value.indoor()),
                        value.accuracy()==io.github.rs0325.ashenfrontier.climate.room.RoomAir.Accuracy.APPROXIMATE,value.untypedPollution());
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
    private long nextRadiationTick;
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
        if(data.radiation!=null)data.radiation.end(player.level().getGameTime());
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
    void mining(io.github.rs0325.ashenfrontier.core.SoilContamination soil) {
        capture();enqueue(Kind.MINING,soil==null?0:soil.lead(),soil==null?0:soil.pcb(),soil==null?0:soil.caesium137(),soil==null?0:1,false,false,0);
    }
    void hit(double hp) { capture();enqueue(Kind.HIT,hp,0,0,0,false,false,0); }
    void jump() { capture();enqueue(Kind.JUMP,0,0,0,0,false,false,0); }
    void intake(double water,double energy,double protein,double fat,boolean vc,boolean vf,io.github.rs0325.ashenfrontier.core.food.IntakeExposure exposure) {
        capture();
        if(exposure.tick()!=data.observed)throw new IllegalArgumentException("摂取の作用時刻が一致しません");
        enqueue(Kind.INTAKE,water,energy,protein,fat,vc,vf,0);data.frames.getLast().intakeExposure=exposure;data.frames.getLast().intakeCommitted=true;
    }
    void reserveIntake(long operation) { capture();enqueue(Kind.INTAKE,0,0,0,0,false,false,operation); }
    void completeIntake(long operation,double water,double energy,double protein,double fat,boolean vc,boolean vf,io.github.rs0325.ashenfrontier.core.food.IntakeExposure exposure) {
        var ordered=new java.util.ArrayList<>(data.frames);boolean found=false;
        for(int n=0;n<ordered.size();n++) {
            var frame=ordered.get(n);
            if(frame.kind!=Kind.INTAKE||frame.generation!=operation)continue;
            var committed=new Frame(Kind.INTAKE,frame.lease,frame.from,frame.to,frame.activity,frame.submerged,water,energy,protein,fat,vc,vf,operation);
            if(exposure.tick()!=frame.from)throw new IllegalArgumentException("摂取の作用時刻が一致しません");
            committed.intakeExposure=exposure;committed.intakeCommitted=true;committed.effects=frame.effects;ordered.set(n,committed);found=true;break;
        }
        if(!found)throw new IllegalStateException("摂取の完了時点の予約がありません");
        data.frames.clear();data.frames.addAll(ordered);schedule(1);
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
        return current.air.oxygenPa()>=16000&&data.exposure.stages[0]<2&&data.exposure.stages[1]<2;
    }
    /** deadlineまで有限に処理し、未完了の末尾を現在時刻へ飛ばさない。 */
    int work(long deadline,int maximumSteps) {
        if(System.nanoTime()>=deadline) {
            pending=true;if(data.online)schedule(1);return 0;
        }
        if(data.online)capture();
        if(input==REAL&&data.online&&System.nanoTime()<deadline) {
            long now=player.level().getGameTime();
            if(now>=nextRadiationTick&&(data.radiation==null||data.radiation.changed()||data.radiation.validUntil()<=now
                    ||!player.blockPosition().equals(data.radiation.position()))) {
                if(data.radiation!=null)data.radiation.end(now);
                data.radiation=new RadiationField.Point(player,data.radiation);
            }
            data.radiation.work(deadline);
        }
        int steps=0,waitTicks=1;
        while(steps<maximumSteps&&System.nanoTime()<deadline&&!data.frames.isEmpty()) {
            var frame=data.frames.getFirst();
            if(frame.kind==Kind.INTAKE&&frame.generation>0&&IntakeService.pending(player,frame.generation))break;
            if(frame.kind==Kind.OFFLINE) { data.cursor=frame.to;data.frames.removeFirst();steps++;continue; }
            var lease=data.leases.get(frame.lease);var level=level(lease);
            if(level==null)break;
            if(frame.kind==Kind.SPAN) {
                if(frame.radiation!=null&&!frame.radiation.done) {
                    frame.radiation.work(deadline);if(!frame.radiation.done)break;
                }
                long boundary=(Math.floorDiv(frame.from,20)+1)*20;
                if(input==REAL&&frame==data.frames.peekLast()&&data.online&&lease.end<0&&frame.to<boundary) {
                    waitTicks=(int)Math.max(1,boundary-player.level().getGameTime());break;
                }
                long to=Math.min(frame.to,boundary);
                var result=input.sample(level,lease.pos,frame.from,to,frame.submerged);
                if(result.isEmpty())break;
                var observation=result.get();
                if(observation.from!=frame.from||observation.to<=frame.from||observation.to>to)
                    throw new IllegalStateException("生理環境の区間列が不連続です");
                double real=GameTime.realSeconds(observation.to-observation.from);
                double game=GameTime.gameSeconds(observation.to-observation.from,observation.day);
                double inhaledBefore=data.exposure.inhaledBq;
                var nuclide=Nuclides.get("ashen_frontier:cs137");
                double radioactiveDecay=nuclide.isEmpty()||real==0?0:Math.log(2)/nuclide.get().halfLifeGameSeconds()*game/real;
                Exposure.advance(data.exposure,observation.exposure,frame.activity,real,game,radioactiveDecay);
                radiation(frame,observation,real,game);
                data.exposure.approximate|=observation.approximate;
                if(observation.untypedPollution){data.exposure.unassessedChemicalSeconds+=real;data.exposure.unassessedReasons|=8;}
                double inhaled=data.exposure.inhaledBq-inhaledBefore;
                if(inhaled>0)data.exposure.nuclear("ashen_frontier:cs137",0,0,inhaled,inhaled);
                if(nuclide.isPresent()) {
                    double sv=inhaled*nuclide.get().inhalationSvBq();
                    data.exposure.committedLower+=sv;data.exposure.committedUpper+=sv;
                } else {data.exposure.unassessedBq+=inhaled;if(inhaled>0)data.exposure.unassessedReasons|=4;}
                gasDamage(frame,observation,real);
                boolean validSleep=frame.activity!=Activity.SLEEP||observation.air.oxygenPa()>=16000
                        &&data.exposure.stages[0]<2&&data.exposure.stages[1]<2;
                if(!validSleep)data.invalidSleep=Math.max(data.invalidSleep,frame.generation);
                var breathing=Physiology.evaluate(data.body,observation.air,validSleep?frame.activity:Activity.REST,
                        GameTime.realSeconds(observation.to-observation.from),
                        GameTime.gameSeconds(observation.to-observation.from,observation.day));
                if(input==REAL) {
                    String dimension=level.dimension().location().toString();
                    long sequence=data.breathSerials.getOrDefault(dimension,0L)+1;
                    io.github.rs0325.ashenfrontier.climate.room.RoomAir.get(level).respiration(data.owner,sequence,lease.pos.above(),
                            observation.from,observation.to,breathing.oxygenMol(),breathing.co2Mol(),breathing.heatJ());
                    data.breathSerials.put(dimension,sequence);
                }
                if(!frame.effects){data.body.damage=0;data.exposure.damage=0;}
                frame.from=observation.to;data.cursor=observation.to;lease.through=observation.to;
                EnvironmentRetention.request(level,data.owner,lease.registration,lease.pos.above(),lease.from,lease.through,0,EnvironmentRetention.Action.COMMIT);
                if(lease.id==data.currentLease) { current=observation;currentPosition=lease.pos; }
                if(frame.from<frame.to) { steps++;continue; }
            } else {
                switch(frame.kind) {
                    case MINING -> Exposure.mining(data.exposure,frame.a,frame.b,frame.c,frame.d==1);
                    case HIT -> Physiology.wound(data.body,frame.a);
                    case JUMP -> Physiology.jump(data.body);
                    case INTAKE -> {
                        if(frame.intakeCommitted) {
                            Physiology.ingest(data.body,frame.a,frame.b,frame.c,frame.d,frame.vitaminC,frame.vitaminFat);
                            oral(frame.intakeExposure);
                        }
                    }
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
            schedule(pending?waitTicks:20);
        }
        return steps;
    }
    private void radiation(Frame frame,Observation observation,double real,double game) {
        var point=frame.radiation;
        if(point==null) { Exposure.radiation(data.exposure,java.util.List.of(),real,game,false);return; }
        data.exposure.approximate=true;
        long end=Math.min(observation.to,point.validUntil());
        if(observation.from<point.tick||end<=observation.from) {
            Exposure.radiation(data.exposure,java.util.List.of(),real,game,false);return;
        }
        double seconds=GameTime.realSeconds(end-observation.from),elapsedGame=0;
        if(!point.rates.isEmpty()) {
            var history=input.clock(level(data.leases.get(frame.lease)));
            if(history.isEmpty())throw new IllegalStateException("確定済み環境の時計がありません");
            elapsedGame=point.ageAt(observation.from,history.get());
        }
        double gameRate=20*86400.0/observation.day;
        var sources=new java.util.ArrayList<Exposure.Source>();
        if(point.ground>0)sources.add(new Exposure.Source(point.ground,point.ground,0));
        boolean known=point.known;
        for(var rate:point.rates) {
            var definition=Nuclides.get(rate.nuclide());
            if(definition.isEmpty()) { known=false;continue; }
            double lambda=Math.log(2)/definition.get().halfLifeGameSeconds();
            double factor=Math.exp(-lambda*elapsedGame);
            sources.add(new Exposure.Source(rate.lower()*factor,rate.upper()*factor,lambda*gameRate));
        }
        Exposure.radiation(data.exposure,sources,seconds,seconds*gameRate,known);
        if(!point.rates.isEmpty())point.ageThrough(observation.from,end,elapsedGame,seconds*gameRate);
        if(end<observation.to)Exposure.radiation(data.exposure,java.util.List.of(),real-seconds,game-seconds*gameRate,false);
    }
    void radiationMoved() { nextRadiationTick=Math.max(nextRadiationTick,(player.level().getGameTime()+19)/20*20); }
    double workFactor() { return Math.min(data.body.workFactor(),data.exposure.workFactor()); }
    private void oral(io.github.rs0325.ashenfrontier.core.food.IntakeExposure value) {
        double lower=0,upper=0;
        data.exposure.oralMassKg+=value.massKg();
        if(!value.nuclide().isEmpty()) {
            data.exposure.nuclear(value.nuclide(),value.activityLowerBq(),value.activityUpperBq(),0,0);
            var definition=Nuclides.get(value.nuclide());
            if(definition.isPresent()) {
                lower=value.activityLowerBq()*definition.get().ingestionSvBq();
                upper=value.activityUpperBq()*definition.get().ingestionSvBq();
            } else {data.exposure.unassessedBq+=value.activityUpperBq();data.exposure.unassessedReasons|=4;}
        }
        for(var entry:value.substancesKg().entrySet())
            if(!entry.getKey().equals("lead")&&!entry.getKey().equals("pcb")) {data.exposure.unassessedKg+=entry.getValue();data.exposure.unidentifiedOralKg.merge(entry.getKey(),entry.getValue(),Double::sum);data.exposure.unassessedReasons|=2;}
        Exposure.oral(data.exposure,value.substancesKg().getOrDefault("lead",0.0),value.substancesKg().getOrDefault("pcb",0.0),lower,upper);
    }
    private void gasDamage(Frame frame,Observation observation,double real) {
        var state=data.exposure;
        boolean enabled=frame.effects;
        if(!enabled||!state.enabled) { state.damage=0;state.notified=false;state.notificationTick=-1;state.grace=30; }
        state.enabled=enabled;
        if(!enabled)return;
        double[] u={Math.max(0,observation.exposure.co()-5e-6)/200e-6,
                Math.max(0,observation.exposure.co2()-.001)/.03,Math.max(0,16000-observation.exposure.oxygenPa())/6000};
        boolean dangerous=false,episode=false;
        for(int i=0;i<3;i++) { dangerous|=state.stages[i]==3&&u[i]>=1;episode|=state.load[i]>=.8; }
        if(!episode) { state.notified=false;state.notificationTick=-1;state.grace=30;return; }
        if(dangerous&&!state.notified) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("survival.ashen_frontier.exposure.danger"),true);
            state.notified=true;state.notificationTick=player.level().getGameTime();
        }
        if(!state.notified||!data.online)return;
        double notifiedReal=Math.max(0,observation.to-Math.max(observation.from,state.notificationTick))/20.0;
        double grace=Math.min(state.grace,notifiedReal);state.grace-=grace;
        if(dangerous)state.damage+=.05*(notifiedReal-grace);
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
        var value=PhysiologyUpdate.snapshot(data.body,data.exposure,pending,AshenFrontierSurvival.EFFECTS.get());
        long now=player.level().getGameTime();
        if(!value.equals(sent)||now-lastSent>=20) {
            PhysiologyUpdate.send(player,value);sent=value;lastSent=now;
        }
    }
    private void applyEffects() {
        var body=data.body;boolean enabled=AshenFrontierSurvival.EFFECTS.get();
        double factor=enabled?Math.max(.5,workFactor()):1;
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
        if(!enabled) {
            body.damage=0;data.exposure.damage=0;data.exposure.enabled=false;
            data.exposure.notified=false;data.exposure.notificationTick=-1;data.exposure.grace=30;return;
        }
        double due=Math.floor(body.damage*2)/2;body.damage-=due;
        double gas=Math.floor(data.exposure.damage*2)/2;data.exposure.damage-=gas;due+=gas;
        if(enabled&&due>0) {
            AshenFrontierSurvival.APPLYING_DAMAGE=true;
            try { player.hurt(player.damageSources().generic(),(float)due); }
            finally { AshenFrontierSurvival.APPLYING_DAMAGE=false; }
        }
    }
}
