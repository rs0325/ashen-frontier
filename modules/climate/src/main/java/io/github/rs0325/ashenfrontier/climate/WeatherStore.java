package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.world.MapVersion;
import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.ArrayList;
import java.util.TreeMap;

/** 気象の保存正本。全step履歴を保持せず、2冪stepのcheckpointと現在状態を保持する。 */
final class WeatherStore extends SavedData {
    static final String ID="ashen_frontier_weather";
    static final double GRACE_END=86400;
    final MapVersion version;
    final long seed;
    final WeatherModel model;
    final WeatherTimeline timeline;
    final TreeMap<Long,WeatherFrame> checkpoints=new TreeMap<>();
    WeatherTime time;
    WeatherFrame current,previous;
    WeatherModel.Step work;
    long clockRevision;

    private WeatherStore(MapVersion version,long seed,WeatherTimeline timeline,DayLengthHistory history) {
        this.version=version;this.seed=seed;this.timeline=timeline;time=new WeatherTime(history);
        model=new WeatherModel(FixedClimate.require(version).cells(),seed,version.mapVersion()+"|"+version.formatVersion()+"|"+version.manifestHash());
    }
    static WeatherStore get(ServerLevel level) {
        var version=ClimateQuery.mapVersion(level).orElseThrow();
        long seed=level.getSeed()^version.manifestHash().hashCode();
        var clock=ClimateClock.get(level);
        var factory=new Factory<>(()->{
            var data=new WeatherStore(version,seed,new WeatherTimeline(java.util.List.of(WeatherService.settings(0))),clock.history());
            data.current=data.model.initial(data.timeline.at(0),clock.history(),GRACE_END);
            data.previous=data.current;data.checkpoints.put(0L,data.current);data.clockRevision=clock.revision();data.setDirty();return data;
        },(tag,registries)->{ var data=load(tag,version,seed,clock.history());data.clockRevision=clock.revision();return data; },null);
        var result=level.getDataStorage().computeIfAbsent(factory,ID);
        return result;
    }
    boolean settings(WeatherSettings value) {
        var previous=timeline.changes().getLast();
        if(!timeline.append(value))return false;
        if(!previous.equals(timeline.changes().getLast())) { invalidate(value.tick());setDirty(); }
        return true;
    }
    void clock(DayLengthHistory history,long revision,long boundary) {
        if(revision==clockRevision)return;
        time=new WeatherTime(history);clockRevision=revision;invalidate(boundary);setDirty();
    }
    private void invalidate(long boundary) {
        if(boundary>0)checkpoints.tailMap(boundary,true).clear();
        if(current.tick()>=boundary&&boundary>0) {
            var checkpoint=checkpoints.floorEntry(boundary-1).getValue();
            if(previous.tick()<boundary&&previous.tick()>checkpoint.tick())checkpoint=previous;
            current=previous=checkpoint;
        }
        work=null;
    }
    /** 通常は期限までに少数セルずつ先行計算し、遅延時も最大セル数と実時間予算を守る。 */
    int service(long now,int maximumCells,long deadline) {
        int used=0;
        int normal=(4096+timeline.at(current.tick()).intervalTicks()-1)/timeline.at(current.tick()).intervalTicks()+2;
        int limit=timeline.next(current.tick(),time)<=now?maximumCells:Math.min(maximumCells,normal);
        while(used<limit) {
            if(work==null) {
                long end=timeline.next(current.tick(),time);
                work=model.step(current,end,timeline.at(end),time,GRACE_END);
            }
            if(work.cursor<4096) {
                int before=work.cursor;
                work.advance(Math.min(32,limit-used));used+=work.cursor-before;
            }
            if(work.cursor==4096) {
                if(work.end>now)break;
                previous=current;current=work.result();work=null;
                if((current.step()&(current.step()-1))==0)checkpoints.put(current.tick(),current);
                setDirty();
                if(timeline.next(current.tick(),time)>now) {
                    int interval=timeline.at(current.tick()).intervalTicks();
                    limit=Math.min(limit,used+(4096+interval-1)/interval+2);
                }
            }
            if(System.nanoTime()>=deadline)break;
        }
        return used;
    }
    WeatherReplay replay(int x,int z,long from,long to) {
        var checkpoint=checkpoints.floorEntry(from).getValue();
        if(current.tick()<=from&&current.tick()>checkpoint.tick())checkpoint=current;
        return new WeatherReplay(model,new WeatherTimeline(timeline.changes()),time,GRACE_END,checkpoint,x,z,from,to);
    }
    static void verify(CompoundTag tag,MapVersion expected,long seed) {
        if(tag.getInt("format")!=1||tag.getInt("weather_model")!=WeatherModel.VERSION||tag.getDouble("grace_end_game_seconds")!=GRACE_END
                ||tag.getLong("seed")!=seed||!MapVersion.CODEC.parse(NbtOps.INSTANCE,tag.getCompound("map")).getOrThrow().equals(expected))
            throw new IllegalStateException("気象保存の版・seed・初期猶予が一致しません");
        var current=readFrame(tag.getCompound("current"));
        if(readFrame(tag.getCompound("previous")).tick()>current.tick())throw new IllegalStateException("直前気象が逆順です");
        var rows=tag.getList("checkpoints",Tag.TAG_COMPOUND);
        if(rows.isEmpty()||rows.size()>64)throw new IllegalStateException("気象checkpointが不正です");
        long previous=-1;
        for(int i=0;i<rows.size();i++) {
            var frame=readFrame(rows.getCompound(i));
            if(frame.tick()<=previous||i==0&&frame.tick()!=0)throw new IllegalStateException("気象checkpointが逆順です");
            if(frame.tick()>tag.getCompound("current").getLong("tick"))throw new IllegalStateException("現在より新しい気象checkpointです");
            previous=frame.tick();
        }
        readSettings(tag);
    }
    static WeatherStore load(CompoundTag tag,MapVersion version,long seed,DayLengthHistory history) {
        verify(tag,version,seed);
        var data=new WeatherStore(version,seed,readSettings(tag),history);
        data.current=readFrame(tag.getCompound("current"));data.previous=readFrame(tag.getCompound("previous"));
        var rows=tag.getList("checkpoints",Tag.TAG_COMPOUND);
        for(int i=0;i<rows.size();i++) { var frame=readFrame(rows.getCompound(i));data.checkpoints.put(frame.tick(),frame); }
        return data;
    }
    private static WeatherTimeline readSettings(CompoundTag tag) {
        var rows=tag.getList("settings",Tag.TAG_COMPOUND);
        var values=new ArrayList<WeatherSettings>();
        for(int i=0;i<rows.size();i++) {
            var row=rows.getCompound(i);
            values.add(new WeatherSettings(row.getLong("tick"),row.getInt("interval"),row.getInt("events"),row.getBoolean("dynamic")));
        }
        return new WeatherTimeline(values);
    }
    static CompoundTag settingTag(WeatherSettings value) {
        var tag=new CompoundTag();tag.putLong("tick",value.tick());tag.putInt("interval",value.intervalTicks());
        tag.putInt("events",value.enabledEvents());tag.putBoolean("dynamic",value.dynamic());return tag;
    }
    static WeatherSettings setting(CompoundTag tag) {
        return new WeatherSettings(tag.getLong("tick"),tag.getInt("interval"),tag.getInt("events"),tag.getBoolean("dynamic"));
    }
    static CompoundTag writeFrame(WeatherFrame frame) {
        var tag=new CompoundTag();tag.putLong("tick",frame.tick());tag.putLong("step",frame.step());
        long[] bits=new long[frame.values.length];
        for(int i=0;i<bits.length;i++)bits[i]=Double.doubleToLongBits(frame.values[i]);
        tag.putLongArray("values",bits);return tag;
    }
    static WeatherFrame readFrame(CompoundTag tag) {
        long[] bits=tag.getLongArray("values");double[] values=new double[bits.length];
        for(int i=0;i<bits.length;i++) {
            values[i]=Double.longBitsToDouble(bits[i]);
            if(!Double.isFinite(values[i]))throw new IllegalStateException("非有限の気象状態です");
        }
        if(values.length==WeatherFrame.CELLS*WeatherFrame.FIELDS)for(int i=0;i<WeatherFrame.CELLS;i++) {
            int at=i*WeatherFrame.FIELDS;
            if(values[at+WeatherFrame.Q]<0||values[at+WeatherFrame.RAIN]<0||values[at+WeatherFrame.SNOW]<0
                    ||values[at+WeatherFrame.DUST]<0||values[at+WeatherFrame.POLLUTION]<0
                    ||values[at+WeatherFrame.CLOUD]<0||values[at+WeatherFrame.CLOUD]>1)
                throw new IllegalStateException("気象の非負水分・濃度・雲量が不正です");
        }
        return new WeatherFrame(tag.getLong("tick"),tag.getLong("step"),values);
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        tag.putInt("format",1);tag.putInt("weather_model",WeatherModel.VERSION);tag.putLong("seed",seed);tag.putDouble("grace_end_game_seconds",GRACE_END);
        tag.put("map",MapVersion.CODEC.encodeStart(NbtOps.INSTANCE,version).getOrThrow());
        tag.put("current",writeFrame(current));tag.put("previous",writeFrame(previous));
        var frames=new ListTag();for(var frame:checkpoints.values())frames.add(writeFrame(frame));tag.put("checkpoints",frames);
        var settings=new ListTag();
        for(var value:timeline.changes()) {
            var row=new CompoundTag();row.putLong("tick",value.tick());row.putInt("interval",value.intervalTicks());
            row.putInt("events",value.enabledEvents());row.putBoolean("dynamic",value.dynamic());settings.add(row);
        }
        tag.put("settings",settings);
        var active=new ListTag();
        for(var system:model.systems.active(time.seconds(current.tick()),timeline.at(current.tick()).dynamic()?timeline.at(current.tick()).enabledEvents():0,GRACE_END)) {
            var row=new CompoundTag();row.putString("type",system.type().name());row.putDouble("start",system.start());row.putDouble("end",system.end());
            row.putDouble("x",system.x());row.putDouble("z",system.z());row.putDouble("radius",system.radius());active.add(row);
        }
        tag.put("active_systems",active);return tag;
    }
}
