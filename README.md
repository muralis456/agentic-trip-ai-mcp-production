# AgenticTripAI

**An Agentic AI Travel Planning & Orchestration Platform**

Graph-orchestrated multi-agent system: LangGraph4j decides *what runs next*; specialist agents live in `com.example.travel.agent`. Infrastructure stays in `com.example.travel.service`.

## Architecture branding

```
AgenticTripAI
 ├── Spring AI
 ├── LangGraph4j
 ├── Multi-Agent Orchestration
 ├── Tool Calling
 ├── Parallel Agents
 ├── Autonomous Replanning
 ├── Persistent Memory
 └── Human-in-the-Loop
```

## Workflow

```
USER → Semantic Intent LLM → schema validation → Planner → Router → Planner → Router
         → Airport (only if needsFlights)
         → fan_out (native LangGraph parallel fan-out)
              ├→ Flight
              ├→ Hotel
              ├→ Research
              └→ Weather  (each node no-ops when its needs* flag is false)
         → Supervisor (deterministic + LLM quality hint → proceed | retry)
         → Budget → Itinerary (or skip)
         → Hybrid Validator (deterministic + structured semantic JSON + plan quality score)
              FAIL / quality < 0.80 → Replanner (ReplanAction enum + selective needs*) → Router
              PASS → Final (Grounded Finalization)
         → interruptBefore(HITL)
              Approve → Complete → END
              Modify  → Modification Agent → Replan → Router
              Reject  → Cancel → END
```

LangGraph4j parallel fan-out uses `addParallelNodeExecutor(fan_out, …)` so Flight/Hotel/Research/Weather run concurrently and fan in at Supervisor.

### Grounded Finalization Pattern

Validated `TravelState` is rendered deterministically. The Final LLM writes **tips only**.

### Three memories

| Concern | Store | Purpose |
|--------|--------|---------|
| Conversation memory | `conversation_memory` | Chat turns |
| Graph checkpoints | `lg4jthread` / `lg4jcheckpoint` | Node, `TravelState`, HITL, time-travel |
| User preferences | `user_preference` | Airport, style, currency |

## Streaming

`POST /api/plan/start` → **202** `{threadId}` then `GET /api/plan/{threadId}/events` (SSE: `started` / `node` / `complete` / `failed`). Sync `POST /api/plan` still waits for the full graph.

`GET /api/plan/{threadId}/history` returns pipeline, plan quality, node failures, execution timeline, and LangGraph **state snapshots**.

## Guardrails

`max-retries`, `max-llm-calls`, `max-tavily-calls`, `max-iterations` (graph step cap).

## Tests

`AgentEvaluationTest`, `GraphTransitionTest`, plus `src/test/resources/evaluation/travel_cases.json`. `HitlPostgresCheckpointIT` needs PostgreSQL.

## Run

```bash
cd agentic-trip-ai
mvn spring-boot:run
```

UI: http://localhost:8081

**Maven coordinates:** `com.example:agentic-trip-ai`


## MCP LLM Tool Selection Logging

The client logs the dynamic MCP tool-selection path so it is easy to observe:
- MCP tool lookup requested
- LLM-selected/resolved MCP tool
- MCP tool invocation started
- MCP invocation failures

Use `DEBUG` for detailed tool-selection diagnostics and `INFO` for the selected tool and invocation lifecycle.

## RAG Evaluation & Groundedness

The project now includes a deterministic RAG evaluation layer that can run without an LLM judge:

- Retrieval context relevance
- Expected keyword coverage
- Expected source recall
- Overall retrieval evidence score
- Answer-vs-context groundedness screening metric

Endpoints:

```text
POST /api/rag/evaluate
POST /api/rag/groundedness
```

`/api/rag/evaluate` runs the built-in retrieval benchmark against the current pgvector index. The benchmark now generates an evidence-only answer and uses an LLM judge for groundedness. `/api/rag/groundedness` accepts `{ "answer": "...", "context": "..." }` and returns a lexical screening score. `/api/rag/judge` accepts `{ "userRequest": "...", "answer": "...", "context": "..." }` and returns LLM-as-a-judge groundedness, coverage, unsupported-claim rate, pass/fail and reason. During the graph, `ValidatorNode` judges the generated itinerary against RAG evidence; a failed judge adds a validation error and sends the graph through the existing replan loop.

The groundedness score is a guardrail/smoke metric, not a substitute for an LLM-as-judge evaluation. For production evaluation, add a second judge using a stronger model and compare it with these deterministic metrics.

### Persistent trip-memory retrieval

Requests that semantically ask to recall/reopen a previous trip are classified by the Intent Agent with `needsHistory=true`. The graph routes those requests to the `history` node, which reads the newest saved structured trip from PostgreSQL `trip_history` and falls back to structured `conversation_memory` rows created by older versions. It does not start a new Planner/specialist run and does not use the browser's localStorage as the source of truth.

## P0 production hardening

See `P0-PRODUCTION-SECURITY.md` for the authentication, authorization, Flyway, bounded execution, rate limiting, and durable SSE changes included in this build.


### Security configuration
The application uses the modern Spring Security request-matcher DSL and does not depend on `AntPathRequestMatcher`. See `P0-PRODUCTION-SECURITY.md` for the authentication and production-hardening details.


## Local password reset email (Mailpit)

No real email provider is required for development. This project uses the **native Windows Mailpit executable**, so Docker Desktop, WSL, and hardware virtualization are not required.

Mailpit endpoints:
- SMTP: `localhost:1025`
- Web inbox: http://localhost:8025

### Native Mailpit setup

Download the Windows AMD64 Mailpit release and extract `mailpit.exe`. The default development configuration expects:

```text
C:\softwares\mailpit-windows-amd64\mailpit.exe
```

If Mailpit is installed somewhere else, set the environment variable `MAILPIT_EXE` to the full path of `mailpit.exe`.

When the application runs with the `dev` profile, `NativeMailpitManager` automatically starts Mailpit if SMTP port `1025` is not already in use. If Mailpit is already running, it is reused. When the application starts Mailpit itself, it uses a persistent SQLite database at `data/mailpit/mailpit.db`, so the inbox survives a normal application restart.

You can also start it manually with:

```text
scripts\start-mailpit.bat
```

The helper script uses the same persistent database (`data\mailpit\mailpit.db`) and `--max 0`, so messages are not removed just because Mailpit restarts. If you start `mailpit.exe` yourself, start it with the same `--database` path to preserve the same inbox.

Then open the inbox at `http://localhost:8025`.

The Spring Boot dev profile sends password-reset emails to Mailpit using SMTP on port `1025`; no real email provider is required.

Then use **Forgot password** on the login page. The reset email appears in the Mailpit inbox. The reset token is never returned by the forgot-password API and only the hashed token is stored in PostgreSQL.

Production should replace Mailpit with a real transactional SMTP/email provider through environment variables.

### Spring Boot Dashboard

The application defaults to the `dev` Spring profile when no profile is explicitly selected. Running `agentic-trip-ai-mcp-production` from Spring Boot Dashboard therefore enables native Mailpit auto-start without Docker.

- Application: `http://localhost:8081`
- Mailpit inbox: `http://localhost:8025`
- Mailpit SMTP: `localhost:1025`

If Mailpit is already running, the application reuses it. If it is not running and `mailpit.exe` exists at the configured path, the application starts it automatically.

To disable auto-start for a local run, set:

```text
TRAVEL_MAILPIT_ENABLED=false
```

To change the executable location, set:

```text
MAILPIT_EXE=C:\path\to\mailpit.exe
```

Optional persistence settings:

```text
MAILPIT_DATABASE=./data/mailpit/mailpit.db
MAILPIT_MAX_MESSAGES=0
```

`MAILPIT_MAX_MESSAGES=0` disables count-based pruning. The SQLite database file is local development state and is ignored by Git.


## Stop / Resume hardening (v2)

This build hardens user Stop as a control-flow operation rather than a provider/LLM failure:

- Stop is clickable immediately when a live run card is created.
- If the user clicks Stop before `/api/plan/start` returns the durable thread id, the UI queues the Stop intent and sends it as soon as the thread id is available.
- User Stop requests are persisted as `STOP_REQUESTED`; the active future is interrupted, and the graph resumes from the latest persisted LangGraph checkpoint when the user says `Continue`.
- Interrupted Ollama/HTTP calls are classified as `STOPPED_BY_USER`, not `LLM_FAILED`.
- Planner/specialist fallback catches propagate `GraphStopRequestedException` instead of swallowing it and continuing work.
- The graph wrapper emits `node_complete=STOPPED` and does not classify the node as a provider/application failure.
- The application logs explicitly record the stop request, whether an active future was interrupted, and checkpoint preservation.
- SSE exposes `stop_requested` followed by `stopped` so the UI transitions cleanly from Working → Stopping → Stopped.

Full Maven compilation was not run in this environment because Maven is not installed. The embedded JavaScript was syntax-checked with Node.js and the distribution ZIP was integrity-checked with `unzip -t`.
