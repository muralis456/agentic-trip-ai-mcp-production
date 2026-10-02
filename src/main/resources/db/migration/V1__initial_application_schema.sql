-- Initial relational schema used by the Spring/JPA portion of AgenticTripAI.
-- On an existing installation Flyway baselines this migration and applies V2+.

create table if not exists airport_location (
    id bigserial primary key,
    city varchar(100) not null,
    country varchar(100) not null,
    airport_name varchar(200) not null,
    iata_code varchar(3) not null unique,
    icao_code varchar(4),
    location_type varchar(30)
);

create table if not exists conversation_memory (
    id bigserial primary key,
    user_id varchar(255) not null,
    session_id varchar(255) not null,
    conversation_id varchar(120),
    role varchar(20) not null,
    content text not null,
    created_at timestamptz not null,
    structured_data text
);
create index if not exists idx_conversation_memory_user_created on conversation_memory(user_id, created_at);
create index if not exists idx_conversation_memory_user_conversation on conversation_memory(user_id, conversation_id, created_at);

create table if not exists trip_history (
    id bigserial primary key,
    user_id varchar(255) not null,
    thread_id varchar(255) not null unique,
    title varchar(255) not null,
    origin varchar(255),
    destination varchar(255),
    departure_date varchar(255),
    return_date varchar(255),
    travelers integer not null,
    budget_label varchar(255),
    status varchar(255),
    awaiting_approval boolean not null,
    quality_score integer not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    plan_json text not null
);
create index if not exists idx_trip_history_user_updated on trip_history(user_id, updated_at);

create table if not exists user_preference (
    user_id varchar(80) primary key,
    preferred_airport varchar(8),
    travel_style varchar(40),
    currency varchar(8),
    preferred_hotel_rating varchar(8),
    last_destination varchar(80)
);
