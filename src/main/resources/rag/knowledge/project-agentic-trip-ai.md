# AgenticTripAI Project Knowledge

## Purpose
AgenticTripAI is a Java/Spring Boot travel-planning application used to demonstrate production-oriented Agentic AI patterns. It combines Spring AI, LangGraph4j, MCP, Agentic RAG, PostgreSQL and human-in-the-loop approval.

## Core workflow
User request -> Intent -> Planner -> Agentic RAG -> Router -> Airport resolution when flights are required -> parallel specialist execution -> Supervisor -> Budget -> Itinerary -> Validator -> Final -> HITL.

If validation fails or the user modifies a requirement, the graph selectively replans and re-runs only affected capabilities instead of restarting the whole workflow.

## Specialist capabilities
- Flight: searches flight options through MCP.
- Hotel: searches accommodation through MCP.
- Weather: retrieves forecast information through MCP.
- Research: retrieves travel research and destination information through MCP.
- RAG: retrieves durable knowledge from the PostgreSQL/pgvector knowledge base.

## State and orchestration
TravelState is the shared working memory for the LangGraph4j workflow. It carries request details, extracted requirements, specialist results, budget, itinerary, validation results, retry/replan information, HITL information, provenance and RAG evaluation fields.

The graph supports fan-out/fan-in, retries, validation, selective re-execution, checkpoints and human approval.

## MCP architecture
The MCP client connects to a separate Spring AI MCP server using Streamable HTTP. MCP server tools include search_flights, search_hotels, get_weather, resolve_airport and search_travel_research. The client discovers tool metadata through MCP tools/list and uses an LLM-based selector to choose the most appropriate discovered capability before invoking it.

MCP is responsible for standardized tool/data access. It is not the orchestration layer.

## Agentic RAG architecture
Durable travel knowledge is stored as documents, chunked and embedded into PostgreSQL + pgvector. Agentic RAG decides whether retrieval is needed, creates or rewrites a retrieval query, performs hybrid retrieval, reranks evidence, compresses context and evaluates evidence sufficiency. Retrieval can be repeated within a bounded iteration limit when evidence is insufficient.

RAG is used for durable knowledge and should not be treated as the authoritative source for live prices, flight inventory, hotel availability, weather forecasts, exchange rates or current legal decisions. Current facts should come from MCP tools or authoritative live sources.

## RAG evaluation
The project contains deterministic retrieval evaluation and an LLM-as-a-judge layer. RAG metrics include evidence score, groundedness, judge pass/fail and judge reasoning. Poorly grounded RAG output can become a validation failure and trigger re-planning.

## Persistence
PostgreSQL stores application data, conversation memory and LangGraph checkpoints. pgvector stores embeddings for RAG.

## Technology stack
Java 26; Spring Boot 4.x; Spring AI 2.0.x; LangGraph4j; Ollama; PostgreSQL; pgvector; MCP; Thymeleaf/SSE; JPA.

## Key engineering patterns
- Shared state instead of uncontrolled agent-to-agent context passing.
- Deterministic Java validation around LLM decisions.
- Bounded retries and graph iterations.
- Provenance and execution logging.
- Human approval for sensitive outcomes.
- Separate durable knowledge retrieval from live tool execution.
