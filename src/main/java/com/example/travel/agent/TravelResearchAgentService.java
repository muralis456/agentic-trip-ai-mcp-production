package com.example.travel.agent;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.graph.TravelState;
import com.example.travel.graph.model.ResearchExtraction;
import com.example.travel.model.TravelAttraction;
import com.example.travel.model.TravelResearch;
import com.example.travel.model.SearchHit;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import com.example.travel.tool.CurrencyTool;
import com.example.travel.tool.TavilySearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Research agent: LLM decides which tools to call (Tavily, currency), then structures output.
 */
@Service
public class TravelResearchAgentService {

    private static final Logger log = LoggerFactory.getLogger(TravelResearchAgentService.class);

    private final RoutedLlm routedLlm;
    private final TavilySearchTool tavilySearchTool;
    private final CurrencyTool currencyTool;
    private final JsonSupport jsonSupport;

    public TravelResearchAgentService(RoutedLlm routedLlm,
                                      TavilySearchTool tavilySearchTool,
                                      CurrencyTool currencyTool,
                                      JsonSupport jsonSupport) {
        this.routedLlm = routedLlm;
        this.tavilySearchTool = tavilySearchTool;
        this.currencyTool = currencyTool;
        this.jsonSupport = jsonSupport;
    }

    public ResearchResult research(TravelState state) {
        String destination = state.destination();
        log.info("Research agent (LLM-first) destination={}", destination);

        String content = "";
        if (state.needsResearch()) {
            try {
                String system = "You are the Travel Research Agent. Decide which tools you need. "
                        + "Use Tavily for attractions/food/local tips. Use Currency when prices need conversion. "
                        + "Do NOT call weather tools. After observations, return JSON only: "
                        + "{\"research\":[{\"topic\":\"\",\"summary\":\"\"}],"
                        + "\"attractions\":[{\"name\":\"\",\"description\":\"\",\"area\":\"\"}]}. "
                        + "Use plain ASCII in JSON strings.";
                String user = "Destination=" + destination + ", style=" + state.travelStyle()
                        + "\nReplan guidance: " + state.replanGuidance()
                        + "\nWeather from dedicated agent: "
                        + (state.weather() == null ? "n/a" : state.weather().toDisplay());
                content = routedLlm.complete(AgentRole.EXTRACT, system, user, tavilySearchTool, currencyTool);
            } catch (Exception exception) {
                    if (exception instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;
            log.warn("Research LLM failed for destination={}", destination, exception);
            }
        }

        ResearchExtraction extraction = parseExtraction(content, destination, "");
        List<TravelResearch> research = new ArrayList<>(
                extraction.getResearch() == null ? List.of() : extraction.getResearch());
        research.removeIf(item -> item == null || JsonSupport.looksLikeJsonObject(item.getSummary()));
        extraction.setResearch(research);
        if (extraction.getAttractions() == null) {
            extraction.setAttractions(List.of());
        }
        return new ResearchResult(extraction, List.of());
    }

    private ResearchExtraction parseExtraction(String content, String destination, String rawResearch) {
        return jsonSupport.read(content, ResearchExtraction.class)
                .filter(this::hasUsefulContent)
                .or(() -> fromTree(content))
                .orElseGet(() -> plaintextFallback(destination, rawResearch, content));
    }

    private boolean hasUsefulContent(ResearchExtraction extraction) {
        boolean hasResearch = extraction.getResearch() != null && extraction.getResearch().stream()
                .anyMatch(item -> item != null
                        && !TravelState.isBlank(item.getSummary())
                        && !JsonSupport.looksLikeJsonObject(item.getSummary()));
        boolean hasAttractions = extraction.getAttractions() != null && extraction.getAttractions().stream()
                .anyMatch(item -> item != null && !TravelState.isBlank(item.getName()));
        return hasResearch || hasAttractions;
    }

    private java.util.Optional<ResearchExtraction> fromTree(String content) {
        return jsonSupport.readTree(content).map(this::fromNode).filter(this::hasUsefulContent);
    }

    private ResearchExtraction fromNode(JsonNode root) {
        ResearchExtraction extraction = new ResearchExtraction();
        List<TravelResearch> research = new ArrayList<>();
        JsonNode researchNode = root.get("research");
        if (researchNode != null && researchNode.isArray()) {
            for (JsonNode node : researchNode) {
                String topic = text(node, "topic");
                String summary = text(node, "summary");
                if (!summary.isBlank() && !JsonSupport.looksLikeJsonObject(summary)) {
                    research.add(new TravelResearch(topic.isBlank() ? "Tip" : topic, summary));
                }
            }
        }
        List<TravelAttraction> attractions = new ArrayList<>();
        JsonNode attractionsNode = root.get("attractions");
        if (attractionsNode != null && attractionsNode.isArray()) {
            for (JsonNode node : attractionsNode) {
                String name = text(node, "name");
                if (!name.isBlank()) {
                    attractions.add(new TravelAttraction(name, text(node, "description"), text(node, "area")));
                }
            }
        }
        extraction.setResearch(research);
        extraction.setAttractions(attractions);
        return extraction;
    }

    private ResearchExtraction plaintextFallback(String destination, String rawResearch, String content) {
        ResearchExtraction fallback = new ResearchExtraction();
        return fromTree(content).filter(this::hasUsefulContent).orElseGet(() -> {
            String summary = readablePlaintext(rawResearch, content, destination);
            fallback.setResearch(List.of(new TravelResearch("Destination guide", summary)));
            fallback.setAttractions(List.of());
            return fallback;
        });
    }

    private String readablePlaintext(String rawResearch, String content, String destination) {
        if (!TravelState.isBlank(rawResearch) && !JsonSupport.looksLikeJsonObject(rawResearch)) {
            return trim(rawResearch, 900);
        }
        if (!TravelState.isBlank(content) && !JsonSupport.looksLikeJsonObject(content)) {
            return trim(content, 900);
        }
        return "Local tips and attractions for " + destination
                + " will be refined in the itinerary.";
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asString("").trim();
    }

    private static String trim(String value, int max) {
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "...";
    }

    public static final class ResearchResult {
        private final ResearchExtraction extraction;
        private final List<SearchHit> hits;

        public ResearchResult(ResearchExtraction extraction, List<SearchHit> hits) {
            this.extraction = extraction;
            this.hits = hits == null ? List.of() : hits;
        }

        public ResearchExtraction extraction() {
            return extraction;
        }

        public List<SearchHit> hits() {
            return hits;
        }
    }
}
