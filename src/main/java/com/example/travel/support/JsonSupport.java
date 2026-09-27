package com.example.travel.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JsonSupport {

    private static final Logger log = LoggerFactory.getLogger(JsonSupport.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    public JsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> Optional<T> read(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String json = unwrap(raw);
        Optional<T> direct = tryRead(json, type);
        if (direct.isPresent()) {
            return direct;
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String slice = json.substring(start, end + 1);
            Optional<T> sliced = tryRead(slice, type);
            if (sliced.isPresent()) {
                return sliced;
            }
            Optional<T> repaired = tryRead(repairLlmJson(slice), type);
            if (repaired.isPresent()) {
                return repaired;
            }
        }
        log.debug("Could not parse {} from model output", type.getSimpleName());
        return Optional.empty();
    }

    public Optional<JsonNode> readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String json = unwrap(raw);
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }
        try {
            return Optional.ofNullable(objectMapper.readTree(json));
        } catch (Exception first) {
            try {
                return Optional.ofNullable(objectMapper.readTree(repairLlmJson(json)));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
    }

    /**
     * True when text looks like a dumped JSON object (should not be shown raw in the UI).
     */
    public static boolean looksLikeJsonObject(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return (trimmed.startsWith("{") && trimmed.contains("\""))
                || trimmed.contains("{\"research\"")
                || trimmed.contains("\"attractions\"");
    }

    private <T> Optional<T> tryRead(String json, Class<T> type) {
        try {
            return Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * LLMs often insert bare "" inside prose (e.g. missing Japanese glyphs), which breaks JSON.
     * Protect legitimate empty values, then neutralize the rest.
     */
    String repairLlmJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        String protectedEmpty = json
                .replace(":\"\"", ":<<EMPTY>>")
                .replace(": \"\"", ": <<EMPTY>>");
        String neutralized = protectedEmpty.replace("\"\"", "'");
        return neutralized
                .replace(":<<EMPTY>>", ":\"\"")
                .replace(": <<EMPTY>>", ": \"\"");
    }

    private String unwrap(String raw) {
        Matcher matcher = JSON_BLOCK.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return raw.trim();
    }
}
