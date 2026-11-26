package io.github.rs0325.ashenfrontier.climate;

/** 論理気象区間の中点赤緯を保持し、正の水平日射だけを解析積分する。 */
final class SolarEnergy {
    private SolarEnergy() {}
    static double between(double latitude, double declinationGameSecond, double fromGameSecond, double toGameSecond) {
        return primitive(latitude,declinationGameSecond,toGameSecond)-primitive(latitude,declinationGameSecond,fromGameSecond);
    }
    private static double primitive(double latitude, double reference, double seconds) {
        double phase=reference/(86400*24);
        double noon=Math.sin(SolarPosition.at(latitude,phase,0.5).altitudeRad());
        double midnight=Math.sin(SolarPosition.at(latitude,phase,0).altitudeRad());
        double a=(noon+midnight)/2,b=(noon-midnight)/2;
        double day=Math.floor(seconds/86400), hour=2*Math.PI*(seconds/86400-day-0.25);
        double full=integral(a,b,-Math.PI,Math.PI);
        return (day*full+integral(a,b,-Math.PI/2,hour))*86400/(2*Math.PI)*1361*0.75;
    }
    private static double integral(double a,double b,double from,double to) {
        if(a>=b)return a*(to-from)+b*(Math.sin(to)-Math.sin(from));
        if(a<=-b)return 0;
        double h=Math.acos(-a/b),sum=0;
        for(int n=-1;n<=1;n++) {
            double lo=Math.max(from,2*Math.PI*n-h),hi=Math.min(to,2*Math.PI*n+h);
            if(hi>lo)sum+=a*(hi-lo)+b*(Math.sin(hi)-Math.sin(lo));
        }
        return sum;
    }
}
