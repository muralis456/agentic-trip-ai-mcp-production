package com.example.travel.tool;

import com.example.travel.repository.McpToolCircuitStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DistributedMcpCircuitState {

    private static final Logger log =
            LoggerFactory.getLogger(DistributedMcpCircuitState.class);

    private final McpToolCircuitStateRepository repository;

    public DistributedMcpCircuitState(McpToolCircuitStateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public boolean isOpen(String toolName, long nowEpochMs) {
        boolean open = Boolean.TRUE.equals(repository.isOpen(toolName, nowEpochMs));

        log.debug("MCP circuit state checked tool={} open={} nowEpochMs={}",
                toolName, open, nowEpochMs);

        return open;
    }

    @Transactional
    public void recordSuccess(String toolName) {
        long now = System.currentTimeMillis();
        repository.resetCircuit(toolName, now);

        log.info("MCP circuit reset after successful tool invocation tool={} nowEpochMs={}",
                toolName, now);
    }

    @Transactional
    public void recordFailure(String toolName, int threshold, long openMs, long nowEpochMs) {
        repository.recordFailure(toolName, threshold, nowEpochMs, openMs);

        log.warn("MCP circuit failure recorded tool={} threshold={} openMs={} nowEpochMs={}",
                toolName, threshold, openMs, nowEpochMs);
    }
}
