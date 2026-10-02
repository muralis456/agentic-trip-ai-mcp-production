package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.Locale;
import java.util.Map;

@Service
@ConditionalOnBean(JevDecisionService.class)
public class JevProviderDecisionService {
    private static final Logger log = LoggerFactory.getLogger(JevProviderDecisionService.class);
    private final JevDecisionService decisions;

    public JevProviderDecisionService(JevDecisionService decisions) {
        this.decisions = decisions;
    }

    public Decision choose(TravelState state, String failure) {
        log.info(
                "jev.provider.decision-start failurePresent={}",
                failure != null && !failure.isBlank());

        try {
            var d = decisions.choose(
                    JevDecisionPolicy.DecisionKind.PROVIDER,
                    Map.of(
                            "capability", "flight search",
                            "failure", failure == null ? "" : failure,
                            "request", state.userRequest()),
                    "Choose the preferred flight provider to try next. This is only a routing preference; authorization, availability, circuit state and actual fallback remain deterministic server policy.",
                    Map.of(
                            "IGNAV", "Use Ignav when available for structured live flight offers.",
                            "AVIATIONSTACK", "Use AviationStack as the configured flight provider."));

            String c = d.choice().toUpperCase();
            boolean valid = Map.of("IGNAV", 1, "AVIATIONSTACK", 1).containsKey(c);
            Decision result = d.accepted() && valid
                    ? new Decision(c, d.confidence(), true, "jev-policy-accepted")
                    : new Decision("", d.confidence(), false, "deterministic-provider-policy-fallback");

            log.info(
                    "jev.provider.decision outcome={} recommendation={} confidence={} accepted={} reason={} model={}",
                    result.provider(),
                    c,
                    fmt(result.confidence()),
                    result.accepted(),
                    result.reason(),
                    d.model());
            return result;
        } catch (Exception ex) {
            log.warn(
                    "jev.provider.decision-fallback reason={}",
                    ex.getClass().getSimpleName());
            return new Decision("", 0, false, "jev unavailable: " + ex.getClass().getSimpleName());
        }
    }

    private static String fmt(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    public record Decision(String provider, double confidence, boolean accepted, String reason) { }
}
