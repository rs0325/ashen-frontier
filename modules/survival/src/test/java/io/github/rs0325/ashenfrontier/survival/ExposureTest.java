package io.github.rs0325.ashenfrontier.survival;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ExposureTest {
    private static Exposure.Air air(double co,double co2,double oxygen,double dust) {
        return new Exposure.Air(co,co2,oxygen,dust,false);
    }
    @Test void gasThresholdsAndSourceRemoval() {
        var s=new Exposure.State();
        for(int i=0;i<22;i++)Exposure.advance(s,air(.0012,420e-6,21200,0),Activity.REST,1,72,0);
        assertEquals(3,s.stages[0]);assertEquals(5.975*(1-Math.exp(-22.0/120)),s.load[0],1e-14);
        double before=s.load[0];
        for(int i=0;i<120;i++)Exposure.advance(s,air(0,420e-6,21200,0),Activity.REST,1,72,0);
        assertEquals(before/Math.E,s.load[0],1e-14);assertTrue(s.stages[0]<3);
        assertEquals(.0012*22,s.integral[0],1e-14);
    }
    @Test void pressureAndKnownIndoorDust() {
        var high=new Exposure.State();var low=new Exposure.State();
        for(int i=0;i<60;i++) {
            Exposure.advance(high,air(0,420e-6,.2095*40000,840e-9),Activity.WALK,1,72,0);
            Exposure.advance(low,new Exposure.Air(0,420e-6,.2095*101325,840e-9,true),Activity.REST,1,72,0);
        }
        assertTrue(high.stages[2]>=2);assertTrue(high.stages[3]>=1);
        assertEquals(0,low.load[2]);assertEquals(0,low.load[3]);
    }
    @Test void chemicalsRecoverButRouteTotalsAndCommittedDoseRemain() {
        var s=new Exposure.State();Exposure.oral(s,1e-5,2e-6,1.3e-6,1.3013e-6);
        for(int i=0;i<30;i++)Exposure.advance(s,air(0,420e-6,21200,0),Activity.REST,1,86400,0);
        assertEquals(.5e-6,s.load[4],1e-20);assertEquals(2e-6*Math.pow(.5,30.0/7),s.load[5],1e-20);
        assertEquals(1e-5,s.oralLead);assertEquals(1.3e-6,s.committedLower);assertEquals(1.3013e-6,s.committedUpper);
    }
    @Test void gammaUsesRealSecondsAndActualMetres() {
        assertEquals(9.27e-14*1e6/3600,Exposure.pointRate(1e6,1,0,2.575e-17),1e-24);
        assertEquals(Exposure.pointRate(1e6,1,0,2.575e-17)/4,Exposure.pointRate(1e6,2,0,2.575e-17),1e-24);
        assertEquals(Exposure.pointRate(1e6,1,0,2.575e-17)*.01,Exposure.pointRate(1e6,1,1,2.575e-17),1e-24);
    }
    @Test void generatedGasSplitsUseIndependentOracle() {
        int splits=0,removals=0,dayChanges=0;
        for(int seed=0;seed<1024;seed++) {
            var random=new Random(12620261127L+seed);var s=new Exposure.State();double expected=0;
            for(int operation=0;operation<64;operation++) {
                double co=operation%4==0?0:random.nextDouble()*.002;
                double input=Math.max(0,co-5e-6)/200e-6;
                double seconds=.05+random.nextDouble()*.95,gameRate=operation%2==0?72:144;
                double left=seconds*random.nextDouble();
                Exposure.advance(s,air(co,420e-6,21200,0),Activity.REST,left,left*gameRate,0);
                Exposure.advance(s,air(co,420e-6,21200,0),Activity.REST,seconds-left,(seconds-left)*gameRate,0);
                expected=input+(expected-input)*Math.exp(-seconds/120);
                assertEquals(expected,s.load[0],5e-13,"seed="+seed+" operation="+operation);
                splits++;if(co==0)removals++;if(operation%2==0)dayChanges++;
            }
        }
        assertTrue(splits>=8192);assertTrue(removals>=2048);assertTrue(dayChanges>=512);
    }
    @Test void miningPulseSplitsConserveKnownInhaledMass() {
        var one=new Exposure.State();var split=new Exposure.State();
        Exposure.mining(one,.001,1e-5,1000,true);Exposure.mining(split,.001,1e-5,1000,true);
        Exposure.advance(one,air(0,420e-6,21200,0),Activity.HEAVY,1,72,0);
        for(int i=0;i<20;i++)Exposure.advance(split,air(0,420e-6,21200,0),Activity.HEAVY,.05,3.6,0);
        assertEquals(one.load[3],split.load[3],1e-14);assertEquals(one.inhaledLead,split.inhaledLead,1e-25);
        double expected=.5e-6/8*.001*.25*(35.0/60000)*30*(1-Math.exp(-1.0/30));
        assertEquals(expected,one.inhaledLead,1e-25);assertEquals(one.load[4],split.load[4],1e-25);
    }
    @Test void exponentialRadiationAndRecoveryAreSplitInvariant() {
        var random=new Random(126137133);
        for(int i=0;i<512;i++) {
            double lambda=.0001+random.nextDouble()*.02,rate=1e-8+random.nextDouble()*2e-6;
            double seconds=1+random.nextDouble()*500,gameRate=20+random.nextDouble()*150;
            var one=new Exposure.State();var split=new Exposure.State();one.load[6]=split.load[6]=.29;
            Exposure.radiation(one,java.util.List.of(new Exposure.Source(rate,rate,lambda)),seconds,seconds*gameRate,true);
            double t=0;
            while(t<seconds) {
                double dt=Math.min(.05,seconds-t),current=rate*Math.exp(-lambda*t);
                Exposure.radiation(split,java.util.List.of(new Exposure.Source(current,current,lambda)),dt,dt*gameRate,true);t+=dt;
            }
            double expected=rate/lambda*(1-Math.exp(-lambda*seconds));
            assertEquals(expected,one.externalUpper,1e-16);assertEquals(one.externalUpper,split.externalUpper,1e-15);
            assertEquals(one.load[6],split.load[6],1e-11);assertEquals(one.stages[6],split.stages[6]);
            assertEquals(0,one.committedUpper);
        }
    }
    @Test void oneSecondGasHoldReportsErrorAgainstFineAndClosedForm() {
        double[] values=new double[2];double horizon=300;
        for(int pass=0;pass<2;pass++) {
            double dt=pass==0?1:.1;var s=new Exposure.State();
            for(int step=0;step<(int)(horizon/dt);step++) {
                double co2=.04+(.000420-.04)*Math.exp(-.01*step*dt);
                Exposure.advance(s,new Exposure.Air(0,co2,21200,0,false),Activity.REST,dt,dt*72,0);
            }
            values[pass]=s.load[1];
        }
        double start=Math.log((.04-.000420)/(.04-.001))/.01,d=horizon-start;
        double expected=(.04-.001)/.03*(1-Math.exp(-d/30))
                -(.04-.000420)/.03*Math.exp(-.01*start)*(Math.exp(-.01*d)-Math.exp(-d/30))/(1-.01*30);
        assertTrue(Math.abs(values[1]-expected)<Math.abs(values[0]-expected));
        System.out.printf("#126 CO2 hold REAL_seconds=300 one_second=%.12f tenth_second=%.12f closed_form=%.12f error_1s=%.12g error_0.1s=%.12g%n",
                values[0],values[1],expected,Math.abs(values[0]-expected),Math.abs(values[1]-expected));
    }
}
