package com.example.travel.service;

import com.example.travel.entity.ApiRateLimitBucket;
import com.example.travel.repository.ApiRateLimitBucketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class ApiRateLimitService {
    private final ApiRateLimitBucketRepository repository;
    private final int maxRequests;
    private final Duration window;

    public ApiRateLimitService(ApiRateLimitBucketRepository repository,
                               @Value("${travel.runtime.rate-limit-per-window:6}") int maxRequests,
                               @Value("${travel.runtime.rate-limit-window-seconds:60}") long windowSeconds) {
        this.repository = repository;
        this.maxRequests = Math.max(1, maxRequests);
        this.window = Duration.ofSeconds(Math.max(1, windowSeconds));
    }

    @Transactional
    public void initialize(String userId) {
        if (userId == null || userId.isBlank() || repository.existsById(userId)) return;
        ApiRateLimitBucket bucket = new ApiRateLimitBucket();
        bucket.setUserId(userId);
        bucket.setWindowStartedAt(Instant.now());
        bucket.setRequestCount(0);
        repository.save(bucket);
    }

    @Transactional
    public boolean tryAcquire(String userId) {
        Instant now = Instant.now();
        ApiRateLimitBucket bucket = repository.findForUpdate(userId).orElse(null);
        if (bucket == null) {
            // Newly registered accounts are initialized by AuthService. If a legacy
            // account has no bucket, create it here; subsequent requests use a row lock.
            bucket = new ApiRateLimitBucket();
            bucket.setUserId(userId);
            bucket.setWindowStartedAt(now);
            bucket.setRequestCount(1);
            repository.saveAndFlush(bucket);
            return true;
        }

        if (Duration.between(bucket.getWindowStartedAt(), now).compareTo(window) >= 0) {
            bucket.setWindowStartedAt(now);
            bucket.setRequestCount(1);
            repository.save(bucket);
            return true;
        }
        if (bucket.getRequestCount() >= maxRequests) {
            return false;
        }
        bucket.setRequestCount(bucket.getRequestCount() + 1);
        repository.save(bucket);
        return true;
    }
}
