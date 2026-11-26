package io.github.rs0325.ashenfrontier.core.testing;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** 同期性能検査のCPU時計。生成したスレッドだけを測り、別スレッドの作業は含めない。 */
public final class ThreadCpuClock {
    private final ThreadMXBean threads;
    private final Thread thread=Thread.currentThread();
    private long previous=-1;
    private long cpuResolution,wallResolution;

    /** CPU時計を有効にする。未対応・取得不可の場合はIllegalStateExceptionで検査を失敗させる。 */
    public ThreadCpuClock() {this(ManagementFactory.getThreadMXBean());}

    ThreadCpuClock(ThreadMXBean threads) {
        this.threads=threads;
        if(!threads.isCurrentThreadCpuTimeSupported())
            throw new IllegalStateException("測定スレッドのCPU時間に対応していません");
        try {
            if(!threads.isThreadCpuTimeEnabled())threads.setThreadCpuTimeEnabled(true);
        } catch(UnsupportedOperationException|SecurityException error) {
            throw new IllegalStateException("スレッドCPU時計を有効にできません",error);
        }
        now();
    }

    /**
     * ウォームアップ前に時計を選ぶ。上限の1%以下の観測増分ならCPU、それ以外は壁時計を使う。
     * 両時計の最小正増分と選択を記録する。CPU取得不能・較正不能・両時計の分解能不足は失敗とする。
     */
    public boolean useCpu(String test,long limitNanos) {
        now();
        if(cpuResolution==0)measureResolution();
        long allowed=limitNanos/100;
        boolean cpu=cpuResolution<=allowed;
        if(!cpu && wallResolution>allowed)
            throw new IllegalStateException("両時計の分解能が不足: cpu="+cpuResolution+" wall="+wallResolution+" 上限の1%="+allowed+" ns");
        System.getLogger(ThreadCpuClock.class.getName()).log(System.Logger.Level.INFO,
                "PERFORMANCE_CLOCK test="+test+" thread="+thread.getName()+" selected="+(cpu?"cpu":"wall")
                +" cpu_resolution_ns="+cpuResolution+" wall_resolution_ns="+wallResolution
                +" allowed_resolution_ns="+allowed+" limit_ns="+limitNanos+" updates=16");
        return cpu;
    }

    private void measureResolution() {
        long lastCpu=now(),lastWall=System.nanoTime(),deadline=lastWall+5_000_000_000L;
        long cpu=Long.MAX_VALUE,wall=Long.MAX_VALUE;
        for(int updates=0;updates<16;) {
            long currentCpu=now(),currentWall=System.nanoTime();
            if(currentCpu>lastCpu) {cpu=Math.min(cpu,currentCpu-lastCpu);updates++;}
            if(currentWall>lastWall)wall=Math.min(wall,currentWall-lastWall);
            if(currentWall>deadline)
                throw new IllegalStateException("CPU時計の分解能を5秒以内に較正できません: 更新数="+updates);
            lastCpu=currentCpu;lastWall=currentWall;
        }
        cpuResolution=cpu;wallResolution=wall;
    }

    /** CPU時間をnsで返す。対象スレッドの変更、取得不能、時計の減少は失敗として扱う。 */
    public long now() {
        if(Thread.currentThread()!=thread)
            throw new IllegalStateException("CPU時計は生成した測定スレッドだけで使用できます");
        long value;
        try {value=threads.getCurrentThreadCpuTime();}
        catch(UnsupportedOperationException|SecurityException error) {
            throw new IllegalStateException("測定スレッドのCPU時間を読み取れません",error);
        }
        if(value<0)throw new IllegalStateException("測定スレッドのCPU時間を取得できません: "+value);
        if(value<previous)throw new IllegalStateException("測定スレッドのCPU時計が減少しました");
        previous=value;
        return value;
    }
}
