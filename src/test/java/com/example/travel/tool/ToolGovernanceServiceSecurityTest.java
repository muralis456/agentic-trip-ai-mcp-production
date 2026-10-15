package com.example.travel.tool;

import com.example.travel.exception.ToolApprovalRequiredException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;

class ToolGovernanceServiceSecurityTest {
    private ToolGovernanceService newGovernance() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        return new ToolGovernanceService(true, 2048, "",
                "search_flights:READ_ONLY,book_flight:SIDE_EFFECTING", "",
                "USER:READ_ONLY;ADMIN:READ_ONLY,SIDE_EFFECTING", 3, 30_000L,
                beans.getBeanProvider(DistributedMcpCircuitState.class));
    }

    @Test
    void deniesToolInvocationWithoutIdentityContext() {
        assertThrows(SecurityException.class,
                () -> newGovernance().authorize("search_flights", "travel search", "{}"));
    }

    @Test
    void deniesUnconfiguredToolsEvenForAdmin() {
        try (ToolInvocationContext.Scope ignored = ToolInvocationContext.open("admin-1", "ADMIN", true)) {
            assertThrows(SecurityException.class,
                    () -> newGovernance().authorize("shell_exec", "debug", "{}"));
        }
    }

    @Test
    void requiresApprovalForSideEffectingTool() {
        try (ToolInvocationContext.Scope ignored = ToolInvocationContext.open("user-1", "ADMIN", false)) {
            assertThrows(ToolApprovalRequiredException.class,
                    () -> newGovernance().authorize("book_flight", "book selected flight", "{}"));
        }
    }

    @Test
    void allowsSideEffectingToolOnlyWithPrivilegedRoleAndApproval() {
        try (ToolInvocationContext.Scope ignored = ToolInvocationContext.open("user-1", "ADMIN", true)) {
            assertDoesNotThrow(() -> newGovernance().authorize("book_flight", "book selected flight", "{}"));
        }
    }

    @Test
    void deniesSideEffectingToolForRegularUserEvenWhenApprovalFlagIsSet() {
        try (ToolInvocationContext.Scope ignored = ToolInvocationContext.open("user-1", "USER", true)) {
            assertThrows(SecurityException.class,
                    () -> newGovernance().authorize("book_flight", "book selected flight", "{}"));
        }
    }

    @Test
    void deniesArgumentsAboveConfiguredByteLimit() {
        String oversized = "x".repeat(4096);
        try (ToolInvocationContext.Scope ignored = ToolInvocationContext.open("user-1", "USER", false)) {
            assertThrows(SecurityException.class,
                    () -> newGovernance().authorize("search_flights", "search", oversized));
        }
    }
}
