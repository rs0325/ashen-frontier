package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WeatherModelTest {
    static List<FixedClimate.Cell> cells() {
        var cells=new ArrayList<FixedClimate.Cell>();
        for(int z=0;z<64;z++)for(int x=0;x<64;x++) {
            int region=x/8;double latitude=80-(z+0.5)*2.5;
            cells.add(new FixedClimate.Cell(-16128+x*512,-16128+z*512,region,latitude,region==3?2000:0,
                    x*512,region==4?1500:0,ClimateProfile.forRegion(region)));
        }
        return cells;
    }
    static WeatherReplay replay(long from,long to,DayLengthHistory history,List<WeatherSettings> settings) {
        var model=new WeatherModel(cells(),47,"fixture");
        var initial=model.initial(settings.getFirst(),history,86400);
        return new WeatherReplay(model,new WeatherTimeline(settings),new WeatherTime(history),86400,initial,-8000,-8000,from,to);
    }
    static List<WeatherReplay.Interval> collect(WeatherReplay replay,int budget) {
        var result=new ArrayList<WeatherReplay.Interval>();
        while(!replay.complete()) {
            replay.advance(budget);
            replay.page().ifPresent(page->{ result.addAll(page.intervals());replay.acknowledge(page.intervals().getLast().endTick()); });
        }
        return result;
    }
    @Test void doubleBufferOrderAndNonnegativeWater() {
        var model=new WeatherModel(cells(),47,"fixture");
        var settings=new WeatherSettings(0,600,511,true);var history=new DayLengthHistory(0,24000);
        var initial=model.initial(settings,history,86400);
        boolean rain=false,snow=false;
        for(int i=0;i<4096;i++) { rain|=initial.cell(i).rainKgM2GameSecond()>0;snow|=initial.cell(i).snowKgM2GameSecond()>0; }
        assertTrue(rain&&snow);
        var forward=model.step(initial,600,settings,new WeatherTime(history),86400);
        var reverse=model.step(initial,600,settings,new WeatherTime(history),86400);
        for(int i=0;i<4096;i++)forward.update(i);
        for(int i=4095;i>=0;i--)reverse.update(i);
        assertArrayEquals(forward.next,reverse.next);
        var frame=new WeatherFrame(600,1,forward.next);
        for(int step=0;step<80;step++) {
            var work=model.step(frame,frame.tick()+600,settings,new WeatherTime(history),86400);
            work.advance(4096);frame=work.result();
            for(int i=0;i<4096;i++) {
                var state=frame.cell(i);
                assertTrue(state.vaporKgM2()>=0);
                assertTrue(state.rainKgM2GameSecond()>=0&&state.snowKgM2GameSecond()>=0);
                assertTrue((state.rainKgM2GameSecond()+state.snowKgM2GameSecond())*2160<=state.vaporKgM2()+1e-10);
                assertTrue(Double.isFinite(state.pressureAnomalyPa()));
            }
        }
        assertFalse(Arrays.equals(initial.values,frame.values));
    }
    @Test void allNineEventsHaveFiniteRegionalSeasonalLifetimesAndGrace() {
        var systems=new WeatherSystems(cells(),47);int found=0;
        for(int day=0;day<240;day++)for(int quarter=0;quarter<4;quarter++) {
            double now=(day+quarter/4.0)*86400;
            for(var event:systems.active(now,511,86400)) {
                found|=event.type().bit();
                assertTrue(event.start()<=now&&event.end()>now);
                if(event.type().destructive())assertTrue(event.start()>=86400);
                assertTrue(event.strength()>=0&&event.strength()<=1);
            }
        }
        assertEquals(511,found);
        assertTrue(systems.active(3*86400,0,86400).isEmpty());
        var last=systems.active(10*86400,511,86400);
        for(var event:last)assertFalse(systems.active(event.end()+1,511,86400).stream()
                .anyMatch(next->next.type()==event.type()&&next.start()==event.start()));
    }
    @Test void partitionedHistoryAndDayLengthChangesMatch() {
        var history=new DayLengthHistory(0,24000);history.change(850,12347);
        var settings=List.of(new WeatherSettings(0,600,511,true),new WeatherSettings(1370,200,255,true));
        var whole=collect(replay(0,5000,history,settings),513);
        var repeated=collect(replay(0,5000,history,settings),4096);
        assertEquals(whole,repeated);
        var left=collect(replay(0,1733,history,settings),256);
        var right=collect(replay(1733,5000,history,settings),256);
        double rain=whole.stream().mapToDouble(WeatherReplay.Interval::rainKgM2).sum();
        double splitRain=left.stream().mapToDouble(WeatherReplay.Interval::rainKgM2).sum()+right.stream().mapToDouble(WeatherReplay.Interval::rainKgM2).sum();
        assertEquals(rain,splitRain,1e-10);
        double solar=whole.stream().mapToDouble(WeatherReplay.Interval::solarJM2).sum();
        assertEquals(solar,left.stream().mapToDouble(WeatherReplay.Interval::solarJM2).sum()
                +right.stream().mapToDouble(WeatherReplay.Interval::solarJM2).sum(),1e-5);
        assertEquals(1733,right.getFirst().startTick());
        var zero=collect(replay(1370,1370,history,settings),4096).getFirst();
        assertEquals(0,zero.rainKgM2());assertEquals(0,zero.solarJM2());
    }
    @Test void repeatedAcknowledgementKeepsPendingNextPage() {
        var cursor=replay(0,1800,new DayLengthHistory(0,24000),List.of(new WeatherSettings(0,600,511,true)));
        cursor.advance(1);
        assertEquals(600,cursor.page().orElseThrow().intervals().getLast().endTick());
        cursor.acknowledge(600);
        cursor.advance(4096);
        var pending=cursor.page().orElseThrow();
        assertEquals(1200,pending.intervals().getLast().endTick());
        cursor.acknowledge(600);
        assertEquals(pending,cursor.page().orElseThrow());
        assertFalse(cursor.complete());
        assertThrows(IllegalArgumentException.class,()->cursor.acknowledge(1800));
        cursor.acknowledge(1200);
        cursor.advance(4096);
        cursor.acknowledge(1800);
        cursor.acknowledge(1800);
        assertTrue(cursor.complete());

        var zero=replay(600,600,new DayLengthHistory(0,24000),List.of(new WeatherSettings(0,600,511,true)));
        zero.advance(4096);
        assertFalse(zero.complete());
        zero.acknowledge(600);
        zero.acknowledge(600);
        assertTrue(zero.complete());
    }
    @Test void spatialInterpolationDoesNotJumpAtCellEdges() {
        var history=new DayLengthHistory(0,24000);
        var settings=new WeatherSettings(0,600,511,true);
        var model=new WeatherModel(cells(),47,"fixture");var initial=model.initial(settings,history,86400);
        var work=model.step(initial,600,settings,new WeatherTime(history),86400);work.advance(4096);
        var frame=work.result();
        var a=WeatherReplay.spatial(frame,-8193,-8000);var b=WeatherReplay.spatial(frame,-8192,-8000);
        assertTrue(Math.abs(a.vaporKgM2()-b.vaporKgM2())<0.1);
        assertTrue(Math.abs(a.pressureAnomalyPa()-b.pressureAnomalyPa())<5);
        assertEquals(frame.cell(0),WeatherReplay.spatial(frame,-16384,-16384));
    }
    @Test void solarIntegrationHasNightZeroAndPartitionInvariance() {
        assertEquals(0,SolarEnergy.between(0,43200,0.6*86400,0.9*86400),1e-7);
        assertEquals(0,SolarEnergy.between(80,0,0,86400));
        double whole=SolarEnergy.between(40,43200,0,86400);
        assertTrue(whole>0);
        assertEquals(whole,SolarEnergy.between(40,43200,0,12345)+SolarEnergy.between(40,43200,12345,86400),1e-5);
    }
    @Test void nightAcrossDayBoundaryNeverProducesNegativeEnergy() {
        assertEquals(0,SolarEnergy.between(40,1639443.6,1637283.6,1641603.6));
    }
    @Test void historyOneAndTenYearsBenchmark() {
        var history=new DayLengthHistory(0,24000);var settings=List.of(new WeatherSettings(0,600,511,true));
        for(int years:new int[]{1,10}) {
            System.gc();
            var memory=java.lang.management.ManagementFactory.getMemoryMXBean();
            var thread=java.lang.management.ManagementFactory.getThreadMXBean();
            long heapBefore=memory.getHeapMemoryUsage().getUsed();
            long cpuBefore=thread.isCurrentThreadCpuTimeSupported()?thread.getCurrentThreadCpuTime():-1;
            long start=System.nanoTime(),calls=0,maximumPage=0;double water=0,energy=0;
            var cursor=replay(0,years*24L*24000,history,settings);
            while(!cursor.complete()) {
                cursor.advance(1024);calls++;
                var page=cursor.page();
                if(page.isPresent()) {
                    maximumPage=Math.max(maximumPage,page.get().intervals().size());
                    for(var interval:page.get().intervals()) { water+=interval.rainKgM2()+interval.snowKgM2();energy+=interval.solarJM2(); }
                    cursor.acknowledge(page.get().intervals().getLast().endTick());
                }
            }
            assertTrue(maximumPage<=128&&water>=0&&energy>=0);
            double elapsedMs=(System.nanoTime()-start)/1e6;
            double cpuMs=cpuBefore>=0?(thread.getCurrentThreadCpuTime()-cpuBefore)/1e6:-1;
            System.gc();
            long heapAfter=memory.getHeapMemoryUsage().getUsed();
            System.out.printf("M3-134 replay years=%d ms=%.3f cpu_ms=%.3f calls=%d live_frame_bytes=%d retained_heap_delta=%d water=%.9f solar=%.3f%n",
                    years,elapsedMs,cpuMs,calls,2L*4096*12*8,heapAfter-heapBefore,water,energy);
            java.lang.ref.Reference.reachabilityFence(cursor);
        }
    }
}
