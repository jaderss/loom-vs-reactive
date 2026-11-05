#!/usr/bin/env bash
# One app, one scenario, one round. Called by make bench; also fine to run by hand after make up.
# Usage: scripts/bench.sh <app> <scenario> [java-version for legacy-lock]
set -euo pipefail

app=${1:?usage: bench.sh <app> <scenario> [java-version]}
scenario=${2:?usage: bench.sh <app> <scenario> [java-version]}
toxiproxy="http://localhost:${TOXIPROXY_PORT:-8474}"
run_id=${RUN_ID:-local}
label=$app
extra=()

case $scenario in
  read | statement | overload) ;;
  slow-db)
    extra=(-e "USERS=${SLOW_DB_USERS:-600}")
    curl -fsS -X POST "$toxiproxy/proxies/postgres/toxics" \
      -d "{\"name\":\"slow-db\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":${DB_LATENCY_MS:-20}}}" >/dev/null
    ;;
  legacy-lock)
    [[ $app == mvc-vt ]] || { echo "legacy-lock runs on mvc-vt only" >&2; exit 2; }
    java_version=${3:-${JAVA_VERSION:-25}}
    label=mvc-vt-java$java_version
    export JAVA_VERSION=$java_version LEGACY_LOCK=true
    export JAVA_OPTS="${JAVA_OPTS:-} -XX:StartFlightRecording=filename=/results/${run_id}_legacy-lock_${label}.jfr,settings=default"
    extra=(-e "APP_LABEL=$label" -e "USERS=${LEGACY_LOCK_USERS:-100}")
    ;;
  *)
    echo "unknown scenario $scenario" >&2
    exit 2
    ;;
esac

cleanup() {
  [[ -n ${stats_pid:-} ]] && kill "$stats_pid" 2>/dev/null
  docker compose stop "$app" >/dev/null 2>&1
  curl -fsS -X POST "$toxiproxy/reset" >/dev/null
  return 0
}
trap cleanup EXIT

docker compose exec -T postgres psql -U bench -d bench -qAt -c "select pg_stat_statements_reset()" >/dev/null
docker compose up -d --force-recreate "$app"

mkdir -p results
while sleep 5; do
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' | sed "s/^/$(date -u +%H:%M:%S),/"
done >"results/${run_id}_${scenario}_${label}.docker.txt" &
stats_pid=$!

docker compose run --rm -e "TARGET=http://$app:8080" -e "SCENARIO=$scenario" "${extra[@]}" loadgen

if ! curl -fsS -m 5 "http://$(docker compose port "$app" 8080)/stats" >/dev/null; then
  echo "$app did not answer /stats after the run, saving a thread dump" >&2
  docker compose kill -s QUIT "$app" >/dev/null 2>&1 || true
  sleep 3
  docker compose logs --no-color "$app" >"results/${run_id}_${scenario}_${label}.stall.txt"
fi

scripts/statements.sh >"results/${run_id}_${scenario}_${label}.sql.txt"
