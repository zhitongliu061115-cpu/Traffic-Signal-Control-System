create table auth_user (
    id uuid primary key,
    username varchar(128) not null,
    normalized_username varchar(128) not null unique,
    email varchar(256) not null,
    normalized_email varchar(256) not null unique,
    password_hash varchar(512) not null,
    role varchar(64) not null,
    enabled boolean not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index idx_auth_user_normalized_username on auth_user(normalized_username);
create index idx_auth_user_normalized_email on auth_user(normalized_email);
