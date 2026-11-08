package com.trading.scanner.execution.lock;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * High-throughput striped symbol lock manager.
 * Serializes order creation, trailing stop modifications, and exit executions
 * on a per-symbol granularity, completely eliminating race conditions and
 * over-execution hazards while allowing independent stock evaluations to run
 * in parallel across worker threads with zero bottleneck.
 */
@Component
public class StripedSymbolLockManager {

    private final Map<String, ReentrantLock> symbolLocks = new ConcurrentHashMap<>();

    public ReentrantLock getLock(String symbol) {
        String key = normalize(symbol);
        return symbolLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    public void executeWithLock(String symbol, Runnable action) {
        ReentrantLock lock = getLock(symbol);
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public <T> T executeWithLock(String symbol, Supplier<T> action) {
        ReentrantLock lock = getLock(symbol);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private String normalize(String symbol) {
        return symbol != null ? symbol.trim().toUpperCase(Locale.ROOT) : "GLOBAL";
    }
}
