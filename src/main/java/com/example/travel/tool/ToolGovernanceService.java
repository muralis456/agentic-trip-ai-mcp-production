package com.example.travel.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.travel.exception.ToolApprovalRequiredException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Deterministic policy enforcement point for every MCP invocation.
 *
 * <p>The LLM may select a tool, but it never decides whether that tool is
 * permitted. This service evaluates identity, tool allowlisting, role policy,
 * argument safety, side-effect classification and approval requirements before
 * the ToolCallback is invoked.</p>
 */
@Service
public class ToolGovernanceService {
    private static final Logger log = LoggerFactory.getLogger(ToolGovernanceService.class);

    private enum ActionClass {
        READ_ONLY,
        SIDE_EFFECTING
    }

    private final boolean enabled;
    private final int maxArgumentBytes;
    private final Set<String> allowedTools;
    private final Set<String> allowedUsers;
    private final Map<String, EnumSet<ActionClass>> rolePermissions;

    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final int circuitFailureThreshold;
    private final long circuitOpenMs;

    public ToolGovernanceService(
            @Value("${travel.mcp.governance.enabled:true}") boolean enabled,
            @Value("${travel.mcp.governance.max-argument-bytes:16384}") int maxArgumentBytes,
            @Value("${travel.mcp.governance.allowed-tools:search_flights,search_hotels,get_weather,resolve_airport,search_travel_research,generate_itinerary}") String configuredTools,
            @Value("${travel.mcp.governance.allowed-users:}") String configuredUsers,
            @Value("${travel.mcp.governance.roles:USER:READ_ONLY;ADMIN:READ_ONLY,SIDE_EFFECTING}") String configuredRoles,
            @Value("${travel.mcp.governance.circuit-failure-threshold:3}") int circuitFailureThreshold,
            @Value("${travel.mcp.governance.circuit-open-ms:30000}") long circuitOpenMs) {
        this.enabled = enabled;
        this.maxArgumentBytes = Math.max(1024, maxArgumentBytes);
        this.allowedTools = parseSet(configuredTools);
        this.allowedUsers = parseSet(configuredUsers);
        this.rolePermissions = parseRoles(configuredRoles);
        this.circuitFailureThreshold = Math.max(1, circuitFailureThreshold);
        this.circuitOpenMs = Math.max(1000, circuitOpenMs);
    }

    /**
     * Authorize one concrete MCP invocation.
     *
     * @throws SecurityException when the identity/tool/arguments violate policy
     * @throws ToolApprovalRequiredException when a side-effecting action needs HITL
     */
    public void authorize(String toolName, String agentPurpose, String argumentsJson) {
        if (!enabled) {
            return;
        }

        if (toolName == null || toolName.isBlank()) {
            throw new SecurityException("MCP tool name is required");
        }

        ToolInvocationContext.Context context = ToolInvocationContext.current()
                .orElseThrow(() -> new SecurityException("Authenticated MCP invocation context is required"));

        String userId = context.userId();
        if (userId == null || userId.isBlank()) {
            throw new SecurityException("Authenticated user is required for MCP tool execution");
        }

        if (!allowedUsers.isEmpty() && !allowedUsers.contains(userId)) {
            deny(toolName, userId, "user-not-allowed");
            throw new SecurityException("User is not allowed to invoke MCP tools: " + userId);
        }

        if (!allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
            deny(toolName, userId, "tool-not-allowed");
            throw new SecurityException("MCP tool is not allowed: " + toolName);
        }

        if (argumentsJson != null
                && argumentsJson.getBytes(StandardCharsets.UTF_8).length > maxArgumentBytes) {
            deny(toolName, userId, "arguments-too-large");
            throw new SecurityException("MCP tool arguments exceed the configured safety limit");
        }

        String key = key(toolName);
        Long until = cooldownUntil.get(key);
        if (until != null && until > System.currentTimeMillis()) {
            throw new IllegalStateException("MCP tool circuit is open: " + toolName);
        }

        ActionClass actionClass = classify(toolName);
        String role = normalizeRole(context.role());
        EnumSet<ActionClass> permissions = rolePermissions.getOrDefault(
                role, EnumSet.noneOf(ActionClass.class));

        if (!permissions.contains(actionClass)) {
            deny(toolName, userId, "role-not-allowed role=" + role);
            throw new SecurityException("Role " + role + " is not allowed to invoke " + toolName);
        }

        if (actionClass == ActionClass.SIDE_EFFECTING && !context.approvalGranted()) {
            log.info("mcp.governance.approval-required tool={} userId={} role={}",
                    toolName, userId, role);
            throw new ToolApprovalRequiredException(toolName,
                    "Human approval is required before executing side-effecting MCP tool: " + toolName);
        }

        log.info("mcp.governance.allow tool={} userId={} role={} actionClass={} purpose={}",
                toolName, userId, role, actionClass, abbreviate(agentPurpose));
    }

    private ActionClass classify(String toolName) {
        String normalized = toolName.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.matches(".*(book|booking|cancel|delete|pay|payment|charge|purchase|transfer|create_order|confirm).*")) {
            return ActionClass.SIDE_EFFECTING;
        }
        return ActionClass.READ_ONLY;
    }

    private void deny(String toolName, String userId, String reason) {
        log.warn("mcp.governance.deny tool={} userId={} reason={}", toolName, userId, reason);
    }

    public void recordSuccess(String toolName) {
        failures.remove(key(toolName));
        cooldownUntil.remove(key(toolName));
    }

    public void recordFailure(String toolName) {
        String key = key(toolName);
        int count = failures.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        if (count >= circuitFailureThreshold) {
            cooldownUntil.put(key, System.currentTimeMillis() + circuitOpenMs);
            log.warn("mcp.governance.circuit-open tool={} failures={} openMs={}",
                    toolName, count, circuitOpenMs);
        }
    }

    private Set<String> parseSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Map<String, EnumSet<ActionClass>> parseRoles(String value) {
        Map<String, EnumSet<ActionClass>> result = new ConcurrentHashMap<>();
        if (value == null || value.isBlank()) {
            return Map.of("USER", EnumSet.of(ActionClass.READ_ONLY));
        }

        for (String definition : value.split(";")) {
            String[] parts = definition.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            EnumSet<ActionClass> permissions = EnumSet.noneOf(ActionClass.class);
            for (String permission : parts[1].split(",")) {
                try {
                    permissions.add(ActionClass.valueOf(permission.trim().toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // Ignore unknown configuration values rather than granting access.
                }
            }
            result.put(normalizeRole(parts[0]), permissions);
        }

        return Map.copyOf(result);
    }

    private String normalizeRole(String role) {
        return role == null || role.isBlank()
                ? "USER"
                : role.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String key(String value) {
        return value == null ? "" : value.trim();
    }

    private String abbreviate(String value) {
        if (value == null) return "";
        String normalized = value.replace("\n", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }
}
