package io.github.rs0325.ashenfrontier.survival;

/** REAL熱・急性とGAME長期を分離した区間計算。乱数・壁時計・Minecraftには依存しない。 */
public final class Physiology {
    public static final double CORE_CAPACITY=219996, SKIN_CAPACITY=24444, LATENT_HEAT=2448000;
    public record Air(double kelvin,double humidity,double wind,double solarWattsM2,double pressurePa,
                      double oxygenPa,double rainKgM2,boolean covered,boolean submerged) {
        public Air {
            if(!Double.isFinite(kelvin)||kelvin<=0||!Double.isFinite(humidity)||humidity<0||humidity>1
                    ||!Double.isFinite(wind)||wind<0||!Double.isFinite(solarWattsM2)||solarWattsM2<0
                    ||!Double.isFinite(pressurePa)||pressurePa<=0||!Double.isFinite(oxygenPa)||oxygenPa<0
                    ||!Double.isFinite(rainKgM2)||rainKgM2<0)throw new IllegalArgumentException("生理環境の単位が不正です");
        }
    }
    private Physiology() {}
    private static double sat(double c) { return Math.exp(18.6686-4030.183/(c+235)); }
    /** 1評価の最大刻みは1 REAL秒。長い呼出しをサーバーの同期処理で繰り返さない。 */
    public record Respiration(double oxygenMol,double co2Mol,double heatJ) {}
    public static void advance(Body b,Air air,Activity activity,double real,double game) {
        evaluate(b,air,activity,real,game);
    }
    /** 既存の熱流を積算し、C06へ渡す呼吸と乾性・呼吸顕熱を返す。潜熱は含まない。 */
    public static Respiration evaluate(Body b,Air air,Activity activity,double real,double game) {
        if(!Double.isFinite(real)||!Double.isFinite(game)||real<0||game<0)
            throw new IllegalArgumentException("生理区間が不正です");
        if(real==0&&game!=0)throw new IllegalArgumentException("睡眠返済を架空の経過にしないでください");
        if(real==0)return new Respiration(0,0,0);
        double remaining=real,metabolicJ=0,dryJ=0;
        while(remaining>1e-9) {
            double dt=Math.min(1,remaining);
            var flux=integrate(b,air,activity,dt,real);
            double cost=waterCost(flux,dt,game/real);
            boolean depleted=b.water>0&&cost>b.water;
            if(depleted) {
                double lo=0,hi=dt;
                for(int i=0;i<32;i++) {
                    double mid=(lo+hi)/2;
                    if(waterCost(integrate(b,air,activity,mid,real),mid,game/real)<=b.water)lo=mid;
                    else hi=mid;
                }
                if(lo==0) { b.water=0;continue; }
                dt=lo;flux=integrate(b,air,activity,dt,real);
                cost=waterCost(flux,dt,game/real);
            }
            metabolicJ+=flux[4];dryJ+=flux[5];
            double dg=game*(dt/real);
            b.wet=wetAt(b.wet,air,dt,real);
            b.core+=flux[0];b.skin+=flux[1];
            b.water=depleted?0:Math.max(0,b.water-cost);
            b.energy=Math.max(0,b.energy-10_000_000*dg/86400);
            b.protein=Math.max(0,b.protein-.06*dg/86400);b.fat=Math.max(0,b.fat-.04*dg/86400);
            b.vitaminC+=dg;b.vitaminFat+=dg;
            b.emptyEnergy=b.energy==0?b.emptyEnergy+dg:0;
            b.emptyProtein=b.protein==0?b.emptyProtein+dg:0;b.emptyFat=b.fat==0?b.emptyFat+dg:0;
            b.coldFeeling=b.skin<305.15?b.coldFeeling+dt:0;
            b.coldStrong=b.skin<293.15?b.coldStrong+dt:0;
            b.coldDanger=b.skin<288.15?b.coldDanger+dt:0;
            b.warmFeeling=b.skin>308.15?b.warmFeeling+dt:0;
            refresh(b);
            int physical=Math.max(Math.max(b.levels[0],b.levels[1]),
                    Math.max(Math.max(b.levels[2],b.levels[3]),Math.max(b.levels[4],b.levels[5])));
            double recovery=new double[]{1,.75,.5,.25}[physical],hours=dg/3600;
            b.stamina=Math.clamp(b.stamina+activity.staminaPerSecond*dt
                    *(activity.staminaPerSecond>0?recovery:1),0,100);
            if(b.stamina==0)b.exhausted=true;if(b.stamina>=20)b.exhausted=false;
            b.fatigue=Math.clamp(b.fatigue+activity.fatiguePerHour*hours
                    *(activity.fatiguePerHour<0?recovery:1),0,100);
            b.sleepDebt=Math.clamp(b.sleepDebt+(activity==Activity.SLEEP?-1:.5)*dg,0,86400);
            boolean healthy=b.water>41.3&&b.energy>10_000_000&&b.protein>.03&&b.fat>.02
                    &&b.core>=309.15&&b.core<=311.15&&air.oxygenPa>=16000;
            double stress=(physical>=3||air.oxygenPa<16000?2:0)+(b.sleepDebt>=43200?1:0)
                    +(Math.max(b.levels[3],Math.max(b.levels[4],b.levels[5]))>=2?1:0);
            if(stress>0)b.mental=Math.max(0,b.mental-Math.min(4,stress)*hours);
            else if(healthy&&b.mental<80&&(activity==Activity.SLEEP||activity==Activity.REST))
                b.mental=Math.min(80,b.mental+(activity==Activity.SLEEP?2:1)*hours);
            if(healthy&&b.injury<40&&(activity==Activity.SLEEP||activity==Activity.REST))
                b.injury=Math.max(0,b.injury-(activity==Activity.SLEEP?2:1)*hours*recovery);
            if(b.core<308.15||b.core>=312.15||b.water<=37.8||b.emptyEnergy>=86400)b.damage+=dt/120;
            remaining-=dt;
        }
        refresh(b);
        double oxygen=metabolicJ/20422.5224/22.414;
        return new Respiration(oxygen,.85*oxygen,dryJ);
    }
    private static double wetAt(double wet,Air air,double dt,double real) {
        return air.submerged?1:Math.clamp(wet+(!air.covered?air.rainKgM2*dt/real:0)
                -(air.rainKgM2==0?dt/1800:0),0,1);
    }
    private static double[] integrate(Body b,Air air,Activity activity,double dt,double real) {
        double wet=wetAt(b.wet,air,dt,real);boolean water=b.water>0;
        var a=rates(b.core,b.skin,wet,air,activity,water);
        var c=rates(b.core+a[0]*dt/2,b.skin+a[1]*dt/2,wet,air,activity,water);
        var d=rates(b.core+c[0]*dt/2,b.skin+c[1]*dt/2,wet,air,activity,water);
        var e=rates(b.core+d[0]*dt,b.skin+d[1]*dt,wet,air,activity,water);
        return new double[]{(a[0]+2*c[0]+2*d[0]+e[0])*dt/6,(a[1]+2*c[1]+2*d[1]+e[1])*dt/6,
                (a[2]+2*c[2]+2*d[2]+e[2])*dt/6,(a[3]+2*c[3]+2*d[3]+e[3])*dt/6,
                (a[4]+2*c[4]+2*d[4]+e[4])*dt/6,(a[5]+2*c[5]+2*d[5]+e[5])*dt/6};
    }
    private static double waterCost(double[] flux,double dt,double gameRate) {
        double budget=3*gameRate*dt/86400+Math.max(0,flux[2]-.007826326*dt/3600)
                +Math.max(0,flux[3]-.028215607*dt/3600);
        // 基礎に含まれる量を重ねず、実際の分泌量も体水分から必ず引く。
        return Math.max(budget,flux[2]+flux[3]);
    }
    private static double[] rates(double core,double skin,double wet,Air air,Activity activity,boolean water) {
        double tc=core-273.15,ts=skin-273.15,ta=air.kelvin-273.15;
        double met=activity.met,v=Math.max(.1,air.wind),p=air.pressurePa/101325;
        double clo=activity==Activity.SLEEP?2:1,rc=.155*clo*Math.max(.35,1-.65*wet)*Math.max(.5,1/(1+.15*(v-.1)));
        double f=1+.15*clo,hc=Math.max(Math.max(3*Math.pow(p,.53),8.6*Math.pow(v*p,.53)),
                5.66*Math.pow(Math.max(0,met-.85),.39));
        double dry=1.8*(ts-ta)/(rc+1/(f*(hc+4.7)));
        double blood=Math.clamp((6.3+120*Math.max(0,tc-36.8))/(1+.5*Math.max(0,33.7-ts)),.5,90);
        double transfer=1.8*(5.28+1.163*blood)*(tc-ts);
        double shiver=Math.min(19.4*Math.max(0,33.7-ts)*Math.max(0,36.8-tc),Math.max(0,4-met)*58.2);
        double m=met*58.2+shiver,vap=air.humidity*sat(ta);
        double resp=water?1.8*Math.max(0,.0023*m*(44-vap)):0;double sensible=1.8*.0014*m*(34-ta);
        double maximum=1.8*Math.max(0,(sat(ts)-vap)/(1/(2.2/p*f*hc)+rc/(2.2/p*.45)));
        double warm=Math.max(0,.9*(tc-36.8)+.1*(ts-33.7));
        double sweat=water?Math.clamp(170*warm*Math.exp(Math.max(0,ts-33.7)/10.7),0,500)*1.8/3600000:0;
        double diffusion=water?.06*maximum:0,evap=Math.min(LATENT_HEAT*sweat,.64*maximum)+diffusion;
        double solar=air.covered?0:1.8*.25*.7*air.solarWattsM2;
        return new double[]{(1.8*m-transfer-resp-sensible)/CORE_CAPACITY,
                (transfer-dry-evap+solar)/SKIN_CAPACITY,sweat,(resp+diffusion)/LATENT_HEAT,1.8*m,dry+sensible};
    }
    /** intakeは確定済みの実kg/Jだけ。transaction/食品安全性の判定は所有側が行う。 */
    public static void ingest(Body b,double water,double joules,double protein,double fat,boolean c,boolean soluble) {
        if(!Double.isFinite(water+joules+protein+fat)||water<0||joules<0||protein<0||fat<0)
            throw new IllegalArgumentException("摂取量が不正です");
        b.water=Math.min(42,b.water+water);b.energy=Math.min(20_000_000,b.energy+joules);
        b.protein=Math.min(.18,b.protein+protein);b.fat=Math.min(.12,b.fat+fat);
        if(c)b.vitaminC=0;if(soluble)b.vitaminFat=0;
        if(b.energy>0)b.emptyEnergy=0;if(b.protein>0)b.emptyProtein=0;if(b.fat>0)b.emptyFat=0;
        refresh(b);
    }
    public static void wound(Body b,double damageHp) {
        if(!Double.isFinite(damageHp)||damageHp<0)throw new IllegalArgumentException("被弾量が不正です");
        b.injury=Math.min(100,b.injury+5*damageHp);refresh(b);
    }
    public static void jump(Body b) { b.stamina=Math.max(0,b.stamina-10);if(b.stamina==0)b.exhausted=true;refresh(b); }
    record SleepResult(long consumed,long lastCredit,boolean applied) {}
    static SleepResult creditSleep(Body body,long generation,long consumed,long tick,long lastCredit,double elapsed,boolean valid) {
        if(generation<=consumed)return new SleepResult(consumed,lastCredit,false);
        boolean applied=valid&&(lastCredit<0||elapsed>=43200);
        if(applied) { body.sleepDebt=Math.max(0,body.sleepDebt-86400);refresh(body); }
        return new SleepResult(generation,applied?tick:lastCredit,applied);
    }
    private static int high(double x,double a,double c,double d) { return x>=d?3:x>=c?2:x>=a?1:0; }
    private static int low(double x,double a,double c,double d) { return x<=d?3:x<=c?2:x<=a?1:0; }
    public static void refresh(Body b) {
        int cold=Math.max(b.core<308.15?3:b.core<308.65?2:b.core<309.15?1:0,
                b.coldDanger>=300?3:b.coldStrong>=60?2:b.coldFeeling>=30?1:0);
        int heat=Math.max(b.core>=312.15?3:b.core>=311.15?2:b.core>310.65?1:0,b.warmFeeling>=30?1:0);
        int[] next={cold,heat,high(42-b.water,.7,1.4,2.8),low(b.energy,1e7,5e6,0),
                b.protein<=.03?b.emptyProtein>=259200?3:b.emptyProtein>=86400?2:1:0,
                b.fat<=.02?b.emptyFat>=259200?3:b.emptyFat>=86400?2:1:0,
                high(b.vitaminC/86400,3,7,14),high(b.vitaminFat/86400,7,14,28),
                low(b.stamina,20,10,0),high(b.fatigue,40,70,90),high(b.sleepDebt,28800,43200,57600),
                low(b.mental,60,40,20),high(b.injury,10,20,40)};
        for(int i=0;i<next.length;i++) {
            int previous=b.levels[i];
            if(next[i]<previous&&previous>0) {
                boolean hold=switch(i) {
                    case 0 -> b.core<new double[]{0,309.35,308.85,308.35}[previous]
                            ||b.skin<new double[]{0,305.35,293.35,288.35}[previous];
                    case 1 -> b.core>=new double[]{0,310.45,310.95,311.95}[previous]
                            ||previous==1&&b.skin>307.95;
                    case 2 -> 42-b.water>=new double[]{0,.6,1.3,2.7}[previous];
                    case 3 -> b.energy<=new double[]{0,10.5e6,5.5e6,.5e6}[previous];
                    case 8 -> b.stamina<new double[]{0,25,15,5}[previous];
                    case 9 -> b.fatigue>=new double[]{0,35,65,85}[previous];
                    case 10 -> b.sleepDebt>=new double[]{0,25200,39600,54000}[previous];
                    case 11 -> b.mental<=new double[]{0,65,45,25}[previous];
                    case 12 -> b.injury>=new double[]{0,5,15,35}[previous];
                    default -> false;
                };
                if(hold)next[i]=previous;
            }
            b.levels[i]=next[i];
        }
    }
}
