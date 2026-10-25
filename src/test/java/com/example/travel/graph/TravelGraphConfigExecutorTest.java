package com.example.travel.graph;

import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TravelGraphConfigExecutorTest {

    @Test
    void specialistExecutorUsesBoundedQueueAndCallerRunsBackpressure() {
        var executor = new TravelGraphConfig().travelParallelExecutor(2, 3);
        try {
            ThreadPoolExecutor pool = assertInstanceOf(ThreadPoolExecutor.class, executor);
            assertEquals(3, pool.getQueue().remainingCapacity());
            AtomicReference<Thread> executionThread = new AtomicReference<>();
            pool.getRejectedExecutionHandler().rejectedExecution(
                    () -> executionThread.set(Thread.currentThread()), pool);
            assertSame(Thread.currentThread(), executionThread.get(),
                    "saturated work should apply backpressure on the submitting thread");

            pool.shutdown();
            assertThrows(RejectedExecutionException.class, () ->
                    pool.getRejectedExecutionHandler().rejectedExecution(() -> { }, pool),
                    "shutdown must reject explicitly instead of silently dropping a FutureTask");
        } finally {
            executor.shutdownNow();
        }
    }
}
