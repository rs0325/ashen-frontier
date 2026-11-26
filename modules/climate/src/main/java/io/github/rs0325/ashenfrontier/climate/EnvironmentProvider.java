package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget;
import io.github.rs0325.ashenfrontier.climate.room.RoomAir;
import io.github.rs0325.ashenfrontier.climate.room.RoomPhysics;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentQuery;
import io.github.rs0325.ashenfrontier.core.environment.EnvironmentSample;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import io.github.rs0325.ashenfrontier.world.MapCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** C01/C06へ同じ実屋外履歴を提供する。未計算は応答せず、有限仕事量で次tickへ継続する。 */
public final class EnvironmentProvider {
    private static final Map<ServerLevel,EnvironmentProvider> LEVELS=new IdentityHashMap<>();
    private final ServerLevel level;
    private RoomAir rooms;
    private final Map<Range,Job> jobs=new LinkedHashMap<>(16,.75f,true);
    private final java.util.ArrayDeque<Job> activeJobs=new java.util.ArrayDeque<>();
    private final java.util.TreeMap<Long,WeatherFrame> checkpoints=new java.util.TreeMap<>();
    private long clockRevision=-1,settingsTick=-1;
    private String reason="environment.ashen_frontier_climate.replaying";
    private record Range(long from,long to) {}
    private record OutdoorRow(long from,long to,long day,double gameFrom,double reference,
                              RoomPhysics.Air air,double humidity,double rain,double snow,
                              double transmission,boolean contaminated) {}

    private final class Job {
        final Range range;
        final Map<BlockPos,List<OutdoorRow>> rows=new HashMap<>();
        WeatherReplay cursor;
        boolean terminal,done,queued;
        long touched;
        Job(Range range,BlockPos pos) { this.range=range;add(pos);queue(); }
        void queue() { if(!queued) { queued=true;activeJobs.addLast(this); } }
        void add(BlockPos pos) {
            if(done)queue();
            rows.put(pos.immutable(),new ArrayList<>());
            rows.values().forEach(List::clear);terminal=false;done=false;
            cursor=WeatherQuery.begin(level,pos,range.from,range.to).orElseThrow();
            var cached=checkpoints.floorEntry(range.from);
            if(cached!=null&&cached.getKey()>cursor.frame.tick())
                cursor=new WeatherReplay(cursor.model,cursor.timeline,cursor.time,cursor.graceEnd,cached.getValue(),
                        pos.getX(),pos.getZ(),range.from,range.to);
        }
        void advance() {
            if(cursor.work!=null) {
                var cached=checkpoints.get(cursor.work.end);
                if(cached!=null) { cursor.frame=cached;cursor.work=null; }
            }
            cursor.advance(32);
            checkpoints.put(cursor.frame.tick(),cursor.frame);
            while(checkpoints.size()>64)checkpoints.pollFirstEntry();
            var page=cursor.page();
            if(page.isPresent()) {
                for(var interval:page.get().intervals())for(var target:rows.entrySet())
                    target.getValue().add(row(cursor,target.getKey(),interval.startTick(),interval.endTick()));
                cursor.acknowledge(page.get().intervals().getLast().endTick());
            }
            if(cursor.complete()) {
                if(terminal||range.from==range.to) {
                    done=true;
                }
                else {
                    terminal=true;
                    cursor=new WeatherReplay(cursor.model,cursor.timeline,cursor.time,cursor.graceEnd,cursor.frame,
                            cursor.x,cursor.z,range.to,range.to);
                }
            }
        }
    }
    private EnvironmentProvider(ServerLevel level) {
        this.level=level;this.rooms=RoomAir.get(level);
        EnvironmentBudget.activate(level);
        rooms.outside(this::outside);
    }
    public static void install() {
        NeoForge.EVENT_BUS.addListener((EnvironmentQuery query)->{
            if(ClimateQuery.mapVersion(query.level()).isEmpty()||!MapCoordinates.contains(query.position().getX(),query.position().getZ()))return;
            var provider=get(query.level());
            provider.environment(query.position(),query.startTick(),query.endTick()).ifPresent(query::provide);
            if(query.values().isEmpty())query.pending(provider.reason);
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event)->{
            for(var provider:List.copyOf(LEVELS.values()))if(provider.level.getServer()==event.getServer()) {
                long start=System.nanoTime(),remaining=EnvironmentBudget.remaining(provider.level);
                if(remaining>0)provider.work(start+Math.min(180_000,remaining),8192);
                EnvironmentBudget.charge(provider.level,System.nanoTime()-start);
            }
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event)->{
            if(event.getLevel() instanceof ServerLevel level)detach(level);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event)->LEVELS.entrySet().removeIf(e->e.getKey().getServer()==event.getServer()));
    }
    static void detach(ServerLevel level) {
        LEVELS.remove(level);EnvironmentBudget.deactivate(level);
    }
    public static EnvironmentProvider get(ServerLevel level) {
        var existing=LEVELS.get(level);
        if(existing!=null) {
            var current=RoomAir.get(level);
            if(existing.rooms!=current) { existing.rooms=current;current.outside(existing::outside); }
            return existing;
        }
        var provider=new EnvironmentProvider(level);LEVELS.put(level,provider);return provider;
    }
    /** 実サーバーとGameTestが使う同じ有限作業。deadlineはnanoTimeの期限。 */
    public int work(long deadline,int maximumCells) {
        int calls=0;
        var oldest=jobs.entrySet().iterator();
        for(int n=0;n<64&&oldest.hasNext()&&System.nanoTime()<deadline;n++) {
            var entry=oldest.next();var job=entry.getValue();
            if(!job.done)continue;
            if(level.getGameTime()-job.touched<=200)break;
            oldest.remove();
        }
        while(calls*32<maximumCells&&!activeJobs.isEmpty()&&System.nanoTime()<deadline) {
            var job=activeJobs.removeFirst();job.queued=false;
            if(job.done)continue;
            job.advance();calls++;
            if(!job.done)job.queue();else job.touched=level.getGameTime();
        }
        return calls*32;
    }

    private OutdoorRow row(WeatherReplay cursor,BlockPos pos,long from,long to) {
        var weather=cursor.observationAt(pos.getX(),pos.getZ(),from,to);
        var state=weather.state();
        var base=FixedClimate.require(ClimateQuery.mapVersion(level).orElseThrow())
                .sample(pos.getX(),pos.getY(),pos.getZ(),cursor.time.seconds(cursor.frame.tick()));
        double temperature=base.temperatureK()+state.temperatureAnomalyK();
        double pressure=Math.max(1,base.pressurePa()+state.pressureAnomalyPa());
        // 地球の背景組成。代表汚染を未定義の化学種からCOへ換算しない。
        var air=new RoomPhysics.Air(temperature,pressure,0.1e-6,420e-6,0.2095,Math.hypot(state.windXMps(),state.windZMps()));
        double reference=cursor.time.seconds(cursor.frame.tick())
                +weather.settings().intervalTicks()*43200.0/cursor.time.dayLength(cursor.frame.tick());
        return new OutdoorRow(from,to,cursor.time.dayLength(from),cursor.time.seconds(from),reference,air,
                Math.clamp(state.vaporKgM2()/WeatherModel.capacity(temperature),0,1),
                state.rainKgM2GameSecond(),state.snowKgM2GameSecond(),
                (1-0.75*state.cloudFraction())*Math.exp(-3e6*state.dustKgM3()),state.contaminatedRain());
    }
    private Optional<List<OutdoorRow>> data(BlockPos pos,long from,long to) { return data(pos,from,to,false); }
    private Optional<List<OutdoorRow>> data(BlockPos pos,long from,long to,boolean prepareHorizon) {
        if(from<0||to<from||to>level.getGameTime())throw new IllegalArgumentException("環境要求の時刻が不正です");
        long revision=ClimateClock.get(level).revision();
        long setting=WeatherStore.get(level).timeline.at(level.getGameTime()).tick();
        if(revision!=clockRevision||setting!=settingsTick) {
            long boundary=Long.MAX_VALUE;
            if(revision!=clockRevision)boundary=Math.min(boundary,ClimateClock.get(level).history().changes().getLast().tick());
            if(setting!=settingsTick)boundary=Math.min(boundary,setting);
            checkpoints.tailMap(boundary,true).clear();
            jobs.clear();activeJobs.clear();clockRevision=revision;settingsTick=setting;
        }
        var exact=jobs.get(new Range(from-Math.floorMod(from,600),prepareHorizon?level.getGameTime():to));
        if(exact!=null&&exact.done&&exact.rows.containsKey(pos)) {
            exact.touched=level.getGameTime();return Optional.of(exact.rows.get(pos));
        }
        for(var job:jobs.values()) {
            var values=job.rows.get(pos);
            if(values!=null&&!values.isEmpty()&&job.range.from<=from
                    &&(job.done&&job.range.to>=to||values.getLast().to>to)) {
                job.touched=level.getGameTime();jobs.get(job.range);return Optional.of(values);
            }
        }
        for(var pending:jobs.values())if(!pending.done&&pending.range.from<=from&&pending.range.to>=to) {
            if(!pending.rows.containsKey(pos))pending.add(pos);
            pending.touched=level.getGameTime();jobs.get(pending.range);return Optional.empty();
        }
        if(jobs.values().stream().anyMatch(j->!j.done&&j.rows.containsKey(pos)))return Optional.empty();
        var range=new Range(from-Math.floorMod(from,600),prepareHorizon?level.getGameTime():to);
        var job=jobs.get(range);
        if(job==null) { job=new Job(range,pos);jobs.put(range,job); }
        else if(!job.rows.containsKey(pos))job.add(pos);
        job.touched=level.getGameTime();
        // 現行フレームで直ちに答えられる要求は1セル予算でも完成する。
        if(job.rows.size()==1&&job.cursor.work==null&&job.cursor.frame.tick()<=from
                &&job.cursor.timeline.next(job.cursor.frame.tick(),job.cursor.time)>to) {
            job.advance();
            if(!job.done&&job.cursor.work==null)job.advance();
            if(job.done)return Optional.of(job.rows.get(pos));
        }
        return Optional.empty();
    }
    private static int index(List<OutdoorRow> rows,long tick) {
        int lo=0,hi=rows.size()-1;
        while(lo<hi) { int mid=(lo+hi+1)>>>1;if(rows.get(mid).from<=tick)lo=mid;else hi=mid-1; }
        return lo;
    }
    private Optional<List<RoomAir.Boundary>> outside(ServerLevel requested,BlockPos pos,long from,long to) {
        return data(pos,from,to,true).map(values->{
            var result=new ArrayList<RoomAir.Boundary>();
            if(from==to)result.add(new RoomAir.Boundary(from,to,values.get(index(values,from)).air));
            else for(int i=index(values,from);i<values.size();i++) {
                var row=values.get(i);if(row.from>=to)break;
                long a=Math.max(from,row.from),b=Math.min(to,row.to);
                if(b>a)result.add(new RoomAir.Boundary(a,b,row.air));
            }
            return List.copyOf(result);
        });
    }
    private Optional<List<EnvironmentSample>> environment(BlockPos station,long from,long to) {
        var pos=station.above();
        var observed=rooms.sample(pos,from);
        if(!observed.confirmed()&&observed.status()!=RoomAir.Status.MISSING_BOUNDARY) {
            reason="room_status.ashen_frontier_climate."+observed.status().name().toLowerCase(java.util.Locale.ROOT);
            return Optional.empty();
        }
        var outdoor=data(pos,from,to);
        if(outdoor.isEmpty()) { reason="environment.ashen_frontier_climate.replaying";return Optional.empty(); }
        var cuts=new TreeSet<Long>(rooms.boundaries(pos,from,to));cuts.add(from);cuts.add(to);
        for(int n=index(outdoor.get(),from);n<outdoor.get().size();n++) {
            var row=outdoor.get().get(n);if(row.to>=to)break;if(row.to>from)cuts.add(row.to);
        }
        var initialCuts=new ArrayList<>(cuts);
        for(int n=0;n+1<initialCuts.size();n++) {
            long a=initialCuts.get(n),b=initialCuts.get(n+1);
            if(b-a<=1)continue;
            var first=rooms.sample(pos,a);var last=rooms.sample(pos,b-1);
            if(!first.confirmed()||!last.confirmed()) {
                reason="environment.ashen_frontier_climate.replaying";return Optional.empty();
            }
            boolean warm=first.state().orElseThrow().temperatureK()>273.15;
            if(warm==(last.state().orElseThrow().temperatureK()>273.15))continue;
            long left=a+1,right=b-1;
            while(left<right) {
                long middle=left+(right-left)/2;var sample=rooms.sample(pos,middle);
                if(!sample.confirmed()) { reason="environment.ashen_frontier_climate.replaying";return Optional.empty(); }
                if((sample.state().orElseThrow().temperatureK()>273.15)==warm)left=middle+1;else right=middle;
            }
            cuts.add(left);
        }
        var points=new ArrayList<>(cuts);var result=new ArrayList<EnvironmentSample>();
        for(int i=0;i<Math.max(1,points.size()-1);i++) {
            long a=points.get(i),b=from==to?a:points.get(i+1);
            var local=rooms.sample(pos,a);
            if(!local.confirmed()||local.state().isEmpty()) {
                reason="room_status.ashen_frontier_climate."+local.status().name().toLowerCase(java.util.Locale.ROOT);
                return Optional.empty();
            }
            var row=outdoor.get().get(index(outdoor.get(),a));
            double seconds=GameTime.gameSeconds(b-a,row.day);
            double gameA=row.gameFrom+GameTime.gameSeconds(a-row.from,row.day);
            double energy=SolarEnergy.between(MapCoordinates.latitude(pos.getZ()),row.reference,gameA,gameA+seconds)*row.transmission;
            double rain=(row.rain+row.snow)*seconds;
            result.add(new EnvironmentSample(a,b,row.day,local.state().get().temperatureK(),row.air.pressurePa(),row.humidity,
                    rain,energy,rain>0?Optional.of(AshenFrontierCore.id(row.contaminated?"contaminated_water":"raw_water")):Optional.empty(),
                    local.covered(),local.ventilated()));
        }
        return Optional.of(List.copyOf(result));
    }
}
