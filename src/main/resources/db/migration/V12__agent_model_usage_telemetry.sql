create table if not exists agent_model_usage_event (
    id bigserial primary key,
    user_id varchar(120) not null,
    thread_id varchar(160) not null,
    model varchar(120) not null,
    operation varchar(40) not null,
    status varchar(16) not null,
    input_tokens bigint not null default 0,
    output_tokens bigint not null default 0,
    duration_ms bigint not null default 0,
    created_at timestamptz not null default current_timestamp,
    constraint ck_agent_model_usage_status check (status in ('SUCCESS', 'ERROR')),
    constraint ck_agent_model_usage_tokens check (input_tokens >= 0 and output_tokens >= 0),
    constraint ck_agent_model_usage_duration check (duration_ms >= 0)
);

create index if not exists idx_agent_model_usage_user_time
    on agent_model_usage_event (user_id, created_at desc);
create index if not exists idx_agent_model_usage_user_thread_time
    on agent_model_usage_event (user_id, thread_id, created_at desc);
create index if not exists idx_agent_model_usage_user_model_time
    on agent_model_usage_event (user_id, model, created_at desc);
