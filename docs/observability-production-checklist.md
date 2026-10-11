# Evaluation and observability production checklist

## Before deploying

- [ ] Confirm the deployment uses the same authenticated principal identifier for Authentication.getName() and TravelState.USER_ID. Do not use a client-supplied user ID to scope dashboard queries.
- [ ] Apply V11__agent_goal_evaluation_history.sql, then V12__agent_model_usage_telemetry.sql, to the target PostgreSQL database. Flyway is currently disabled, so these files are not auto-applied.
- [ ] Verify the application DB role can read/write both tables and the indexes exist.
- [ ] Start with Hibernate schema validation enabled in a staging environment and confirm both entity mappings match the database.
- [ ] Sign in as two separate users; create evaluations/model calls for each; verify each user only sees their own durable history.
- [ ] Verify unauthenticated requests to /api/observability/dashboard receive HTTP 401 and the /evaluation page requires sign-in.
- [ ] Exercise success, provider error, retry, fallback, streaming, resume, approve, modify, and retry-task paths. Compare model-call totals, tokens, error counts, and duration against provider/application logs.
- [ ] Simulate PostgreSQL write failures. Trip planning should continue, while logs should record that telemetry was dropped.
- [ ] Configure model prices only from current provider pricing. Estimated cost is an estimate, not a billing source of truth.

## Data retention

Both tables contain user-linked operational history and will grow until a retention policy is applied. Decide and document the retention period with product/security owners. If 180 days is acceptable, the optional SQL below removes older rows; run it during a low-traffic window and adapt the period to your policy:

```sql
-- Preview counts first; this is an opt-in maintenance action.
SELECT 'agent_goal_evaluation' AS table_name, count(*) AS rows_to_delete
FROM agent_goal_evaluation WHERE created_at < now() - interval '180 days'
UNION ALL
SELECT 'agent_model_usage_event', count(*)
FROM agent_model_usage_event WHERE created_at < now() - interval '180 days';

BEGIN;
DELETE FROM agent_goal_evaluation
WHERE created_at < now() - interval '180 days';
DELETE FROM agent_model_usage_event
WHERE created_at < now() - interval '180 days';
COMMIT;
```

Do not automate deletion until the retention period, backup/restore expectations, and any audit requirements have been approved. For high-volume deployments, delete in bounded batches to avoid long-running transactions and table bloat.

## Known metric scope

- Goal evaluations and model usage events are durable and account-scoped.
- MCP/replan/node counters are currently process-wide Micrometer counters and reset when the application restarts. Do not interpret these as account-specific or long-term totals.
- Missing token counts from providers may be recorded as zero; zero therefore means “no token usage reported” as well as potentially “zero tokens.” Check provider support before using these fields for billing.
- Telemetry writes are best-effort. A database outage can cause missing events, intentionally avoiding failure of the user's travel-planning request.
