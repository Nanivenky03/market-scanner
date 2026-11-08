package com.trading.scanner.execution.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StripedSymbolLockManagerTest {

    private final StripedSymbolLockManager lockManager = new StripedSymbolLockManager();

    @Test
    @DisplayName("Serializes operations on the exact same symbol")
    void executeWithLock_sameSymbol_shouldSerialize() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    lockManager.executeWithLock("INFY", () -> {
                        int current = counter.get();
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {}
                        counter.set(current + 1);
                    });
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(threadCount, counter.get());
    }

    @Test
    @DisplayName("Allows concurrent execution across different symbols without blocking")
    void executeWithLock_differentSymbols_shouldExecuteConcurrently() throws InterruptedException {
        CountDownLatch infyStarted = new CountDownLatch(1);
        CountDownLatch tcsCompleted = new CountDownLatch(1);
        AtomicBoolean tcsExecutedConcurrently = new AtomicBoolean(false);

        Thread thread1 = new Thread(() -> {
            lockManager.executeWithLock("INFY", () -> {
                infyStarted.countDown();
                try {
                    // Hold lock on INFY for 100ms
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
            });
        });

        Thread thread2 = new Thread(() -> {
            try {
                infyStarted.await();
                // TCS should NOT be blocked by INFY lock
                lockManager.executeWithLock("TCS", () -> {
                    tcsExecutedConcurrently.set(true);
                    tcsCompleted.countDown();
                });
            } catch (InterruptedException ignored) {}
        });

        thread1.start();
        thread2.start();

        boolean tcsFinishedInTime = tcsCompleted.await(50, TimeUnit.MILLISECONDS);
        thread1.join();
        thread2.join();

        assertTrue(tcsFinishedInTime, "TCS execution should not be blocked by INFY lock");
        assertTrue(tcsExecutedConcurrently.get());
    }
}
