-- Hibernate @Lob maps String values to PostgreSQL large objects (OID).
-- Graph progress and trip plans are JSON/text and must be stored as TEXT so
-- reads work on normal pooled/auto-commit connections.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'graph_progress_event'
          AND column_name = 'payload'
          AND udt_name = 'oid'
    ) THEN
        ALTER TABLE graph_progress_event ADD COLUMN payload_text text;
        UPDATE graph_progress_event
           SET payload_text = CASE
               WHEN payload IS NULL THEN NULL
               ELSE convert_from(lo_get(payload), 'UTF8')
           END;
        ALTER TABLE graph_progress_event DROP COLUMN payload;
        ALTER TABLE graph_progress_event RENAME COLUMN payload_text TO payload;
        ALTER TABLE graph_progress_event ALTER COLUMN payload SET NOT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'trip_history'
          AND column_name = 'plan_json'
          AND udt_name = 'oid'
    ) THEN
        ALTER TABLE trip_history ADD COLUMN plan_json_text text;
        UPDATE trip_history
           SET plan_json_text = CASE
               WHEN plan_json IS NULL THEN NULL
               ELSE convert_from(lo_get(plan_json), 'UTF8')
           END;
        ALTER TABLE trip_history DROP COLUMN plan_json;
        ALTER TABLE trip_history RENAME COLUMN plan_json_text TO plan_json;
        ALTER TABLE trip_history ALTER COLUMN plan_json SET NOT NULL;
    END IF;
END $$;
