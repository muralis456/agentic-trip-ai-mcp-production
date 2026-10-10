package com.example.travel.observability;

import com.example.travel.model.GoalEvaluation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentEvaluationLedgerTest {
    @Test
    void recordsGoalOutcomeAndKeepsBoundedRecentHistory() {
        AgentEvaluationLedger ledger = new AgentEvaluationLedger();
        GoalEvaluation achieved = new GoalEvaluation();
        achieved.setStatus(GoalEvaluation.Status.ACHIEVED);
        achieved.setScore(1.0);
        achieved.setReason("All mandatory constraints satisfied");

        ledger.record(null, achieved);
        assertEquals(1, ledger.summary().get("evaluations"));
        assertEquals(1L, ledger.summary().get("achieved"));
        assertEquals(1.0, ledger.summary().get("averageScore"));

        GoalEvaluation partial = new GoalEvaluation();
        partial.setStatus(GoalEvaluation.Status.PARTIAL);
        partial.setScore(0.5);
        for (int i = 0; i < 205; i++) ledger.record(null, partial);

        assertEquals(200, ledger.summary().get("evaluations"));
        assertEquals(100, ledger.recent(100).size());
        assertEquals("PARTIAL", ledger.recent(1).getFirst().status());
    }
}
