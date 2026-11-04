\getenv accounts SEED_ACCOUNTS
\getenv txn_per_account SEED_TXN_PER_ACCOUNT

insert into account (id, owner, currency, balance_minor)
select id,
       'owner-' || id,
       (array['EUR', 'GBP', 'CHF', 'CAD', 'AUD'])[id % 5 + 1],
       id * 7919 % 10000000
from generate_series(1::bigint, :accounts) as id;

insert into txn (id, account_id, booked_at, amount_minor, description)
overriding system value
select (a - 1) * :txn_per_account + n,
       a,
       timestamptz '2026-01-01 00:00:00+00' + n * interval '1 hour' + a % 3600 * interval '1 second',
       (a * 31 + n * 7919) % 20000 - 15000,
       (array['card', 'transfer', 'fee', 'salary'])[n % 4 + 1]
from generate_series(1::bigint, :accounts) as a,
     generate_series(1::bigint, :txn_per_account) as n;

select setval(pg_get_serial_sequence('txn', 'id'), (select max(id) from txn));

create index txn_account_booked on txn (account_id, booked_at desc);

vacuum analyze account;
vacuum analyze txn;
