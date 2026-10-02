package com.example.travel.graph;

import com.example.travel.model.GoalEvaluation;
import com.example.travel.jev.JevGoalDecisionService;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ProductionEvaluateNode implements NodeAction<TravelState> {
    private final GoalEvaluationService evaluator;
    private final com.example.travel.observability.AgentObservabilityService observability;
    private final java.util.Optional<JevGoalDecisionService> jevGoalDecision;
    public ProductionEvaluateNode(GoalEvaluationService evaluator,
                                  com.example.travel.observability.AgentObservabilityService observability,
                                  java.util.Optional<JevGoalDecisionService> jevGoalDecision){
        this.evaluator=evaluator;
        this.observability=observability;
        this.jevGoalDecision=jevGoalDecision;
    }
    @Override public Map<String,Object> apply(TravelState state){
        GoalEvaluation e=evaluator.evaluate(state);
        observability.recordGoalOutcome(e.getStatus().name());
        Map<String,Object> u=new LinkedHashMap<>();
        u.put(TravelState.GOAL_EVALUATION,e);
        String route = e.getStatus().name();
        if (jevGoalDecision.isPresent()) {
            var d = jevGoalDecision.get().decide(state, e);
            route = d.route();
            u.put(TravelState.REPLAN_NOTES, e.getReason() + " unmet=" + e.getUnmetCriteria() + " decision=" + d.route());
        }
        u.put(TravelState.SUPERVISOR_DECISION, route);
        if (!u.containsKey(TravelState.REPLAN_NOTES)) u.put(TravelState.REPLAN_NOTES,e.getReason()+" unmet="+e.getUnmetCriteria());

        // A successful recovery consumes the previous failure marker. Keeping
        // nodeFailure around after the capability has recovered can incorrectly
        // influence routing and can make a completed plan look recoverable/failed.
        if (e.getStatus() == GoalEvaluation.Status.ACHIEVED) {
            u.putAll(NodeFailureSupport.clear());
            u.put(TravelState.RETRY_TASK, "");
        }
        u.putAll(TravelState.trace("evaluate",e.getStatus().name().toLowerCase(),e.getReason()));
        return u;
    }
}
