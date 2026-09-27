package com.example.travel.graph;

import com.example.travel.agent.BudgetAgentService;
import com.example.travel.agent.ItineraryAgentService;
import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runs dependency-sensitive downstream tasks after independent specialists complete. */
@Component
public class ProductionFinalizeExecutionNode implements NodeAction<TravelState> {
    private final BudgetAgentService budget;
    private final ItineraryAgentService itinerary;
    public ProductionFinalizeExecutionNode(BudgetAgentService budget, ItineraryAgentService itinerary) { this.budget=budget; this.itinerary=itinerary; }
    @Override public Map<String,Object> apply(TravelState state) {
        AgentPlan p=state.agentPlan(); Map<String,Object> u=new LinkedHashMap<>();
        AgentTask b=p.task("budget");
        if(b!=null && ready(p,b)) {
            try { b.setStatus(AgentTask.Status.RUNNING); u.put(TravelState.BUDGET_SUMMARY,budget.assess(state)); b.setStatus(AgentTask.Status.SUCCEEDED); }
            catch(Exception e){ b.setStatus(AgentTask.Status.FAILED); b.setFailureReason(msg(e)); }
        }
        AgentTask i=p.task("itinerary");
        if(i!=null && ready(p,i)) {
            try { i.setStatus(AgentTask.Status.RUNNING); u.put(TravelState.ITINERARY,itinerary.build(state)); i.setStatus(AgentTask.Status.SUCCEEDED); }
            catch(Exception e){ i.setStatus(AgentTask.Status.FAILED); i.setFailureReason(msg(e)); }
        }
        u.put(TravelState.AGENT_PLAN,p); u.putAll(TravelState.trace("execute_finalize","ok","budget/itinerary dependency phase")); return u;
    }
    private boolean ready(AgentPlan p,AgentTask t){ return p.ready(t.getId()); }
    private String msg(Exception e){return e.getMessage()==null?"execution failed":e.getMessage();}
}
