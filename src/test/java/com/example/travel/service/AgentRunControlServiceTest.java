package com.example.travel.service;

import com.example.travel.entity.AgentRunControl;
import com.example.travel.repository.AgentRunControlRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRunControlServiceTest {
    private final AgentRunControlRepository repository = mock(AgentRunControlRepository.class);
    private final AgentRunControlService service = new AgentRunControlService(repository);

    @Test
    void stoppedRunLookupIsScopedToConversationWhenNoThreadWasProvided() {
        AgentRunControl otherConversationRun = stoppedRun("user-1", "chat-a", "thread-a");
        when(repository.findByUserIdAndConversationIdAndStatusOrderByUpdatedAtDesc(
                eq("user-1"), eq("chat-b"), eq(AgentRunControlService.STOPPED), any()))
                .thenReturn(List.of());

        Optional<AgentRunControl> result = service.findStoppedForConversation("user-1", "chat-b", "");

        assertTrue(result.isEmpty());
        verify(repository, never()).findByUserIdAndStatusOrderByUpdatedAtDesc(anyString(), anyString(), any());
        verify(repository, never()).findByThreadId(otherConversationRun.getThreadId());
    }

    @Test
    void stoppedRunLookupPrefersTheOwnedThreadFromTheActiveChat() {
        AgentRunControl preferred = stoppedRun("user-1", "legacy-conversation", "thread-a");
        when(repository.findByThreadId("thread-a")).thenReturn(Optional.of(preferred));

        Optional<AgentRunControl> result = service.findStoppedForConversation("user-1", "chat-a", "thread-a");

        assertTrue(result.isPresent());
        assertSame(preferred, result.get());
        verify(repository, never()).findByUserIdAndConversationIdAndStatusOrderByUpdatedAtDesc(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void activeChatThreadPreventsFallbackToAnOlderStoppedRun() {
        AgentRunControl completed = stoppedRun("user-1", "chat-a", "thread-current");
        completed.setStatus(AgentRunControlService.COMPLETED);
        when(repository.findByThreadId("thread-current")).thenReturn(Optional.of(completed));

        Optional<AgentRunControl> result = service.findStoppedForConversation("user-1", "chat-a", "thread-current");

        assertTrue(result.isEmpty());
        verify(repository, never()).findByUserIdAndConversationIdAndStatusOrderByUpdatedAtDesc(
                anyString(), anyString(), anyString(), any());
    }

            @Test
            void activeRunLookupFindsOnlyTheCurrentConversation() {
            AgentRunControl active = stoppedRun("user-1", "chat-a", "thread-a");
            active.setStatus(AgentRunControlService.RUNNING);
            when(repository.findByUserIdAndConversationIdAndStatusOrderByUpdatedAtDesc(
                eq("user-1"), eq("chat-a"), eq(AgentRunControlService.RUNNING), any()))
                .thenReturn(List.of(active));

            Optional<AgentRunControl> result = service.findActiveForConversation("user-1", "chat-a", "");

            assertTrue(result.isPresent());
            assertSame(active, result.get());
            verify(repository, never()).findByUserIdAndConversationIdAndStatusOrderByUpdatedAtDesc(
                eq("user-1"), eq("chat-b"), anyString(), any());
            }

    private static AgentRunControl stoppedRun(String userId, String conversationId, String threadId) {
        AgentRunControl run = new AgentRunControl();
        run.setUserId(userId);
        run.setConversationId(conversationId);
        run.setThreadId(threadId);
        run.setStatus(AgentRunControlService.STOPPED);
        return run;
    }
}