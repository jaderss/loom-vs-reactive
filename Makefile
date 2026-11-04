include .env
export

RUN_ID ?= $(shell date -u +%Y%m%dT%H%M%SZ)
RUN_ID := $(RUN_ID)
HOST_UID := $(shell id -u)
HOST_GID := $(shell id -g)
export RUN_ID HOST_UID HOST_GID

COMPOSE := docker compose

.PHONY: help build up bench contract dashboard down clean

help:
	@echo "make up         start Postgres (seeded on first start), Toxiproxy and the rates mock"
	@echo "make bench      every scenario in SCENARIOS for every app in APPS, ROUNDS times"
	@echo "make contract   run the contract suite against every app in APPS"
	@echo "make dashboard  serve the charts on http://localhost:8080 (DASHBOARD_PORT to change)"
	@echo "make down       stop everything"
	@echo "make clean      delete result files (results/sample stays)"
	@echo "Override anything from .env, for example: make bench APPS=webflux SCENARIOS=statement ROUNDS=1"

build:
	$(COMPOSE) build mock $(APPS)
	@for v in $(LEGACY_LOCK_JAVA_VERSIONS); do JAVA_VERSION=$$v $(COMPOSE) build mvc-vt || exit 1; done
	$(COMPOSE) --profile tools build loadgen

up:
	$(COMPOSE) up -d --wait postgres toxiproxy mock

bench: build up | results
	@for round in $$(seq 1 $(ROUNDS)); do \
		for scenario in $(SCENARIOS); do \
			if [ $$scenario = legacy-lock ]; then \
				for v in $(LEGACY_LOCK_JAVA_VERSIONS); do \
					RUN_ID=$(RUN_ID)-r$$round scripts/bench.sh mvc-vt legacy-lock $$v || exit 1; \
				done; \
			else \
				for app in $(APPS); do RUN_ID=$(RUN_ID)-r$$round scripts/bench.sh $$app $$scenario || exit 1; done; \
			fi; \
		done; \
	done

contract: up
	@for app in $(APPS); do \
		echo "== contract against $$app"; \
		$(COMPOSE) up -d --force-recreate $$app && \
		$(COMPOSE) run --rm -e BASE_URL=http://$$app:8080 contract; \
		status=$$?; $(COMPOSE) stop $$app; [ $$status -eq 0 ] || exit $$status; \
	done

dashboard:
	$(COMPOSE) up -d --build dashboard
	@echo "Dashboard on http://localhost:$${DASHBOARD_PORT:-8080}"

down:
	$(COMPOSE) --profile tools down --remove-orphans

clean:
	rm -f results/*.csv results/*.txt results/*.jfr

results:
	mkdir -p results
