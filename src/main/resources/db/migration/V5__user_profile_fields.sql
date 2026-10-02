-- Profile fields introduced after the initial authentication migration.
-- Existing accounts remain valid; newly registered accounts are required to provide all fields.
alter table app_user add column if not exists first_name varchar(80);
alter table app_user add column if not exists last_name varchar(80);
alter table app_user add column if not exists email varchar(254);

create unique index if not exists uk_app_user_email
    on app_user (lower(email))
    where email is not null;
