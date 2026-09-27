package com.example.travel.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hard caps so a looping agent cannot burn LLM/Tavily forever.
 * Counters are shared explicitly with pooled specialist threads via {@link #capture()}/{@link #use}.
 */
@Component
public class AgentExecutionBudget {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionBudget.class);

    private static final ThreadLocal<Counters> CTX = new ThreadLocal<>();

    private final int maxLlmCalls;
    private final int maxTavilyCalls;

    public AgentExecutionBudget(
            @Value("${travel.graph.max-llm-calls:20}") int maxLlmCalls,
            @Value("${travel.graph.max-tavily-calls:5}") int maxTavilyCalls) {
        this.maxLlmCalls = maxLlmCalls;
        this.maxTavilyCalls = maxTavilyCalls;
    }

    public void begin() {
        CTX.set(new Counters());
    }

    public void end() {
        CTX.remove();
    }

    public Counters capture() {
        return CTX.get();
    }

    public void use(Counters counters) {
        if (counters != null) {
            CTX.set(counters);
        }
    }

    public boolean tryConsumeLlm() {
        Counters counters = ensure();
        int next = counters.llm.incrementAndGet();
        if (next > maxLlmCalls) {
            counters.llm.decrementAndGet();
            log.warn("LLM budget exhausted ({}/{})", maxLlmCalls, maxLlmCalls);
            return false;
        }
        return true;
    }

    public boolean tavilyAvailable() {
        Counters counters = CTX.get();
        if (counters == null) {
            return true;
        }
        return counters.tavily.get() < maxTavilyCalls;
    }

    public boolean tryConsumeTavily() {
        Counters counters = ensure();
        int next = counters.tavily.incrementAndGet();
        if (next > maxTavilyCalls) {
            counters.tavily.decrementAndGet();
            log.warn("Tavily budget exhausted ({}/{})", maxTavilyCalls, maxTavilyCalls);
            return false;
        }
        return true;
    }

    private Counters ensure() {
        Counters counters = CTX.get();
        if (counters == null) {
            counters = new Counters();
            CTX.set(counters);
        }
        return counters;
    }

    public static final class Counters {
        private final AtomicInteger llm = new AtomicInteger();
        private final AtomicInteger tavily = new AtomicInteger();
    }
}
