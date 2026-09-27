# Travel RAG Data Modeling

## Recommended metadata
Every knowledge chunk should carry metadata such as:
- `domain`: visa, destination, transport, hotel, weather, safety, culture, packing, budget, etc.
- `country` or `destination`.
- `source_type`: official, airline, hotel, tourism board, editorial, internal.
- `source_url`.
- `published_at` and `updated_at` when known.
- `valid_from` / `valid_until` when applicable.
- `language`.
- `content_version`.
- `risk_level`.

## Retrieval rules
High-risk topics such as visas, border rules, safety advisories and health requirements should favor authoritative and recent sources. Static travel knowledge can be used for planning principles.

## Grounding
The answer should retain source provenance and avoid presenting stale or unverified knowledge as current fact.
