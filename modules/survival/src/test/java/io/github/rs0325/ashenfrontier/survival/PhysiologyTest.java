package io.github.rs0325.ashenfrontier.survival;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhysiologyTest {
    static Physiology.Air air(double c,double humidity,double wind,boolean wet) {
        return new Physiology.Air(c+273.15,humidity,wind,0,101325,21200,0,true,wet);
    }
    @Test void noEvaporativeCoolingWithoutWaterAndSplitAtDepletion() {
        var hot=air(40,.2,.1,false);
        var full=new Body();full.core=312.15;full.skin=308.15;
        var dry=full.copy();dry.water=0;
        Physiology.advance(full,hot,Activity.HEAVY,1,0);
        Physiology.advance(dry,hot,Activity.HEAVY,1,0);
        assertTrue(dry.skin>full.skin,"水分0の皮膚を発汗で冷やさない");
        assertTrue(dry.core>full.core,"水分0では水蒸気の呼吸放熱もない");
        var small=new Body();small.core=312.15;small.skin=308.15;small.water=.0001;
        var split=small.copy();
        Physiology.advance(small,hot,Activity.HEAVY,4,288);
        for(int i=0;i<80;i++)Physiology.advance(split,hot,Activity.HEAVY,.05,3.6);
        assertEquals(0,small.water);assertEquals(0,split.water);
        assertEquals(split.core,small.core,2e-6);assertEquals(split.skin,small.skin,2e-6);
    }
    @Test void comfortableEquilibriumAndBedInsulation() {
        var rest=new Body();Physiology.advance(rest,air(22,.5,.1,false),Activity.REST,28800,0);
        assertEquals(36.83025289,rest.core-273.15,.00001);
        var sleep=new Body();Physiology.advance(sleep,air(22,.5,.1,false),Activity.SLEEP,28800,0);
        assertEquals(36.8028,sleep.core-273.15,.001);
        assertEquals(0,rest.damage);assertEquals(0,sleep.damage);
    }
    @Test void coldWindAndWetAccelerateCooling() {
        var still=new Body();var wind=new Body();var wet=new Body();
        Physiology.advance(still,air(-10,.5,.1,false),Activity.REST,1800,0);
        Physiology.advance(wind,air(-10,.5,5,false),Activity.REST,1800,0);
        Physiology.advance(wet,air(-10,.5,5,true),Activity.REST,1800,0);
        assertTrue(still.core>wind.core);assertTrue(wind.core>wet.core);
        assertTrue(wet.level(0)>=2);assertTrue(wet.skin<still.skin);
    }
    @Test void humidHeatLimitsEvaporationAndWarnsBeforeDamage() {
        var dry=new Body();var humid=new Body();
        Physiology.advance(dry,air(40,.2,.1,false),Activity.HEAVY,2000,0);
        Physiology.advance(humid,air(40,.8,.1,false),Activity.HEAVY,2000,0);
        assertTrue(humid.core>dry.core);assertTrue(humid.level(1)>=2);assertTrue(humid.damage>0);
        assertTrue(humid.water<42);
    }
    @Test void dayBudgetIsNotThermalHeatAndIntakeRestoresReserves() {
        var a=new Body();var b=new Body();
        Physiology.advance(a,air(22,.5,.1,false),Activity.REST,1,0);
        Physiology.advance(b,air(22,.5,.1,false),Activity.REST,1,43200);
        assertEquals(a.core,b.core,1e-12);assertEquals(a.skin,b.skin,1e-12);
        assertEquals(40.5,b.water,1e-8);assertEquals(15e6,b.energy,1e-8);
        assertEquals(.09,b.protein,1e-12);assertEquals(.06,b.fat,1e-12);
        Physiology.ingest(b,1,5e6,.09,.06,true,true);
        assertEquals(41.5,b.water,1e-8);assertEquals(20e6,b.energy,1e-8);
        assertEquals(0,b.vitaminC);assertEquals(0,b.vitaminFat);
    }
    @Test void shortagesVitaminsAndLongIntervalsRemainFinite() {
        var b=new Body();
        Physiology.advance(b,air(22,.5,.1,false),Activity.REST,3600,86400*28);
        for(double value:b.values())assertTrue(Double.isFinite(value));
        assertEquals(0,b.energy);assertEquals(0,b.protein);assertEquals(0,b.fat);
        assertEquals(3,b.level(6));assertEquals(3,b.level(7));assertTrue(b.damage>0);
    }
    @Test void restIsNotSleepAndSevereInjuryIsNotSleepHealed() {
        var rest=new Body();rest.fatigue=50;rest.sleepDebt=36000;rest.mental=60;rest.injury=50;
        var sleep=rest.copy();
        Physiology.advance(rest,air(22,.5,.1,false),Activity.REST,1,3600);
        Physiology.advance(sleep,air(22,.5,.1,false),Activity.SLEEP,1,3600);
        assertEquals(37800,rest.sleepDebt);assertEquals(32400,sleep.sleepDebt);
        assertEquals(42,rest.fatigue);assertEquals(38,sleep.fatigue);
        assertEquals(50,sleep.injury);assertTrue(sleep.mental>rest.mental);
        Physiology.wound(rest,2);assertEquals(60,rest.injury);
        for(int i=0;i<10;i++)Physiology.jump(rest);
        assertTrue(rest.exhausted);assertEquals(0,rest.stamina);
    }
    @Test void temperatureWarningsFollowApprovedStrictBoundaries() {
        var body=new Body();body.core=309.15;Physiology.refresh(body);assertEquals(0,body.level(0));
        body.core=309.14;Physiology.refresh(body);assertEquals(1,body.level(0));
        body.core=309.34;Physiology.refresh(body);assertEquals(1,body.level(0));
        body.core=309.35;Physiology.refresh(body);assertEquals(0,body.level(0));
        body.core=310.65;Physiology.refresh(body);assertEquals(0,body.level(1));
        body.core=310.66;Physiology.refresh(body);assertEquals(1,body.level(1));
        body.core=312.15;Physiology.refresh(body);assertEquals(3,body.level(1));
    }
    @Test void intervalPartitionAndNoInventedSleepTime() {
        var a=new Body();var b=new Body();var env=air(8,.8,2,false);
        Physiology.advance(a,env,Activity.WALK,60,4320);
        for(int i=0;i<60;i++)Physiology.advance(b,env,Activity.WALK,1,72);
        assertArrayEquals(a.values(),b.values(),1e-8);
        assertThrows(IllegalArgumentException.class,()->Physiology.advance(b,env,Activity.SLEEP,0,28800));
    }
}
