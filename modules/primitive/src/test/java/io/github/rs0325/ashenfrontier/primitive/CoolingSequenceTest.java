package io.github.rs0325.ashenfrontier.primitive;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/** 期待値は設計の指数式から独立に積算し、製品の冷却関数・保存値から作らない。 */
class CoolingSequenceTest {
    @Test void generatedUnloadedRestoreSameTimeAndDayLength() {
        int restoredLong=0,sameTime=0,dayChanges=0;
        for(int column=0;column<128;column++) {
            var random=new Random(13520261126L+column);
            var heat=new HearthHeat();
            double expected=900+random.nextDouble()*200, fuel=16_000_000;
            heat.restore(fuel,16_000_000,expected);
            long day=24000;
            for(int operation=0;operation<32;operation++) {
                if(operation%8==0) { day=day==24000?12000:24000;dayChanges++; }
                long ticks=operation%8==1?24L*24000*(1+random.nextInt(10)):random.nextInt(3000);
                double ambient=265+random.nextDouble()*55;
                double seconds=ticks*86400.0/day;
                expected=ambient+(expected-ambient)*Math.exp(-seconds/7200);
                var result=heat.advance(0,seconds,ambient,false,false);
                assertEquals(expected,heat.temperatureK(),1e-10);
                assertEquals(0,result.chemicalJoules());assertEquals(fuel,heat.fuelJoules());
                if(operation%8==1) {
                    var restored=new HearthHeat();
                    restored.restore(heat.fuelJoules(),heat.heatingValueJKg(),heat.temperatureK());
                    heat=restored;restoredLong++;
                    assertEquals(expected,heat.temperatureK(),1e-10);
                }
                if(operation%4==2) {
                    var again=heat.advance(0,0,ambient,false,false);
                    sameTime++;
                    assertEquals(0,again.storedJoules());
                    assertEquals(expected,heat.temperatureK(),1e-10);
                }
            }
        }
        assertTrue(restoredLong>=32);assertTrue(sameTime>=64);assertTrue(dayChanges>=32);
        System.out.printf("M3-135 cooling oracle columns=128 operations=4096 unloaded_long_restore=%d same_time=%d day_changes=%d%n",
                restoredLong,sameTime,dayChanges);
    }
}
