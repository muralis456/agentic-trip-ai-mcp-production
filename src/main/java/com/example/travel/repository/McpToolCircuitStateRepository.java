package com.example.travel.repository;

import com.example.travel.entity.McpToolCircuitState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface McpToolCircuitStateRepository extends JpaRepository<McpToolCircuitState, String> {

    @Query("""
            select case when s.cooldownUntilEpochMs is not null
                         and s.cooldownUntilEpochMs > :nowEpochMs
                        then true else false end
            from McpToolCircuitState s
            where s.toolName = :toolName
            """)
    Boolean isOpen(@Param("toolName") String toolName,
                   @Param("nowEpochMs") long nowEpochMs);

    @Modifying
    @Query(value = """
            INSERT INTO mcp_tool_circuit_state
                (tool_name, failure_count, cooldown_until_epoch_ms, updated_at_epoch_ms)
            VALUES (:toolName, 0, NULL, :nowEpochMs)
            ON CONFLICT (tool_name) DO UPDATE
            SET failure_count = 0,
                cooldown_until_epoch_ms = NULL,
                updated_at_epoch_ms = EXCLUDED.updated_at_epoch_ms
            """, nativeQuery = true)
    int resetCircuit(@Param("toolName") String toolName,
              @Param("nowEpochMs") long nowEpochMs);

    @Modifying
    @Query(value = """
            INSERT INTO mcp_tool_circuit_state
                (tool_name, failure_count, cooldown_until_epoch_ms, updated_at_epoch_ms)
            VALUES (:toolName, 1, NULL, :nowEpochMs)
            ON CONFLICT (tool_name) DO UPDATE
            SET failure_count = mcp_tool_circuit_state.failure_count + 1,
                cooldown_until_epoch_ms =
                    CASE
                        WHEN mcp_tool_circuit_state.failure_count + 1 >= :threshold
                        THEN :nowEpochMs + :openMs
                        ELSE mcp_tool_circuit_state.cooldown_until_epoch_ms
                    END,
                updated_at_epoch_ms = EXCLUDED.updated_at_epoch_ms
            """, nativeQuery = true)
    int recordFailure(@Param("toolName") String toolName,
                      @Param("threshold") int threshold,
                      @Param("nowEpochMs") long nowEpochMs,
                      @Param("openMs") long openMs);
}
