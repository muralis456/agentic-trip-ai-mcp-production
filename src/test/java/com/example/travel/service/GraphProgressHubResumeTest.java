package com.example.travel.service;

import com.example.travel.entity.GraphProgressEvent;
import com.example.travel.repository.GraphProgressEventRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

class GraphProgressHubResumeTest {
    @Test
    void replayAfterCursorDoesNotCloseOnPreviousStoppedEvent() {
        GraphProgressEventRepository repository = mock(GraphProgressEventRepository.class);
        when(repository.findTop200ByThreadIdOrderByIdDesc("thread-1")).thenReturn(List.of(
                event(102L, "started", "{\"threadId\":\"thread-1\",\"node\":\"MODIFY\"}"),
                event(101L, "resume_state", "{\"threadId\":\"thread-1\",\"node\":\"RESUME_STATE\"}"),
                event(100L, "stopped", "{\"threadId\":\"thread-1\",\"message\":\"old run stopped\"}")));
        when(repository.findByThreadIdAndIdGreaterThanOrderByIdAsc("thread-1", 102L))
                .thenReturn(List.of());
        GraphProgressHub hub = new GraphProgressHub(repository, new ObjectMapper());

        try {
            hub.subscribe("thread-1", 100L);

            verify(repository, timeout(1500))
                    .findByThreadIdAndIdGreaterThanOrderByIdAsc("thread-1", 102L);
        } finally {
            hub.shutdown();
        }
    }

    private static GraphProgressEvent event(Long id, String type, String payload) {
        GraphProgressEvent event = new GraphProgressEvent();
        event.setId(id);
        event.setThreadId("thread-1");
        event.setEventType(type);
        event.setPayload(payload);
        event.setCreatedAt(Instant.now());
        return event;
    }
}
