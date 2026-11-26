package io.github.rs0325.ashenfrontier.core.testing;

import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.util.function.LongSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThreadCpuClockTest {
    private static ThreadMXBean bean(boolean supported,AtomicBoolean enabled,LongSupplier time) {
        return (ThreadMXBean)Proxy.newProxyInstance(ThreadMXBean.class.getClassLoader(),new Class<?>[]{ThreadMXBean.class},
                (proxy,method,args)->switch(method.getName()) {
                    case "isCurrentThreadCpuTimeSupported" -> supported;
                    case "isThreadCpuTimeEnabled" -> enabled.get();
                    case "setThreadCpuTimeEnabled" -> {enabled.set((boolean)args[0]);yield null;}
                    case "getCurrentThreadCpuTime" -> time.getAsLong();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void unsupportedCpuClockFailsInsteadOfUsingWallTime() {
        assertTrue(assertThrows(IllegalStateException.class,
                ()->new ThreadCpuClock(bean(false,new AtomicBoolean(),()->0L))).getMessage().contains("対応"));
    }

    @Test void disabledClockIsEnabledAndUnavailableReadFails() {
        var enabled=new AtomicBoolean();var time=new AtomicLong();
        var clock=new ThreadCpuClock(bean(true,enabled,time::get));
        assertTrue(enabled.get());assertEquals(0,clock.now());
        time.set(123);assertEquals(123,clock.now());
        time.set(-1);assertTrue(assertThrows(IllegalStateException.class,clock::now).getMessage().contains("取得できません"));
    }

    @Test void decreasingCounterFails() {
        var time=new AtomicLong(100);var clock=new ThreadCpuClock(bean(true,new AtomicBoolean(true),time::get));
        time.set(99);assertTrue(assertThrows(IllegalStateException.class,clock::now).getMessage().contains("減少"));
    }

    @Test void anotherThreadCannotReadTheMeasuredClock() {
        var clock=new ThreadCpuClock(bean(true,new AtomicBoolean(true),()->100L));
        var error=CompletableFuture.supplyAsync(()->assertThrows(IllegalStateException.class,clock::now)).join();
        assertTrue(error.getMessage().contains("測定スレッド"));
    }
    @Test void fineObservedResolutionUsesCpu() {
        var time=new AtomicLong();
        var clock=new ThreadCpuClock(bean(true,new AtomicBoolean(true),()->time.getAndAdd(1_000)));
        assertTrue(clock.useCpu("fine",1_000_000));
    }

    @Test void coarseObservedResolutionUsesWallEvenForChunkBudget() {
        var time=new AtomicLong();
        var clock=new ThreadCpuClock(bean(true,new AtomicBoolean(true),()->time.getAndAdd(15_625_000)));
        assertFalse(clock.useCpu("coarse_tick",1_000_000));
        assertFalse(clock.useCpu("coarse_chunk",25_000_000));
    }

    @Test void selectionDependsOnBudgetWithoutChangingSamples() {
        var time=new AtomicLong();
        var clock=new ThreadCpuClock(bean(true,new AtomicBoolean(true),()->time.getAndAdd(20_000)));
        assertFalse(clock.useCpu("small_budget",1_000_000));
        assertTrue(clock.useCpu("large_budget",25_000_000));
    }
}
