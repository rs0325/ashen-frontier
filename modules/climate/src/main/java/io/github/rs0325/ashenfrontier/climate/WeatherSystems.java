package io.github.rs0325.ashenfrontier.climate;

import java.util.ArrayList;
import java.util.List;

/** seedとGAME時刻から再構成する移動気圧系。地域・季節の条件で発生し、有限時間で消える。 */
final class WeatherSystems {
    record System(WeatherEvent type, double x, double z, double radius, double start, double end, double strength) {
        double at(int cell) {
            double dx=cell%64-x,dz=cell/64-z,weight=1-(dx*dx+dz*dz)/(radius*radius);
            return weight>0 ? weight*weight*strength : 0;
        }
    }
    private final List<FixedClimate.Cell> cells;
    private final long seed;
    private final int[][] candidates=new int[9][];
    WeatherSystems(List<FixedClimate.Cell> cells,long seed) {
        this.cells=cells;this.seed=seed;
        for(var type:WeatherEvent.values()) {
            var eligible=new ArrayList<Integer>();
            for(int i=0;i<cells.size();i++)if(eligible(type,cells.get(i)))eligible.add(i);
            candidates[type.ordinal()]=eligible.stream().mapToInt(Integer::intValue).toArray();
        }
    }
    private static boolean eligible(WeatherEvent type,FixedClimate.Cell c) {
        double lat=Math.abs(c.latitudeDegrees()),rh=c.profile().relativeHumidity();
        return switch(type) {
            case SANDSTORM -> c.region()==4 || lat>=18&&lat<=32&&rh<0.6;
            case HEATWAVE,DROUGHT -> lat<58&&rh<0.75;
            case ACID_RAIN -> c.region()==5||c.region()==6;
            case BLIZZARD,COLD_WAVE -> lat>35||c.surfaceElevationM()>1800;
            case THUNDERSTORM -> lat<55&&rh>=0.6;
            case WINDSTORM -> lat>=30;
            case FOG -> rh>=0.6;
        };
    }
    List<System> active(double seconds,int enabled,double graceEnd) {
        var active=new ArrayList<System>();
        long day=(long)Math.floor(seconds/86400);
        for(long birth=Math.max(0,day-2);birth<=day;birth++)for(var type:WeatherEvent.values()) {
            if((enabled&type.bit())==0)continue;
            long h=mix(seed^birth*0x9e3779b97f4a7c15L^type.ordinal()*0xd1b54a32d192ed03L);
            if(unit(h)>0.45)continue;
            double start=(birth+unit(mix(h))*0.6)*86400;
            double duration=(type==WeatherEvent.DROUGHT||type==WeatherEvent.HEATWAVE||type==WeatherEvent.COLD_WAVE?1.25:0.5)*86400;
            if(seconds<start||seconds>=start+duration||type.destructive()&&start<graceEnd)continue;
            var choices=candidates[type.ordinal()];
            if(choices.length==0)continue;
            int cell=choices[Math.floorMod(mix(h+1),choices.length)];
            var c=cells.get(cell);
            double temperature=ClimateModel.temperatureK(c.latitudeDegrees(),c.surfaceElevationM(),start,c.profile());
            if((type==WeatherEvent.HEATWAVE||type==WeatherEvent.DROUGHT||type==WeatherEvent.THUNDERSTORM)&&temperature<285)continue;
            if((type==WeatherEvent.BLIZZARD||type==WeatherEvent.COLD_WAVE)&&temperature>278)continue;
            double age=(seconds-start)/duration,strength=Math.pow(Math.sin(Math.PI*age),2);
            double direction=Math.abs(c.latitudeDegrees())>=30&&Math.abs(c.latitudeDegrees())<60?1:-1;
            double x=cell%64+direction*(seconds-start)/86400*3;
            double z=cell/64+(seconds-start)/86400*Math.signum(c.latitudeDegrees());
            active.add(new System(type,x,z,4+unit(mix(h+2))*3,start,start+duration,strength));
        }
        return List.copyOf(active);
    }
    static long mix(long z) {
        z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;z=(z^(z>>>27))*0x94d049bb133111ebL;return z^(z>>>31);
    }
    static double unit(long value) { return (value>>>11)*0x1.0p-53; }
}
