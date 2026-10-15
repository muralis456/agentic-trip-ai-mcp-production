# AgenticTripAI production-gap remediation

This checklist complements the existing security, stop/resume, MCP governance, and evaluation implementations. It distinguishes code-level regression coverage from deployment verification.

## Security invariants

- [x] MCP client authorization is deterministic Java policy, not an LLM/Jev decision.
- [x] Missing invocation identity is denied.
- [x] Unknown tools are denied.
- [x] Side-effecting actions require an authorized role and explicit approval context.
- [x] External evidence is inspected for instruction-like text and wrapped as untrusted evidence.
- [x] Regression tests cover prompt injection, trust-boundary tag neutralization, identity absence, tool allow-list enforcement, approval enforcement, and argument-size limits.
- [x] Caller-supplied approval booleans cannot authorize side-effecting or approval-gated MCP tools; the server fails closed until a server-owned approval verifier exists.
- [x] Local unit tests cover idempotency key claims, concurrent duplicate requests, key reuse with a different payload, and per-user key isolation.
- [x] Trip checkpoint ownership rejects overlapping user-ID prefixes and forged thread IDs; thread IDs must match the authenticated user plus a UUID.
- [ ] Add/verify authenticated-principal propagation across every async/executor boundary; the controller currently overwrites request userId from Authentication, but end-to-end coverage remains outstanding.
- [ ] Run local HTTP tests proving the MCP server rejects missing/invalid API keys and production-profile startup fails closed when required config is missing.

## Stop, resume, and side-effect safety

The application already exposes persisted stop/resume and graph checkpoint behavior. Before production release, exercise these invariants against PostgreSQL and the actual MCP server:

1. Stop during each provider/LLM call transitions the run to STOPPED, not FAILED.
2. Continue resumes from the latest durable checkpoint rather than creating a second live run.
3. A stop or retry never silently replays a non-idempotent side effect.
4. Every external side effect has a stable idempotency key or an explicit reconciliation/compensation strategy.
5. After stop, no new tool invocation starts; already-submitted provider requests are recorded as possibly completed when cancellation cannot be guaranteed.
6. An operator can identify the thread, action, approval decision, and outcome from durable audit events without logging secrets or raw tool arguments.

## Adversarial evaluation gate

Add or maintain automated cases for:
- Tool output containing "ignore previous instructions", fake system/developer tags, or requests to invoke another tool.
- Untrusted content attempting to change destination, dates, budget, identity, permissions, or approval state.
- Missing identity, unknown tool, oversized arguments, unauthorized role, absent approval, and invalid MCP API key.
- Cross-user attempts to read trip history, checkpoints, evaluation records, runtime events, or exported CSV data (thread ownership policy unit tests added; controller-level coverage of every resource endpoint remains outstanding).
- Replan after a provider timeout, stop during parallel fan-out, and resume after process restart.
- Idempotency-key duplicate submission, key reuse with a different payload, and same key across different users (covered by IdempotencyServiceSecurityTest).
- Malformed/low-confidence Jev output and tool descriptions containing adversarial instructions.

The expected outcome for every denied case is no provider side effect, a safe user-facing error, and a structured denial event that excludes secrets and raw arguments.

## Local test gate (current development focus)

- Run the MCP server Maven tests after the server-side approval fail-closed change, especially McpToolGovernanceSecurityTest.
- Run the production app Maven tests after the idempotency isolation regression tests, especially IdempotencyServiceSecurityTest.
- Add HTTP/controller and PostgreSQL-backed tests for authenticated identity propagation, cross-user resource access, and stop/resume across process restart before treating those gaps as closed.
- These are local/CI verification targets; production deployment and multi-replica certification are intentionally out of scope for the current local-testing phase.

## Deployment gate

- Apply the production application's database migrations V11, V12, and V13 in order before enabling evaluation/observability features that depend on them; this application currently documents these migrations as manually applied.
- Run the MCP server with SPRING_PROFILES_ACTIVE=prod, a secret-manager-supplied non-empty MCP_API_KEY, and a shared PostgreSQL governance store.
- Test with at least two MCP server replicas and confirm circuit/rate-limit state is shared.
- Verify production startup fails when the shared governance store or API key is absent.
- Verify provider/model prices remain unavailable unless explicit rates are configured.
- Run the full Maven test suite and PostgreSQL checkpoint integration tests in CI; do not treat this checklist or unit tests alone as deployment certification.

## Acceptance criteria

A release is not ready until unauthorized calls are rejected server-side, cross-user reads are blocked, approval cannot be self-granted by an LLM/tool result, stop/resume does not silently duplicate side effects, adversarial regression tests pass, and the production profile fails closed when required security dependencies are missing.
