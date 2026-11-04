# loom-vs-reactive: plan

> **Status:** Draft.
> **Companion:** [`SDD.md`](SDD.md) has the design, the scaffolding and the versions.
> **Open questions:** see [§8](#8-open-questions).

---

## 1. TL;DR

Spring added WebFlux because blocking a platform thread per request was expensive. Virtual threads made blocking cheap again. This project measures if WebFlux and Kotlin coroutines still win anything over Spring MVC on virtual threads, with a real Postgres and a slow downstream API, and what each stack does when the connection pool runs out. Go with pgx runs the same contract as a reference.

The output follows prime-bench: one `make bench`, one CSV per run, a dashboard, and a README that opens with the finding.

---

## 2. Question and hypotheses

The question: for an I/O bound Spring service, is reactive still worth what it costs to write and to debug?

| # | Hypothesis | Tested by |
|---|---|---|
| H1 | MVC on virtual threads matches WebFlux in throughput and p99 while the database is healthy. | `read`, `statement` |
| H2 | When the pool is the bottleneck, every stack hits the same ceiling. Pool size and query time set it, not the thread model. | `slow-db` |
| H3 | Past saturation the stacks fail in different ways: queue growth, timeouts, memory. | `overload` (open model) |
| H4 | MVC on platform threads falls behind once concurrent requests pass the Tomcat thread count. | `statement` with `mvc-platform` |
| H5 | Reactive code costs more to write and to read. | Lines of code, dependency count, stack trace depth of the same 502 |
| H6 | The Java 21 pinning problem with `synchronized` is gone on Java 25 (JEP 491). | `legacy-lock`, same app on Java 21 and 25 |

These are expectations, not results. The README reports what the runs show, including any hypothesis that fails.

---

## 3. Contenders

| Id | Stack | Database access | Downstream client |
|---|---|---|---|
| `mvc-vt` | Spring Boot MVC, Java, virtual threads | `JdbcClient`, HikariCP | `RestClient` |
| `mvc-platform` | Same image as `mvc-vt`, virtual threads off | Same | Same |
| `webflux` | Spring WebFlux, Java | R2DBC `DatabaseClient`, r2dbc-pool | `WebClient` |
| `coroutines` | Spring WebFlux, Kotlin `suspend` functions | R2DBC `DatabaseClient` with coroutine extensions | `WebClient` with `awaitBody` |
| `go` | `net/http`, standard library | pgx `pgxpool` | `net/http` client |

Each app runs the configuration a team would ship to production for that stack. Every config value that differs from the defaults has a written reason in `METHODOLOGY.md`. No app gets tuning the others did not get.

---

## 4. Phases

No dates. Each phase ends with a check that says it is done.

### Phase 0: Bootstrap

* Repo files: `.gitignore`, `.env`, `Makefile`, `compose.yaml`.
* Copy `loadgen`, `mock` and `dashboard` from prime-bench. Keep the CSV schema and add the new columns (see [SDD §5.6](SDD.md#56-csv-schema)).
* Postgres 18 with `pg_stat_statements`, Toxiproxy in front of it.
* CI skeleton, SonarQube Cloud project with Automatic Analysis off.

Done when: `make up` starts Postgres, Toxiproxy and the mock, and CI is green.

### Phase 1: Contract and data

* Schema and seed in `db/init`, loaded by Postgres on first start. Apps never run migrations.
* Black box contract suite in Go (`contract/`), pointed at any app through `BASE_URL`. The same suite runs against all five contenders.

Done when: the contract suite runs in CI and fails against a stub for the right reasons.

### Phase 2: Apps

Order: `mvc` first, since it defines the reference behavior. Then `webflux`, `coroutines`, `go`.

For each app:

1. Load `java-engineer` before writing (JVM apps).
2. Unit tests, HTTP tests with the server on port 0, Testcontainers Postgres.
3. Contract suite green.
4. `/code-review` before the merge to `main`.

Done when: all five pass the contract suite in CI and Sonar shows 80% coverage on new code.

### Phase 3: Fairness pass

* Same pool size, same timeouts, same JSON, same SQL text.
* Short run per app, then compare `pg_stat_statements`: same statements, same calls per request.
* Profile the slowest app before trusting its number (JFR or pprof). Fix real mistakes and write each fix in `METHODOLOGY.md`.

Done when: the statement digest matches across apps and every config difference has a reason.

### Phase 4: Scenarios

* Add an open model to `loadgen` (fixed arrival rate) next to the closed model. The overload scenario needs it, since a closed model slows down with the server and hides queueing (coordinated omission).
* Scenarios `read`, `statement`, `slow-db`, `overload`, `legacy-lock` ([SDD §5.5](SDD.md#55-scenarios)).
* Several rounds per scenario, warmup excluded, median of rounds plus spread.

Done when: `make bench` writes one CSV per app and scenario, and the CI smoke run checks the count and the headers.

### Phase 5: Analysis

* Flame graphs (async-profiler, pprof) only for the two results that surprise the most.
* Dashboard tab per scenario. Chart types and colors checked with the `dataviz` skill.
* Optional: the same runs on Docker Desktop Kubernetes, as in prime-bench.

Done when: each hypothesis has a yes, a no, or "depends on", with the chart that shows it.

### Phase 6: Write-up and launch

* README opens with the finding and one chart. `METHODOLOGY.md` has the rules and the limits.
* Posts on r/java, r/Kotlin, Hacker News and LinkedIn. The title carries the number that surprises.
* Ask people who know each stack for a PR if they see unfair config. Those PRs bring attention and make the result harder to argue with.

Done when: README and posts reviewed by Jader.

---

## 5. Skills and their roles

| Skill | Role in this project | When |
|---|---|---|
| `java-engineer` | Idioms and config for MVC, WebFlux, R2DBC, coroutines and virtual threads. Gates idioms on the Java version. | Before each JVM app, and for config in Phase 3 |
| `java-code-review` (`/code-review`) | Panel review per app. Extra lens: is the app idiomatic and fair to its own stack. | Before each app merges |
| `engineering:testing-strategy` | Shape of the contract suite and the test pyramid per app. | Phase 1 |
| `simplify` | Remove code an app does not need, so the line counts compare like with like. | End of Phase 2 |
| `dataviz` | Chart forms, series colors, CVD checks. | Phase 5 |
| `tech-design-writer` | `SDD.md`, `METHODOLOGY.md`. | Phases 0 and 6 |
| `jader-voice` | README, commit messages, posts. | Every phase, final pass in Phase 6 |
| `security-review` | Secrets, images, workflow permissions before the repo gets traffic. | Phase 6 |
| `run` | Start the stack and confirm a change works, not only the tests. | Any phase |

---

## 6. Best practices

### 6.1 Benchmark method

* Same CPU and memory per app from `.env`: 2 CPUs, 1.5 GB, ParallelGC, heap at 75% of the limit. Same values as prime-bench.
* Postgres, Toxiproxy, mock and loadgen get their own CPUs. Nothing shares CPUs with the app under test.
* The dataset fits in Postgres memory. The benchmark measures the app and the driver, not the disk.
* Toxiproxy stays in the path for every scenario, with no toxics in the healthy ones. Same network path everywhere.
* Warmup excluded. Several rounds, median and spread reported.
* Open model for overload, so latency includes the time a request waited to be sent.
* Percentiles by nearest rank, one implementation for every CSV. CI fails if headers differ.
* Raw CSVs published. Each CSV row records versions and config.
* Correctness under load: loadgen checks a sample of response bodies. An app that answers fast and wrong fails the run.

### 6.2 Code

* `java-engineer` rules: no comments, records, constructor injection, Palantir format through Spotless, ktlint for Kotlin.
* Money in integer minor units, as in prime-bench.
* Missing downstream field or downstream failure is a 502 with body `downstream unavailable`. Detail goes to the log without the account id.
* Same failure rules in every app: a timeout per call, the first failure cancels the other calls, a client-side socket timeout on every database connection.
* HTTP layer tested with the server on port 0, so glue code passes the Sonar gate.

### 6.3 Repo

* Commits straight to `main`, one short present-tense paragraph.
* Versions pinned in `.env` and in the build files. Images tagged with their version.
* CLAUDE.md stays local (global gitignore).

---

## 7. Tools

| Tool | Use |
|---|---|
| Docker Compose | Local runs and CI |
| Toxiproxy | Latency and outages between the apps and Postgres |
| `pg_stat_statements` | Proof that every app runs the same SQL |
| JFR, JDK Mission Control | JVM profile, virtual thread events such as `jdk.VirtualThreadPinned` |
| async-profiler | CPU and allocation flame graphs |
| Go pprof | Go profile |
| scc | Lines of code per app |
| SonarQube Cloud | Quality gate, coverage from JaCoCo and Go profiles |
| GitHub Actions | Build, tests, contract suite, smoke bench |
| Docker Desktop Kubernetes | Optional second environment |

Versions are in [SDD §7](SDD.md#7-libraries-and-versions).

---

## 8. Open questions

| # | Question | Why it matters | Default assumed in this plan |
|---|---|---|---|
| Q1 | Add Quarkus or Helidon? | Bigger audience, more work, more risk of unfair config. | No. Open to PRs after launch. |
| Q2 | Add a GraalVM native variant of `mvc-vt`? | prime-bench already covers native. | No. |
| Q3 | Which pool size? | Drives H2. | One value for every app in `.env`, plus one run that sweeps it. |
| Q4 | Is a synthetic `synchronized` block a fair test for H6? | It is artificial. | Keep it, label it synthetic in the README. |
| Q5 | Run on Kubernetes too? | Second environment made prime-bench stronger, but doubles run time. | Only if Compose results raise a doubt. |
