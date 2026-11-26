package io.github.rs0325.ashenfrontier.climate;

import java.util.List;
import static io.github.rs0325.ashenfrontier.climate.WeatherFrame.*;

/** 四近傍の有界移流・拡散と気候への緩和。厳密な全球予報・エネルギー保存則は対象外。 */
public final class WeatherModel {
    public static final int VERSION=1;
    private final List<FixedClimate.Cell> cells;
    private final double[] mean=new double[CELLS],season=new double[CELLS],daily=new double[CELLS],humidity=new double[CELLS];
    final WeatherSystems systems;
    final long seed;
    final String identity;
    public WeatherModel(List<FixedClimate.Cell> cells,long seed,String identity) {
        if(cells.size()!=CELLS)throw new IllegalArgumentException("4096固定セルが必要です");
        this.cells=List.copyOf(cells);this.seed=seed;this.identity=identity;systems=new WeatherSystems(cells,seed);
        for(int i=0;i<CELLS;i++) {
            var c=cells.get(i);
            mean[i]=ClimateModel.annualSeaLevelK(c.latitudeDegrees(),c.profile())+StandardAtmosphere.temperatureOffsetK(c.surfaceElevationM());
            season[i]=Math.sin(Math.toRadians(c.latitudeDegrees()))*c.profile().seasonAmplitudeK();
            daily[i]=c.profile().dayAmplitudeK();
            int east=i%64<63?i+1:i;
            double relief=baseWind(c.latitudeDegrees())>0?c.westReliefM():
                    Math.max(0,cells.get(east).surfaceElevationM()-c.surfaceElevationM());
            humidity[i]=Math.clamp(c.profile().relativeHumidity()+0.08*Math.exp(-c.coastDistanceBlocks()/8192)
                    -0.2*relief/(1500+relief),0.1,0.95);
        }
    }
    double temperature(int cell,double seconds) {
        double days=seconds/86400;
        return mean[cell]-Math.cos(2*Math.PI*(days/24-0.04))*season[cell]
                -Math.cos(2*Math.PI*(days+0.125))*daily[cell];
    }
    static double capacity(double temperature) { return Math.clamp(10+(temperature-273.15),3,70); }
    public WeatherFrame initial(WeatherSettings settings,io.github.rs0325.ashenfrontier.core.time.DayLengthHistory history,double graceEnd) {
        var time=new WeatherTime(history);
        var frame=new WeatherFrame(0,0,new double[CELLS*FIELDS]);
        var step=new Step(frame,0,settings,time,graceEnd);
        for(int i=0;i<CELLS;i++)step.initialize(i);
        return new WeatherFrame(0,0,step.next);
    }
    Step step(WeatherFrame frame,long end,WeatherSettings settings,WeatherTime time,double graceEnd) {
        return new Step(frame,end,settings,time,graceEnd);
    }
    final class Step {
        final WeatherFrame old;
        final long end;
        final double[] next=new double[CELLS*FIELDS];
        final WeatherSettings settings;
        final WeatherTime time;
        final double seconds,elapsed,alpha,seasonal,dailyPhase;
        final double[] xWave=new double[64],zWave=new double[64];
        final List<WeatherSystems.System> events;
        int cursor;
        Step(WeatherFrame old,long end,WeatherSettings settings,WeatherTime time,double graceEnd) {
            this.old=old;this.end=end;this.settings=settings;this.time=time;
            seconds=time.seconds(end);elapsed=seconds-time.seconds(old.tick());
            alpha=(end-old.tick())/600.0;
            seasonal=-Math.cos(2*Math.PI*(seconds/(86400*24)-0.04));
            dailyPhase=-Math.cos(2*Math.PI*(seconds/86400+0.125));
            double phase=WeatherSystems.unit(WeatherSystems.mix(seed))*Math.PI*2+seconds/86400;
            for(int i=0;i<64;i++) { xWave[i]=Math.sin(i*0.18-phase);zWave[i]=Math.cos(i*0.15+phase*0.7); }
            events=systems.active(seconds,settings.dynamic()?settings.enabledEvents():0,graceEnd);
        }
        void initialize(int i) {
            double temperature=mean[i]+seasonal*season[i]+dailyPhase*daily[i];
            next[i*FIELDS+Q]=capacity(temperature)*humidity[i];
            finishCell(i,0,0,next[i*FIELDS+Q],baseWind(cells.get(i).latitudeDegrees()),0);
        }
        boolean advance(int budget) {
            int stop=Math.min(CELLS,cursor+budget);
            while(cursor<stop)update(cursor++);
            return cursor==CELLS;
        }
        private double mix(int i,int field,double target) {
            int x=i%64,z=i/64;
            double center=previous(i,field);
            double neighbors=previous(x>0?i-1:i,field)+previous(x<63?i+1:i,field)
                    +previous(z>0?i-64:i,field)+previous(z<63?i+64:i,field);
            double u=old.get(i,U),v=old.get(i,V);
            int upX=u>0?(x>0?i-1:i):(x<63?i+1:i);
            int upZ=v>0?(z>0?i-64:i):(z<63?i+64:i);
            double ax=Math.min(0.1,Math.abs(u)*30/512)*alpha,az=Math.min(0.1,Math.abs(v)*30/512)*alpha;
            double relaxation=field==Q?0.01:0.04;
            return (1-(0.08+relaxation)*alpha-ax-az)*center+0.02*alpha*neighbors+relaxation*alpha*target
                    +ax*previous(upX,field)+az*previous(upZ,field);
        }
        private double previous(int i,int field) {
            double value=old.get(i,field);
            return field==Q?Math.max(0,value-(old.get(i,RAIN)+old.get(i,SNOW))*elapsed):value;
        }
        void update(int i) {
            double baseline=mean[i]+seasonal*season[i]+dailyPhase*daily[i];
            if(!settings.dynamic()) { finishCell(i,0,0,capacity(baseline)*humidity[i],baseWind(cells.get(i).latitudeDegrees()),0);return; }
            double wave=xWave[i%64]*zWave[i/64];
            double pressure=mix(i,P,1800*wave);
            double anomaly=mix(i,T,3*wave);
            double rainSeason=1+2*(cells.get(i).profile().summerRainFraction()-0.5)*Math.signum(cells.get(i).latitudeDegrees())*seasonal;
            double target=capacity(baseline)*humidity[i]*rainSeason*(1+0.15*wave);
            double supply=cells.get(i).profile().annualPrecipitationKgM2()/(86400*24)*rainSeason*elapsed;
            double moisture=Math.max(0,mix(i,Q,target)+supply);
            int x=i%64,z=i/64;
            double u=baseWind(cells.get(i).latitudeDegrees())+(old.get(x>0?i-1:i,P)-old.get(x<63?i+1:i,P))*0.008;
            double v=(old.get(z>0?i-64:i,P)-old.get(z<63?i+64:i,P))*0.008;
            finishCell(i,pressure,anomaly,moisture,u,v);
        }
        private void finishCell(int i,double pressure,double anomaly,double moisture,double u,double v) {
            int at=i*FIELDS;var cell=cells.get(i);
            double rainBoost=0,dust=cell.region()==4?40e-9:5e-9,pollution=cell.region()==5||cell.region()==6?80e-9:8e-9;
            int mask=0;double severity=0;
            for(var event:events) {
                double strength=event.at(i);
                if(strength<0.01)continue;
                mask|=event.type().bit();severity=Math.max(severity,strength);
                switch(event.type()) {
                    case HEATWAVE -> anomaly+=0.04*alpha*8*strength;
                    case COLD_WAVE,BLIZZARD -> anomaly-=0.04*alpha*8*strength;
                    case DROUGHT -> moisture*=1-0.2*alpha*strength;
                    case SANDSTORM -> { dust+=800e-9*strength;u+=10*strength; }
                    case ACID_RAIN -> { rainBoost+=0.001*strength;pollution+=200e-9*strength; }
                    case THUNDERSTORM -> { rainBoost+=0.002*strength;pressure-=0.04*alpha*1200*strength;u+=12*strength; }
                    case WINDSTORM -> { u+=20*strength;v+=8*strength; }
                    case FOG -> moisture=Math.max(moisture,capacity(mean[i]+seasonal*season[i]+dailyPhase*daily[i]+anomaly)*0.98*strength);
                }
            }
            double temperature=mean[i]+seasonal*season[i]+dailyPhase*daily[i]+anomaly;
            double cap=capacity(temperature),rh=Math.clamp(moisture/cap,0,1);
            double cloud=Math.clamp((rh-0.55)/0.45,0,1);
            double rate=settings.dynamic()?cell.profile().annualPrecipitationKgM2()/(86400*24)*Math.max(0,(rh-0.7)/0.3)*3+rainBoost:0;
            double intervalSeconds=settings.intervalTicks()*86400.0/time.dayLength(end);
            rate=Math.min(rate,moisture/intervalSeconds);
            double snow=Math.clamp((274.15-temperature)/2,0,1);
            if(alpha>0&&settings.dynamic()) {
                double wash=Math.clamp(rate*elapsed*0.1,0,0.2);
                dust=Math.max(0,mix(i,DUST,dust)*(1-wash));
                pollution=Math.max(0,mix(i,POLLUTION,pollution)*(1-wash));
            }
            next[at+P]=Math.clamp(pressure,-6000,6000);next[at+T]=Math.clamp(anomaly,-25,25);next[at+Q]=moisture;
            next[at+U]=Math.clamp(u,-45,45);next[at+V]=Math.clamp(v,-45,45);next[at+CLOUD]=cloud;
            next[at+RAIN]=rate*(1-snow);next[at+SNOW]=rate*snow;next[at+DUST]=dust;
            next[at+POLLUTION]=pollution;next[at+EVENTS]=mask;next[at+SEVERITY]=severity;
        }
        WeatherFrame result() {
            if(cursor<CELLS)throw new IllegalStateException("気象stepは未完了です");
            return new WeatherFrame(end,old.step()+1,next);
        }
    }
    static double baseWind(double latitude) {
        double a=Math.abs(latitude);return a>=30&&a<60?6:-4;
    }
}
