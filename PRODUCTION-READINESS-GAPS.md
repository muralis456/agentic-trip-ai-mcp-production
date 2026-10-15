# AgenticTripAI production-gap remediation

This checklist complements the existing security, stop/resume, MCP governance, and evaluation implementations. It distinguishes code-level regression coverage from deployment verification.

## Security invariants

- [x] MCP client authorization is deterministic Java policy, not an LLM/Jev decision.
- [x] Missing invocation identity is denied.
- [x] Unknown tools are denied.
- [x] Side-effecting actions fail closed; caller-supplied approval booleans cannot authorize them.
- [ ] Complete the cross-service server-owned approval workflow: persist approvals and bind them to authenticated approver, user, tool, exact argument digest, run ID, expiry, and single-use consumption. The current server intentionally blocks approval-gated tools until this verifier is wired into actual MCP invocation context.
- [x] External evidence is inspected for instruction-like text and wrapped as untrusted evidence.
- [x] Regression tests cover prompt injection, trust-boundary tag neutralization, identity absence, tool allow-list enforcement, approval enforcement, and argument-size limits.
- [x] Local unit tests cover idempotency key claims, concurrent duplicate requests, key reuse with a different payload, and per-user key isolation.
- [x] Trip checkpoint ownership rejects overlapping user-ID prefixes, forged thread IDs, and non-canonical UUID suffixes.
- [x] Evaluation dashboard tests verify persisted evaluation, model usage, and runtime-event queries are scoped to the authenticated principal.
- [x] Controller tests verify the authenticated principal is used for plan creation, trip history/detail, checkpoint restore/history, and event-stream ownership.
- [x] Parallel worker test verifies the authenticated user and least-privilege USER role are installed in ToolInvocationContext and cleared before a worker thread is reused.
- [ ] Extend identity tests through actual remote MCP transport and authenticated async resume end-to-end.
- [ ] Run local HTTP tests proving the MCP server rejects missing/invalid API keys and production-profile startup fails closed when required config is missing.

## Stop, resume, and side-effect safety

- [x] ProductionExecutionNode checks durable stop state before marking tasks started or submitting new specialist work.
- [x] Existing fan-out cancellation test covers stop during parallel work.
- [x] AgentRunControlService tests cover cross-user stop denial, persistence of STOP_REQUESTED, stop/completion race handling, and recovery of orphaned runs after restart.
- [x] Stopped-checkpoint lookup test prevents fallback across user boundaries.
- [ ] Add PostgreSQL-backed resume tests that verify already-terminal tasks are not invoked again after process restart.
- [ ] Define durable idempotency/reconciliation for any future non-read-only external side effect. A stopped provider request may already have completed remotely even if the local future is cancelled.
- [ ] Verify durable audit records identify run, action, approval decision, and outcome without secrets or raw tool arguments.

## Adversarial evaluation gate

Automated coverage should include:
- Tool output containing "ignore previous instructions", fake system/developer tags, or requests to invoke another tool.
- Untrusted content attempting to change destination, dates, budget, identity, permissions, or approval state.
- Missing identity, unknown tool, oversized arguments, unauthorized role, absent approval, and invalid MCP API key.
- Cross-user trip history, checkpoint, evaluation, runtime-event, and export attempts (unit/controller tests cover selected surfaces; HTTP tests for every protected resource remain outstanding).
- Replan after provider timeout, stop during parallel fan-out, and resume after process restart.
- Duplicate idempotency-key submission, key reuse with a different payload, and same key across different users.
- Malformed/low-confidence Jev output and tool descriptions containing adversarial instructions.

The expected outcome for every denied case is no provider side effect, a safe user-facing error, and a structured denial event that excludes secrets and raw arguments.

## Local test gate

- MCP server CI passed after the approval fail-closed change (workflow run #66).
- Application CI previously failed test compilation because the async identity test lacked the `ToolInvocationContext` import and inferred `Object` for a submitted callable. The missing import and callable type inference are fixed, and the worker-context test now declares `throws Exception` for its `Future.get()` interruption. Do not mark the latest changes green until CI for these fixes passes.
- PostgreSQL-backed checkpoint/idempotency tests and HTTP security integration tests remain necessary before declaring the local verification set complete.
- Production deployment and multi-replica certification are intentionally out of scope for the current local-testing phase.

## Deployment gate

- Apply production application migrations V11, V12, and V13 in order before enabling dependent evaluation/observability features; the application documents these migrations as manually applied.
- Run the MCP server with SPRING_PROFILES_ACTIVE=prod, a secret-manager-supplied non-empty MCP_API_KEY, and a shared PostgreSQL governance store.
- Test with at least two MCP server replicas and confirm circuit/rate-limit state is shared.
- Verify production startup fails when the shared governance store or API key is absent.
- Verify provider/model prices remain unavailable unless explicit rates are configured.
- Run the full Maven suite and PostgreSQL checkpoint integration tests in CI; do not treat this checklist or unit tests alone as deployment certification.

## Acceptance criteria

A release is not ready until unauthorized calls are rejected server-side, cross-user reads are blocked, approval cannot be self-granted by an LLM/tool result, stop/resume does not silently duplicate side effects, adversarial regression tests pass, and the production profile fails closed when required security dependencies are missing.
