package com.example.travel.rag;

import com.example.travel.entity.AirportLocation;
import com.example.travel.repository.AirportLocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads bundled knowledge and keeps airport/city RAG knowledge synchronized with
 * the airport_location table. The database table is the single source of truth
 * for city/airport records used by the application.
 */
@Component
@Order(2)
public class RagKnowledgeLoader implements org.springframework.boot.CommandLineRunner {

    public static final String AIRPORT_CITY_TYPE = "airport-city-knowledge";

    private static final Logger log = LoggerFactory.getLogger(RagKnowledgeLoader.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final AirportLocationRepository airportRepository;
    private final ResourcePatternResolver resourceResolver;
    private final boolean seedOnEmpty;

    public RagKnowledgeLoader(VectorStore vectorStore,
                              JdbcTemplate jdbcTemplate,
                              AirportLocationRepository airportRepository,
                              @Value("${travel.rag.seed-on-empty:true}") boolean seedOnEmpty) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.airportRepository = airportRepository;
        this.resourceResolver = new PathMatchingResourcePatternResolver();
        this.seedOnEmpty = seedOnEmpty;
    }

    @Override
    public void run(String... args) {
        syncKnowledge(false);
    }

    /** Rebuilds all bundled and database-backed knowledge. */
    public synchronized Map<String, Object> reindex() throws Exception {
        return syncKnowledge(true);
    }

    private synchronized Map<String, Object> syncKnowledge(boolean forceReindex) {
        try {
            Integer count = jdbcTemplate.queryForObject("select count(*) from vector_store", Integer.class);
            boolean empty = count == null || count == 0;

            if (forceReindex) {
                jdbcTemplate.execute("truncate table vector_store");
                empty = true;
            }

            int bundledDocuments = 0;
            int bundledChunks = 0;
            if ((forceReindex || seedOnEmpty) && empty) {
                Resource[] resources = resourceResolver.getResources("classpath:/rag/knowledge/*.md");
                List<Document> documents = new ArrayList<>();
                for (Resource resource : resources) {
                    documents.add(toDocument(resource));
                }
                List<Document> chunks = split(documents);
                vectorStore.add(chunks);
                bundledDocuments = resources.length;
                bundledChunks = chunks.size();
                log.info("Seeded bundled RAG knowledge: documents={} chunks={}", bundledDocuments, bundledChunks);
            } else if (!empty) {
                log.info("RAG knowledge base already contains {} chunks; keeping existing bundled knowledge", count);
            }

            // City/airport data is dynamic application data, so synchronize it on every startup.
            int cityDocuments = syncAirportCityKnowledge();
            int total = jdbcTemplate.queryForObject("select count(*) from vector_store", Integer.class);
            return Map.of(
                    "bundledDocuments", bundledDocuments,
                    "bundledChunks", bundledChunks,
                    "airportCityDocuments", cityDocuments,
                    "totalChunks", total
            );
        } catch (Exception ex) {
            log.warn("RAG knowledge sync skipped. Ensure PostgreSQL has pgvector and Ollama embedding model is available: {}",
                    ex.getMessage());
            return Map.of("error", String.valueOf(ex.getMessage()));
        }
    }

    private int syncAirportCityKnowledge() {
        // Remove only generated city records; never touch hand-authored travel/project knowledge.
        jdbcTemplate.update("delete from vector_store where metadata ->> 'type' = ?", AIRPORT_CITY_TYPE);

        List<AirportLocation> airports = airportRepository.findAll();
        if (airports.isEmpty()) {
            log.warn("No airport_location records found; no city knowledge generated");
            return 0;
        }

        List<Document> documents = airports.stream()
                .map(this::toAirportDocument)
                .toList();
        List<Document> chunks = split(documents);
        vectorStore.add(chunks);
        log.info("Synchronized airport/city RAG knowledge from database: cities={} chunks={}", airports.size(), chunks.size());
        return airports.size();
    }

    private Document toDocument(Resource resource) throws Exception {
        String filename = resource.getFilename();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", filename);
        String type = filename != null && filename.startsWith("project-") ? "project-knowledge"
                : (filename != null && filename.startsWith("travel-") ? "travel-knowledge" : "destination-knowledge");
        metadata.put("type", type);
        if (filename != null && filename.endsWith(".md")) {
            String base = filename.substring(0, filename.length() - 3);
            if (base.startsWith("destination-")) {
                String destination = base.substring("destination-".length());
                metadata.put("destination", destination);
                metadata.put("destinationKey", destination.split("-")[0]);
            } else if (base.startsWith("city-")) {
                metadata.put("city", base.substring("city-".length()));
                metadata.put("destinationKey", base.substring("city-".length()));
            } else if ("travel-knowledge".equals(type)) {
                metadata.put("destinationKey", "global");
            }
        }
        return new Document(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8), metadata);
    }

    private Document toAirportDocument(AirportLocation airport) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", AIRPORT_CITY_TYPE);
        metadata.put("source", "airport_location");
        metadata.put("city", airport.getCity());
        metadata.put("country", airport.getCountry());
        metadata.put("iata", airport.getIataCode());
        metadata.put("icao", airport.getIcaoCode());
        metadata.put("airport", airport.getAirportName());
        metadata.put("destinationKey", normalizeKey(airport.getCity()));

        String content = """
                # Airport and City Knowledge

                City: %s
                Country: %s
                Airport: %s
                IATA: %s
                ICAO: %s
                Location type: %s

                This record is generated from the AgenticTripAI airport_location database table.
                Use it for stable city, country, airport and IATA/ICAO lookup.
                For current flight schedules, availability, prices, delays and live operational status, use the MCP flight capability instead.
                """.formatted(
                safe(airport.getCity()), safe(airport.getCountry()), safe(airport.getAirportName()),
                safe(airport.getIataCode()), safe(airport.getIcaoCode()), safe(airport.getLocationType()));
        return new Document(content, metadata);
    }

    private List<Document> split(List<Document> documents) {
        return TokenTextSplitter.builder().build().apply(documents);
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase().trim().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "Not available" : value;
    }
}
