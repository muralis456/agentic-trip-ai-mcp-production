create table if not exists agent_run_control (
    id bigserial primary key,
    user_id varchar(80) not null,
    conversation_id varchar(120) not null,
    thread_id varchar(160) not null unique,
    status varchar(24) not null,
    original_request text,
    policy varchar(32),
    started_at timestamptz not null,
    updated_at timestamptz not null
);
create index if not exists idx_agent_run_control_user_conversation
    on agent_run_control(user_id, conversation_id, status, updated_at);
create index if not exists idx_agent_run_control_thread
    on agent_run_control(thread_id);
