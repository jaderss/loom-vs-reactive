# SDD: loom-vs-reactive (v1)

> **Status:** Draft.
> **Author:** Jader Santos.
> **Implements:** [`PLAN.md`](PLAN.md).
> **Reuses:** `loadgen`, `mock` and `dashboard` from [prime-bench](https://github.com/jaderss/prime-bench).
> **Open questions:** see [§12](#12-open-questions).

---

## 1. Context

Virtual threads are final since Java 21 and Spring Boot turns them on with one property. Java 24 removed most pinning on `synchronized` (JEP 491). The original reason for WebFlux, few threads serving many waiting requests, now holds for plain MVC too. Many teams still write new services on WebFlux or Kotlin coroutines, and the debate runs mostly on opinions and on benchmarks without a database.

## 2. Problem

There is no reproducible comparison of MVC on virtual threads, WebFlux and coroutines against a real database and a real downstream call, that also shows what each one does when the pool is exhausted or the load passes capacity.

## 3. Goals

* Same HTTP contract, same SQL, same resources for four contenders (see [PLAN §3](PLAN.md#3-contenders)).
* Throughput, latency percentiles, errors, CPU and memory per scenario.
* Behavior past saturation, measured with an open model.
* Cost of the code: lines, dependencies, stack trace depth.
* One command to reproduce, CSVs published, CI smoke run on every push.

## 4. Non-goals

* Fastest possible app. Each app uses production config for its stack, not benchmark tricks.
* Disk performance. The dataset fits in memory.
* Stacks outside Spring, Go included (see [§10.4](#104-a-go-or-rust-reference) and [§12 Q1](#12-open-questions)).
* Writes and transactions. The ledger project covers that.

---

## 5. Proposed design

### 5.1 High level

One app runs at a time. Loadgen drives it, Toxiproxy sits between the app and Postgres, the mock plays the downstream rates API.

```
             HTTP                 TCP                    TCP
 loadgen  --------->  app  ------------>  toxiproxy  -------->  postgres
 (closed or           |                    (toxics per            (seeded,
  open model)         |  HTTP               scenario)              pg_stat_statements)
                      +-------->  mock (rates, latency and jitter from env)
```

Every box has its own CPU limit in `compose.yaml`. The app gets `APP_CPUS` and `APP_MEMORY_MB`.

### 5.2 Components

| Component | Responsibility | Where it lives |
|---|---|---|
| `mvc` | Spring MVC, `JdbcClient`, `RestClient`. `VIRTUAL_THREADS=true` or `false` picks the mode. | `apps/mvc` |
| `webflux` | Spring WebFlux in Java, R2DBC, `WebClient`. | `apps/webflux` |
| `coroutines` | Spring WebFlux in Kotlin with `suspend` handlers, R2DBC, `WebClient`. | `apps/coroutines` |
| `contract` | Black box HTTP suite, same for every app. | `contract` |
| `loadgen` | Closed and open model, one CSV per run, body sampling. | `loadgen` |
| `mock` | Rates API with latency, jitter, error rate from env. | `mock` |
| `db` | Schema, seed, `postgresql.conf`. | `db` |
| `dashboard` | One tab per scenario. | `dashboard` |

### 5.3 Data model

Loaded once by `docker-entrypoint-initdb.d`. Apps only read.

```sql
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

create index txn_account_booked on txn (account_id, booked_at desc);
```

Seed with `generate_series`, deterministic, no `random()`. Owner is `owner-<id>`, the balance and every transaction amount and date are formulas of the account id and the row number. Transaction ids are set by the seed too (`overriding system value`), so they do not depend on insert order. The index is built after the rows go in. Loadgen uses the same formulas and the fixed `MOCK_RATE` to compute the expected body for any id, which is how `bad_bodies` works. The formulas live in one place on the Go side, `loadgen/seed`, and the contract suite uses the same package, so a drift between SQL and Go fails the contract run. `SEED_ACCOUNTS` and `SEED_TXN_PER_ACCOUNT` come from `.env` (read with `\getenv` in `02-seed.sql`), sized so the whole dataset fits in `shared_buffers`. Postgres keeps its data in an anonymous volume, and Compose keeps that volume when it recreates the container, so a new seed size alone does not reseed. `make up` compares the row counts with `SEED_ACCOUNTS` and `SEED_TXN_PER_ACCOUNT` and recreates Postgres with a fresh volume when they differ.

### 5.4 HTTP contract

| Endpoint | Work | Success | Errors |
|---|---|---|---|
| `GET /accounts/{id}` | One query by primary key. | `200` with the account | `400` id not a number, `404` unknown id, `503` `database unavailable` |
| `GET /accounts/{id}/statement?limit=20` | Three calls in parallel: account query, last `limit` transactions, rates from the mock. Converts the balance. | `200` with account, transactions, `balanceUsdMinor` | `400` id not a number or `limit` outside 1 to 100, `404` unknown id, `502` `downstream unavailable`, `503` `database unavailable` |
| `GET /health` | Liveness only, no database call. | `200` `ok` | (none) |
| `GET /stats` | Process stats for loadgen: app, lang, version, mode, CPUs, pool size, RSS from `/proc/self/status`, CPU seconds from the JDK's `OperatingSystemMXBean`. The pool size is read from the pool settings Boot bound (`HikariDataSource`, `R2dbcProperties`), not from a copy. | `200` with JSON | (none) |

Error bodies are plain text: `bad request`, `account not found`, `downstream unavailable`, `database unavailable`. `/stats` takes the place of the `/stats` endpoint the prime-bench apps had, so loadgen reads RSS and CPU the same way for every app.

The account query and the rates call run at the same time, so the app does not know the currency when it calls the mock. The mock answers `GET /rates` with every currency the seed uses, `{"to":"USD","rates":{"EUR":"1.0900",...}}`, and the app picks the account currency after the join. A currency missing from the map is a `502`.

Sample `statement` body:

```json
{
  "id": 42,
  "owner": "owner-42",
  "currency": "EUR",
  "balanceMinor": 1250075,
  "balanceUsdMinor": 1362581,
  "transactions": [
    { "id": 901, "bookedAt": "2026-10-30T14:02:11Z", "amountMinor": -4599, "description": "card" }
  ]
}
```

Rules for the three calls, the same in every app:

* Each call has its own timeout: `DB_QUERY_TIMEOUT_MS` for the queries, `DOWNSTREAM_TIMEOUT_MS` for the rate.
* The first failure cancels the other two and the request answers at once.
* A rate failure or timeout is a `502`. A database failure or timeout is a `503`.

| App | Mechanism | Cancel and timeout |
|---|---|---|
| `mvc` virtual | `FanOut`: `FutureTask`s on `Executors.newVirtualThreadPerTaskExecutor()`, a queue of completed tasks | Hikari `connection-timeout` for the wait on the pool, `spring.jdbc.template.query-timeout` for the query (pgjdbc sends a cancel to Postgres), `socketTimeout` as the last guard. The JDK `HttpClient` read timeout for the rate. `FanOut.join()` takes tasks in completion order, and the first failure calls `cancel(true)` on the others and rethrows the cause, so `ApiExceptionHandler` sees the original exception |
| `mvc` platform | Same code on a fixed platform pool of `FANOUT_THREADS` | Same. `FANOUT_THREADS` is 3 times `TOMCAT_MAX_THREADS`, so the fan-out pool never caps throughput before Tomcat does (H4) |
| `webflux` | `Mono.zip` | r2dbc-pool `max-acquire-time` for the pool, `statementTimeout` for the query, `timeout(acquire + query)` per call as the client side guard. `timeout()` on the rate call. `zip` cancels the others on error |
| `coroutines` | `coroutineScope { async {} }` | Same pool and statement settings as `webflux`, `withTimeout(acquire + query)` per database call, `withTimeout` on the rate call. The scope cancels the others on failure |

So every app bounds the wait for a connection by `DB_ACQUIRE_TIMEOUT_MS` and the query by `DB_QUERY_TIMEOUT_MS`. Where the bound sits differs by stack: the JDBC query timeout cancels on the server and the client still waits for the answer, the reactive guard ends the call on the client. Under a frozen or very slow network MVC answers at the socket timeout and the reactive apps at the guard. The contract suite accepts any `503` under `DB_SOCKET_TIMEOUT_S` plus 2 seconds and does not hide the difference.

A database failure is a Spring `DataAccessException` in every app. `JdbcClient` and `DatabaseClient` translate driver errors, and the reactive apps wrap their client side timeout and pool errors in `DataAccessResourceFailureException`, so the three `ApiExceptionHandler`s map the same type to `503`.

`StructuredTaskScope` is still preview in Java 25, so `mvc` does not use it. `cancel(true)` interrupts the thread: a virtual thread blocked on a socket read wakes up, a platform thread does not, and only the query timeout ends its call. That difference is part of what the benchmark shows, not something to hide.

Rounding for `balanceUsdMinor`: half even, same in every app. The rate comes from the mock as a decimal string. Every app uses `BigDecimal` with `RoundingMode.HALF_EVEN`. Loadgen and the contract suite compute the expected value with `big.Rat` and a small `RoundHalfEven` helper in `loadgen/seed`. The contract suite has cases that land exactly on `.5`.

### 5.5 Scenarios

| Scenario | Endpoint | Injected | Load model | Answers |
|---|---|---|---|---|
| `read` | `/accounts/{id}` | (none) | Closed | H1 |
| `statement` | `/statement` | Mock latency and jitter | Closed | H1, H4 |
| `slow-db` | `/statement` | Toxiproxy latency on Postgres | Closed | H2 |
| `overload` | `/statement` | Mock latency | Open, arrival rate stepped above capacity | H3 |
| `legacy-lock` | `/statement` with `LEGACY_LOCK=true` | A `synchronized` block on a striped monitor (`LEGACY_LOCK_STRIPES`, picked by account id) around the rate call. `mvc` virtual only, Java 21 and 25 | Closed | H6 |

The lock in `legacy-lock` is striped on purpose. One global lock lets one request through at a time on both Java versions, which measures contention, not pinning. With many stripes there is almost no contention, but each virtual thread still blocks on I/O inside a monitor. On Java 21 that pins the carrier thread, and with 2 CPUs there are 2 carriers. On Java 25 it does not pin (JEP 491). JFR records `jdk.VirtualThreadPinned` in both runs as proof.

Account ids follow a uniform distribution over the seeded range. Capacity for `overload` comes from the `statement` run on the same machine: the step goes from below to above that number.

### 5.6 CSV schema

prime-bench header (`kind, lang, lang_version, mode, run_id, elapsed_s, cpus, bits, concurrency, completed, errors, throughput_ops_s, latency_p50_ms, latency_p95_ms, latency_p99_ms, latency_max_ms, rss_mb, cpu_pct`) without `bits`, plus:

| Column | Meaning |
|---|---|
| `scenario` | `read`, `statement`, `slow-db`, `overload`, `legacy-lock` |
| `app` | Contender id, for example `mvc-vt` |
| `load_model` | `closed` or `open` |
| `arrival_rate` | Requests per second sent, open model only |
| `pool_size` | Database pool size |
| `latency_p999_ms` | Needed for overload |
| `timeouts` | Client side timeouts, apart from `errors` |
| `bad_bodies` | Sampled responses that failed the body check |

`mode` holds the concurrency model the app reports: `virtual`, `platform`, `reactor`, `coroutines`. Files are named `<run_id>_<scenario>_<app>.csv`, and `legacy-lock` uses `mvc-vt-java21` and `mvc-vt-java25` as the app label. Requests sent during `RAMP_UP` are the warmup and are not recorded. The closed model writes one `summary` row. The open model writes one `summary` row per arrival rate, after the last in-flight request of every step is done, and measures latency from the time the request was due, not the time it left. `make bench` runs `ROUNDS` rounds with `-r<n>` appended to the run id.

### 5.7 Failure modes inside the benchmark

| Failure | Detection | Response |
|---|---|---|
| App answers fast with wrong data | `bad_bodies` above zero | Run marked failed, CI red |
| App dies under overload | Container exit code, `completed` drops to zero | Recorded as a result, not hidden |
| Postgres becomes the bottleneck instead of the app | Postgres CPU near its limit in `docker stats` | Run invalid, raise Postgres CPUs and run again |
| Loadgen becomes the bottleneck | Loadgen CPU near its limit, open model falls behind the target rate | Run invalid |

---

## 6. Scaffolding

```
loom-vs-reactive/
├── .env                         versions, resources, workload (no secrets)
├── .gitignore
├── CLAUDE.md                    local only, ignored globally
├── Makefile                     build, up, bench, contract, dashboard, down, clean
├── compose.yaml
├── sonar-project.properties
├── README.md
├── apps/
│   ├── mvc/
│   │   ├── Dockerfile           Temurin JDK build, JRE run, JAVA_VERSION arg (21 or 25)
│   │   ├── mvnw, pom.xml        maven.compiler.release 21, so one jar runs on 21 and 25
│   │   └── src/
│   │       ├── main/java/dev/jaderss/lvr/mvc/
│   │       │   ├── Application.java
│   │       │   ├── BenchProperties.java        record bound to bench.*
│   │       │   ├── AccountController.java
│   │       │   ├── AccountRepository.java      JdbcClient
│   │       │   ├── RatesClient.java            RestClient on the JDK HttpClient
│   │       │   ├── StatementService.java       parallel fan-out
│   │       │   ├── FanOut.java                 first failure cancels the rest (no StructuredTaskScope on 21)
│   │       │   ├── ExecutorConfig.java         virtual or platform executor
│   │       │   ├── LegacyLock.java             striped synchronized block for legacy-lock
│   │       │   ├── StatsController.java        /health and /stats
│   │       │   ├── ApiExceptionHandler.java    @RestControllerAdvice, 400, 404, 502, 503
│   │       │   ├── *Exception.java             account not found, downstream unavailable, invalid limit
│   │       │   └── Account.java, Txn.java, Statement.java, Rates.java   package-private records
│   │       ├── main/resources/application.yaml
│   │       └── test/java/dev/jaderss/lvr/mvc/
│   │           ├── AccountControllerIT.java    port 0, Testcontainers Postgres, rates stub (failsafe)
│   │           └── StatementServiceTest.java   unit, runs inside the image build too
│   ├── webflux/                 same layout, Mono, DatabaseClient, WebClient, DatabaseConfig for R2DBC timeouts
│   ├── coroutines/
│   │   ├── pom.xml              kotlin-maven-plugin with the spring compiler plugin
│   │   └── src/main/kotlin/dev/jaderss/lvr/coroutines/
│   │       ├── Application.kt
│   │       ├── Config.kt                       BenchProperties, R2DBC timeouts
│   │       ├── AccountController.kt            suspend functions
│   │       ├── AccountRepository.kt            DatabaseClient awaitOneOrNull, flow
│   │       ├── RatesClient.kt                  WebClient awaitBody
│   │       ├── StatementService.kt             coroutineScope and async
│   │       ├── Errors.kt                       exceptions and withDeadline
│   │       ├── ApiExceptionHandler.kt, StatsController.kt
│   │       └── Model.kt                        data classes
├── contract/                    Go, BASE_URL env, run against any app
│   ├── go.mod                   imports loadgen/seed through a replace directive
│   └── contract_test.go
├── db/
│   ├── init/01-schema.sql
│   ├── init/02-seed.sql
│   ├── postgresql.conf          shared_buffers, max_connections, pg_stat_statements
│   └── toxiproxy.json           the postgres proxy every app connects through
├── loadgen/                     from prime-bench, plus open model and body sampling
│   └── seed/                    seed formulas in Go, expected bodies
├── mock/                        from prime-bench, rates endpoint only, PUT /config for the contract suite
├── dashboard/                   from prime-bench: React, Recharts, nginx; tab per scenario, median of rounds
├── scripts/
│   ├── bench.sh                 one app and scenario per call
│   └── statements.sh            pg_stat_statements digest per app
├── results/
│   └── sample/                  committed sample CSVs, the rest ignored
├── docs/
│   ├── PLAN.md
│   ├── SDD.md
│   └── METHODOLOGY.md           written in Phase 3 and 6
└── .github/workflows/ci.yml
```

---

## 7. Libraries and versions

Checked on Maven Central, the Go module proxy and Docker Hub on 2026-11-04. JVM libraries marked **BOM** come from `spring-boot-dependencies` 4.1.2 and are not overridden in the poms.

### 7.1 Runtime

| Item | Version | Notes |
|---|---|---|
| Java (Temurin) | 25 | LTS. `mvc` compiles with `release 21` and no idioms newer than 21, so the same jar runs on Java 21 for `legacy-lock` |
| Spring Boot | 4.1.2 | Parent pom for `mvc`, `webflux`, `coroutines` |
| Spring Framework | 7.0.10 | BOM |
| Tomcat | 11.0.27 | BOM, `mvc` |
| Reactor | 2025.0.8 | BOM, `webflux` and `coroutines` (Reactor Netty) |
| Jackson | 3.1.7 | BOM |
| Kotlin | 2.3.21 | BOM. 2.4.21 exists, stay on the BOM line |
| kotlinx-coroutines | 1.10.2 | BOM |
| PostgreSQL JDBC | 42.7.14 | BOM |
| HikariCP | 7.0.2 | BOM |
| r2dbc-postgresql | 1.1.3.RELEASE | BOM |
| r2dbc-pool | 1.0.2.RELEASE | BOM. `spring-boot-starter-r2dbc` does not bring it, so the poms declare it (version still from the BOM). Without it Boot builds an unpooled `ConnectionFactory` |
| Micrometer | 1.17.2 | BOM. Not used in v1: Actuator would add request observations the benchmark does not need. Pool use comes from `pg_stat_activity` if needed |
| Go | 1.27 | Loadgen, mock and the contract suite, same as prime-bench |
| PostgreSQL image | 18.6 | `postgres:18.6` |
| Toxiproxy | 2.13.0 | `ghcr.io/shopify/toxiproxy:2.13.0` |
| Distroless | `static-debian12:nonroot` | Go images, as in prime-bench |
| Node, nginx | 26, 1.31 | Dashboard, as in prime-bench |

### 7.2 Build and test

| Item | Version | Notes |
|---|---|---|
| JUnit | 6.0.3 | BOM |
| AssertJ | 3.27.7 | BOM |
| Testcontainers | 2.0.5 | BOM, modules `testcontainers-postgresql`, `testcontainers-toxiproxy` |
| Awaitility | 4.3.0 | BOM |
| ArchUnit | 1.5.1 | `archunit-junit5`, only if a layer rule earns it |
| Spotless Maven plugin | 3.11.0 | Palantir for Java, ktlint for Kotlin |
| Palantir Java Format | 2.103.0 | |
| ktlint | 1.8.0 | Through Spotless |
| JaCoCo | 0.8.16 | Java and Kotlin bytecode, XML for Sonar |
| Maven wrapper | from `mvn wrapper:wrapper` | Same as prime-bench |

### 7.3 Tools

| Tool | Version | Notes |
|---|---|---|
| async-profiler | 4.6 | Attached in a profiling run, never in measured runs |
| scc | 4.1.0 | Lines of code per app |
| GitHub Actions | `checkout@v7`, `setup-java@v6`, `setup-node@v7` | Same as prime-bench |

---

## 8. Configuration

`.env` starting values. Workload numbers are a first guess, set after the first runs on this machine.

```bash
JAVA_VERSION=25
GO_VERSION=1.27
POSTGRES_VERSION=18.6
TOXIPROXY_VERSION=2.13.0
NODE_VERSION=26
NGINX_VERSION=1.31

APP_CPUS=2
APP_MEMORY_MB=1536
HEAP_PERCENT=75
JVM_GC=ParallelGC
JAVA_OPTS=
POSTGRES_CPUS=2
TOXIPROXY_CPUS=1
LOADGEN_CPUS=2
MOCK_CPUS=1

POOL_SIZE=20
DB_ACQUIRE_TIMEOUT_MS=2000
DB_QUERY_TIMEOUT_MS=2000
DB_SOCKET_TIMEOUT_S=5
DOWNSTREAM_TIMEOUT_MS=1000
TOMCAT_MAX_THREADS=200
FANOUT_THREADS=600
LEGACY_LOCK_STRIPES=1024

SEED_ACCOUNTS=100000
SEED_TXN_PER_ACCOUNT=50

APPS=mvc-vt mvc-platform webflux coroutines
SCENARIOS=read statement slow-db overload legacy-lock
LEGACY_LOCK_JAVA_VERSIONS=21 25
USERS=2000
THINK_TIME=1s
DURATION=60s
RAMP_UP=15s
ROUNDS=5
REQUEST_TIMEOUT=10s
SAMPLE_EVERY=100
STATEMENT_LIMIT=20
MOCK_LATENCY=30ms
MOCK_JITTER=10ms
MOCK_RATE=1.0900
MOCK_ERROR_RATE=0
DB_LATENCY_MS=20
ARRIVAL_RATES=1000 2000 4000 8000
```

`DB_LATENCY_MS` is a plain number because the Toxiproxy latency toxic takes milliseconds. `REQUEST_TIMEOUT` is the loadgen client timeout, counted in `timeouts`. Toxiproxy listens on host port 8474 and the mock on 8090.

Spring properties that change per app:

```yaml
spring:
  threads:
    virtual:
      enabled: ${VIRTUAL_THREADS:true}
  datasource:
    hikari:
      maximum-pool-size: ${POOL_SIZE}
      connection-timeout: ${DB_ACQUIRE_TIMEOUT_MS}
      data-source-properties:
        socketTimeout: ${DB_SOCKET_TIMEOUT_S}
        connectTimeout: ${DB_SOCKET_TIMEOUT_S}
  jdbc:
    template:
      query-timeout: ${DB_QUERY_TIMEOUT_MS}ms
  r2dbc:
    pool:
      initial-size: ${POOL_SIZE}
      max-size: ${POOL_SIZE}
      max-acquire-time: ${DB_ACQUIRE_TIMEOUT_MS}ms
```

`initial-size` matches Hikari, which keeps `minimum-idle` equal to the pool size by default. Every app starts with a full pool.

The downstream clients get no connection cap, same rule as prime-bench: the JDK `HttpClient` has none, Reactor Netty gets `maxConnections(10000)` and no pending acquire limit (the default is 16 connections on 2 CPUs).

Hikari takes `connection-timeout` as a number of milliseconds, a value like `2s` fails at startup. `JdbcTemplate` takes the query timeout in whole seconds: Boot drops the rest, so `1500` becomes 1 second and `500` becomes no timeout at all. `mvc` refuses to start unless `DB_QUERY_TIMEOUT_MS` is a whole number of seconds (`QueryTimeoutCheck`), so a sweep cannot leave one app without a query timeout. The R2DBC apps set `statementTimeout` and `connectTimeout` in a `ConnectionFactoryOptionsBuilderCustomizer` from the same variables.

`DB_SOCKET_TIMEOUT_S` is the last guard when Postgres stops answering. A frozen server enforces no `statement_timeout`, so only a client-side timeout ends the call.

---

## 9. Testing and CI

| Level | What | Where |
|---|---|---|
| Unit | Statement assembly, rounding, error mapping | Each app |
| HTTP | Server on port 0, Testcontainers Postgres, stubbed mock | Each app |
| Contract | Same Go suite against every running app. Includes `502` and `503` cases: the suite sets the mock through `PUT /config` and Postgres through the Toxiproxy API, then waits for the app to recover | `contract`, CI job per app |
| Smoke bench | Every scenario, short duration, small load | CI, counts CSVs and compares headers |
| Fairness | `statements.sh` digest equal across apps | CI after the smoke bench |

CI jobs: `images` (build and unit tests inside each Dockerfile), `java` (`./mvnw verify` for the three Maven modules: surefire unit tests, failsafe `*IT` on Testcontainers, Spotless check), `go` (`go test ./...` with coverage for loadgen and the mock), `contract`, `smoke`, `dashboard` (`npm ci`, oxlint, `tsc` and `vite build`), `sonar`, `secrets`. HTTP tests are named `*IT` so `package` inside the Dockerfile runs only the unit tests, since there is no Docker inside a Docker build. The dashboard only draws the CSVs and has no tests, so Sonar leaves it out of coverage.

---

## 10. Alternatives considered

### 10.1 k6 or Gatling instead of the prime-bench loadgen

**What:** use a known load tool.
**Why not:** the prime-bench loadgen already writes the CSV the dashboard reads, and adding the open model is small. A second tool means a second CSV format and a second percentile method.

### 10.2 Spring Data repositories instead of `JdbcClient` and `DatabaseClient`

**What:** `CrudRepository` and `ReactiveCrudRepository`.
**Why not:** two queries do not justify the extra layer, and the mapping cost would differ between JDBC and R2DBC. Plain clients keep the SQL text identical and visible.

### 10.3 One app per mode instead of `mvc` with a switch

**What:** separate `mvc-vt` and `mvc-platform` modules.
**Why not:** same code is the point of H4. One image with one property removes any other difference.

### 10.4 A Go or Rust reference

**What:** run the same contract in Go (`net/http`, pgx) or Rust (axum, sqlx) next to the Spring apps, for scale.
**Why not:** a native app sets the scale of every chart. In a smoke run Go used about a seventh of the CPU and a twentieth of the memory of the Spring apps, so the result reads as JVM against native, which is not the question. The question is which Spring stack to pick. Go was built and passed the contract suite, then was removed for that reason.

---

## 11. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Config looks unfair to fans of one stack | High | High | `METHODOLOGY.md` lists every setting with a reason. Invite PRs. |
| Laptop noise moves the numbers | Medium | Medium | Rounds, spread reported, same machine for a full run, CI only for smoke. |
| Postgres becomes the limit and every app looks the same | Medium | High | Postgres CPU check in each run (see §5.7). |
| R2DBC driver gaps under failure (cancel, timeouts) | Medium | Medium | Report as a finding, do not work around it in code. |
| `legacy-lock` read as a strawman | Medium | Low | Label as synthetic, keep apart from the main result. |

---

## 12. Open questions

| # | Question | Why it matters | Default assumed in this doc |
|---|---|---|---|
| Q1 | Add Quarkus or Helidon? | Bigger audience, more work. | No. |
| Q2 | Pool size per app or one value? | H2 depends on it. | One value, plus a sweep run. |
| Q3 | Parallel calls inside `statement`, or sequential? | Parallel shows the concurrency model, sequential is closer to many real services. | Parallel. |
| Q4 | Seed size? | Must fit in memory and still need the index. | 100,000 accounts, 50 transactions each, adjusted after the first run. |
| Q5 | Open model arrival steps? | Depends on the capacity measured on this machine. | Set after the `statement` run. |

---

## 13. References

* [prime-bench](https://github.com/jaderss/prime-bench): harness, CSV schema, CI checks.
* JEP 444 (virtual threads), JEP 491 (synchronize virtual threads without pinning), JEP 505 (structured concurrency, preview).
* Gil Tene on coordinated omission, for the open model.
