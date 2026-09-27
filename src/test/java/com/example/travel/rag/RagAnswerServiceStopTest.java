package com.example.travel.rag;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.exception.GraphStopRequestedException;
import com.example.travel.graph.TravelState;
import com.example.travel.graph.node.RagNode;
import com.example.travel.rag.AgenticRagService.RagResult;
import com.example.travel.service.RoutedLlm;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagAnswerServiceStopTest {
    @Test
    void stopExceptionMustPropagateInsteadOfReturningGroundedFallback() {
        RoutedLlm routedLlm = mock(RoutedLlm.class);
        when(routedLlm.complete(eq(AgentRole.FINAL), anyString(), anyString()))
                .thenThrow(new GraphStopRequestedException());
        RagAnswerService service = new RagAnswerService(routedLlm);
        TravelState state = new TravelState(Map.of(
                TravelState.USER_REQUEST, "Travel in Tokyo",
                TravelState.DESTINATION, "Tokyo"));

        assertThrows(GraphStopRequestedException.class, () -> service.answer(
                state, "Travel in Tokyo", "Tokyo has local train service and seasonal rain.",
                true, 0.9, List.of("guide")));
    }

        @Test
        void ragNodeMustPropagateStopInsteadOfRecordingTaskFailure() {
                AgenticRagService ragService = mock(AgenticRagService.class);
                RagResult ragResult = mock(RagResult.class);
                when(ragService.run(any())).thenReturn(ragResult);
                when(ragResult.sufficient()).thenReturn(true);
                when(ragResult.context()).thenReturn("grounded context");
                when(ragResult.query()).thenReturn("Tokyo travel guidance");
                when(ragResult.sources()).thenReturn(List.of("guide"));
                when(ragResult.evidenceScore()).thenReturn(0.9);
                RagAnswerService answerService = mock(RagAnswerService.class);
                doThrow(new GraphStopRequestedException()).when(answerService)
                                .answer(any(), anyString(), anyString(), anyBoolean(), anyDouble(), anyList());
                RagNode node = new RagNode(ragService, answerService);

                assertThrows(GraphStopRequestedException.class,
                                () -> node.apply(new TravelState(Map.of(TravelState.DESTINATION, "Tokyo"))));
        }
}
