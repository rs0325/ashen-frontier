package io.github.rs0325.ashenfrontier.survival;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/** 期待値には製品の積分関数・保存台帳・線源サンプラーを使わない。 */
class GeneratedExposureTest {
    @Test void fixedSeedsCoverReplaySavePartialIntakeAndRadiation() {
        int splits=0,removed=0,saved=0,days=0,replayed=0,unknown=0,water=0,geometry=0;
        for(int seed=0;seed<1024;seed++) {
            var random=new Random(126270000L+seed);var actual=new Exposure.State();
            double co=0,lead=0,pcb=0,gamma=0,oralLead=0,oralPcb=0,committed=0,external=0,missing=0;
            double gameRate=72;
            for(int operation=0;operation<64;operation++) {
                int kind=operation%16;double dt=.05+.95*random.nextDouble(),dg=dt*gameRate;
                switch(kind) {
                    case 0,1,2,3,12,13,14 -> {
                        double fraction=kind==3?0:random.nextDouble()*.0015;
                        double u=Math.max(0,fraction-.000005)/.0002;
                        double cut=dt*random.nextDouble();
                        var air=new Exposure.Air(fraction,.000420,21200,0,false);
                        Exposure.advance(actual,air,Activity.REST,cut,cut*gameRate,0);
                        Exposure.advance(actual,air,Activity.REST,dt-cut,(dt-cut)*gameRate,0);
                        co=u+(co-u)*Math.exp(-dt/120);
                        lead*=Math.pow(2,-dg/(30*86400));pcb*=Math.pow(2,-dg/(7*86400));
                        splits++;if(kind==3)removed++;
                    }
                    case 4,15 -> { actual=Exposure.State.restore(actual.snapshot());saved++; }
                    case 5 -> { gameRate=random.nextBoolean()?144:36;days++; }
                    case 6 -> {
                        Exposure.advance(actual,new Exposure.Air(0,.000420,21200,0,false),Activity.REST,0,0,0);
                        Exposure.radiation(actual,List.of(),0,0,true);replayed++;
                    }
                    case 7 -> { Exposure.radiation(actual,List.of(),dt,dg,false);missing+=dt;unknown++; }
                    case 8 -> {
                        // 0.5kgの内容物を0.25kgずつ。経口量は独立の実kg/Bqから求める。
                        for(int n=0;n<2;n++)Exposure.oral(actual,.25*1e-8,.25*5e-10,5*1.3e-8,5*1.3e-8);
                        double addedLead=.5e-8,addedPcb=.5*5e-10;
                        oralLead+=addedLead;oralPcb+=addedPcb;lead+=.1*addedLead;pcb+=addedPcb;committed+=10*1.3e-8;water++;
                    }
                    case 9,11 -> {
                        double distance=.5+random.nextDouble()*7.5,thickness=kind==9?1:0;
                        double expectedRate=9.27e-14/3600*1e6/(distance*distance)*Math.max(.01,Math.exp(-18.4*thickness));
                        double actualRate=Exposure.pointRate(1e6,distance,thickness,2.575e-17);
                        Exposure.radiation(actual,List.of(new Exposure.Source(actualRate,actualRate,0)),dt,dg,true);
                        double k=Math.log(2)*gameRate/86400;
                        gamma=gamma*Math.exp(-k*dt)+expectedRate/k*(1-Math.exp(-k*dt));external+=expectedRate*dt;geometry++;
                    }
                    case 10 -> {
                        double amount=random.nextDouble()*1e-8;
                        Exposure.oral(actual,amount,amount/10,0,0);
                        oralLead+=amount;oralPcb+=amount/10;lead+=amount*.1;pcb+=amount/10;
                    }
                    default -> fail("未定義の生成操作");
                }
                String context="seed="+seed+" operation="+operation;
                assertEquals(co,actual.load[0],5e-13,context);
                assertEquals(lead,actual.load[4],1e-20,context);assertEquals(pcb,actual.load[5],1e-20,context);
                assertEquals(gamma,actual.load[6],1e-19,context);
                assertEquals(oralLead,actual.oralLead,1e-20,context);assertEquals(oralPcb,actual.oralPcb,1e-20,context);
                assertEquals(committed,actual.committedLower,1e-19,context);assertEquals(committed,actual.committedUpper,1e-19,context);
                assertEquals(external,actual.externalLower,1e-19,context);assertEquals(external,actual.externalUpper,1e-19,context);
                assertEquals(missing,actual.unassessedSeconds,1e-12,context);
            }
        }
        assertTrue(splits>=8192);assertTrue(removed>=2048);assertTrue(saved>=1024);assertTrue(days>=512);
        assertTrue(replayed>=1024);assertTrue(unknown>=512);assertTrue(water>=512);assertTrue(geometry>=512);
        System.out.printf("#126 generated seeds=1024 operations=65536 splits=%d removal=%d save=%d day=%d replay=%d unknown=%d partial_water=%d geometry=%d%n",
                splits,removed,saved,days,replayed,unknown,water,geometry);
    }
}
