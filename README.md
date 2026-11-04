# loom-vs-reactive

[![CI](https://github.com/jaderss/loom-vs-reactive/actions/workflows/ci.yml/badge.svg)](https://github.com/jaderss/loom-vs-reactive/actions/workflows/ci.yml) [![Quality gate](https://sonarcloud.io/api/project_badges/measure?project=jaderss_loom-vs-reactive&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jaderss_loom-vs-reactive) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jaderss_loom-vs-reactive&metric=coverage)](https://sonarcloud.io/summary/new_code?id=jaderss_loom-vs-reactive)

Spring MVC on virtual threads, Spring WebFlux and Kotlin coroutines, with the same HTTP contract against the same Postgres. The question: for an I/O bound service, is reactive still worth what it costs to write and to debug?

Each app gets the same CPU and memory, the same SQL, the same pool size and the same timeouts. Scenarios cover a healthy database, a slow database with the pool as the bottleneck, load above capacity with an open model, and the old `synchronized` pinning problem on Java 21 against Java 25.

Status: apps, harness and dashboard done, CI green. No results yet.

* [Plan](docs/PLAN.md): hypotheses, phases, tools.
* [SDD](docs/SDD.md): design, contract, timeouts, versions.

## Contenders

| App | Stack | Database | Rates API client |
|---|---|---|---|
| `mvc-vt` | Spring MVC, virtual threads | `JdbcClient`, HikariCP | `RestClient` |
| `mvc-platform` | Same image as `mvc-vt` with virtual threads off: 200 Tomcat threads | Same | Same |
| `webflux` | Spring WebFlux, Java | R2DBC `DatabaseClient`, r2dbc-pool | `WebClient` |
| `coroutines` | Spring WebFlux, Kotlin `suspend` functions | R2DBC `DatabaseClient` | `WebClient` with `awaitBody` |

All four run on Spring Boot 4.1.2 and Java 25, with 2 CPUs, 1.5 GB, ParallelGC and 75% of the memory for the heap.

## Scenarios

| Scenario | What runs |
|---|---|
| `read` | `GET /accounts/{id}`: one query by primary key |
| `statement` | `GET /accounts/{id}/statement`: account, last 20 transactions and a rate from a mock API, in parallel. The mock answers in 30 ms ± 10 ms |
| `slow-db` | `statement` with 20 ms added to every Postgres reply by Toxiproxy, so the pool of 20 connections is the limit |
| `overload` | `statement` at a fixed arrival rate that steps from 1,000 to 8,000 requests per second |
| `legacy-lock` | `statement` on `mvc-vt` with a `synchronized` block around the rate call, on Java 21 and on Java 25 |

Loadgen checks a sample of response bodies against the seed. An app that answers fast with wrong data fails the run.

## Running

You need git, `make`, `curl` and Docker running (Docker Desktop on macOS and Windows). No JDK, Kotlin, Go or Node on the host: everything builds inside containers.

The defaults cap 8 CPUs in Docker: 2 for the app under test, 2 for Postgres, 1 for Toxiproxy, 2 for loadgen and 1 for the mock. With fewer CPUs, the containers share cores and the numbers get noisy. Lower `POSTGRES_CPUS` and `LOADGEN_CPUS` in that case, and say so when you share results.

```bash
git clone https://github.com/jaderss/loom-vs-reactive.git
cd loom-vs-reactive
make bench        # build the images, then every scenario for every app, 5 rounds
make dashboard    # open http://localhost:8080
make down         # stop everything
```

With the defaults, each round is about 35 minutes of load, plus app startups. The first `make bench` also builds the images and seeds Postgres with 100,000 accounts and 5 million transactions. `make help` lists every target.

For a first look, one scenario and one round, about 5 minutes of load:

```bash
make bench SCENARIOS=statement ROUNDS=1
```

Any value from `.env` can be overridden on the command line:

```bash
make bench APPS="mvc-vt webflux"              # only some apps
make bench SCENARIOS="read slow-db"           # only some scenarios
make bench USERS=5000 THINK_TIME=500ms        # heavier closed load
make bench SCENARIOS=overload ARRIVAL_RATES="500 1000 2000"   # other overload steps
make bench SCENARIOS=slow-db DB_LATENCY_MS=50 # slower database
make bench POOL_SIZE=50                       # bigger pool in every app
make bench MOCK_LATENCY=80ms                  # slower rates API
make bench SEED_ACCOUNTS=10000                # smaller dataset, Postgres reseeds
make dashboard DASHBOARD_PORT=9090            # 8080 already taken
```

`DB_QUERY_TIMEOUT_MS` must be a whole number of seconds. `JdbcTemplate` keeps only the seconds, so `mvc` refuses to start otherwise.

### Contract suite

The same black box suite runs against every app: exact bodies against the seed, half even rounding, `400`, `404`, `502` when the rates API fails or is slow, `503` when Postgres is down or slow, and that the first failure cancels the other calls.

```bash
make contract                 # every app
make contract APPS=webflux    # one app
```

### Results

Every run writes to `results/`, named `<run id>-r<round>_<scenario>_<app>`:

| File | What it holds |
|---|---|
| `.csv` | Throughput, latency percentiles (p50 to p99.9, nearest rank), errors, timeouts, wrong bodies, memory and CPU. One `interval` row per second, one `summary` row per run (per arrival rate for `overload`) |
| `.sql.txt` | The SQL the app sent, from `pg_stat_statements`. The text must match across apps |
| `.docker.txt` | `docker stats` every 5 seconds, to check Postgres or loadgen was not the bottleneck |
| `.jfr` | Flight recordings from `legacy-lock`, with a `jdk.VirtualThreadPinned` event for every pinned thread (command below) |

To count the pinned threads without a JDK on the host:

```bash
docker run --rm -v "$PWD/results:/results" eclipse-temurin:25-jdk \
  sh -c 'for f in /results/*.jfr; do echo "$f"; jfr summary "$f" | grep VirtualThreadPinned; done'
```

`make clean` deletes them. The apps listen on ports 8081 to 8084 while they run, the mock on 8090, the Toxiproxy API on 8474.

Same harness idea as [prime-bench](https://github.com/jaderss/prime-bench): one command, one CSV per run, a dashboard.
