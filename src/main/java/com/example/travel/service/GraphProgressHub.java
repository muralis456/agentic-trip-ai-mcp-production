package com.example.travel.service;

import com.example.travel.entity.GraphProgressEvent;
import com.example.travel.repository.GraphProgressEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable SSE progress hub. Events are persisted in PostgreSQL so a client can
 * reconnect to a different application instance and still replay progress.
 */
@Component
public class GraphProgressHub {
    private static final Logger log = LoggerFactory.getLogger(GraphProgressHub.class);
    private static final long SSE_TIMEOUT_MS = 15 * 60 * 1000L;

    private final GraphProgressEventRepository repository;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService poller = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "graph-progress-poller");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<SseEmitter, ScheduledFuture<?>> subscriptions = new ConcurrentHashMap<>();

    public GraphProgressHub(GraphProgressEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void open(String threadId) {
        // The first event will create the durable stream. No JVM-local state is required.
    }

    public SseEmitter subscribe(String threadId) {
        return subscribe(threadId, false);
    }

    public long latestEventId(String threadId) {
        return repository.findTop1ByThreadIdOrderByIdDesc(threadId)
                .map(GraphProgressEvent::getId)
                .orElse(0L);
    }

    public SseEmitter subscribe(String threadId, long afterId) {
        return subscribe(threadId, false, afterId);
    }

    /**
     * Subscribe to progress. When liveOnly is true, ignore all events that
     * existed before the subscription. This is required for retries on an
     * existing LangGraph thread: the thread already contains a terminal event
     * from the previous run, which must not immediately close the new SSE.
     */
    public SseEmitter subscribe(String threadId, boolean liveOnly) {
        return subscribe(threadId, liveOnly, -1L);
    }

    private SseEmitter subscribe(String threadId, boolean liveOnly, long afterId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        long initialLastId = afterId >= 0 ? afterId : (liveOnly
                ? repository.findTop1ByThreadIdOrderByIdDesc(threadId).map(GraphProgressEvent::getId).orElse(0L)
                : 0L);
        long[] lastId = {initialLastId};

        try {
            // Force the HTTP/SSE response to commit immediately. The retry UI
            // subscribes before POST /api/plan/retry and waits for EventSource
            // onopen before starting the backend action. Without an initial SSE
            // frame, some servlet/proxy combinations can keep the connection
            // pending indefinitely because no event has been emitted yet.
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .data("{\"threadId\":\"" + threadId.replace("\"", "") + "\"}"));

            if (liveOnly) {
                // Do not replay historical events. The poller will deliver only
                // events emitted after this subscription was established.
            } else {
            List<GraphProgressEvent> replay = new ArrayList<>(repository.findTop200ByThreadIdOrderByIdDesc(threadId));
            replay.sort(java.util.Comparator.comparing(GraphProgressEvent::getId));
            for (GraphProgressEvent event : replay) {
                if (event.getId() <= lastId[0]) continue;
                send(emitter, event);
                lastId[0] = event.getId();
                if (isTerminal(event.getEventType())) {
                    completeQuietly(emitter, closed);
                    return emitter;
                }
            }
            }
        } catch (Exception ex) {
            log.warn("Could not replay SSE progress threadId={} liveOnly={}", threadId, liveOnly, ex);
        }

        ScheduledFuture<?> future = poller.scheduleWithFixedDelay(() -> poll(threadId, emitter, lastId, closed),
                500, 500, TimeUnit.MILLISECONDS);
        subscriptions.put(emitter, future);
        emitter.onCompletion(() -> cleanup(emitter));
        emitter.onTimeout(() -> cleanup(emitter));
        emitter.onError(error -> cleanup(emitter));
        return emitter;
    }

    public void emit(String threadId, String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("ts", System.currentTimeMillis());
        event.putAll(payload);

        try {
            GraphProgressEvent persisted = new GraphProgressEvent();
            persisted.setThreadId(threadId);
            persisted.setCreatedAt(Instant.now());
            persisted.setEventType(type);
            persisted.setPayload(objectMapper.writeValueAsString(event));
            repository.saveAndFlush(persisted);

            // Keep the database bounded per thread. Retain the most recent 200 events.
            List<GraphProgressEvent> recent = repository.findTop200ByThreadIdOrderByIdDesc(threadId);
            if (recent.size() >= 200) {
                long cutoff = recent.get(recent.size() - 1).getId();
                repository.deleteByThreadIdAndIdLessThan(threadId, cutoff);
            }
        } catch (Exception ex) {
            // Progress is observability/UI state; a database hiccup must never fail the agent graph.
            log.warn("Could not persist graph progress threadId={} type={}", threadId, type, ex);
        }
    }

    public void close(String threadId) {
        // Terminal events are persisted by emit(). Reconnecting clients will replay them.
    }

    private void poll(String threadId, SseEmitter emitter, long[] lastId, AtomicBoolean closed) {
        if (closed.get()) {
            cleanup(emitter);
            return;
        }
        try {
            List<GraphProgressEvent> events = repository.findByThreadIdAndIdGreaterThanOrderByIdAsc(threadId, lastId[0]);
            for (GraphProgressEvent event : events) {
                send(emitter, event);
                lastId[0] = event.getId();
                if (isTerminal(event.getEventType())) {
                    completeQuietly(emitter, closed);
                    return;
                }
            }
        } catch (Exception ex) {
            log.debug("SSE progress poll failed threadId={} message={}", threadId, ex.getMessage());
        }
    }

    private void send(SseEmitter emitter, GraphProgressEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);
            payload.put("eventId", event.getId());
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().id(String.valueOf(event.getId()))
                    .name(event.getEventType()).data(json));
        } catch (Exception ex) {
            cleanup(emitter);
        }
    }

    private boolean isTerminal(String type) {
        return "complete".equals(type) || "failed".equals(type) || "stopped".equals(type);
    }

    private void completeQuietly(SseEmitter emitter, AtomicBoolean closed) {
        if (!closed.compareAndSet(false, true)) return;
        cleanup(emitter);
        try { emitter.complete(); } catch (RuntimeException ignored) { }
    }

    private void cleanup(SseEmitter emitter) {
        ScheduledFuture<?> future = subscriptions.remove(emitter);
        if (future != null) future.cancel(false);
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
    }
}
