create table if not exists agent_goal_evaluation (
    id bigserial primary key,
    user_id varchar(120) not null,
    thread_id varchar(120) not null,
    goal varchar(200),
    destination varchar(120),
    budget varchar(100),
    status varchar(24) not null,
    score double precision not null,
    reason varchar(500),
    satisfied_criteria_json text not null default '[]',
    unmet_criteria_json text not null default '[]',
    blocking_issues_json text not null default '[]',
    recoverable boolean not null default false,
    created_at timestamptz not null default current_timestamp,
    constraint ck_agent_goal_evaluation_score check (score >= 0 and score <= 1)
);

create index if not exists idx_agent_goal_eval_user_created
    on agent_goal_evaluation (user_id, created_at desc);
create index if not exists idx_agent_goal_eval_user_status
    on agent_goal_evaluation (user_id, status, created_at desc);
create index if not exists idx_agent_goal_eval_thread
    on agent_goal_evaluation (user_id, thread_id, created_at desc);
