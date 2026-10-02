package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import com.example.travel.service.ModelRoutingContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@ConditionalOnBean(JevDecisionService.class)
public class JevModelRoutingService {
    private final JevDecisionService decisions;
    public JevModelRoutingService(JevDecisionService decisions){this.decisions=decisions;}
    public String choose(TravelState state){
        String fallback=ModelRoutingContext.normalize(state.modelPolicy());
        String selectedModel = state.selectedModel();
        if (selectedModel != null && selectedModel.contains(":")) return selectedModel.trim();
        try{
            var d=decisions.choose(Map.of("request",state.userRequest(),"currentPolicy",fallback,"complexity",ModelRoutingContext.getComplexity().name(),"tripPlanning",state.isTripPlanningWorkflow(),"requestType",state.requestType(),"needsFlights",state.needsFlights(),"needsHotels",state.needsHotels(),"needsResearch",state.needsResearch(),"needsItinerary",state.needsItinerary(),"needsKnowledge",state.needsKnowledge()),
                "Choose the model policy for this graph turn. Prefer FAST for simple extraction, BALANCED for normal planning, and REASONING for complex multi-constraint recovery.",
                Map.of("FAST","Simple, low-risk structured work.","BALANCED","Normal travel planning and synthesis.","REASONING","Complex constraints, recovery, or ambiguous multi-step decisions."));
            String c=d.choice().toUpperCase();
            return d.accepted()&&Map.of("FAST",1,"BALANCED",1,"REASONING",1).containsKey(c)?c:fallback;
        }catch(Exception ignored){return fallback;}
    }
}
