# Agent Observability

This branch adds the first production observability layer for AgenticTripAI.

## Endpoints

- Actuator health: `/actuator/health`
- Metrics: `/actuator/metrics`
- Prometheus scrape endpoint: `/actuator/prometheus`

Spring Boot/Micrometer provides HTTP instrumentation and Spring AI observations. `AgentObservabilityService` emits bounded application metrics.

## Application metrics

| Metric | Purpose |
|---|---|
| `travel.agent.runs` | Agent planning boundary outcome |
| `travel.agent.run` | Agent planning latency |
| `travel.agent.nodes` | Graph execution-wave outcome |
| `travel.agent.node` | Graph execution-wave latency |
| `travel.mcp.calls` | MCP tool outcome |
| `travel.mcp.call` | MCP tool latency |
| `travel.mcp.retries` | MCP retry count |
| `travel.agent.retries` | Agent retry events |
| `travel.agent.replans` | Replan events |
| `travel.agent.goal.outcomes` | Goal completion/failure outcomes |
| `travel.llm.requests` | LLM call count when token usage is recorded |
| `travel.llm.tokens` | Input/output token totals |
| `travel.llm.cost.usd` | Estimated token cost when rates are configured |
| `travel.provider.calls` | External provider outcome |
| `travel.provider.call` | External provider latency |

Token/cost recording is provider-agnostic. When an adapter has exact usage, call `recordLlmUsage(provider, model, operation, inputTokens, outputTokens)`. Token counts remain metric values rather than labels.

## Tracing

Micrometer Tracing bridges to OpenTelemetry. HTTP requests, application observations, and downstream instrumented clients can share trace/span context.

Configure `TRAVEL_OBSERVABILITY_TRACE_SAMPLE_RATE` and `OTEL_EXPORTER_OTLP_ENDPOINT`. Default OTLP endpoint is `http://localhost:4318/v1/traces`.

## Recommended dashboards

1. Agent Overview — request rate, goal outcomes, p95 latency.
2. LLM & Tokens — input/output tokens, requests, estimated cost.
3. MCP — tool calls, failures, retries, p95 latency.
4. Provider Health — provider failures, 4xx/5xx/429 and latency.
5. Agent Behavior — retries, replans, node failures.

## Cardinality and privacy

User IDs, prompts, raw tool arguments, authorization tokens, and provider payloads are never metric labels. Keep sensitive operational details in controlled logs/audit records instead.

## Local smoke test

After starting the application, check `http://localhost:8081/actuator/health` and `http://localhost:8081/actuator/prometheus`. Execute one travel plan and verify that `travel.agent.*` and `travel.mcp.*` series appear.