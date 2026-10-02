package com.example.travel.tool;

import com.example.travel.repository.McpToolCircuitStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DistributedMcpCircuitState {

    private final McpToolCircuitStateRepository repository;

    public DistributedMcpCircuitState(McpToolCircuitStateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public boolean isOpen(String toolName, long nowEpochMs) {
        return Boolean.TRUE.equals(repository.isOpen(toolName, nowEpochMs));
    }

    @Transactional
    public void recordSuccess(String toolName) {
        repository.reset(toolName, System.currentTimeMillis());
    }

    @Transactional
    public void recordFailure(String toolName, int threshold, long openMs, long nowEpochMs) {
        repository.recordFailure(toolName, threshold, nowEpochMs, openMs);
    }
}
