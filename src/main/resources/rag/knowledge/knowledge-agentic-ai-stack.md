# Agentic AI Knowledge for the Project

## Java developer stack
The project demonstrates that a Java/Spring Boot backend developer can build Agentic AI applications using Spring AI, LangGraph4j, MCP, RAG and PostgreSQL.

## Responsibilities by technology
- Spring Boot: application runtime, APIs, dependency injection and production backend concerns.
- Spring AI: LLM integration, embeddings, vector-store integration and tool abstractions.
- LangGraph4j: stateful graph orchestration, routing, fan-out/fan-in, retries, validation and replanning.
- RAG: retrieves durable knowledge relevant to a task.
- PostgreSQL + pgvector: stores embeddings and retrieved knowledge.
- MCP: standardized discovery and invocation of external tools and data sources.
- A2A: optional protocol for agent-to-agent communication when specialist agents become independently deployable services.
- Ollama: local LLM/embedding runtime for development without mandatory cloud model usage.

## Architectural rule
Do not use RAG for information that must be current at execution time when a live tool/API is available. Use MCP/live APIs for flights, hotel inventory, weather, exchange rates and other dynamic facts. Use RAG for stable documents, policies, guides and enterprise knowledge.

## Production concerns
Production Agentic AI should include authentication/authorization, prompt-injection defenses, tool permission controls, rate limiting, audit logs, observability/tracing, evaluation datasets, cost/latency monitoring, structured outputs, bounded retries and human approval for high-risk actions.
