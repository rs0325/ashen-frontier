package io.github.rs0325.ashenfrontier.climate.room;

import jdk.jfr.Recording;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Label;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.LongByReference;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.io.IOException;

/** GameTest専用の診断。GC/VM停止/Java待機をJFRへ、各作業の壁時計/CPU時間をCSVへ残す。 */
final class RoomSearchProfile implements AutoCloseable {
    @Name("ashen.RoomSearchWork")
    @Label("室探索の作業")
    static final class WorkEvent extends Event {
        long wallNanos;
        long cpuNanos;
        long cycles;
    }

    private final Recording recording = new Recording();
    private final Function cycleQuery;
    private final WinNT.HANDLE thread;
    private final LongByReference cycleValue = new LongByReference();
    private final java.lang.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final ArrayList<String> rows = new ArrayList<>();
    private final String name;

    RoomSearchProfile(String name) {
        this.name = name;
        if (Platform.isWindows()) {
            cycleQuery = NativeLibrary.getInstance("kernel32").getFunction("QueryThreadCycleTime", Function.ALT_CONVENTION);
            thread = Kernel32.INSTANCE.GetCurrentThread();
        } else { cycleQuery = null; thread = null; }
        if (!threads.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("スレッドCPU時間を取得できません");
        threads.setThreadCpuTimeEnabled(true);
        for (var event : new String[]{"jdk.GCPhasePause", "jdk.SafepointBegin", "jdk.SafepointEnd",
                "jdk.ThreadPark", "jdk.JavaMonitorWait", "jdk.JavaMonitorEnter"})
            recording.enable(event).withThreshold(Duration.ZERO).withoutStackTrace();
        recording.enable(WorkEvent.class).withThreshold(Duration.ZERO).withoutStackTrace();
        recording.start();
        rows.add("thread,start_sec,start_nano,end_sec,end_nano,wall_ns,cpu_ns,cycles");
    }

    long measure(Runnable work) {
        long cpu = threads.getCurrentThreadCpuTime();
        long cycles = cycles();
        var start = Instant.now();
        var event = new WorkEvent();
        event.begin();
        long now = System.nanoTime();
        work.run();
        long wall = System.nanoTime() - now;
        event.end();
        var end = Instant.now();
        cycles = cycleQuery == null ? -1 : cycles() - cycles;
        cpu = threads.getCurrentThreadCpuTime() - cpu;
        event.wallNanos = wall; event.cpuNanos = cpu; event.cycles = cycles; event.commit();
        rows.add(Thread.currentThread().threadId() + "," + start.getEpochSecond() + "," + start.getNano()
                + "," + end.getEpochSecond() + "," + end.getNano() + "," + wall + "," + cpu + "," + cycles);
        return wall;
    }

    private long cycles() {
        if (cycleQuery == null) return -1;
        if (cycleQuery.invokeInt(new Object[]{thread, cycleValue}) == 0)
            throw new IllegalStateException("スレッドの実行サイクルを取得できません");
        return cycleValue.getValue();
    }

    @Override public void close() {
        try {
            recording.stop();
            recording.dump(Path.of("room-search-" + name + ".jfr"));
            Files.write(Path.of("room-search-" + name + ".csv"), rows);
        } catch (IOException error) { throw new IllegalStateException("探索の診断記録を保存できません", error); }
        finally { recording.close(); }
    }
}
