# RAG Knowledge Base

This directory contains durable knowledge bundled with AgenticTripAI.

- `project-*` documents describe software projects and engineering/domain knowledge suitable for project-aware RAG.
- Other markdown files contain travel knowledge.

Do not add secrets, credentials, access tokens, production PII, customer records or confidential source code to this directory.

After adding or changing documents, rebuild the vector index with `POST /api/rag/reindex`.

## Expanded Travel Knowledge

The knowledge base includes planning guidance for destination selection, visas/passports, flights and airports, accommodation, local transport, weather/seasonality, budgeting, food and experiences, family/solo/group travel, safety, insurance, packing, itinerary optimization, cultural etiquette, rail/road trips, business travel, and RAG metadata/provenance.

Use live MCP/tools for changing facts such as current fares, availability, forecasts, opening hours, exchange rates, advisories and entry requirements.


## City and airport directory
The RAG corpus now includes `project-airport-city-directory.md` plus one `city-*.md` document for every city seeded by `AirportLocationSeeder`. This allows destination-aware retrieval and city/IATA grounding.
