-- Ensures the P0 per-user API rate-limit table exists on installations
-- that were upgraded from a schema created before rate limiting was added.
-- Safe to run on fresh and existing databases.
create table if not exists api_rate_limit_bucket (
    user_id varchar(80) primary key,
    window_started_at timestamptz not null,
    request_count integer not null
);
