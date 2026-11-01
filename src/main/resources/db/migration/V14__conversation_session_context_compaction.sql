create table if not exists conversation_session (
    id bigserial primary key,
    user_id varchar(255) not null,
    conversation_id varchar(120) not null,
    last_graph_thread_id varchar(255),
    last_role varchar(20),
    metadata_json text,
    compacted_summary text,
    compacted_through_message_id bigint not null default 0,
    message_count bigint not null default 0,
    compaction_version integer not null default 0,
    last_activity_at timestamptz not null default current_timestamp,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    constraint uk_conversation_session_user_conversation unique (user_id, conversation_id),
    constraint ck_conversation_session_message_count check (message_count >= 0),
    constraint ck_conversation_session_compaction_version check (compaction_version >= 0)
);
create index if not exists idx_conversation_session_user_activity
    on conversation_session(user_id, last_activity_at desc);
