package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@ConditionalOnBean(JevDecisionService.class)
public class JevHitlDecisionService {
    private static final Logger log = LoggerFactory.getLogger(JevHitlDecisionService.class);
    private final JevDecisionService decisions;
    public JevHitlDecisionService(JevDecisionService decisions){this.decisions=decisions;}
    public Decision decide(TravelState state){
        log.info("jev.hitl.decision-start userInputRequired={} validationErrors={} goalStatus={}", state.userInputRequired(), state.validationErrors().size(), state.goalEvaluation()==null?"UNKNOWN":state.goalEvaluation().getStatus());
        if(state.userInputRequired() || !state.validationErrors().isEmpty() || (state.goalEvaluation()!=null && state.goalEvaluation().getStatus()!=com.example.travel.model.GoalEvaluation.Status.ACHIEVED)) return new Decision("ASK_USER",1,true,"hard policy requires user review");
        try{
            var d=decisions.choose(Map.of("request",state.userRequest(),"tripPlanning",state.isTripPlanningWorkflow(),"goalStatus",state.goalEvaluation()==null?"UNKNOWN":state.goalEvaluation().getStatus().name(),"validationErrors",state.validationErrors()),
                "Decide whether this completed result can be returned automatically or should pause for user review. Never auto-complete when validation is unresolved or the goal is not achieved.",
                Map.of("AUTO_COMPLETE","All deterministic checks pass and the result can be returned without user confirmation.","ASK_USER","User review is required or material ambiguity remains."));
            String c=d.choice().toUpperCase();
            Decision result = d.accepted()&&(c.equals("AUTO_COMPLETE")||c.equals("ASK_USER"))?new Decision(c,d.confidence(),true,"jev"):new Decision("ASK_USER",d.confidence(),false,"low-confidence or invalid decision");
            log.info("jev.hitl.decision outcome={} confidence={} accepted={} reason={}", result.route(), result.confidence(), result.accepted(), result.reason());
            return result;
        }catch(Exception ex){ log.warn("jev.hitl.decision-fallback outcome=ASK_USER reason={}", ex.getClass().getSimpleName()); return new Decision("ASK_USER",0,false,"jev unavailable: "+ex.getClass().getSimpleName());}
    }
    public record Decision(String route,double confidence,boolean accepted,String reason){}
}
