create table if not exists password_reset_token (
    id bigserial primary key,
    user_id bigint not null references app_user(id) on delete cascade,
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    used_at timestamptz null,
    created_at timestamptz not null
);

create index if not exists idx_password_reset_token_user
    on password_reset_token(user_id);
