package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.world.MapCoordinates;
import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 仕事量制限付きの過去再生。advanceは最大指定セル数だけ計算する。
 * pageは要求の一部分であり、全要求成功を表さない。所有者は消費後に末尾をacknowledgeする。
 */
public final class WeatherReplay {
    public record Interval(long startTick,long endTick,WeatherState state,WeatherSettings settings,double rainKgM2,double snowKgM2,double solarJM2) {}
    public record Page(List<Interval> intervals,boolean last) { public Page { intervals=List.copyOf(intervals); } }
    final WeatherModel model;
    final WeatherTimeline timeline;
    final WeatherTime time;
    final double graceEnd;
    final int x,z;
    final long from,to;
    WeatherFrame frame;
    WeatherModel.Step work;
    long through,acknowledged;
    boolean finished;
    final List<Interval> rows=new ArrayList<>();
    WeatherReplay(WeatherModel model,WeatherTimeline timeline,WeatherTime time,double graceEnd,
                  WeatherFrame checkpoint,int x,int z,long from,long to) {
        if(from<0||to<from||checkpoint.tick()>to||!MapCoordinates.contains(x,z))
            throw new IllegalArgumentException("気象再生の要求範囲が不正です");
        this.model=model;this.timeline=timeline;this.time=time;this.graceEnd=graceEnd;frame=checkpoint;
        this.x=x;this.z=z;this.from=from;this.to=to;through=acknowledged=from;
    }
    /** 完成したページはacknowledgeされるまで保持する。戻り値は実際に評価したセル数。 */
    public int advance(int maximumCells) {
        if(maximumCells<=0||maximumCells>8192)throw new IllegalArgumentException("再生の仕事量は1～8192セルです");
        if(!rows.isEmpty()||finished)return 0;
        int used=0;
        while(used<maximumCells&&rows.size()<128&&!finished) {
            if(work!=null) {
                int before=work.cursor;
                boolean complete=work.advance(maximumCells-used);
                used+=work.cursor-before;
                if(!complete)break;
                frame=work.result();work=null;
            }
            long end=timeline.next(frame.tick(),time);
            if(from==to&&end>to) {
                rows.add(observation(to,to));finished=true;break;
            }
            if(from!=to&&end>through) {
                long start=Math.max(through,frame.tick()),stop=Math.min(to,end);
                if(stop>start) { rows.add(observation(start,stop));through=stop; }
                if(through==to) { finished=true;break; }
            }
            if(rows.size()==128)break;
            work=model.step(frame,end,timeline.at(end),time,graceEnd);
        }
        return used;
    }
    private Interval observation(long start,long end) {
        var state=spatial(frame,x,z);
        double a=time.seconds(start),b=time.seconds(end);
        var settings=timeline.at(frame.tick());
        double reference=time.seconds(frame.tick())+settings.intervalTicks()*43200.0/time.dayLength(frame.tick());
        double transmission=(1-0.75*state.cloudFraction())*Math.exp(-3e6*state.dustKgM3());
        double energy=SolarEnergy.between(MapCoordinates.latitude(z),reference,a,b)*transmission;
        return new Interval(start,end,state,settings,state.rainKgM2GameSecond()*(b-a),state.snowKgM2GameSecond()*(b-a),energy);
    }
    public Optional<Page> page() { return rows.isEmpty()?Optional.empty():Optional.of(new Page(rows,finished)); }
    public void acknowledge(long endTick) {
        if(!rows.isEmpty()&&rows.getLast().endTick()==endTick) {
            rows.clear();acknowledged=endTick;return;
        }
        if(endTick!=acknowledged)throw new IllegalArgumentException("未提供の気象ページを確定できません");
    }
    public boolean complete() { return finished&&rows.isEmpty(); }
    public long evaluatedThrough() { return through; }

    /** セル中心を節点とする双線形補間。外周は最端セルを保持し、反対側へ循環しない。 */
    static WeatherState spatial(WeatherFrame frame,int x,int z) {
        double px=Math.clamp((x-MapCoordinates.MIN)/512.0-0.5,0,63),pz=Math.clamp((z-MapCoordinates.MIN)/512.0-0.5,0,63);
        int ix=(int)px,iz=(int)pz;
        int[] indices={iz*64+ix,iz*64+Math.min(63,ix+1),Math.min(63,iz+1)*64+ix,Math.min(63,iz+1)*64+Math.min(63,ix+1)};
        double fx=px-ix,fz=pz-iz;
        double[] weights={(1-fx)*(1-fz),fx*(1-fz),(1-fx)*fz,fx*fz};
        double[] values=new double[WeatherFrame.FIELDS];
        int mask=0;
        for(int i=0;i<4;i++) {
            for(int field=0;field<values.length;field++)values[field]+=frame.get(indices[i],field)*weights[i];
            if(weights[i]>0)mask|=(int)frame.get(indices[i],WeatherFrame.EVENTS);
        }
        values[WeatherFrame.EVENTS]=mask;
        return new WeatherState(values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],
                values[8],values[9],mask,values[11]);
    }
}
