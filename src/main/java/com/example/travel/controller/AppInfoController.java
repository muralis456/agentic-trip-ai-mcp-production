package com.example.travel.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class AppInfoController {

    @Value("${spring.application.name:agentic-trip-ai}")
    private String applicationName;

    /**
     * Some browser extensions probe {@code /json/version}; without this mapping Spring logs 404s as errors.
     */
    @GetMapping("/json/version")
    public Map<String, Object> jsonVersion() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "AgenticTripAI");
        body.put("application", applicationName);
        body.put("version", "0.0.1-SNAPSHOT");
        return body;
    }
}
