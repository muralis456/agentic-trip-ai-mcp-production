#!/usr/bin/env bash
set -euo pipefail

: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"
: "${PGUSER:=postgres}"
: "${PGPASSWORD:=postgres}"
: "${PGDATABASE:=agentic_trip_test}"
export PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE

psql -v ON_ERROR_STOP=1 -f src/main/resources/db/migration/V11__agent_goal_evaluation_history.sql
psql -v ON_ERROR_STOP=1 -f src/main/resources/db/migration/V12__agent_model_usage_telemetry.sql
psql -v ON_ERROR_STOP=1 -f src/main/resources/db/migration/V13__agent_runtime_event_telemetry.sql

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
    IF to_regclass('public.agent_goal_evaluation') IS NULL THEN
        RAISE EXCEPTION 'V11 did not create agent_goal_evaluation';
    END IF;
    IF to_regclass('public.agent_model_usage_event') IS NULL THEN
        RAISE EXCEPTION 'V12 did not create agent_model_usage_event';
    END IF;
    IF to_regclass('public.agent_runtime_event') IS NULL THEN
        RAISE EXCEPTION 'V13 did not create agent_runtime_event';
    END IF;
    IF to_regclass('public.idx_agent_goal_eval_user_created') IS NULL
       OR to_regclass('public.idx_agent_goal_eval_user_status') IS NULL
       OR to_regclass('public.idx_agent_goal_eval_thread') IS NULL THEN
        RAISE EXCEPTION 'V11 expected user-scoped indexes are missing';
    END IF;
    IF to_regclass('public.idx_agent_runtime_user_time') IS NULL
       OR to_regclass('public.idx_agent_runtime_user_thread_time') IS NULL
       OR to_regclass('public.idx_agent_runtime_user_type_time') IS NULL THEN
        RAISE EXCEPTION 'V13 expected user-scoped indexes are missing';
    END IF;
    IF to_regclass('public.idx_agent_model_usage_user_time') IS NULL
       OR to_regclass('public.idx_agent_model_usage_user_thread_time') IS NULL
       OR to_regclass('public.idx_agent_model_usage_user_model_time') IS NULL THEN
        RAISE EXCEPTION 'V12 expected user-scoped indexes are missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_agent_runtime_event_cost'
          AND conrelid = 'public.agent_runtime_event'::regclass
    ) THEN
        RAISE EXCEPTION 'V13 provider cost constraint is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_agent_goal_evaluation_score'
          AND conrelid = 'public.agent_goal_evaluation'::regclass
    ) THEN
        RAISE EXCEPTION 'V11 score constraint is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_agent_model_usage_status'
          AND conrelid = 'public.agent_model_usage_event'::regclass
    ) THEN
        RAISE EXCEPTION 'V12 status constraint is missing';
    END IF;
END $$;
SQL

echo "Observability migrations V11/V12/V13 passed PostgreSQL schema smoke checks."
