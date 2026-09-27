package com.example.travel.support;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSupportTest {

    @Test
    void repairsBareQuotesInsideProseAndParsesResearch() {
        String broken = """
                {"research":[{"topic":"Local tips","summary":"Learn phrases such as "" (konnichiwa) for hello."}],
                "attractions":[{"name":"Shibuya Crossing","description":"Busy intersection.","area":"Shibuya"}]}
                """;
        JsonSupport jsonSupport = new JsonSupport(new ObjectMapper());
        Optional<com.example.travel.graph.model.ResearchExtraction> parsed =
                jsonSupport.read(broken, com.example.travel.graph.model.ResearchExtraction.class);
        assertTrue(parsed.isPresent());
        assertTrue(parsed.get().getAttractions().stream().anyMatch(a -> "Shibuya Crossing".equals(a.getName())));
    }
}
