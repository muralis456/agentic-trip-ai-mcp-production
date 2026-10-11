package com.example.travel.observability;

import com.example.travel.entity.AgentGoalEvaluation;
import com.example.travel.graph.TravelState;
import com.example.travel.model.GoalEvaluation;
import com.example.travel.repository.AgentGoalEvaluationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentEvaluationLedgerTest {
    @Test
    void persistsStructuredEvaluationWithOwnerAndCriteria() throws Exception {
        AgentGoalEvaluationRepository repository = mock(AgentGoalEvaluationRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenReturn("[\"flight options available\"]");
        AgentEvaluationLedger ledger = new AgentEvaluationLedger(repository, mapper);

        TravelState state = new TravelState(java.util.Map.of(
                TravelState.USER_ID, "alice",
                TravelState.GRAPH_THREAD_ID, "thread-123",
                TravelState.DESTINATION, "Tokyo"));
        GoalEvaluation evaluation = new GoalEvaluation();
        evaluation.setStatus(GoalEvaluation.Status.PARTIAL);
        evaluation.setScore(0.65);
        evaluation.setReason("One requirement is still unmet");
        evaluation.setSatisfiedCriteria(List.of("flight options available"));
        evaluation.setUnmetCriteria(List.of("hotel within budget"));

        ledger.record(state, evaluation);

        var captor = org.mockito.ArgumentCaptor.forClass(AgentGoalEvaluation.class);
        verify(repository).save(captor.capture());
        AgentGoalEvaluation saved = captor.getValue();
        assertEquals("alice", saved.getUserId());
        assertEquals("thread-123", saved.getThreadId());
        assertEquals("Tokyo", saved.getDestination());
        assertEquals("PARTIAL", saved.getStatus());
        assertEquals(0.65, saved.getScore(), 0.0001);
        assertEquals("[\"flight options available\"]", saved.getSatisfiedCriteriaJson());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void nullStatusIsNormalizedByEvaluationModelBeforePersistence() {
        AgentGoalEvaluationRepository repository = mock(AgentGoalEvaluationRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        AgentEvaluationLedger ledger = new AgentEvaluationLedger(repository, mapper);

        TravelState state = new TravelState(java.util.Map.of(TravelState.USER_ID, "alice"));
        GoalEvaluation evaluation = new GoalEvaluation();
        evaluation.setStatus(null);

        ledger.record(state, evaluation);

        var captor = org.mockito.ArgumentCaptor.forClass(AgentGoalEvaluation.class);
        verify(repository).save(captor.capture());
        assertEquals("PARTIAL", captor.getValue().getStatus());
    }

    @Test
    void queriesRecentHistoryAndAggregateSummaryOnlyForRequestedUser() {
        AgentGoalEvaluationRepository repository = mock(AgentGoalEvaluationRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        AgentEvaluationLedger ledger = new AgentEvaluationLedger(repository, mapper);

        AgentGoalEvaluation row = new AgentGoalEvaluation();
        row.setUserId("alice");
        row.setThreadId("thread-1");
        row.setStatus("ACHIEVED");
        row.setScore(1.0);
        row.setCreatedAt(java.time.Instant.parse("2026-10-10T10:00:00Z"));
        when(repository.findByUserIdOrderByCreatedAtDesc(eq("alice"), any(Pageable.class))).thenReturn(List.of(row));
        when(repository.averageScoreByUserId("alice")).thenReturn(1.0);
        when(repository.countByUserId("alice")).thenReturn(1L);
        when(repository.countByUserIdAndStatus("alice", "ACHIEVED")).thenReturn(1L);
        when(repository.countByUserIdAndStatus("alice", "PARTIAL")).thenReturn(0L);
        when(repository.countByUserIdAndStatus("alice", "FAILED")).thenReturn(0L);
        when(repository.countByUserIdAndStatus("alice", "NEEDS_USER")).thenReturn(0L);

        assertEquals("ACHIEVED", ledger.recent("alice", 50).getFirst().status());
        assertEquals(1L, ledger.summary("alice").get("evaluations"));
        verify(repository).findByUserIdOrderByCreatedAtDesc(eq("alice"), any(Pageable.class));
        verify(repository, never()).findByUserIdOrderByCreatedAtDesc(eq("bob"), any(Pageable.class));
    }

    @Test
    void evaluationPersistenceFailureDoesNotEscapeIntoTravelExecution() {
        AgentGoalEvaluationRepository repository = mock(AgentGoalEvaluationRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenReturn("[]");
        when(repository.save(any(AgentGoalEvaluation.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        AgentEvaluationLedger ledger = new AgentEvaluationLedger(repository, mapper);

        TravelState state = new TravelState(java.util.Map.of(TravelState.USER_ID, "alice"));
        GoalEvaluation evaluation = new GoalEvaluation();
        evaluation.setStatus(GoalEvaluation.Status.PARTIAL);

        assertDoesNotThrow(() -> ledger.record(state, evaluation));
        verify(repository).save(any(AgentGoalEvaluation.class));
    }

    @Test
    void searchWithoutTextUsesQueryThatNeverBindsNullTextPattern() {
        AgentGoalEvaluationRepository repository = mock(AgentGoalEvaluationRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(repository.searchForUserWithoutText(eq("alice"), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        AgentEvaluationLedger ledger = new AgentEvaluationLedger(repository, mapper);

        assertTrue(ledger.search("alice", 0, 0, 25, null, null, "createdAt", "DESC").isEmpty());

        verify(repository).searchForUserWithoutText(eq("alice"), isNull(), isNull(), any(Pageable.class));
        verify(repository, never()).searchForUserWithText(anyString(), any(), any(), anyString(), any(Pageable.class));
    }

    @Test
    void searchWithTextUsesTextSearchQuery() {
        AgentGoalEvaluationRepository repository = mock(AgentGoalEvaluationRepository.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(repository.searchForUserWithText(eq("alice"), isNull(), isNull(), eq("%tokyo%"),
                any(Pageable.class))).thenReturn(org.springframework.data.domain.Page.empty());
        AgentEvaluationLedger ledger = new AgentEvaluationLedger(repository, mapper);

        assertTrue(ledger.search("alice", 0, 0, 25, null, "tokyo", "createdAt", "DESC").isEmpty());

        verify(repository).searchForUserWithText(eq("alice"), isNull(), isNull(), eq("%tokyo%"),
                any(Pageable.class));
        verify(repository, never()).searchForUserWithoutText(anyString(), any(), any(), any(Pageable.class));
    }
}
