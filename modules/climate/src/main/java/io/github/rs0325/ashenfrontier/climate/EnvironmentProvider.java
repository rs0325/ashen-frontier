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
    private final LinkedHashMap<Range,Job> jobs=new LinkedHashMap<>(16,.75f,true);
    private final Map<BlockPos,List<Job>> byPosition=new HashMap<>();
    private long access;
    private long boundaryRevision;
    private final java.util.ArrayDeque<Job> activeJobs=new java.util.ArrayDeque<>();
    private final java.util.Set<Job> pendingJobs=new java.util.LinkedHashSet<>();
    private final java.util.TreeMap<Long,WeatherFrame> checkpoints=new java.util.TreeMap<>();
    private long clockRevision=-1,settingsTick=-1;
    private WeatherTimeline frameTimeline;
    private WeatherTime frameTime;
    private String reason="environment.ashen_frontier_climate.replaying";
    private record Range(long from,long to) {}
    private record OutdoorRow(long from,long to,long day,double gameFrom,double reference,
                              RoomPhysics.Air air,double humidity,double rain,double snow,
                              double transmission,boolean contaminated,double dust,double pollution) {}
    private record WeatherPoint(BlockPos position,long tick) {}
    private final LinkedHashMap<WeatherPoint,OutdoorRow> observations=new LinkedHashMap<>(16,.75f,true);
    private record BoundaryKey(BlockPos position,long from,long to) {}
    private final Map<BoundaryKey,List<OutdoorRow>> frameRows=new LinkedHashMap<>(16,.75f,true);
    private final Map<BoundaryKey,List<RoomAir.Boundary>> boundaries=new LinkedHashMap<>(16,.75f,true);
    private long boundaryTick=-1,boundaryCacheRevision=-1;

    private final class Job {
        final Range range;
        final Map<BlockPos,List<OutdoorRow>> rows=new HashMap<>();
        WeatherReplay cursor;
        boolean terminal,done,queued;
        long touched;
        long accessed;
        Job(Range range,BlockPos pos) { this.range=range;add(pos);queue(); }
        void queue() { if(!queued) { queued=true;activeJobs.addLast(this); } }
        void add(BlockPos pos) {
            if(done)queue();
            rows.put(pos.immutable(),new ArrayList<>());
            byPosition.computeIfAbsent(pos.immutable(),p->new ArrayList<>()).add(this);
            terminal=false;done=false;
            pendingJobs.add(this);
            cursor=WeatherQuery.begin(level,pos,range.from,range.to).orElseThrow();
            frameTimeline=cursor.timeline;frameTime=cursor.time;
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
                for(var interval:page.get().intervals())for(var target:rows.entrySet()) {
                    var values=target.getValue();
                    if(!values.isEmpty()) {
                        var last=values.getLast();
                        if(last.to>interval.startTick()||last.from==interval.startTick()&&last.to==interval.endTick())continue;
                    }
                    values.add(row(cursor,target.getKey(),interval.startTick(),interval.endTick()));
                }
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
            if(done)pendingJobs.remove(this);
        }
    }
    private EnvironmentProvider(ServerLevel level) {
        this.level=level;this.rooms=RoomAir.get(level);
        EnvironmentBudget.activate(level);
        rooms.outside(this::outside,this::boundaryVersion);
    }
    public static void install() {
        NeoForge.EVENT_BUS.addListener((EnvironmentQuery query)->{
            if(ClimateQuery.mapVersion(query.level()).isEmpty()||!MapCoordinates.contains(query.position().getX(),query.position().getZ()))return;
            var provider=get(query.level());
            provider.environment(query.position(),query.startTick(),query.endTick(),query.firstOnly()).ifPresent(query::provide);
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
            if(existing.rooms!=current) { existing.rooms=current;current.outside(existing::outside,existing::boundaryVersion); }
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
            for(var pos:job.rows.keySet()) {
                var local=byPosition.get(pos);local.remove(job);if(local.isEmpty())byPosition.remove(pos);
            }
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
        return row(cursor.frame,cursor.timeline,cursor.time,pos,from,to);
    }
    private OutdoorRow row(WeatherFrame frame,WeatherTimeline timeline,WeatherTime time,BlockPos pos,long from,long to) {
        var key=new WeatherPoint(pos.immutable(),frame.tick());
        var cached=observations.get(key);
        if(cached!=null)return new OutdoorRow(from,to,time.dayLength(from),time.seconds(from),cached.reference,
                cached.air,cached.humidity,cached.rain,cached.snow,cached.transmission,cached.contaminated,cached.dust,cached.pollution);
        var state=WeatherReplay.spatial(frame,pos.getX(),pos.getZ());
        var settings=timeline.at(frame.tick());
        var base=FixedClimate.require(ClimateQuery.mapVersion(level).orElseThrow())
                .sample(pos.getX(),pos.getY(),pos.getZ(),time.seconds(frame.tick()));
        double temperature=base.temperatureK()+state.temperatureAnomalyK();
        double pressure=Math.max(1,base.pressurePa()+state.pressureAnomalyPa());
        // 地球の背景組成。代表汚染を未定義の化学種からCOへ換算しない。
        var air=new RoomPhysics.Air(temperature,pressure,0.1e-6,420e-6,0.2095,Math.hypot(state.windXMps(),state.windZMps()));
        double reference=time.seconds(frame.tick())
                +settings.intervalTicks()*43200.0/time.dayLength(frame.tick());
        var result=new OutdoorRow(from,to,time.dayLength(from),time.seconds(from),reference,air,
                Math.clamp(state.vaporKgM2()/WeatherModel.capacity(temperature),0,1),
                state.rainKgM2GameSecond(),state.snowKgM2GameSecond(),
                (1-0.75*state.cloudFraction())*Math.exp(-3e6*state.dustKgM3()),state.contaminatedRain(),state.dustKgM3(),state.pollutionKgM3());
        observations.put(key,result);
        if(observations.size()>512)observations.remove(observations.keySet().iterator().next());
        return result;
    }
    private long boundaryVersion() { refreshVersions();return boundaryRevision; }
    private void refreshVersions() {
        long revision=ClimateClock.get(level).revision();
        long setting=WeatherStore.get(level).timeline.at(level.getGameTime()).tick();
        if(revision!=clockRevision||setting!=settingsTick) {
            long boundary=Long.MAX_VALUE;
            if(revision!=clockRevision)boundary=Math.min(boundary,ClimateClock.get(level).history().changes().getLast().tick());
            if(setting!=settingsTick)boundary=Math.min(boundary,setting);
            checkpoints.tailMap(boundary,true).clear();
            jobs.clear();byPosition.clear();observations.clear();frameRows.clear();activeJobs.clear();pendingJobs.clear();frameTimeline=null;frameTime=null;boundaryRevision++;clockRevision=revision;settingsTick=setting;
        }
    }
    private Optional<List<OutdoorRow>> data(BlockPos pos,long from,long to) { return data(pos,from,to,false); }
    private Optional<List<OutdoorRow>> data(BlockPos pos,long from,long to,boolean prepareHorizon) {
        if(from<0||to<from||to>level.getGameTime())throw new IllegalArgumentException("環境要求の時刻が不正です");
        refreshVersions();
        var key=new BoundaryKey(pos.immutable(),from,to);
        var cached=frameRows.get(key);if(cached!=null)return Optional.of(cached);
        var completed=fromFrames(pos,from,to);
        if(completed.isPresent()) {
            if(frameRows.size()>=512)frameRows.remove(frameRows.keySet().iterator().next());
            frameRows.put(key,completed.get());return completed;
        }
        var exact=access(new Range(from-Math.floorMod(from,600),prepareHorizon?level.getGameTime():to));
        if(exact!=null) {
            var values=exact.rows.get(pos);
            if(covers(exact,values,from,to)) { exact.touched=level.getGameTime();return Optional.of(values); }
        }
        Job selected=null;
        var local=byPosition.getOrDefault(pos,List.of());
        for(var job:local) {
            var values=job.rows.get(pos);
            if(covers(job,values,from,to)
                    &&(selected==null||selected.accessed<job.accessed))selected=job;
        }
        if(selected!=null) {
            selected.touched=level.getGameTime();access(selected.range);return Optional.of(selected.rows.get(pos));
        }
        Job pending=null;boolean localPending=false;
        for(var candidate:pendingJobs) {
            if(candidate.rows.containsKey(pos))localPending=true;
            if(candidate.range.from<=from&&candidate.range.to>=to&&(pending==null||pending.accessed<candidate.accessed))pending=candidate;
        }
        if(pending!=null) {
            if(!pending.rows.containsKey(pos))pending.add(pos);
            pending.touched=level.getGameTime();access(pending.range);return Optional.empty();
        }
        if(localPending)return Optional.empty();
        var range=new Range(from-Math.floorMod(from,600),prepareHorizon?level.getGameTime():to);
        var job=access(range);
        if(job==null) { job=new Job(range,pos);jobs.put(range,job);job.accessed=++access; }
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
    /** 計算済みの全域フレームが要求全域を覆うときだけ、同じ一定係数の区間を直接組み立てる。 */
    private Optional<List<OutdoorRow>> fromFrames(BlockPos pos,long from,long to) {
        if(frameTime==null)return Optional.empty();
        var result=new ArrayList<OutdoorRow>();long start=from;
        do {
            var cached=checkpoints.floorEntry(start);if(cached==null)return Optional.empty();
            long boundary=frameTimeline.next(cached.getKey(),frameTime);if(boundary<=start)return Optional.empty();
            long end=Math.min(to,boundary);
            result.add(row(cached.getValue(),frameTimeline,frameTime,pos,start,end));
            if(end==to)return Optional.of(List.copyOf(result));
            start=end;
        } while(start<to);
        return Optional.empty();
    }
    private static boolean covers(Job job,List<OutdoorRow> values,long from,long to) {
        return values!=null&&!values.isEmpty()&&job.range.from<=from
                &&(job.done&&job.range.to>=to||values.getLast().to>to
                ||from<to&&values.getLast().to==to||from==to&&values.getLast().from==to&&values.getLast().to==to);
    }
    private Job access(Range range) {
        var job=jobs.get(range);if(job!=null)job.accessed=++access;return job;
    }
    private static int index(List<OutdoorRow> rows,long tick) {
        int lo=0,hi=rows.size()-1;
        while(lo<hi) { int mid=(lo+hi+1)>>>1;if(rows.get(mid).from<=tick)lo=mid;else hi=mid-1; }
        return lo;
    }
    private Optional<List<RoomAir.Boundary>> outside(ServerLevel requested,BlockPos pos,long from,long to) {
        refreshVersions();
        if(boundaryCacheRevision!=boundaryRevision) {
            boundaries.clear();boundaryTick=level.getGameTime();boundaryCacheRevision=boundaryRevision;
        }
        var key=new BoundaryKey(pos.immutable(),from,to);
        var cached=boundaries.get(key);
        if(cached!=null)return Optional.of(cached);
        return data(pos,from,to,true).map(values->{
            var result=new ArrayList<RoomAir.Boundary>();
            if(from==to)result.add(new RoomAir.Boundary(from,to,values.get(index(values,from)).air));
            else for(int i=index(values,from);i<values.size();i++) {
                var row=values.get(i);if(row.from>=to)break;
                long a=Math.max(from,row.from),b=Math.min(to,row.to);
                if(b>a)result.add(new RoomAir.Boundary(a,b,row.air));
            }
            var answer=List.copyOf(result);
            if(boundaries.size()>=512)boundaries.remove(boundaries.keySet().iterator().next());
            boundaries.put(key,answer);
            return answer;
        });
    }
    /** C01の同じ確定区間に対する生理入力。酸素は分圧Pa。風は屋内0.1 m/sの承認済み近似。 */
    public record Exposure(EnvironmentSample sample,double windMps,double oxygenPa,double co,double co2,double dustKgM3,boolean indoor,boolean untypedPollution,RoomAir.Accuracy accuracy) {}
    /** 未計算/未観測なら空。最初の区間を確定した後、次の末尾から再照会する。 */
    public Optional<Exposure> exposure(BlockPos station,long from,long to) {
        return resolve(station,from,to,true).map(value->{
            var sample=value.samples.getFirst();var local=value.first;
            return new Exposure(sample,local.status()==RoomAir.Status.INDOOR?.1:value.outdoor.air.windMS(),
                    local.state().orElseThrow().oxygen()*sample.pressurePa(),local.state().orElseThrow().co(),
                    local.state().orElseThrow().co2(),value.outdoor.dust,local.status()==RoomAir.Status.INDOOR,value.outdoor.pollution>0,local.accuracy());
        });
    }
    private record Resolved(List<EnvironmentSample> samples,RoomAir.Sample first,OutdoorRow outdoor) {}
    private Optional<List<EnvironmentSample>> environment(BlockPos station,long from,long to,boolean firstOnly) {
        return resolve(station,from,to,firstOnly).map(Resolved::samples);
    }
    private Optional<Resolved> resolve(BlockPos station,long from,long to,boolean firstOnly) {
        if(from<0||to<from||to>level.getGameTime())throw new IllegalArgumentException("環境要求の時刻が不正です");
        var pos=station.above();
        var observed=rooms.sample(pos,from);
        if(!observed.confirmed()&&observed.status()!=RoomAir.Status.MISSING_BOUNDARY) {
            reason="room_status.ashen_frontier_climate."+observed.status().name().toLowerCase(java.util.Locale.ROOT);
            return Optional.empty();
        }
        long through=to;
        if(firstOnly) {
            refreshVersions();var frame=checkpoints.floorEntry(from);
            if(frameTime!=null&&frame!=null) {
                long boundary=frameTimeline.next(frame.getKey(),frameTime);
                if(boundary>from)through=Math.min(through,boundary);
            }
        }
        var outdoor=data(pos,from,through);
        if(outdoor.isEmpty()) { reason="environment.ashen_frontier_climate.replaying";return Optional.empty(); }
        var cuts=new TreeSet<Long>(rooms.boundaries(pos,from,through,firstOnly));cuts.add(from);cuts.add(through);
        for(int n=index(outdoor.get(),from);n<outdoor.get().size();n++) {
            var row=outdoor.get().get(n);if(row.to>=through)break;if(row.to>from)cuts.add(row.to);
        }
        var initialCuts=new ArrayList<>(cuts);
        for(int n=0;n+1<initialCuts.size()&&(!firstOnly||n==0);n++) {
            long a=initialCuts.get(n),b=initialCuts.get(n+1);
            if(b-a<=1)continue;
            var first=a==from&&observed.confirmed()?observed:rooms.sample(pos,a);var last=rooms.sample(pos,b-1);
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
        RoomAir.Sample first=null;
        for(int i=0;i<(firstOnly?1:Math.max(1,points.size()-1));i++) {
            long a=points.get(i),b=from==to?a:points.get(i+1);
            var local=a==from&&observed.confirmed()?observed:rooms.sample(pos,a);
            if(!local.confirmed()||local.state().isEmpty()) {
                reason="room_status.ashen_frontier_climate."+local.status().name().toLowerCase(java.util.Locale.ROOT);
                return Optional.empty();
            }
            if(first==null)first=local;
            var row=outdoor.get().get(index(outdoor.get(),a));
            double seconds=GameTime.gameSeconds(b-a,row.day);
            double gameA=row.gameFrom+GameTime.gameSeconds(a-row.from,row.day);
            double energy=SolarEnergy.between(MapCoordinates.latitude(pos.getZ()),row.reference,gameA,gameA+seconds)*row.transmission;
            double rain=(row.rain+row.snow)*seconds;
            result.add(new EnvironmentSample(a,b,row.day,local.state().get().temperatureK(),row.air.pressurePa(),row.humidity,
                    rain,energy,rain>0?Optional.of(AshenFrontierCore.id(row.contaminated?"contaminated_water":"raw_water")):Optional.empty(),
                    local.covered(),local.ventilated()));
        }
        return Optional.of(new Resolved(List.copyOf(result),first,outdoor.get().get(index(outdoor.get(),from))));
    }
}
