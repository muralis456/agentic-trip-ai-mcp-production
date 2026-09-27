package com.example.travel.service;

import com.example.travel.entity.AgentRunControl;
import com.example.travel.repository.AgentRunControlRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable control plane for long-running graph executions.
 * LangGraph/Postgres owns the actual workflow checkpoint; this service owns
 * whether the user has requested RUNNING/STOPPED/COMPLETED control.
 */
@Component
public class AgentRunControlService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunControlService.class);
    public static final String RUNNING = "RUNNING";
    public static final String STOP_REQUESTED = "STOP_REQUESTED";
    public static final String STOPPED = "STOPPED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    private final AgentRunControlRepository repository;
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    public AgentRunControlService(AgentRunControlRepository repository) {
        this.repository = repository;
    }

    public AgentRunControl start(String userId, String conversationId, String threadId, String originalRequest, String policy) {
        AgentRunControl row = repository.findByThreadId(threadId).orElseGet(AgentRunControl::new);
        Instant now = Instant.now();
        row.setUserId(userId);
        row.setConversationId(conversationId);
        row.setThreadId(threadId);
        row.setStatus(RUNNING);
        row.setOriginalRequest(originalRequest == null ? "" : originalRequest);
        row.setPolicy(policy == null ? "BALANCED" : policy);
        row.setStartedAt(now);
        row.setUpdatedAt(now);
        stopFlags.put(threadId, new AtomicBoolean(false));
        return repository.saveAndFlush(row);
    }

    public void registerFuture(String threadId, Future<?> future) {
        if (threadId != null && future != null) futures.put(threadId, future);
    }

    public boolean isStopRequested(String threadId) {
        AtomicBoolean flag = stopFlags.get(threadId);
        if (flag != null && flag.get()) return true;
        return repository.findByThreadId(threadId)
                .map(r -> STOP_REQUESTED.equals(r.getStatus()) || STOPPED.equals(r.getStatus()))
                .orElse(false);
    }

    public boolean requestStop(String userId, String threadId) {
        AgentRunControl row = repository.findByThreadId(threadId).orElseThrow();
        if (!userId.equals(row.getUserId())) throw new IllegalStateException("Run does not belong to this user");
        String status = row.getStatus();
        if (COMPLETED.equals(status) || FAILED.equals(status) || STOPPED.equals(status)) return false;
        stopFlags.computeIfAbsent(threadId, ignored -> new AtomicBoolean()).set(true);
        row.setStatus(STOP_REQUESTED);
        row.setUpdatedAt(Instant.now());
        repository.saveAndFlush(row);
        Future<?> future = futures.get(threadId);
        boolean interrupted = future != null && future.cancel(true);
        log.info("Run stop requested userId={} threadId={} futurePresent={} interruptSent={}",
                userId, threadId, future != null, interrupted);
        return true;
    }

    public void markStopped(String threadId) {
        update(threadId, STOPPED);
        log.info("Run stopped threadId={} checkpoint preserved for Continue", threadId);
        futures.remove(threadId);
        stopFlags.computeIfAbsent(threadId, ignored -> new AtomicBoolean()).set(true);
    }

    public void markCompleted(String threadId) {
        Optional<AgentRunControl> current = repository.findByThreadId(threadId);
        if (current.isPresent() && (STOP_REQUESTED.equals(current.get().getStatus())
                || STOPPED.equals(current.get().getStatus()))) {
            log.info("Ignoring completion for stopped run threadId={} status={}",
                    threadId, current.get().getStatus());
            markStopped(threadId);
            return;
        }
        update(threadId, COMPLETED);
        cleanup(threadId);
    }

    public void markFailed(String threadId) {
        Optional<AgentRunControl> current = repository.findByThreadId(threadId);
        if (current.isPresent() && (STOP_REQUESTED.equals(current.get().getStatus())
                || STOPPED.equals(current.get().getStatus()))) {
            log.info("Ignoring failure for stopped run threadId={} status={}",
                    threadId, current.get().getStatus());
            markStopped(threadId);
            return;
        }
        update(threadId, FAILED);
        cleanup(threadId);
    }

    public Optional<AgentRunControl> latestStopped(String userId, String conversationId) {
        return repository.findByUserIdAndConversationIdAndStatusOrderByUpdatedAtDesc(
                        userId, conversationId, STOPPED, PageRequest.of(0, 1))
                .stream().findFirst();
    }

    public Optional<AgentRunControl> findStoppedForConversation(
            String userId, String conversationId, String preferredThreadId) {
        if (preferredThreadId != null && !preferredThreadId.isBlank()) {
            Optional<AgentRunControl> preferred = repository.findByThreadId(preferredThreadId)
                .filter(run -> userId.equals(run.getUserId()));
            if (preferred.isPresent()) {
            return STOPPED.equals(preferred.get().getStatus())
                ? preferred
                : Optional.empty();
            }
        }
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        return latestStopped(userId, conversationId);
    }

        public Optional<AgentRunControl> findActiveForConversation(
            String userId, String conversationId, String preferredThreadId) {
        if (preferredThreadId != null && !preferredThreadId.isBlank()) {
            Optional<AgentRunControl> preferred = repository.findByThreadId(preferredThreadId)
                .filter(run -> userId.equals(run.getUserId()));
            if (preferred.isPresent()) {
            String status = preferred.get().getStatus();
            return RUNNING.equals(status) || STOP_REQUESTED.equals(status)
                ? preferred
                : Optional.empty();
            }
        }
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        Optional<AgentRunControl> running = repository
            .findByUserIdAndConversationIdAndStatusOrderByUpdatedAtDesc(
                userId, conversationId, RUNNING, PageRequest.of(0, 1))
            .stream().findFirst();
        return running.isPresent() ? running : repository
            .findByUserIdAndConversationIdAndStatusOrderByUpdatedAtDesc(
                userId, conversationId, STOP_REQUESTED, PageRequest.of(0, 1))
            .stream().findFirst();
        }

    public Optional<AgentRunControl> latestStoppedForUser(String userId) {
        return repository.findByUserIdAndStatusOrderByUpdatedAtDesc(
                        userId, STOPPED, PageRequest.of(0, 1))
                .stream().findFirst();
    }

    public Optional<AgentRunControl> find(String threadId) {
        return repository.findByThreadId(threadId);
    }

    /** On application restart, no JVM future survives. Existing RUNNING rows are resumable checkpoints, so mark them stopped. */
    @jakarta.annotation.PostConstruct
    public void recoverOrphanedRuns() {
        repository.findAll().stream()
                .filter(r -> RUNNING.equals(r.getStatus()) || STOP_REQUESTED.equals(r.getStatus()))
                .forEach(r -> {
                    r.setStatus(STOPPED);
                    r.setUpdatedAt(Instant.now());
                    repository.save(r);
                });
    }

    private void update(String threadId, String status) {
        repository.findByThreadId(threadId).ifPresent(row -> {
            row.setStatus(status);
            row.setUpdatedAt(Instant.now());
            repository.saveAndFlush(row);
        });
    }

    private void cleanup(String threadId) {
        futures.remove(threadId);
        stopFlags.remove(threadId);
    }
}
