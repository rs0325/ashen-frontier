package io.github.rs0325.ashenfrontier.survival;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/** 製品の微分関数/保存状態を期待値に使わず、熱流と長期予算から別に積分する。 */
class GeneratedPhysiologyTest {
    static final class Oracle {
        double core=36.8,skin=33.7,water=42,energy=20e6,protein=.12,fat=.08,debt=20000,fatigue;
        void step(double seconds,double game,double room,Activity activity) {
            double met=switch(activity) { case REST->1;case WALK->2;case SLEEP->.7;default->throw new AssertionError(); };
            double clo=activity==Activity.SLEEP?2:1;
            int steps=(int)Math.ceil(seconds/.002);double dt=seconds/steps;
            for(int i=0;i<steps;i++) {
                double flow=Math.max(.5,Math.min(90,(6.3+120*Math.max(0,core-36.8))/(1+.5*Math.max(0,33.7-skin))));
                double internal=1.8*(5.28+1.163*flow)*(core-skin);
                double convection=Math.max(3,Math.max(8.6*Math.pow(.1,.53),5.66*Math.pow(Math.max(0,met-.85),.39)));
                double insulation=.155*clo,area=1+.15*clo;
                double dry=1.8*(skin-room)/(insulation+1/(area*(convection+4.7)));
                double shiver=Math.min(19.4*Math.max(0,33.7-skin)*Math.max(0,36.8-core),Math.max(0,4-met)*58.2);
                double metabolism=58.2*met+shiver;
                double vapor=.5*Math.exp(18.6686-4030.183/(room+235));
                double breathing=1.8*Math.max(0,.0023*metabolism*(44-vapor));
                double diffusionCapacity=1.8*Math.max(0,(Math.exp(18.6686-4030.183/(skin+235))-vapor)
                        /(1/(2.2*area*convection)+insulation/(2.2*.45)));
                double sweat=Math.min(500,170*Math.max(0,.9*(core-36.8)+.1*(skin-33.7))
                        *Math.exp(Math.max(0,skin-33.7)/10.7))*1.8/3600000;
                double evaporation=Math.min(2448000*sweat,.64*diffusionCapacity)+.06*diffusionCapacity;
                double dc=(1.8*metabolism-internal-breathing-1.8*.0014*metabolism*(34-room))/219996;
                double ds=(internal-dry-evaporation)/24444;
                core+=dc*dt;skin+=ds*dt;
                water-=Math.max(0,sweat-.007826326/3600)*dt
                        +Math.max(0,(breathing+.06*diffusionCapacity)/2448000-.028215607/3600)*dt;
            }
            water-=3*game/86400;energy-=10e6*game/86400;protein-=.06*game/86400;fat-=.04*game/86400;
            debt=Math.max(0,Math.min(86400,debt+(activity==Activity.SLEEP?-1:.5)*game));
            fatigue=Math.max(0,Math.min(100,fatigue+(activity==Activity.SLEEP?-12:activity==Activity.REST?-8:4)*game/3600));
        }
    }
    @Test void fixedSeedsAgainstSeparateFineStepEnergyBalance() {
        int operations=0,restSleep=0,simultaneous=0,feed=0;
        for(int seed=0;seed<128;seed++) {
            var rng=new Random(12420261126L+seed);var body=new Body();body.sleepDebt=20000;var expected=new Oracle();
            Activity previous=Activity.REST;
            for(int n=0;n<32;n++) {
                var activity=switch(n%3) {case 0->Activity.REST;case 1->Activity.SLEEP;default->Activity.WALK;};
                double real=.1+.9*rng.nextDouble(),game=real*(n<16?72:144),room=21+rng.nextDouble()*4;
                expected.step(real,game,room,activity);
                Physiology.advance(body,PhysiologyTest.air(room,.5,.1,false),activity,real,game);operations++;
                if(previous==Activity.REST&&activity==Activity.SLEEP)restSleep++;
                previous=activity;
                if(n%8==0) {
                    double before=expected.water;expected.water=Math.min(42,before+.02);
                    expected.energy=Math.min(20e6,expected.energy+1000);
                    expected.protein=Math.min(.18,expected.protein+.0001);expected.fat=Math.min(.12,expected.fat+.0001);
                    Physiology.ingest(body,.02,1000,.0001,.0001,false,false);feed++;
                    Physiology.advance(body,PhysiologyTest.air(room,.5,.1,false),activity,0,0);simultaneous++;
                }
                assertEquals(expected.core+273.15,body.core,2e-5,"深部 seed="+seed+" op="+n);
                assertEquals(expected.skin+273.15,body.skin,2e-5,"皮膚 seed="+seed+" op="+n);
                assertEquals(expected.water,body.water,2e-7);
                assertEquals(expected.energy,body.energy,1e-6);assertEquals(expected.protein,body.protein,1e-12);
                assertEquals(expected.fat,body.fat,1e-12);assertEquals(expected.debt,body.sleepDebt,1e-8);
                assertEquals(expected.fatigue,body.fatigue,1e-8);
            }
        }
        assertTrue(operations>=128*32);assertTrue(restSleep>=32);assertTrue(simultaneous>=64);assertTrue(feed>=32);
    }
}
