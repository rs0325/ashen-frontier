package io.github.rs0325.ashenfrontier.survival;

/** 曝露の区間計算。時間・質量・線量はSI、入力は呼出し側が固定した区間の値。 */
public final class Exposure {
    static final double[] TAU={120,30,30,300};
    static final double[][] LIMITS={{.1,.5,1},{.1,.5,1},{.1,.5,1},{.1,.5,1},
            {.5e-6,1e-6,2e-6},{1e-6,5e-6,20e-6},{.3,.5,.7}};
    public static final class State {
        public final double[] load=new double[7],integral=new double[4];
        public final int[] stages=new int[7];
        public double oralLead,oralPcb,inhaledLead,inhaledPcb,inhaledBq;
        public double externalLower,externalUpper,committedLower,committedUpper;
        public double unassessedSeconds,unassessedKg,unassessedBq,unassessedChemicalSeconds;
        public double oralMassKg;
        public final java.util.Map<String,NuclearIntake> nuclearIntakes=new java.util.LinkedHashMap<>();
        public final java.util.Map<String,Double> unidentifiedOralKg=new java.util.LinkedHashMap<>();
        public double pulse,pulseLead,pulsePcb,pulseBq;
        public double grace=30,damage;
        public int unassessedReasons;
        public boolean approximate;
        public boolean notified,enabled=true;
        public long notificationTick=-1;
        public int severity() { int maximum=0;for(int stage:stages)maximum=Math.max(maximum,stage);return maximum; }
        public double workFactor() { return new double[]{1,.9,.75,.5}[severity()]; }
        public Snapshot snapshot() {
            return new Snapshot(load,integral,stages,new double[]{oralLead,oralPcb,inhaledLead,inhaledPcb,inhaledBq,
                    externalLower,externalUpper,committedLower,committedUpper,unassessedSeconds,unassessedKg,unassessedBq,
                    pulse,pulseLead,pulsePcb,pulseBq,grace,damage,unassessedChemicalSeconds,oralMassKg},notified,enabled,notificationTick,unassessedReasons,approximate,
                    nuclearIntakes,unidentifiedOralKg);
        }
        public static State restore(Snapshot snapshot) {
            var s=new State();
            System.arraycopy(snapshot.loads,0,s.load,0,7);System.arraycopy(snapshot.integrals,0,s.integral,0,4);
            System.arraycopy(snapshot.stages,0,s.stages,0,7);var v=snapshot.totals;
            s.oralLead=v[0];s.oralPcb=v[1];s.inhaledLead=v[2];s.inhaledPcb=v[3];s.inhaledBq=v[4];
            s.externalLower=v[5];s.externalUpper=v[6];s.committedLower=v[7];s.committedUpper=v[8];
            s.unassessedSeconds=v[9];s.unassessedKg=v[10];s.unassessedBq=v[11];
            s.pulse=v[12];s.pulseLead=v[13];s.pulsePcb=v[14];s.pulseBq=v[15];s.grace=v[16];s.damage=v[17];s.unassessedChemicalSeconds=v[18];s.oralMassKg=v[19];
            s.notified=snapshot.notified;s.enabled=snapshot.enabled;s.notificationTick=snapshot.notificationTick;
            s.unassessedReasons=snapshot.unassessedReasons;s.approximate=snapshot.approximate;
            s.nuclearIntakes.putAll(snapshot.nuclearIntakes);s.unidentifiedOralKg.putAll(snapshot.unidentifiedOralKg);return s;
        }
        void nuclear(String id,double oralLower,double oralUpper,double inhaledLower,double inhaledUpper) {
            var addition=new NuclearIntake(oralLower,oralUpper,inhaledLower,inhaledUpper);
            nuclearIntakes.merge(id,addition,(a,b)->new NuclearIntake(a.oralLower+b.oralLower,a.oralUpper+b.oralUpper,
                    a.inhaledLower+b.inhaledLower,a.inhaledUpper+b.inhaledUpper));
        }
    }
    public record NuclearIntake(double oralLower,double oralUpper,double inhaledLower,double inhaledUpper) {
        public NuclearIntake {
            if(!Double.isFinite(oralLower+oralUpper+inhaledLower+inhaledUpper)||oralLower<0||oralUpper<oralLower
                    ||inhaledLower<0||inhaledUpper<inhaledLower)throw new IllegalArgumentException("核種別の摂取Bqが不正です");
        }
    }
    /** NBTにも単体検証にも使う、Minecraftに依存しない保存境界。 */
    public record Snapshot(double[] loads,double[] integrals,int[] stages,double[] totals,boolean notified,
                           boolean enabled,long notificationTick,int unassessedReasons,boolean approximate,
                           java.util.Map<String,NuclearIntake> nuclearIntakes,java.util.Map<String,Double> unidentifiedOralKg) {
        public Snapshot {
            loads=loads.clone();integrals=integrals.clone();stages=stages.clone();totals=totals.clone();
            nuclearIntakes=java.util.Map.copyOf(nuclearIntakes);unidentifiedOralKg=java.util.Map.copyOf(unidentifiedOralKg);
            for(var entry:unidentifiedOralKg.entrySet())if(entry.getKey().isBlank()||!Double.isFinite(entry.getValue())||entry.getValue()<0)
                throw new IllegalArgumentException("未評価の経口物質量が不正です");
            if(loads.length!=7||integrals.length!=4||stages.length!=7||totals.length!=20)
                throw new IllegalArgumentException("曝露保存の列の長さが不正です");
            for(var array:new double[][]{loads,integrals,totals})for(double v:array)
                if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException("曝露保存の量が不正です");
            for(int stage:stages)if(stage<0||stage>3)throw new IllegalArgumentException("曝露段階が不正です");
            if(totals[6]<totals[5]||totals[8]<totals[7]||totals[16]>30||notificationTick< -1
                    ||notified!=(notificationTick>=0)||unassessedReasons<0||(unassessedReasons&~15)!=0)
                throw new IllegalArgumentException("曝露の収支・通知が不正です");
        }
        @Override public double[] loads() { return loads.clone(); }
        @Override public double[] integrals() { return integrals.clone(); }
        @Override public int[] stages() { return stages.clone(); }
        @Override public double[] totals() { return totals.clone(); }
    }
    public record Air(double co,double co2,double oxygenPa,double weatherDust,boolean indoor) {
        public Air {
            if(!Double.isFinite(co+co2+oxygenPa+weatherDust)||co<0||co2<0
                    ||oxygenPa<0||weatherDust<0)throw new IllegalArgumentException("曝露入力が不正です");
        }
    }
    private Exposure() {}
    static double approach(double start,double input,double seconds,double tau) {
        return start+(input-start)*-Math.expm1(-seconds/tau);
    }
    static double decayingIntegral(double start,double rate,double seconds) {
        return rate==0?start*seconds:start*-Math.expm1(-rate*seconds)/rate;
    }
    /** 既知の採掘元だけ成分を渡す。未知の成分に現在地の土を流用しない。 */
    public static void mining(State state,double leadKgKg,double pcbKgKg,double bqKg,boolean known) {
        state.pulse+=.5e-6/8;
        if(known) {
            state.pulseLead+=.5e-6/8*leadKgKg;
            state.pulsePcb+=.5e-6/8*pcbKgKg;
            state.pulseBq+=.5e-6/8*bqKg;
        }
    }
    public static double ventilation(Activity activity) {
        return switch(activity) { case SLEEP->6;case REST->8;case WALK->20;case HEAVY->35;case RUN->60;default->12; }/60000.0;
    }
    /** 最大1 REAL秒の固定入力。局所線量の未評価はこの空気入力の確定と独立。 */
    public static void advance(State state,Air air,Activity activity,double real,double game,double radioactiveDecay) {
        if(!Double.isFinite(real+game)||real<0||real>1||game<0||!Double.isFinite(radioactiveDecay)||radioactiveDecay<0)throw new IllegalArgumentException("曝露区間が不正です");
        double[] inputs={Math.max(0,air.co-5e-6)/200e-6,Math.max(0,air.co2-.001)/.03,
                Math.max(0,16000-air.oxygenPa)/6000,
                Math.max(0,(air.weatherDust*(air.indoor?.2:1)+state.pulse)*.25-45e-9)/150e-9};
        for(int i=0;i<3;i++)state.load[i]=approach(state.load[i],inputs[i],real,TAU[i]);
        double background=(air.weatherDust*(air.indoor?.2:1)*.25-45e-9)/150e-9;
        double pulse=state.pulse*.25/150e-9;
        double startDust=state.load[3],endDust=dustAt(startDust,background,pulse,real);
        if(Math.max(0,background+pulse)>startDust&&Math.max(0,background+pulse*Math.exp(-real/30))<endDust) {
            double lo=0,hi=real;
            for(int i=0;i<48;i++) {
                double mid=(lo+hi)/2;
                if(Math.max(0,background+pulse*Math.exp(-mid/30))>dustAt(startDust,background,pulse,mid))lo=mid;else hi=mid;
            }
            stage(state,3,dustAt(startDust,background,pulse,(lo+hi)/2));
        }
        state.load[3]=endDust;
        state.integral[0]+=air.co*real;state.integral[1]+=air.co2*real;
        state.integral[2]+=Math.max(0,16000-air.oxygenPa)*real;
        state.integral[3]+=(air.weatherDust*(air.indoor?.2:1)*real+decayingIntegral(state.pulse,1.0/30,real))*.25;
        double inhaled=ventilation(activity)*.25;
        double lead=decayingIntegral(state.pulseLead,1.0/30,real)*inhaled;
        double pcb=decayingIntegral(state.pulsePcb,1.0/30,real)*inhaled;
        double bq=decayingIntegral(state.pulseBq,1.0/30+radioactiveDecay,real)*inhaled;
        state.inhaledLead+=lead;state.inhaledPcb+=pcb;state.inhaledBq+=bq;
        double ratio=real==0?0:game/real;
        state.load[4]=continuousBurden(state.load[4],state.pulseLead*inhaled*.3,real,ratio,30*86400);
        state.load[5]*=Math.exp(-Math.log(2)*game/(7*86400));
        // PCBの吸入係数は未承認。吸入量を記録し、経口係数を流用しない。
        state.unassessedKg+=pcb;if(pcb>0)state.unassessedReasons|=2;
        double decay=Math.exp(-real/30);
        state.pulse*=decay;state.pulseLead*=decay;state.pulsePcb*=decay;state.pulseBq*=decay*Math.exp(-radioactiveDecay*real);
        refresh(state);
    }
    private static double continuousBurden(double initial,double source,double real,double gameRate,double half) {
        double k=Math.log(2)*gameRate/half,difference=1.0/30-k;
        return initial*Math.exp(-k*real)+source*Math.exp(-k*real)*decayingIntegral(1,difference,real);
    }
    private static double dustAt(double initial,double background,double pulse,double real) {
        double positive=real;
        if(background<0)positive=pulse<=-background?0:Math.min(real,30*Math.log(pulse/-background));
        return Math.max(0,(initial*Math.exp(-positive/300)+background*-Math.expm1(-positive/300)
                +pulse*Math.exp(-positive/300)*decayingIntegral(1,1.0/30-1.0/300,positive))*Math.exp(-(real-positive)/300));
    }
    public static void oral(State state,double lead,double pcb,double lowerSv,double upperSv) {
        if(!Double.isFinite(lead+pcb+lowerSv+upperSv)||Math.min(Math.min(lead,pcb),lowerSv)<0||upperSv<lowerSv)
            throw new IllegalArgumentException("経口曝露量が不正です");
        state.oralLead+=lead;state.oralPcb+=pcb;state.load[4]+=.1*lead;state.load[5]+=pcb;
        state.committedLower+=lowerSv;state.committedUpper+=upperSv;refresh(state);
    }
    public static double pointRate(double bq,double distance,double mineralMeters,double gamma) {
        return gamma*bq/Math.pow(Math.max(.5,distance),2)
                *Math.max(.01,Math.exp(-.008*2300*mineralMeters));
    }
    public record Source(double lowerRate,double upperRate,double decayPerRealSecond) {
        public Source {
            if(lowerRate<0||upperRate<lowerRate||decayPerRealSecond<0||!Double.isFinite(lowerRate+upperRate+decayPerRealSecond))
                throw new IllegalArgumentException("線量率の単位または上下端が不正です");
        }
    }
    /** 静的な地面はdecay=0、時刻付き線源はGAME/REALを換算した崩壊定数。 */
    public static void radiation(State state,java.util.List<Source> sources,double real,double game,boolean known) {
        if(!Double.isFinite(real+game)||real<0||game<0)throw new IllegalArgumentException("照射区間が不正です");
        double upperAtStart=0,upperAtEnd=0;
        for(var source:sources) {
            state.externalLower+=decayingIntegral(source.lowerRate,source.decayPerRealSecond,real);
            state.externalUpper+=decayingIntegral(source.upperRate,source.decayPerRealSecond,real);
            upperAtStart+=source.upperRate;upperAtEnd+=source.upperRate*Math.exp(-source.decayPerRealSecond*real);
        }
        double active=0;
        if(!known) { state.unassessedSeconds+=real;state.unassessedReasons|=1;active=real; }
        else if(upperAtEnd>1e-7)active=real;
        else if(upperAtStart>1e-7) {
            double lo=0,hi=real;
            for(int i=0;i<48;i++) {
                double mid=(lo+hi)/2,rate=0;
                for(var source:sources)rate+=source.upperRate*Math.exp(-source.decayPerRealSecond*mid);
                if(rate>1e-7)lo=mid;else hi=mid;
            }
            active=hi;
        }
        for(var source:sources)state.load[6]+=decayingIntegral(source.upperRate,source.decayPerRealSecond,active);
        stage(state,6,state.load[6]);
        double recovery=real-active;
        double k=real==0?0:Math.log(2)*(game/real)/86400;
        double initial=state.load[6],result=burden(initial,sources,active,recovery,k);
        double initialRate=0,finalRate=0;
        for(var source:sources) {
            initialRate+=source.upperRate*Math.exp(-source.decayPerRealSecond*active);
            finalRate+=source.upperRate*Math.exp(-source.decayPerRealSecond*real);
        }
        if(initialRate>k*initial&&finalRate<k*result) {
            double lo=0,hi=recovery;
            for(int i=0;i<48;i++) {
                double mid=(lo+hi)/2,rate=0;
                for(var source:sources)rate+=source.upperRate*Math.exp(-source.decayPerRealSecond*(active+mid));
                if(rate>k*burden(initial,sources,active,mid,k))lo=mid;else hi=mid;
            }
            stage(state,6,burden(initial,sources,active,(lo+hi)/2,k));
        }
        state.load[6]=result;
        refresh(state);
    }
    private static double burden(double initial,java.util.List<Source> sources,double offset,double duration,double k) {
        double result=initial*Math.exp(-k*duration);
        for(var source:sources)result+=source.upperRate*Math.exp(-source.decayPerRealSecond*offset)
                *Math.exp(-k*duration)*decayingIntegral(1,source.decayPerRealSecond-k,duration);
        return result;
    }
    private static void stage(State state,int index,double value) {
        int stage=state.stages[index];
        while(stage<3&&value>=LIMITS[index][stage])stage++;
        while(stage>0&&value<LIMITS[index][stage-1]*.8)stage--;
        state.stages[index]=stage;
    }
    static void refresh(State state) {
        for(int i=0;i<state.stages.length;i++)stage(state,i,state.load[i]);
    }
}
