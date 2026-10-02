package com.example.travel.tool;

import com.example.travel.exception.ToolApprovalRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Deterministic policy enforcement point for every MCP invocation.
 *
 * <p>The LLM may select a tool, but it never decides whether that tool is
 * permitted. Policy is explicit per tool, identity-aware and independent from
 * tool descriptions.</p>
 */
@Service
public class ToolGovernanceService {
    private static final Logger log = LoggerFactory.getLogger(ToolGovernanceService.class);

    public enum ActionClass {
        READ_ONLY,
        SIDE_EFFECTING
    }

    private final boolean enabled;
    private final int maxArgumentBytes;
    private final Set<String> allowedUsers;
    private final Map<String, ActionClass> toolPolicies;
    private final Map<String, EnumSet<ActionClass>> rolePermissions;
    private final Set<String> approvalTools;

    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final int circuitFailureThreshold;
    private final long circuitOpenMs;
    private final DistributedMcpCircuitState distributedCircuitState;

    public ToolGovernanceService(
            @Value("${travel.mcp.governance.enabled:true}") boolean enabled,
            @Value("${travel.mcp.governance.max-argument-bytes:16384}") int maxArgumentBytes,
            @Value("${travel.mcp.governance.allowed-users:}") String configuredUsers,
            @Value("${travel.mcp.governance.tools:search_flights:READ_ONLY,search_hotels:READ_ONLY,get_weather:READ_ONLY,resolve_airport:READ_ONLY,search_travel_research:READ_ONLY,generate_itinerary:READ_ONLY}") String configuredTools,
            @Value("${travel.mcp.governance.approval-tools:}") String configuredApprovalTools,
            @Value("${travel.mcp.governance.roles:USER:READ_ONLY;ADMIN:READ_ONLY,SIDE_EFFECTING}") String configuredRoles,
            @Value("${travel.mcp.governance.circuit-failure-threshold:3}") int circuitFailureThreshold,
            @Value("${travel.mcp.governance.circuit-open-ms:30000}") long circuitOpenMs) ,
            ObjectProvider<DistributedMcpCircuitState> distributedCircuitState) {
        this.enabled = enabled;
        this.maxArgumentBytes = Math.max(1024, maxArgumentBytes);
        this.allowedUsers = parseSet(configuredUsers);
        this.toolPolicies = parseToolPolicies(configuredTools);
        this.approvalTools = parseSet(configuredApprovalTools);
        this.rolePermissions = parseRoles(configuredRoles);
        this.circuitFailureThreshold = Math.max(1, circuitFailureThreshold);
        this.circuitOpenMs = Math.max(1000, circuitOpenMs);
        this.distributedCircuitState = distributedCircuitState.getIfAvailable();
    }

    public ToolGovernanceService(
            boolean enabled,
            int maxArgumentBytes,
            String configuredUsers,
            String configuredTools,
            String configuredApprovalTools,
            String configuredRoles,
            int circuitFailureThreshold,
            long circuitOpenMs) {
        this(enabled, maxArgumentBytes, configuredUsers, configuredTools, configuredApprovalTools,
                configuredRoles, circuitFailureThreshold, circuitOpenMs, ObjectProviderStub.empty());
    }

    public void authorize(String toolName, String agentPurpose, String argumentsJson) {
        if (!enabled) return;

        String normalizedTool = normalize(toolName);
        if (normalizedTool.isBlank()) {
            throw new SecurityException("MCP tool name is required");
        }

        ToolInvocationContext.Context context = ToolInvocationContext.current()
                .orElseThrow(() -> new SecurityException("Authenticated MCP invocation context is required"));

        String userId = context.userId();
        String role = normalizeRole(context.role());

        if (userId == null || userId.isBlank()) {
            deny(normalizedTool, userId, role, "AUTHENTICATED_USER_REQUIRED");
            throw new SecurityException("Authenticated user is required for MCP tool execution");
        }

        if (!allowedUsers.isEmpty() && !allowedUsers.contains(userId)) {
            deny(normalizedTool, userId, role, "USER_NOT_ALLOWED");
            throw new SecurityException("User is not allowed to invoke MCP tools: " + userId);
        }

        ActionClass actionClass = toolPolicies.get(normalizedTool);
        if (actionClass == null) {
            deny(normalizedTool, userId, role, "TOOL_NOT_CONFIGURED");
            throw new SecurityException("MCP tool is not configured in the governance policy: " + normalizedTool);
        }

        if (argumentsJson != null
                && argumentsJson.getBytes(StandardCharsets.UTF_8).length > maxArgumentBytes) {
            deny(normalizedTool, userId, role, "ARGUMENTS_TOO_LARGE");
            throw new SecurityException("MCP tool arguments exceed the configured safety limit");
        }

        long now = System.currentTimeMillis();
        Long until = cooldownUntil.get(normalizedTool);
        boolean distributedOpen = distributedCircuitState != null
                && distributedCircuitState.isOpen(normalizedTool, now);
        if ((until != null && until > now) || distributedOpen) {
            deny(normalizedTool, userId, role, "CIRCUIT_OPEN");
            throw new IllegalStateException("MCP tool circuit is open: " + normalizedTool);
        }

        EnumSet<ActionClass> permissions = rolePermissions.getOrDefault(
                role, EnumSet.noneOf(ActionClass.class));
        if (!permissions.contains(actionClass)) {
            deny(normalizedTool, userId, role, "ROLE_NOT_ALLOWED");
            throw new SecurityException("Role " + role + " is not allowed to invoke " + normalizedTool);
        }

        boolean approvalRequired = actionClass == ActionClass.SIDE_EFFECTING
                || approvalTools.contains(normalizedTool);
        if (approvalRequired && !context.approvalGranted()) {
            log.info("mcp.policy tool={} userId={} role={} action={} decision=APPROVAL_REQUIRED purpose={}",
                    normalizedTool, userId, role, actionClass, abbreviate(agentPurpose));
            throw new ToolApprovalRequiredException(normalizedTool,
                    "Human approval is required before executing MCP tool: " + normalizedTool);
        }

        log.info("mcp.policy tool={} userId={} role={} action={} decision=ALLOW purpose={}",
                normalizedTool, userId, role, actionClass, abbreviate(agentPurpose));
    }

    public ActionClass actionClass(String toolName) {
        return toolPolicies.get(normalize(toolName));
    }

    public boolean isToolConfigured(String toolName) {
        return toolPolicies.containsKey(normalize(toolName));
    }

    public boolean requiresApproval(String toolName) {
        String normalized = normalize(toolName);
        return approvalTools.contains(normalized)
                || toolPolicies.get(normalized) == ActionClass.SIDE_EFFECTING;
    }

    public void recordSuccess(String toolName) {
        String key = normalize(toolName);
        failures.remove(key);
        cooldownUntil.remove(key);
        if (distributedCircuitState != null) {
            distributedCircuitState.recordSuccess(key);
        }
    }

    public void recordFailure(String toolName) {
        String key = normalize(toolName);
        int count = failures.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        if (count >= circuitFailureThreshold) {
            cooldownUntil.put(key, System.currentTimeMillis() + circuitOpenMs);
            log.warn("mcp.policy circuit-open tool={} failures={} openMs={} distributed={}",
                    key, count, circuitOpenMs, distributedCircuitState != null);
        }
        if (distributedCircuitState != null) {
            distributedCircuitState.recordFailure(key, circuitFailureThreshold, circuitOpenMs,
                    System.currentTimeMillis());
        }
    }

    private static final class ObjectProviderStub {
        static ObjectProvider<DistributedMcpCircuitState> empty() {
            return new ObjectProvider<>() {
                @Override public DistributedMcpCircuitState getObject(Object... args) { return null; }
                @Override public DistributedMcpCircuitState getIfAvailable() { return null; }
                @Override public DistributedMcpCircuitState getIfUnique() { return null; }
                @Override public java.util.stream.Stream<DistributedMcpCircuitState> orderedStream() { return java.util.stream.Stream.empty(); }
                @Override public java.util.stream.Stream<DistributedMcpCircuitState> stream() { return java.util.stream.Stream.empty(); }
            };
        }
    }

    private Map<String, ActionClass> parseToolPolicies(String value) {
        if (value == null || value.isBlank()) return Map.of();
        Map<String, ActionClass> result = new ConcurrentHashMap<>();
        for (String definition : value.split(",")) {
            String[] parts = definition.trim().split(":", 2);
            if (parts.length != 2) continue;
            try {
                ActionClass action = ActionClass.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
                result.put(normalize(parts[0]), action);
            } catch (IllegalArgumentException ignored) {
                log.warn("mcp.policy invalid-tool-policy entry={}", definition);
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, EnumSet<ActionClass>> parseRoles(String value) {
        Map<String, EnumSet<ActionClass>> result = new ConcurrentHashMap<>();
        if (value == null || value.isBlank()) {
            return Map.of("USER", EnumSet.of(ActionClass.READ_ONLY));
        }
        for (String definition : value.split(";")) {
            String[] parts = definition.split(":", 2);
            if (parts.length != 2) continue;
            EnumSet<ActionClass> permissions = EnumSet.noneOf(ActionClass.class);
            for (String permission : parts[1].split(",")) {
                try {
                    permissions.add(ActionClass.valueOf(permission.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // Unknown values never grant permissions.
                }
            }
            result.put(normalizeRole(parts[0]), permissions);
        }
        return Map.copyOf(result);
    }

    private Set<String> parseSet(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRole(String value) {
        return value == null || value.isBlank() ? "USER" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void deny(String toolName, String userId, String role, String reason) {
        log.warn("mcp.policy tool={} userId={} role={} decision=DENY reason={}",
                toolName, userId, role, reason);
    }

    private String abbreviate(String value) {
        if (value == null) return "";
        String normalized = value.replace("\n", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }
}
