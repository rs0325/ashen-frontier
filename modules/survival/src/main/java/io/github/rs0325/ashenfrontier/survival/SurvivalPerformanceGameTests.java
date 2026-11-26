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
        var people=new ArrayList<SurvivalGameTests.Person>();long[] due=new long[4];
        var threads=java.lang.management.ManagementFactory.getThreadMXBean();
        h.assertTrue(threads.isCurrentThreadCpuTimeSupported(),"CPU時間を取得できる");
        if(!threads.isThreadCpuTimeEnabled())threads.setThreadCpuTimeEnabled(true);
        long[] cpu=new long[1999],wall=new long[1999],previous=new long[3];int[] count={0};
        try {
            for(int pass=0;pass<2;pass++) {
                java.util.Arrays.fill(due,0);java.util.Arrays.fill(previous,0);
                boolean measured=pass==1;
                io.github.rs0325.ashenfrontier.climate.EnvironmentPerformanceGameTests.runWorkload(h,(level,players)->{
                long cpuNow=threads.getCurrentThreadCpuTime(),wallNow=System.nanoTime();
                if(measured&&previous[2]>500&&previous[2]<2500) {
                    int n=count[0]++;cpu[n]=cpuNow-previous[0];wall[n]=wallNow-previous[1];
                }
                previous[0]=cpuNow;previous[1]=wallNow;previous[2]=level.getGameTime();
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
                    if(level.getGameTime()>=due[n]) {
                        person.runtime.work(deadline,8);due[n]=level.getGameTime()+(person.runtime.pending?1:20);
                    }
                }
                io.github.rs0325.ashenfrontier.core.environment.EnvironmentBudget.charge(level,System.nanoTime()-bodyStart);
            });
                for(var person:people)h.assertTrue(person.runtime.data.cursor>=1900,"実環境で生理も継続前進する cursor="+person.runtime.data.cursor);
                people.forEach(SurvivalGameTests.Person::close);people.clear();
            }
        } finally { people.forEach(SurvivalGameTests.Person::close); }
        h.assertTrue(count[0]==1999,"同じ仕事の連続する境界から1999区間を実測");
        java.util.Arrays.sort(cpu);java.util.Arrays.sort(wall);
        com.mojang.logging.LogUtils.getLogger().info("M3-124 own_cpu samples={} p95_ms={} p99_ms={} wall_p95_ms={} wall_p99_ms={}",
                count[0],cpu[1899]/1e6,cpu[1979]/1e6,wall[1899]/1e6,wall[1979]/1e6);
        if(!System.getProperty("os.name").startsWith("Windows"))
            h.assertTrue(cpu[1899]<1_000_000&&cpu[1979]<3_000_000,"生理を含むCPU予算");
        else com.mojang.logging.LogUtils.getLogger().info("M3-124 raw CPUはWindowsで量子化。#99 merge後ThreadCpuClockへ統合する");
        h.succeed();
    }
}
