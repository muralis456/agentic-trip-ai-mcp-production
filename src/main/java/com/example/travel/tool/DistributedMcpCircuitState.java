package com.example.travel.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DistributedMcpCircuitState {

    private final JdbcTemplate jdbc;

    public DistributedMcpCircuitState(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isOpen(String toolName, long nowEpochMs) {
        Boolean open = jdbc.queryForObject("""
                SELECT COALESCE(cooldown_until_epoch_ms > ?, false)
                FROM mcp_tool_circuit_state
                WHERE tool_name = ?
                """, Boolean.class, nowEpochMs, toolName);
        return Boolean.TRUE.equals(open);
    }

    public void recordSuccess(String toolName) {
        jdbc.update("""
                INSERT INTO mcp_tool_circuit_state(tool_name, failure_count, cooldown_until_epoch_ms, updated_at_epoch_ms)
                VALUES (?, 0, NULL, ?)
                ON CONFLICT (tool_name) DO UPDATE
                SET failure_count = 0,
                    cooldown_until_epoch_ms = NULL,
                    updated_at_epoch_ms = EXCLUDED.updated_at_epoch_ms
                """, toolName, System.currentTimeMillis());
    }

    public void recordFailure(String toolName, int threshold, long openMs, long nowEpochMs) {
        jdbc.update("""
                INSERT INTO mcp_tool_circuit_state(tool_name, failure_count, cooldown_until_epoch_ms, updated_at_epoch_ms)
                VALUES (?, 1, NULL, ?)
                ON CONFLICT (tool_name) DO UPDATE
                SET failure_count = mcp_tool_circuit_state.failure_count + 1,
                    cooldown_until_epoch_ms = CASE
                        WHEN mcp_tool_circuit_state.failure_count + 1 >= ?
                        THEN ? + ?
                        ELSE mcp_tool_circuit_state.cooldown_until_epoch_ms
                    END,
                    updated_at_epoch_ms = EXCLUDED.updated_at_epoch_ms
                """, toolName, nowEpochMs, threshold, nowEpochMs, openMs);
    }
}
