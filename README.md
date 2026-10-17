# TeleClinic reference platform

A runnable reference implementation of the TeleClinic Technical Design Document (Irembo, Software Architect, Digital Health).
It exists to show the design working, including when parts of it fail. **Synthetic data only.**

## Start

```bash
make up-lite     # build and start; waits until the edge serves the core
make doctor      # check every health endpoint
make smoke       # end-to-end login as patient1 through the edge
make journey     # web journey: consent, triage, co-payment, webhook, queue, Kafka, webhook attacks
make scenario-kill-db-leader      # kill the Postgres leader under writes
make scenario-kill-kafka-broker   # stop one Kafka broker
make scenario-partition           # cut the leader's node off the network, then heal
make clinician   # AI-proposed case, orders gated, clinician override, kill switch, no identifiers in AI logs
make reset       # back to a known state (about 30 s)
make test        # core unit and module-boundary tests (Java 21 in a container)
```

Requirements: Docker with Compose v2, `make`, `curl`. Nothing else needs to be installed on the host.

## What is real and what is a stand-in

| Component | Status |
| --- | --- |
| TeleClinic core (Spring Boot 3, Java 21, Spring Modulith) | Real |
| HAProxy edge with blue/green pool and runtime API | Real |
| Apache APISIX API gateway (correlation id, rate limits, configuration as code) | Real |
| PostgreSQL 16 with Patroni, 3 nodes and a 3-member etcd quorum, behind HAProxy | Real |
| Kafka (KRaft), 3 brokers, replication factor 3, min ISR 2 | Real |
| Valkey for shared HTTP sessions | Real, single node (production: three with Sentinel) |
| Anonymization gateway (Presidio with Rwandan recognizers, allowlist, token vault) | Real |
| Triage model (logistic regression on ONNX Runtime) | Real, but **trained on synthetic data with illustrative labels** |
| **Keycloak** | **Labelled stand-in for IremboAccount** |
| **irembopay-mock** | **Labelled stand-in for IremboPay and for the national insurance coverage check** |

Later build steps add the remaining components; see `docs/build-plan.md` for the order and `docs/DEMO_STORY.md` for what each one must show.

## Built so far (step 1)

- **Backend-for-frontend login.** The core is a confidential OIDC client of IremboAccount (stand-in). Authorization code with PKCE (S256), state and nonce. The id token's issuer is checked against the public issuer while token and key calls use the private network. The browser only ever receives an HttpOnly session cookie; `make smoke` checks that no token reaches the client.
- **Request path.** Browser → HAProxy `:9080` → APISIX → HAProxy `core` pool (blue weight 100, green weight 0) → core. The core's actuator is not reachable from the edge.
- **Correlation id.** Created at the gateway, or kept if the caller supplies one (the demo uses the `demo-` prefix), echoed on every response and written on every log line.
- **Module boundaries.** Nine domain modules are declared; `make test` fails the build if one module reaches into another's internals.

## Built so far (step 2)

- **Patients and guardians.** A guardian (Uwase) books and consents for her daughter (Keza), who has no national ID and is identified by a UPI. Anyone else is refused with an OperationOutcome.
- **Consent first.** No booking without an active consent from the patient or guardian; consent records purposes (treatment, payment, AI triage support) and a policy version.
- **Rules triage in the request.** Deterministic, versioned rules (illustrative content) answer in-process; advice always fits one USSD screen. A minimised `triage.requested` event (age band, sex, coded symptoms) goes to Kafka for the model.
- **Booking and payment saga.** DRAFT, TRIAGED, PENDING_PAYMENT (timer), PAID, QUEUED, with EXPIRED and REFUND_PENDING as compensations. Every transition is recorded with its reason.
- **Payments that cannot be double-counted.** The consultation id is the idempotency key at IremboPay. Webhooks are HMAC-verified with a time window, deduplicated by event id, and confirmed with a server-side status query before anything becomes PAID. A lost webhook is recovered by the timer's status query; a payment after expiry becomes a refund.
- **Insurance.** Coverage is checked before payment; an insured patient (Eric, Mutuelle) pays only the co-payment.
- **Outbox.** Events are stored in the same transaction as the state change and sent to Kafka after commit, keyed by patient id. Events left unsent by a failure go out again on restart; this was observed during development, not only designed.

## Built so far (step 3): the data tier survives failures

Three "nodes", each running etcd, Patroni/PostgreSQL and a Kafka broker, so a whole node can be killed or cut off.
Patroni uses quorum commit (a commit waits for one of the two standbys), `failsafe_mode` off and `pg_rewind`.
HAProxy sends the core to whichever node is leader and cuts sessions to a node that stops being one.

Measured on a 12-core, 16 GB laptop (each scenario is a script you can rerun):

| Scenario | Result |
| --- | --- |
| Kill the database leader while writes run every 200 ms | Writes resumed after 21 s; 0 acknowledged writes lost; the old leader rejoined as a replica |
| Stop one Kafka broker | All partitions kept 2 in-sync replicas; 50 of 50 messages written with `acks=all`, no errors |
| Cut the leader's node off the network (`docker network disconnect`) | The isolated leader stopped accepting writes after 6 to 7 s; the majority elected a new leader after 18 to 23 s; never two writable leaders; a direct write to the isolated node was refused; identical rows on all nodes after healing |

Not in the demo: a hardware watchdog (none inside Docker), PgBouncer, Valkey Sentinel, and a second site.

## Built so far (step 4): AI that advises and never decides

- **Clinician queue** at http://localhost:9080/clinician (sign in as `clinician1`): most urgent first, review deadline per case, an AI-PROPOSED badge, rules and model side by side with contributing factors, confirm or override with a reason, and a signed decision line.
- **Escalate-only merge.** The proposed urgency is the higher of rules and model. The model can never lower the rules floor.
- **The gate is in the domain model, not the screen.** Orders, referrals and prescriptions return 403 until a clinician has finalised the triage. Every decision is recorded with its provenance: the model version as a device agent, the clinician as the verifier.
- **Deadline.** A case not reviewed in time moves to UNREVIEWED_TIMEOUT for a supervisor; the rules urgency stays in force.
- **Kill switch.** One call (`scripts/scenarios/model-kill-switch.sh off`) and new cases stay rules-only; the queue shows a banner. Shadow mode records suggestions without showing them.
- **AI trust zone.** The model service reads `triage.requested`, sends each payload through the anonymization gateway, and posts a signed suggestion back. The gateway keeps an allowlist of fields, replaces the case id with a token only the core can resolve, generalises a few values, and redacts free text with Presidio (Rwandan national IDs, phone numbers, UPIs, Kinyarwanda and French names, districts). The model's output is scanned for identifiers before it leaves the zone. The gateway sits on an internal Docker network with no route out.
- **Build-time gates.** The gateway image is only built if its synthetic PII suite passes (including canary values). The model image is only built if the model meets its release gates (under-triage rate and recall per age group and sex); the results are in its model card.

Measured (synthetic data; numbers describe the demo model, not clinical performance):

| What | Result |
| --- | --- |
| Model validation, 4,800 held-out synthetic cases | accuracy 0.958; under-triage rate 0.032 (gate 0.10); recall for urgent or higher 0.97 to 0.99 in every age group and sex (gate 0.85) |
| Anonymization suite | 6 of 6 tests; 10 of 10 free-text identifiers removed (national IDs, three phone formats, UPI, names, district, email) |
| Model path, event to suggestion in the core | 23 to 36 ms per case |
| Model restart | a new case is handled about 4 s after the restart (the old instance leaves its consumer group cleanly) |
| Escalation example | fever, headache and rash: rules SEMI_URGENT, model URGENT, proposed URGENT |
| AI zone logs after a full run | 0 identifier matches; the anonymizer has no route to the internet |

## Demo users (synthetic)

`patient1` (Uwase), `patient2` (Eric), `clinician1` (Dr Mukamana), `clerk1` (Habimana), `admin1`. One demo password for all of them, set in `.env.example`.

## Ports

See `docs/URLS.md`.

## Memory (measured after step 2)

| Container | Memory in use | Limit |
| --- | --- | --- |
| keycloak | 552 MiB | 768 MiB |
| kafka-1 | 344 MiB | 768 MiB |
| core-blue | 215 MiB | 512 MiB |
| apisix | 161 MiB | 256 MiB |
| postgres | 42 MiB | 256 MiB |
| irembopay-mock | 38 MiB | 128 MiB |
| haproxy | 13 MiB | 128 MiB |

About 2.5 GB for the whole stack after step 4. `make reset` to a working stack: 49 s.
