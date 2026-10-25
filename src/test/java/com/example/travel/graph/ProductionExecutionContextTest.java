package com.example.travel.graph;

import com.example.travel.agent.*;
import com.example.travel.graph.node.HistoryNode;
import com.example.travel.graph.node.RagNode;
import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.service.AgentExecutionBudget;
import com.example.travel.service.AgentRunControlService;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.service.GraphRunContext;
import com.example.travel.service.ModelRoutingContext;
import com.example.travel.tool.AirportLookupTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionExecutionContextTest {

    @Test
    void specialistWorkerUsesSharedRunBudgetAndSelectedModelThenClearsContext() throws Exception {
        String threadId = "alice-" + java.util.UUID.randomUUID();
        AgentExecutionBudget budget = new AgentExecutionBudget(1, 1);
        GraphRunContext runContext = new GraphRunContext(budget);
        budget.begin();
        runContext.open(threadId, budget.capture(), "FAST", "alice");
        runContext.updatePolicy(threadId, "REASONING");
        budget.end();
        ModelRoutingContext.clear();

        AtomicBoolean firstCallAllowed = new AtomicBoolean();
        AtomicBoolean secondCallAllowed = new AtomicBoolean();
        AtomicReference<String> observedPolicy = new AtomicReference<>();
        RagNode ragNode = mock(RagNode.class);
        when(ragNode.apply(any())).thenAnswer(invocation -> {
            observedPolicy.set(ModelRoutingContext.get());
            firstCallAllowed.set(budget.tryConsumeLlm());
            secondCallAllowed.set(budget.tryConsumeLlm());
            return Map.of(TravelState.RAG_CONTEXT, "retrieved evidence");
        });

        AgentRunControlService runControl = mock(AgentRunControlService.class);
        when(runControl.isStopRequested(threadId)).thenReturn(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ProductionExecutionNode node = new ProductionExecutionNode(
                    mock(FlightAgentService.class), mock(HotelAgentService.class),
                    mock(TravelResearchAgentService.class), mock(WeatherAgentService.class),
                    mock(BudgetAgentService.class), mock(ItineraryAgentService.class),
                    ragNode, mock(HistoryNode.class), mock(AirportLookupTool.class),
                    executor, mock(GraphProgressHub.class), runControl, runContext);
            AgentPlan plan = new AgentPlan();
            plan.setTasks(List.of(new AgentTask("knowledge", "retrieve travel knowledge", true)));

            node.apply(new TravelState(Map.of(
                    TravelState.AGENT_PLAN, plan,
                    TravelState.GRAPH_THREAD_ID, threadId,
                    TravelState.USER_ID, "alice")));

            assertTrue(firstCallAllowed.get());
            assertFalse(secondCallAllowed.get(), "a specialist must consume the run's shared LLM budget");
            assertEquals("REASONING", observedPolicy.get());
            assertTrue(executor.submit(() ->
                    budget.capture() == null && "BALANCED".equals(ModelRoutingContext.get())).get(),
                    "pooled specialist thread must not retain the prior run's context");
        } finally {
            runContext.close(threadId);
            executor.shutdownNow();
            ModelRoutingContext.clear();
        }
    }

    @Test
    void updatedModelPolicyIsAttachedToLaterGraphNodes() {
        String threadId = "alice-" + java.util.UUID.randomUUID();
        AgentExecutionBudget budget = new AgentExecutionBudget(3, 2);
        GraphRunContext context = new GraphRunContext(budget);
        budget.begin();
        context.open(threadId, budget.capture(), "FAST", "alice");
        budget.end();
        context.updatePolicy(threadId, "REASONING");
        ModelRoutingContext.clear();

        try {
            context.attach(threadId);
            assertEquals("REASONING", ModelRoutingContext.get());
            assertEquals("alice", ModelRoutingContext.userId());
            assertEquals(threadId, ModelRoutingContext.threadId());
            assertFalse(budget.tryConsumeTavily() && budget.tryConsumeTavily()
                    && budget.tryConsumeTavily());
        } finally {
            context.detach();
            context.close(threadId);
        }
        assertNull(budget.capture());
    }
}
