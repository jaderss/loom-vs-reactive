# loom-vs-reactive

Spring MVC on virtual threads, Spring WebFlux, Kotlin coroutines and Go, with the same HTTP contract against the same Postgres. The question: for an I/O bound service, is reactive still worth what it costs to write and to debug?

Each app gets the same CPU and memory. Scenarios cover a healthy database, a slow database with the pool as the bottleneck, and load above capacity with an open model.

Status: design. No results yet.

* [Plan](docs/PLAN.md): hypotheses, phases, tools.
* [SDD](docs/SDD.md): design, contract, scaffolding, versions.

Same harness idea as [prime-bench](https://github.com/jaderss/prime-bench): one command, one CSV per run, a dashboard.
