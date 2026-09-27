package com.example.travel.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded admission control so one user or traffic spike cannot exhaust the JVM. */
@Component
public class AgentRunAdmissionService {
    private final Semaphore global;
    private final int maxPerUser;
    private final ConcurrentHashMap<String, AtomicInteger> activeByUser = new ConcurrentHashMap<>();

    public AgentRunAdmissionService(
            @Value("${travel.runtime.max-concurrent-plans:12}") int maxConcurrent,
            @Value("${travel.runtime.max-concurrent-plans-per-user:2}") int maxPerUser) {
        this.global = new Semaphore(Math.max(1, maxConcurrent));
        this.maxPerUser = Math.max(1, maxPerUser);
    }

    public boolean tryAcquire(String userId) {
        String key = userId == null ? "" : userId;
        AtomicInteger counter = activeByUser.computeIfAbsent(key, ignored -> new AtomicInteger());
        int next = counter.incrementAndGet();
        if (next > maxPerUser) {
            counter.decrementAndGet();
            cleanup(key, counter);
            return false;
        }
        if (!global.tryAcquire()) {
            counter.decrementAndGet();
            cleanup(key, counter);
            return false;
        }
        return true;
    }

    public void release(String userId) {
        String key = userId == null ? "" : userId;
        AtomicInteger counter = activeByUser.get(key);
        if (counter != null) {
            counter.decrementAndGet();
            cleanup(key, counter);
        }
        global.release();
    }

    private void cleanup(String key, AtomicInteger counter) {
        if (counter.get() <= 0) activeByUser.remove(key, counter);
    }
}
