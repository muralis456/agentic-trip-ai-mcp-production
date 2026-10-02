create table if not exists app_user (
    id bigserial primary key,
    username varchar(80) not null unique,
    password_hash varchar(255) not null,
    role varchar(40) not null,
    enabled boolean not null,
    created_at timestamptz not null
);

create table if not exists api_rate_limit_bucket (
    user_id varchar(80) primary key,
    window_started_at timestamptz not null,
    request_count integer not null
);

create table if not exists graph_progress_event (
    id bigserial primary key,
    thread_id varchar(160) not null,
    created_at timestamptz not null,
    event_type varchar(40) not null,
    payload text not null
);
create index if not exists idx_graph_progress_thread_id on graph_progress_event(thread_id, id);
