package com.example.travel.graph.node;

import com.example.travel.graph.GraphExecutionLogger;
import com.example.travel.graph.NodeFailureSupport;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.exception.GraphStopRequestedException;
import com.example.travel.rag.AgenticRagService;
import com.example.travel.jev.JevRagDecisionService;
import com.example.travel.tool.TavilySearchTool;
import com.example.travel.model.SearchHit;
import com.example.travel.rag.RagAnswerService;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

@Component
public class RagNode implements NodeAction<TravelState> {

    private final AgenticRagService agenticRagService;
    private final RagAnswerService ragAnswerService;
    private final java.util.Optional<JevRagDecisionService> jevRagDecision;
    private final java.util.Optional<TavilySearchTool> tavilySearchTool;

    @org.springframework.beans.factory.annotation.Autowired
    public RagNode(
            AgenticRagService agenticRagService,
            RagAnswerService ragAnswerService,
            java.util.Optional<JevRagDecisionService> jevRagDecision,
            java.util.Optional<TavilySearchTool> tavilySearchTool) {
        this.agenticRagService = agenticRagService;
        this.ragAnswerService = ragAnswerService;
        this.jevRagDecision = jevRagDecision;
        this.tavilySearchTool = tavilySearchTool;
    }

    /** Backward-compatible constructor for existing tests. */
    public RagNode(AgenticRagService agenticRagService, RagAnswerService ragAnswerService) {
        this(agenticRagService, ragAnswerService, java.util.Optional.empty(), java.util.Optional.empty());
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        try {
            String routeName = "RAG";
            if (jevRagDecision.isPresent()) {
                routeName = jevRagDecision.get().decide(state).route();
                if (!List.of("RAG", "WEB", "BOTH", "NONE").contains(routeName)) {
                    routeName = state.needsKnowledge() ? "RAG" : "NONE";
                }
                if ("NONE".equals(routeName)) {
                    Map<String,Object> skipped = new LinkedHashMap<>();
                    skipped.put(TravelState.RAG_ENABLED, Boolean.FALSE);
                    skipped.put(TravelState.RAG_DECISION, "NONE");
                    skipped.putAll(TravelState.trace(TravelGraphNodes.RAG, "skip", "jev decision=" + routeName));
                    return skipped;
                }
            }
            AgenticRagService.RagResult result = null;
            if ("RAG".equals(routeName) || "BOTH".equals(routeName)) {
                result = agenticRagService.run(state);
            }
            if ("WEB".equals(routeName) || "BOTH".equals(routeName)) {
                if (tavilySearchTool.isEmpty()) {
                    if ("WEB".equals(routeName)) {
                        throw new IllegalStateException("WEB evidence route selected but TavilySearchTool is not available.");
                    }
                } else {
                    List<SearchHit> hits = tavilySearchTool.get().searchHits(state.userRequest());
                    StringBuilder web = new StringBuilder();
                    List<String> webSources = new java.util.ArrayList<>();
                    hits.stream().limit(5).forEach(h -> {
                        web.append("[Web: ").append(h.getTitle()).append("]\\n")
                                .append(h.getContent()).append("\\n\\n");
                        if (h.getUrl()!=null && !h.getUrl().isBlank()) {
                            webSources.add(h.getUrl());
                        }
                    });
                    if ("WEB".equals(routeName)) {
                        String context = web.toString().trim();
                        result = new AgenticRagService.RagResult(true, "web", state.userRequest(), context,
                                webSources, 1, !context.isBlank(), "web", hits.size(), hits.size(), context.length(),
                                context.isBlank()?0.0:1.0, state.destination(), "", List.of());
                    } else if ("BOTH".equals(routeName) && result != null && !web.isEmpty()) {
                        String context = result.context() + "\\n\\n" + web;
                        List<String> sources = new java.util.ArrayList<>(result.sources()); sources.addAll(webSources);
                        result = new AgenticRagService.RagResult(result.used(), "both", result.query(), context,
                                sources.stream().distinct().toList(), result.iterations()+1, result.sufficient() || !web.isEmpty(),
                                result.retrievalMethod()+"+web", result.candidateCount()+hits.size(), result.rerankedCount()+hits.size(), context.length(),
                                Math.max(result.evidenceScore(), web.isEmpty()?0.0:1.0), result.destination(), result.country(), result.topics());
                    }
                }
            }
            if (result == null) {
                result = new AgenticRagService.RagResult(false, "none", state.userRequest(), "", List.of(), 0, false,
                        "none", 0, 0, 0, 0.0, state.destination(), "", List.of());
            }
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(TravelState.RAG_ENABLED, !"NONE".equals(routeName));
            updates.put(TravelState.RAG_DECISION, result.decision());
            updates.put(TravelState.RAG_QUERY, result.query());
            updates.put(TravelState.RAG_CONTEXT, result.context());
            String ragAnswer = result.sufficient()
                    ? ragAnswerService.answer(state, result.query(), result.context(),
                            result.sufficient(), result.evidenceScore(), result.sources())
                    : "";
            updates.put(TravelState.RAG_ANSWER, ragAnswer);
            if (TravelState.isBlank(state.destination()) && !TravelState.isBlank(result.destination())) {
                updates.put(TravelState.DESTINATION, result.destination());
            }
            updates.put(TravelState.RAG_SOURCES, result.sources());
            updates.put(TravelState.RAG_ITERATIONS, result.iterations());
            updates.put(TravelState.RAG_SUFFICIENT, result.sufficient());
            updates.put(TravelState.RAG_RETRIEVAL_METHOD, result.retrievalMethod());
            updates.put(TravelState.RAG_CANDIDATE_COUNT, result.candidateCount());
            updates.put(TravelState.RAG_RERANKED_COUNT, result.rerankedCount());
            updates.put(TravelState.RAG_CONTEXT_CHARS, result.contextChars());
            String ragDestination = TravelState.firstNonBlank(result.destination(), state.destination());
            updates.put(TravelState.RAG_DESTINATION, ragDestination);
            updates.put(TravelState.RAG_COUNTRY, result.country());
            updates.put(TravelState.RAG_TOPICS, result.topics());
            updates.put(TravelState.RAG_EVIDENCE_SCORE, result.evidenceScore());
            updates.putAll(TravelState.trace(TravelGraphNodes.RAG, "ok", result.decision()));
            GraphExecutionLogger.specialistResult(TravelGraphNodes.RAG, state, "ok",
                    "decision=" + result.decision() + " method=" + result.retrievalMethod() + " candidates=" + result.candidateCount() + " reranked=" + result.rerankedCount() + " iterations=" + result.iterations() + " sources=" + result.sources().size());
            return updates;
        } catch (GraphStopRequestedException stopped) {
            throw stopped;
        } catch (Exception ex) {
            return NodeFailureSupport.record(TravelGraphNodes.RAG, state, ex, true,
                    state.nodeFailure().getNodeRetryCount());
        }
    }
}
