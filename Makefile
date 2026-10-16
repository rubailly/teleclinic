# TeleClinic reference platform. Run `make help` for the targets.
SHELL := /bin/bash
COMPOSE := docker compose --project-directory . --env-file .env -f compose/edge.yml -f compose/data.yml -f compose/apps.yml
# Fail and retry a stalled download instead of hanging
MVN_NET := -Daether.connector.connectTimeout=20000 -Daether.connector.requestTimeout=60000
# Integration tests start a throwaway Postgres through the host Docker daemon (Testcontainers)
MAVEN := docker run --rm -v "$(CURDIR)/services/core":/w -v teleclinic-m2:/root/.m2 -w /w \
	-v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	--add-host=host.docker.internal:host-gateway maven:3.9.9-eclipse-temurin-21 mvn -B -q $(MVN_NET)

.PHONY: help up-lite down reset doctor test smoke logs ps release-up

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

.env:
	cp .env.example .env

up-lite: .env ## Build and start the lite profile, wait until healthy
	$(COMPOSE) up -d --build --wait
	@# Compose health says the core is up; HAProxy needs two passing checks of its own before it routes to it
	@for i in $$(seq 1 30); do curl -fs -o /dev/null http://localhost:9080/api/public/ping && echo "Edge is serving the core." && exit 0; sleep 1; done; echo "Edge did not reach the core in 30 s" >&2; exit 1

down: .env ## Stop everything (keeps volumes)
	$(COMPOSE) --profile release down

reset: .env ## Back to a known state: remove containers and volumes, start again
	$(COMPOSE) --profile release down -v --remove-orphans
	$(MAKE) up-lite

doctor: .env ## Check every health endpoint
	./scripts/doctor.sh

test: ## Unit and module-boundary tests for the core (Java 21 in a container)
	$(MAVEN) verify

smoke: .env ## End-to-end login through the edge as patient1 (BFF, no token in the browser)
	./scripts/smoke/login.sh patient1

logs: .env ## Follow logs of all services
	$(COMPOSE) logs -f --tail=100

ps: .env ## Show service status
	$(COMPOSE) ps

release-up: .env ## Start core-green (v1.5) beside blue, with weight 0 in HAProxy
	$(COMPOSE) --profile release up -d --build --wait core-green
