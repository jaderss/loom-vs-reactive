create extension if not exists pg_stat_statements;

create table account (
    id            bigint primary key,
    owner         text      not null,
    currency      char(3)   not null,
    balance_minor bigint    not null
);

create table txn (
    id           bigint generated always as identity primary key,
    account_id   bigint      not null references account (id),
    booked_at    timestamptz not null,
    amount_minor bigint      not null,
    description  text        not null
);
