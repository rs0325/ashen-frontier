package io.github.rs0325.ashenfrontier.climate;

import java.util.ArrayList;
import java.util.List;

/** 変更順に保存する気象設定。単一サーバースレッドから使用する。 */
final class WeatherTimeline {
    private final List<WeatherSettings> changes;
    WeatherTimeline(List<WeatherSettings> changes) {
        if(changes.isEmpty()||changes.getFirst().tick()!=0)throw new IllegalArgumentException("気象履歴はtick 0から必要です");
        this.changes=new ArrayList<>();
        for(var value:changes) {
            if(!this.changes.isEmpty()&&this.changes.getLast().tick()>=value.tick())throw new IllegalArgumentException("気象設定が逆順です");
            this.changes.add(value);
        }
    }
    List<WeatherSettings> changes() { return List.copyOf(changes); }
    private int index(long tick) {
        int lo=0,hi=changes.size();
        while(lo+1<hi) { int mid=(lo+hi)>>>1;if(changes.get(mid).tick()<=tick)lo=mid;else hi=mid; }
        return lo;
    }
    WeatherSettings at(long tick) { return changes.get(index(tick)); }
    long next(long tick, WeatherTime time) {
        int index=index(tick);
        var value=changes.get(index);
        long end=Math.addExact(tick,value.intervalTicks());
        if(index+1<changes.size())end=Math.min(end,changes.get(index+1).tick());
        return Math.min(end,time.nextChange(tick));
    }
    boolean append(WeatherSettings value) {
        var last=changes.getLast();
        if(last.intervalTicks()==value.intervalTicks()&&last.enabledEvents()==value.enabledEvents()&&last.dynamic()==value.dynamic())return true;
        if(value.tick()<=last.tick())return false;
        changes.add(value);return true;
    }
}
