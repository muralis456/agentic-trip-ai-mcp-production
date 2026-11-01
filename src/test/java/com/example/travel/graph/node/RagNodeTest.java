package com.example.travel.graph.node;

import com.example.travel.dto.TravelRequest;
import com.example.travel.graph.TravelState;
import com.example.travel.jev.JevRagDecisionService;
import com.example.travel.rag.AgenticRagService;
import com.example.travel.rag.RagAnswerService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagNodeTest {

    @Test
    void explicitKnowledgeIntentCannotBeSkippedByJevNoneRoute() {
        AgenticRagService rag = mock(AgenticRagService.class);
        RagAnswerService answer = mock(RagAnswerService.class);
        JevRagDecisionService jev = mock(JevRagDecisionService.class);
        when(jev.decide(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JevRagDecisionService.Decision("NONE", 0.9, true, "test"));
        when(rag.run(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AgenticRagService.RagResult(true, "rag", "tips for Tokyo", "Grounded travel facts",
                        List.of("tokyo.md"), 1, true, "fast_path", 2, 2, 100,
                        0.9, "Tokyo", "Japan", List.of("transport")));
        when(answer.answer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("- Use local transit cards for city travel.");

        TravelRequest request = new TravelRequest();
        request.setPrompt("Plan a complete trip to Tokyo");
        Map<String, Object> input = TravelState.fromRequest(request, "");
        input.put(TravelState.NEEDS_KNOWLEDGE, true);
        TravelState state = new TravelState(input);
        RagNode node = new RagNode(rag, answer, Optional.of(jev), Optional.empty());

        Map<String, Object> updates = node.apply(state);

        verify(rag).run(state);
        assertEquals("Grounded travel facts", updates.get(TravelState.RAG_CONTEXT));
        assertEquals("- Use local transit cards for city travel.", updates.get(TravelState.RAG_ANSWER));
    }
}
