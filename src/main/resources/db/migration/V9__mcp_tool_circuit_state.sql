CREATE TABLE IF NOT EXISTS mcp_tool_circuit_state (
    tool_name VARCHAR(128) PRIMARY KEY,
    failure_count INTEGER NOT NULL DEFAULT 0,
    cooldown_until_epoch_ms BIGINT NULL,
    updated_at_epoch_ms BIGINT NOT NULL
);
