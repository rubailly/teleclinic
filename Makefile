# TeleClinic reference platform. Run `make help` for the targets.
SHELL := /bin/bash
COMPOSE := REPO_DIR=$(CURDIR) docker compose --project-directory . --env-file .env -f compose/edge.yml -f compose/data.yml -f compose/ai.yml -f compose/apps.yml -f compose/obs.yml
# Fail and retry a stalled download instead of hanging
MVN_NET := -Daether.connector.connectTimeout=20000 -Daether.connector.requestTimeout=60000
# Integration tests start a throwaway Postgres through the host Docker daemon (Testcontainers)
MAVEN := docker run --rm -v "$(CURDIR)/services/core":/w -v teleclinic-m2:/root/.m2 -w /w \
	-v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	--add-host=host.docker.internal:host-gateway maven:3.9.9-eclipse-temurin-21 mvn -B -q $(MVN_NET)

.PHONY: help up-lite down reset doctor test smoke journey scenario-kill-db-leader scenario-kill-kafka-broker scenario-partition ussd scenario-webhooks audit scenario-tamper scenario-sync scenario-identity scenario-emr-offline clinician logs ps release release-bad switch-back interop-network emr-up emr-down emr-wipe emr-reset

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

.env:
	cp .env.example .env

up-lite: .env interop-network ## Build and start the lite profile, wait until healthy
	$(COMPOSE) up -d --build --wait
	@# The next release (green v1.5) is built now, so the release scene does not wait for a build
	$(COMPOSE) --profile release build -q core-green
	@# Compose health says the core is up; HAProxy needs two passing checks of its own before it routes to it
	@for i in $$(seq 1 30); do curl -fs -o /dev/null http://localhost:9080/api/public/ping && echo "Edge is serving the core." && exit 0; sleep 1; done; echo "Edge did not reach the core in 30 s" >&2; exit 1

down: .env ## Stop everything (keeps volumes)
	$(COMPOSE) --profile release down

reset: .env ## Back to a known state: remove containers and volumes, start again (the EMR's demo data too, if it runs)
	@# Data is thrown away, so there is no point waiting for graceful shutdowns
	$(COMPOSE) --profile release down -v -t 1 --remove-orphans
	@# The EMR stays up (it takes minutes to initialise); its demo data goes back to the seed in seconds, while the core
	@# is down, so the first poll after the reset reads the fresh seed
	@if docker ps --format '{{.Names}}' | grep -q '^teleclinic-emr-openmrs-backend-1$$'; then python3 scripts/emr/seed.py --reset; fi
	$(MAKE) up-lite

interop-network:
	@docker network inspect teleclinic-interop >/dev/null 2>&1 || docker network create teleclinic-interop >/dev/null

EMR := docker compose --project-directory . --env-file .env -f compose/emr.yml

emr-up: interop-network ## Start OpenMRS and the sync gateway (OpenFn), then configure both (first start: about 4 minutes)
	python3 scripts/emr/env.py
	$(EMR) up -d --wait
	python3 scripts/emr/bootstrap.py

emr-down: ## Stop OpenMRS and the sync gateway (data kept)
	$(EMR) down -t 20

emr-wipe: ## Remove OpenMRS and the sync gateway with all their data (next emr-up starts from empty)
	$(EMR) down -v -t 5

emr-reset: ## Put the EMR's demo data back to the seed (seconds; done by make reset too)
	python3 scripts/emr/seed.py --reset

doctor: .env ## Check every health endpoint
	./scripts/doctor.sh

test: ## Unit and module-boundary tests for the core (Java 21 in a container)
	@# The container runs as root; give the build output back to the host user whatever the result
	$(MAVEN) verify; rc=$$?; docker run --rm -v "$(CURDIR)/services/core":/w alpine:3.20 chown -R $$(id -u):$$(id -g) /w/target; exit $$rc

smoke: .env ## End-to-end login through the edge as patient1 (BFF, no token in the browser)
	./scripts/smoke/login.sh patient1

journey: .env ## End-to-end web journey: consent, triage, co-payment, webhook, queue, Kafka, webhook attacks
	./scripts/smoke/journey.sh

scenario-kill-db-leader: .env ## Scene 4a: kill the Postgres leader under writes; measure failover, check no loss
	./scripts/scenarios/kill-db-leader.sh

scenario-kill-kafka-broker: .env ## Scene 4a: stop one Kafka broker; producers keep writing with acks=all
	./scripts/scenarios/kill-kafka-broker.sh

scenario-partition: .env ## Scene 4b: isolate the leader's node; minority demotes, majority continues, heal
	./scripts/scenarios/partition-node.sh

ussd: .env ## Scene 1: book for a child from a basic phone (USSD), pay by mobile money, get an SMS
	./scripts/smoke/ussd.sh

scenario-webhooks: .env ## Scene 1c: replay the last payment webhook twice and forge one; nothing changes
	./scripts/scenarios/duplicate-webhooks.sh

audit: .env ## Scene 5b: purpose and consent enforced, who accessed my record, DBA query captured by pgAudit
	./scripts/smoke/audit.sh

scenario-tamper: .env ## Scene 5b: a DBA edits an audit row; the hash chain finds it, pgAudit shows who
	./scripts/scenarios/tamper-audit.sh

scenario-sync: .env ## Scene 3b: Keza's encounter in OpenMRS once, echoes dropped, a phone edited in OpenMRS applied once
	./scripts/scenarios/sync-no-loop.sh

scenario-identity: .env ## Scene 3c: review, link and unlink with national ID write-back; hard block; UPI auto-link
	./scripts/scenarios/identity-review.sh

scenario-emr-offline: .env ## Scene 4c: OpenMRS down while consultations complete; the backlog drains once each
	./scripts/scenarios/emr-offline.sh

clinician: .env ## Scene 2: AI-proposed case, orders refused until FINAL, override, kill switch
	./scripts/smoke/clinician.sh

logs: .env ## Follow logs of all services
	$(COMPOSE) logs -f --tail=100

ps: .env ## Show service status
	$(COMPOSE) ps

release: .env ## Scene 4d: release v1.5 to the idle colour, 1/10/50/100% under load, gated, rollback on errors
	./scripts/release/blue-green.sh v1.5

release-bad: .env ## Scene 4d: rollback drill; v1.6 fails 30% of API calls and must be rolled back automatically
	./scripts/release/blue-green.sh v1.6 --fault-rate 0.3 --expect-rollback

switch-back: .env ## Put traffic back on the previous colour (kept running at weight 0)
	./scripts/release/blue-green.sh --switch-back
