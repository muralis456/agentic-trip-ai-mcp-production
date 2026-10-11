create table if not exists agent_runtime_event (
    id bigserial primary key,
    user_id varchar(120) not null,
    thread_id varchar(160) not null,
    event_type varchar(40) not null,
    component varchar(120) not null,
    status varchar(16) not null,
    duration_ms bigint not null default 0,
    attempt integer not null default 1,
    cost_usd numeric(18,8),
    created_at timestamptz not null default current_timestamp,
    constraint ck_agent_runtime_event_duration check (duration_ms >= 0),
    constraint ck_agent_runtime_event_attempt check (attempt >= 1),
    constraint ck_agent_runtime_event_cost check (cost_usd is null or cost_usd >= 0)
);
create index if not exists idx_agent_runtime_user_time on agent_runtime_event (user_id, created_at desc);
create index if not exists idx_agent_runtime_user_thread_time on agent_runtime_event (user_id, thread_id, created_at desc);
create index if not exists idx_agent_runtime_user_type_time on agent_runtime_event (user_id, event_type, created_at desc);
