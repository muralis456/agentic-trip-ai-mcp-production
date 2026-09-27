-- Graph progress payloads are ordinary JSON text. Do not map them as
-- PostgreSQL large objects/OIDs; Hibernate 7 otherwise uses ClobJdbcType and
-- PostgreSQL rejects reads from auto-commit connections.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
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
