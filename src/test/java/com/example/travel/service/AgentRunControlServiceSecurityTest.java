package com.example.travel.service;

import com.example.travel.entity.AgentRunControl;
import com.example.travel.repository.AgentRunControlRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRunControlServiceSecurityTest {

    private AgentRunControl run(String user, String status) {
        AgentRunControl run = new AgentRunControl();
        run.setUserId(user);
        run.setConversationId("conversation-1");
        run.setThreadId(user + "-" + java.util.UUID.randomUUID());
        run.setStatus(status);
        run.setOriginalRequest("Plan a trip");
        run.setPolicy("BALANCED");
        run.setStartedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        return run;
    }

    @Test
    void anotherUserCannotStopARun() {
        AgentRunControlRepository repository = mock(AgentRunControlRepository.class);
        AgentRunControlService service = new AgentRunControlService(repository);
        AgentRunControl bobRun = run("bob", AgentRunControlService.RUNNING);
        when(repository.findByThreadId(bobRun.getThreadId())).thenReturn(Optional.of(bobRun));

        assertThrows(IllegalStateException.class,
                () -> service.requestStop("alice", bobRun.getThreadId()));

        assertEquals(AgentRunControlService.RUNNING, bobRun.getStatus());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void stopRequestPersistsStateBeforeFutureCancellation() {
        AgentRunControlRepository repository = mock(AgentRunControlRepository.class);
        AgentRunControlService service = new AgentRunControlService(repository);
        AgentRunControl run = run("alice", AgentRunControlService.RUNNING);
        when(repository.findByThreadId(run.getThreadId())).thenReturn(Optional.of(run));
        when(repository.saveAndFlush(any(AgentRunControl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertTrue(service.requestStop("alice", run.getThreadId()));

        assertEquals(AgentRunControlService.STOP_REQUESTED, run.getStatus());
        verify(repository).saveAndFlush(run);
        assertTrue(service.isStopRequested(run.getThreadId()));
    }

    @Test
    void completionCannotOverwriteAConcurrentStopRequest() {
        AgentRunControlRepository repository = mock(AgentRunControlRepository.class);
        AgentRunControlService service = new AgentRunControlService(repository);
        AgentRunControl run = run("alice", AgentRunControlService.STOP_REQUESTED);
        when(repository.findByThreadId(run.getThreadId())).thenReturn(Optional.of(run));
        when(repository.saveAndFlush(any(AgentRunControl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.markCompleted(run.getThreadId());

        assertEquals(AgentRunControlService.STOPPED, run.getStatus());
        verify(repository, atLeastOnce()).saveAndFlush(run);
    }

    @Test
    void processRestartRecoversOrphanedRunningRowsAsStoppedCheckpoints() {
        AgentRunControlRepository repository = mock(AgentRunControlRepository.class);
        AgentRunControlService service = new AgentRunControlService(repository);
        AgentRunControl running = run("alice", AgentRunControlService.RUNNING);
        AgentRunControl stopping = run("bob", AgentRunControlService.STOP_REQUESTED);
        AgentRunControl completed = run("carol", AgentRunControlService.COMPLETED);
        when(repository.findAll()).thenReturn(List.of(running, stopping, completed));
        when(repository.save(any(AgentRunControl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recoverOrphanedRuns();

        assertEquals(AgentRunControlService.STOPPED, running.getStatus());
        assertEquals(AgentRunControlService.STOPPED, stopping.getStatus());
        assertEquals(AgentRunControlService.COMPLETED, completed.getStatus());
        verify(repository, times(2)).save(any(AgentRunControl.class));
    }

    @Test
    void stoppedCheckpointLookupNeverFallsBackAcrossUsers() {
        AgentRunControlRepository repository = mock(AgentRunControlRepository.class);
        AgentRunControlService service = new AgentRunControlService(repository);
        AgentRunControl bobsStoppedRun = run("bob", AgentRunControlService.STOPPED);
        when(repository.findByThreadId(bobsStoppedRun.getThreadId())).thenReturn(Optional.of(bobsStoppedRun));

        assertTrue(service.findStoppedForConversation(
                "alice", "conversation-1", bobsStoppedRun.getThreadId()).isEmpty());
        verify(repository, never()).findByUserIdAndConversationIdAndStatusOrderByUpdatedAtDesc(
                eq("alice"), anyString(), anyString(), any());
    }

}
