#!/usr/bin/env bash
# Digest of the app's SQL since the last pg_stat_statements_reset(): normalized text, calls, rows.
# Apps must send the same statements, so the text column must match across apps.
set -euo pipefail

docker compose exec -T postgres psql -U bench -d bench -qAt -F '|' -c "
  select regexp_replace(lower(query), '\s+', ' ', 'g'), calls, rows
  from pg_stat_statements
  where query ~* '(from account|from txn)'
  order by 1"
