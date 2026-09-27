package com.example.travel.graph;

import com.example.travel.agent.*;
import com.example.travel.exception.GraphStopRequestedException;
import com.example.travel.graph.node.HistoryNode;
import com.example.travel.graph.node.RagNode;
import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.service.AgentRunControlService;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.tool.AirportLookupTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductionExecutionNodeStopTest {

    @Test
    void stopRequestCancelsParallelWorkAndReturnsWithoutJoiningIt() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        AtomicBoolean stopRequested = new AtomicBoolean();
        HistoryNode historyNode = mock(HistoryNode.class);
        when(historyNode.apply(any())).thenAnswer(invocation -> {
            taskStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return Map.of();
        });

        RagNode ragNode = mock(RagNode.class);
        when(ragNode.apply(any())).thenReturn(Map.of(TravelState.RAG_CONTEXT, "context"));
        AgentRunControlService runControlService = mock(AgentRunControlService.class);
        when(runControlService.isStopRequested("thread-1")).thenAnswer(invocation -> stopRequested.get());
        ExecutorService taskExecutor = Executors.newFixedThreadPool(2);
        ProductionExecutionNode node = new ProductionExecutionNode(
                mock(FlightAgentService.class), mock(HotelAgentService.class),
                mock(TravelResearchAgentService.class), mock(WeatherAgentService.class),
                mock(BudgetAgentService.class), mock(ItineraryAgentService.class),
                ragNode, historyNode, mock(AirportLookupTool.class), taskExecutor,
                mock(GraphProgressHub.class), runControlService);

        AgentPlan plan = new AgentPlan();
        plan.setTasks(List.of(
                new AgentTask("history", "history", true),
                new AgentTask("knowledge", "rag", true)));
        TravelState state = new TravelState(Map.of(
            TravelState.AGENT_PLAN, plan,
            TravelState.GRAPH_THREAD_ID, "thread-1"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch graphFinished = new CountDownLatch(1);
        Thread graphThread = new Thread(() -> {
            try {
                node.apply(state);
            } catch (Throwable ex) {
                failure.set(ex);
            } finally {
                graphFinished.countDown();
            }
        });

        try {
            graphThread.start();
            assertTrue(taskStarted.await(2, TimeUnit.SECONDS),
                    () -> "parallel task did not start: " + failure.get());
            stopRequested.set(true);

            assertTrue(graphFinished.await(2, TimeUnit.SECONDS));
            assertInstanceOf(GraphStopRequestedException.class, failure.get());
            assertTrue(taskInterrupted.await(2, TimeUnit.SECONDS));
        } finally {
            graphThread.interrupt();
            taskExecutor.shutdownNow();
        }
    }
}