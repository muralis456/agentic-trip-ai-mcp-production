package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import com.example.travel.model.GoalEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@ConditionalOnBean(JevDecisionService.class)
public class JevReplanDecisionService {
    private static final Logger log = LoggerFactory.getLogger(JevReplanDecisionService.class);
    private final JevDecisionService decisions;
    public JevReplanDecisionService(JevDecisionService decisions){this.decisions=decisions;}
    public Decision choose(TravelState state, GoalEvaluation evaluation, List<String> proposed){
        log.info("jev.replan.decision-start proposedActions={} unmetCriteria={}", proposed == null ? 0 : proposed.size(), evaluation == null || evaluation.getUnmetCriteria() == null ? 0 : evaluation.getUnmetCriteria().size());
        List<String> allowed=List.of("FLIGHT","HOTEL","BUDGET","ITINERARY","RESEARCH","WEATHER","ASK_USER","NONE");
        String fallback=deterministic(evaluation,state);
        try{
            Map<String,String> criteria=new LinkedHashMap<>();
            for(String a:allowed) criteria.put(a, description(a));
            var d=decisions.choose(Map.of("request",state.userRequest(),"unmet",evaluation==null?List.of():evaluation.getUnmetCriteria(),"blocking",evaluation==null||evaluation.getBlockingIssues()==null?List.of():evaluation.getBlockingIssues(),"proposedActions",proposed==null?List.of():proposed),
                    "Choose the single highest-value replan lever. Select only a capability that can address the unmet outcome. Do not choose ASK_USER unless automation cannot safely recover.",criteria);
            String choice=d.choice().toUpperCase();
            Decision result=d.accepted()&&allowed.contains(choice)?new Decision(choice,d.confidence(),true,"jev"):new Decision(fallback,d.confidence(),false,"low-confidence or invalid decision");
            log.info("jev.replan.decision outcome={} confidence={} accepted={} reason={}", result.action(), result.confidence(), result.accepted(), result.reason());
            return result;
        }catch(Exception ex){ log.warn("jev.replan.decision-fallback outcome={} reason={}", fallback, ex.getClass().getSimpleName()); return new Decision(fallback,0,false,"jev unavailable: "+ex.getClass().getSimpleName());}
    }
    private String deterministic(GoalEvaluation e,TravelState s){
        String u=String.join(" ",e==null?List.of():e.getUnmetCriteria()).toLowerCase();
        if(u.contains("flight"))return "FLIGHT"; if(u.contains("hotel"))return "HOTEL"; if(u.contains("budget")||s.overBudget())return "BUDGET"; if(u.contains("itinerary"))return "ITINERARY"; if(u.contains("weather"))return "WEATHER"; if(u.contains("research")||u.contains("knowledge"))return "RESEARCH"; return "ASK_USER";
    }
    private String description(String a){return switch(a){case "FLIGHT"->"Change flight search/provider/date/price strategy.";case "HOTEL"->"Change hotel search, budget, or accommodation strategy.";case "BUDGET"->"Reduce total cost using configurable travel levers.";case "ITINERARY"->"Regenerate itinerary using the new evidence.";case "RESEARCH"->"Acquire missing destination/research evidence.";case "WEATHER"->"Acquire or refine weather evidence.";case "ASK_USER"->"Ask the user because automation cannot safely resolve the blocker.";default->"No replan is required.";};}
    public record Decision(String action,double confidence,boolean accepted,String reason){}
}
