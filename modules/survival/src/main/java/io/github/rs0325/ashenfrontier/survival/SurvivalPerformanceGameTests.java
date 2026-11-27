package io.github.rs0325.ashenfrontier.survival;

import java.util.ArrayList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AshenFrontierSurvival.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurvivalPerformanceGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierSurvival.MOD_ID,timeoutTicks=400,batch="performance_124")
    public static void combinedRealWeatherRoomsStationsAndPhysiology(GameTestHelper h) throws Exception {
        var people=new ArrayList<SurvivalGameTests.Person>();long[] due=new long[4];int[] intakes={0};
        try {
            for(int pass=0;pass<2;pass++) {
                java.util.Arrays.fill(due,0);
                String measurement=pass==0?"physiology_weather_rooms_stations_warmup":"physiology_weather_rooms_stations";
                io.github.rs0325.ashenfrontier.climate.EnvironmentPerformanceGameTests.runWorkload(h,(level,players)->{
                if(people.isEmpty())for(int n=0;n<4;n++)people.add(new SurvivalGameTests.Person(level,players.get(n).blockPosition(),"load124_"+n));
                long bodyStart=System.nanoTime();
                long deadline=bodyStart+Math.min(100_000,io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget.remaining(level));
                if(level.getGameTime()==2500)for(var person:people) {
                    var runtime=person.runtime;
                    com.mojang.logging.LogUtils.getLogger().info("M3-124 body cursor={} observed={} queue={} head={} remaining={}",
                            runtime.data.cursor,runtime.data.observed,runtime.data.frames.size(),
                            io.github.rs0325.ashenfrontier.climate.room.RoomAir.get(level).sample(person.player.blockPosition().above(),
                            runtime.data.cursor).status(),io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget.remaining(level));
                }
                int start=(int)(level.getGameTime()%4);
                for(int k=0;k<4;k++) {
                    int n=(start+k)%4;var person=people.get(n);
                    if(level.getGameTime()%200==100&&person.runtime.data.body.energy<20e6) {
                        person.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,IntakeGameTests.food("canned_food",1,level.getGameTime()));
                        if(IntakeGameTests.action(person,io.github.rs0325.ashenfrontier.core.food.FoodUse.Action.START)
                                &&IntakeGameTests.action(person,io.github.rs0325.ashenfrontier.core.food.FoodUse.Action.FINISH))intakes[0]++;
                    }
                    if(level.getGameTime()>=due[n]) {
                        person.runtime.work(deadline,8);due[n]=level.getGameTime()+(person.runtime.pending?1:20);
                    }
                }
                io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget.charge(level,System.nanoTime()-bodyStart);
            },measurement);
                for(var person:people)h.assertTrue(person.runtime.data.cursor>=1900,"実環境で生理も継続前進する cursor="+person.runtime.data.cursor);
                people.forEach(SurvivalGameTests.Person::close);people.clear();
            }
            h.assertTrue(intakes[0]>=4,"統合負荷に実際に確定した摂取が含まれる count="+intakes[0]);
            com.mojang.logging.LogUtils.getLogger().info("M3-125 integrated actual_intakes={}",intakes[0]);
        } finally { people.forEach(SurvivalGameTests.Person::close); }
        h.succeed();
    }
}
