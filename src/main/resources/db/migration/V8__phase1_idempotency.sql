create table if not exists agent_idempotency (
    user_id varchar(255) not null,
    idempotency_key varchar(255) not null,
    request_hash varchar(128) not null,
    status varchar(32) not null,
    response_body text,
    created_at timestamp with time zone not null,
    primary key (user_id, idempotency_key)
);
create index if not exists idx_agent_idempotency_created_at on agent_idempotency(created_at);
