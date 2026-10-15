package com.example.travel.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trust-boundary helper for user/research/tool text. It does not attempt to
 * decide whether text is malicious; it marks suspicious instruction-like text
 * and wraps untrusted evidence so downstream prompts cannot confuse evidence
 * with system/developer instructions.
 */
@Component
public class PromptInjectionGuard {
    private static final Logger log = LoggerFactory.getLogger(PromptInjectionGuard.class);

    private static final Pattern INSTRUCTION_PATTERN = Pattern.compile(
            "(?i)(ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions|"
          + "system\\s+message|developer\\s+message|reveal\\s+(the\\s+)?(system|developer)?\\s*prompt|"
          + "override\\s+(the\\s+)?(system|security|policy)|disable\\s+(security|safety)|"
          + "execute\\s+(this|the)\\s+(command|tool)|call\\s+the\\s+tool|"
          + "you\\s+are\\s+now\\s+the\\s+system)");

    private static final Pattern TRUST_BOUNDARY_TAG_PATTERN =
            Pattern.compile("(?i)(</?)(system|developer|untrusted-evidence)\\b[^<>]*>");

    public GuardResult inspect(String content) {
        String value = content == null ? "" : content;
        boolean suspicious = INSTRUCTION_PATTERN.matcher(value).find();
        if (suspicious) {
            log.warn("security.prompt-injection-suspected length={} fingerprint={}", value.length(), fingerprint(value));
        }
        return new GuardResult(suspicious, sanitize(value));
    }

    public String wrapUntrusted(String label, String content) {
        GuardResult result = inspect(content);
        String requestedLabel = label == null || label.isBlank() ? "EXTERNAL_CONTENT" : label.trim();
        // Labels are metadata, not content: constrain them to a safe attribute alphabet.
        String safeLabel = requestedLabel.replaceAll("[^A-Za-z0-9._:-]", "_");
        return "<untrusted-evidence source=\"" + safeLabel + "\" suspicious=\"" + result.suspicious() + "\">\\n"
                + result.sanitized() + "\\n</untrusted-evidence>";
    }

    public String sanitize(String content) {
        if (content == null) return "";
        // Neutralize trust-boundary tags case-insensitively. Preserve the words as
        // evidence, but do not let alternate casing recreate prompt delimiters.
        Matcher matcher = TRUST_BOUNDARY_TAG_PATTERN.matcher(content);
        return matcher.replaceAll(match -> (match.group(1).startsWith("</") ? "[/" : "[")
                + match.group(2).toLowerCase(Locale.ROOT) + "]");
    }

    private String fingerprint(String value) {
        return Integer.toHexString(value.toLowerCase(Locale.ROOT).hashCode());
    }

    public record GuardResult(boolean suspicious, String sanitized) {}
}
