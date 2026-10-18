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
make scenario-webhooks            # replay the last payment webhook twice and forge one
make emr-up      # once: eBuzima, OpenMRS O3 and the OpenFn sync gateway (first start about 5 minutes; kept across resets)
make scenario-sync                # Scene 3b: encounter in eBuzima once in the right tenant, echoes dropped, a nurse's phone edit applied once
make scenario-identity            # Scene 3c: review, link, unlink with national ID write-back; hard block; UPI auto-link
make scenario-emr-offline         # Scene 4c: the hospital's OpenMRS down while consultations complete; the backlog drains once each
make audit       # purpose of use and consent enforced, who accessed my record, DBA query captured by pgAudit
make scenario-tamper              # a DBA edits an audit row; the hash chain finds it, pgAudit names the role
make release     # release v1.5 to the idle colour: 1, 10, 50, 100% under load, gated
make release-bad # rollback drill: a build that fails 30% of API calls is caught and rolled back
make capacity    # load test: web intake 25 to 200/s with USSD sessions, then a noisy client (about 6 min; make reset after)
make switch-back # put traffic back on the previous colour
make ussd        # book for a child from a basic phone, pay by mobile money, get an SMS
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
| USSD gateway (menus, sessions in Valkey, one page per step) | Real |
| eBuzima stand-in: ERPNext 15 with the Frappe Healthcare app (the stack of the national eBuzima), one site, each health centre a tenant | Real software; **not the Ministry's instance or its customisations**; data synthetic |
| OpenMRS O3 (reference application 3.7, no demo data) as Kibagabaga District Hospital's EMR | Real; its patients are synthetic |
| OpenFn Lightning and worker as the sync gateway (two workflows per EMR) | Real |
| Sync queue, ledger, echo suppression, identity matching and clerk review in the core | Real |
| Audit: interceptor, purpose-of-use and consent policy, pgAudit, hash chain, explorer | Real |
| Log shipper (Vector) for pgAudit lines | Real |
| Prometheus and Grafana (SLO dashboard, 5 alert rules, runbooks) | Real |
| Ops cockpit and scenario runner (runs the scenario scripts, live probes, reconciliation) | Real; a local demo tool on 127.0.0.1 |
| **Phone simulator** | **Labelled stand-in for the telecom aggregator, a basic phone, its mobile-money prompt and SMS** |

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

## Built so far: the USSD channel

- **Phone simulator** at http://localhost:9380 (labelled). Dial `*909*8#`, pick Kinyarwanda or English, enter the PIN (`1234` for the two demo numbers), choose who is sick, consent, pick symptoms one at a time, and get the rules advice in the same session. Booking ends the session with a mobile-money prompt; once IremboPay confirms, the core sends an SMS.
- **Same core, same rules.** The gateway is stateless (sessions in Valkey, 180 s) and calls the core on internal routes the public edge refuses. A phone number is linked to an IremboAccount subject once; the PIN is stored as a bcrypt hash and three wrong tries lock it for 15 minutes. Each unlock gives a 5-minute channel token.
- **One page per step.** Every screen fits 182 characters; the smoke test fails if one does not.

Measured on the demo machine (`make ussd`): the slowest gateway step, booking with the rules triage, took 176 ms; unlocking with the PIN 96 to 158 ms; menu steps under 5 ms. The budget per step is 1,500 ms. The longest page is 130 characters.

## Built so far: the ops cockpit

- **Ops cockpit** at http://127.0.0.1:9500. One button per failure scenario; each runs the same script as `make scenario-*` and streams its output step by step. One scenario at a time.
- **Live probes.** A row is written through pgproxy (the core's path) and the edge is called every 250 ms; the charts show each sample, and a failed write is red. During a leader kill the gap is visible and timed.
- **Nodes.** Each of the three nodes shows its PostgreSQL role, state, lag and timeline, its etcd member and its Kafka broker. A node cut off by `docker network disconnect` is marked; so is a leader key still held by a node that is down.
- **Reconciliation.** Payments in the core against IremboPay's settlement report (count and amount), webhooks processed once with duplicates ignored and forged ones rejected, and outbox events still waiting for Kafka.
- The runner holds the Docker socket, so it uses the host network and listens on 127.0.0.1 only. It is a demo tool and not part of the production design; production runs these drills from a runbook with the same scripts.

Measured from the cockpit, with the probes writing during each run: database leader killed, writes resumed after 16.8 s, 0 of 18 acknowledged writes lost; node partition, the old leader stopped accepting writes after 4.2 s and the majority elected a new leader after 23.0 s, identical rows (count and checksum) on all nodes after healing; one Kafka broker stopped, 50 of 50 messages written with `acks=all`.

## Built so far: blue/green release with HAProxy

- `scripts/release/blue-green.sh` finds the live colour, starts the new version on the idle one at weight 0, and moves traffic 1, 10, 50 and 100 percent through HAProxy's runtime API while k6 sends steady load through the public edge. The version is built from the current source first (a cached build when nothing changed), so an older image with the same tag is never released by mistake; `REUSE_IMAGE=1` releases an existing image as it is. A new colour that fails to start takes no traffic, is put in maintenance and stopped.
- **Gate at every step:** the new colour's own error counters in HAProxy (5xx, response and connection errors) must stay under 1 percent, and HAProxy must keep it up. A breach puts all traffic back on the live colour in milliseconds, puts the new colour into maintenance and stops it.
- The previous colour stays running at weight 0. `make switch-back` returns traffic to it, but only if Docker and HAProxy both report it healthy.
- **Rollback drill:** `make release-bad` deploys v1.6 with a fault switched on by configuration (30 percent of API calls fail). It is the same code as v1.5, standing in for a bad build. Like a real bad build it passes its health checks, so only the traffic gate can catch it.
- The cockpit has a button for each, and its header shows which releases are answering during a canary.

Measured (k6 at 12 requests per second through the edge, plus the cockpit probe):

| Run | Result |
| --- | --- |
| Release v1.5 to green | 1,181 requests, 0 failed, p95 7 ms; 100 s from start to green at 100 percent |
| Rollback drill, v1.6 | caught at the 1 or 10 percent step in three runs; 1 failed request each time; weights switched back in 6 to 14 ms |
| Edge probe during release, switch-back and drill | 285 of 285 requests answered 200 |

The edge rate limit (1,200 requests per minute) is per client: HAProxy records the client address in `X-Real-IP`, overwriting any value sent by the client, and APISIX keys on it. Before this change APISIX only saw HAProxy's address, so all clients shared one limit.

## Built so far: capacity under load

`make capacity` (`scripts/load/capacity.sh`) drives the real paths with k6 at fixed arrival rates, for 60 s per level: web
intake by a signed-in patient (rules triage in the request, consultation stored, model suggestion queued) and whole USSD
sessions from the aggregator's callbacks up to the booking. Sixteen generators run inside the Docker network, each with
its own address, so the edge's per-client quota does not cap the total. Measured on one core instance beside the whole
stack on the 16 GB laptop (percentiles are the worst generator's):

| Web intakes/s + USSD sessions/s | Failed | Web p95 | USSD step p95 | Async backlog |
| --- | --- | --- | --- | --- |
| 25 + 1 | 0 | 196 ms | 171 ms | none |
| 50 + 2 | 0 | 77 ms | 145 ms | none |
| 100 + 4 (about 140 requests/s) | 0 | 166 ms | 264 ms | none |
| 200 + 8 (saturated, about two CPUs) | 0 | 2.4 s | 2.6 s | drained about a minute later |

A noisy client at 80 requests/s got 1,200 a minute and 1,201 refusals (429) while a client at 5/s was fully served.

The test found two faults, both fixed: an overloaded core's health checks timed out behind its own request queue, so
HAProxy took the only live colour out and every request failed (now at most 80 requests in flight per core, the rest
queue in HAProxy for up to 5 s); and with the heap at 75% of a 512 MB container the kernel killed the JVM (now 60% of
640 MB). Model suggestions stay asynchronous under overload: each case is queued at once on the rules result and the
suggestion follows; one anonymizer instance cleared about 35 a second, so production runs several.

## Built so far: service levels, alerts and runbooks

- **SLO dashboard** at http://localhost:9700 (read-only without login): edge availability and error budget against the 99.95% target, API p95, USSD step p95 against 1.5 s, rules p95 against 100 ms, whether the database has a writable leader; then rows for the release (weights and errors per colour), clinical safety (cases waiting, past their deadline, model on or off), bookings and payments (by channel, webhook outcomes, outbox), and the data tier (Patroni leader over time, replica lag, etcd).
- **Where the numbers come from:** the core (Micrometer: rules timing, state changes, webhook outcomes, cases waiting and overdue, outbox backlog), the USSD gateway (time per step), HAProxy on the edge and in front of PostgreSQL (built-in exporter), Patroni and etcd. Prometheus scrapes every 5 s. Metrics are counts and timings only; a test checks that the endpoint carries no identifiers.
- **Five alert rules**, provisioned as code, each with a runbook link: no writable database leader (critical), edge error budget burning fast (critical, 5 minute and 1 hour windows at 14.4 times the budget), triage cases past their review deadline, USSD steps over budget, and events not reaching Kafka.
- **Runbooks** in `docs/runbooks`, one per alert, served at http://127.0.0.1:9500/runbooks/: what it means, what to check first, what to do, what to record.

Measured: killing the database leader from the cockpit, the alert fired 17 s after the scenario started (13 s after the kill) and resolved on its own once pg-3 took over; writes resumed after 18.7 s with 0 of 18 acknowledged writes lost. After `make reset` all five alerts are inactive and the edge shows 0 errors (the cockpit's probe now starts once the core answers).

## Built so far: audit you can verify (Task 3)

- **Purpose of use on every clinical read.** `GET /fhir/Patient/{id}`, the clinician's case and patient views and a staff read of a consultation all need `X-Purpose-Of-Use` (TREAT, PAYMENT, OPERATIONS, PATIENT_ACCESS, BREAK_GLASS). The role must be allowed that purpose; TREAT and PAYMENT need the patient's active consent for it; BREAK_GLASS needs `X-Break-Glass-Reason`. Otherwise: 403 with an OperationOutcome, and the refusal is recorded.
- **Three layers, one trail.** The application records who, role, which patient, what, purpose, basis (consent id, data subject, break-glass) and outcome, through the outbox to Kafka (`audit.events`), so a record is not lost when Kafka is down. pgAudit records every statement by administrator roles and schema changes by the application role; Vector ships those lines to Kafka (`audit.db`). One writer appends both to `audit.event` with a sequence number and a SHA-256 chain; a row lock on the chain head keeps blue and green from forking it.
- **Append-only.** A trigger refuses UPDATE, DELETE and TRUNCATE on the trail. Getting around it needs DDL, which pgAudit records, and the change breaks the chain.
- **Verification.** Every 30 s in the background and on demand. It reports the first entry that changed, is missing, or the trail cut short at the end. A break raises "Audit chain broken" (critical); a break-glass read raises "Break-glass access" for supervisor review. Both have runbooks.
- **Who accessed my record** in the patient app (http://localhost:9080/app/#/access): every read and change by someone else, with role, purpose, refusals and the break-glass reason. **Audit explorer** for auditors at http://localhost:9080/audit/ (`admin1`): both sources, each entry with its hash links, and Verify now. `GET /fhir/AuditEvent?entity=Patient/{id}` returns the same as FHIR AuditEvent resources.
- Found and fixed on the way: the Triage API's suggestion endpoint was open to any signed-in user; it is now clinicians only and needs a purpose of use.

Measured: an administrator's `SELECT` on the patient table in psql reached the trail in about 1 s. In the tamper drill the check names the edited entry ("content does not match its hash"), pgAudit shows `DDL ALTER TABLE`, `WRITE UPDATE`, `DDL ALTER TABLE` by role `postgres` on the leader, the chain alert fires about 10 s after the edit and the break-glass alert about 12 s after the read. 64 core tests, including the chain checks under concurrent appends.

Not in the lite profile (documented in the design): the daily signed checkpoint to write-once storage, shipping the gateway access log (layer 1 is in the HAProxy and APISIX container logs), and a separate auditor role (the demo uses `admin1`). The Docker log source can drop a line in flight if the shipper restarts; production tails the PostgreSQL log files with checkpoints. Consent given in the web app is recorded in the consent table's own history rather than by the interceptor (the consent module sits below the audit module); consent given over USSD is in the trail.

## Built so far: facility EMR sync and identity matching (Task 2)

TeleClinic writes to the EMRs that Rwanda's public facilities actually run, by level:

- **Health centres: eBuzima.** The Ministry's eBuzima is one central, web-based ERPNext site with the Frappe Healthcare app, and each health centre is a tenant of it. The stand-in is the same software (`services/ebuzima`): one site, Kimironko and Remera health centres as two tenants (ERPNext companies), a Kimironko nurse who sees only her tenant (User Permission), TeleClinic's identifiers as custom fields (TeleClinic ID, UPI; the national ID goes in the standard Identification Number field), and a teleconsultation practitioner. It is not the Ministry's instance: its own custom doctypes, partner API policy and webhook policy are assumptions to confirm (design A3).
- **District, referral and teaching hospitals: their own OpenMRS.** Here, Kibagabaga District Hospital (Gasabo), the referral hospital of both health centres.
- **Routing by home facility.** The patient index records each patient's home facility, and the facility registry its level, EMR and tenant. Uwase and Keza are registered at Kimironko Health Centre, so their encounters go to eBuzima in Kimironko's tenant; Eric and the identity-matching patients are followed at Kibagabaga, so theirs go to its OpenMRS. Queue, ledger, cursor, sync events and identity links all carry the EMR, so the same patient can be known to both and one EMR being down does not hold up the other.
- **Both EMRs and OpenFn** run as their own Compose project (`make emr-up`, once). They need minutes to initialise empty databases, so `make reset` does not restart them: it puts their demo data back to the seed through their APIs in seconds. The core works without them, which is also how it behaves when an EMR is offline.
- **Outbound.** A clinician's final decision queues the encounter (only if the patient's consent covers treatment). The core calls the gateway's webhook so a run starts at once; a one-minute schedule is the safety net. The EMR's OpenFn workflow does a conditional create: it finds the patient by the TeleClinic ID on the EMR record (unique there), else by UPI or national ID (and adopts that record), else creates it; it writes the encounter only if none carries the consultation id (in eBuzima, a submitted Patient Encounter in the health centre's tenant, by the integration user). Retries back off exponentially with jitter; after 10 failures a write is a dead letter that can be replayed.
- **Inbound and no loops.** eBuzima calls the gateway's webhook when a patient record is saved (a Frappe document webhook); the workflow then reads every patient modified since its cursor and maps each to a FHIR Patient. OpenMRS is polled through its FHIR API with `_lastUpdated`. Both run every minute as a safety net, and both deliver FHIR to `POST /sync/inbound`, so the core has one inbound contract. The ledger keeps ids, the EMR version and a content hash per record: TeleClinic's own write coming back has the same hash and is dropped as an echo (200); a contact phone changed at the facility (EMR-authoritative) is applied once, audited with the EMR as the author, and not sent back (202).
- **Identity matching.** EMR records TeleClinic does not know are blocked and scored against the patient index (names token by token, birth date exact or day and month swapped, sex, phone, UPI, national ID). Two strong identifiers agreeing (national ID, UPI, exact name with exact birth date) link automatically; the middle band goes to a clerk (`/clerk/`, `clerk1`) with side-by-side evidence; different national IDs are a hard block. A link offers the national ID to that EMR's record; an unlink takes it back. Scene 3c runs on the hospital's OpenMRS. Links, never merges; every decision is audited.
- **Seen in the EMRs:** in eBuzima (http://localhost:9580) the Kimironko nurse sees the teleconsultation under Patient Encounter, in her tenant only, written by "TeleClinic Sync gateway"; in OpenMRS (http://localhost:9880/openmrs/spa, location Kibagabaga District Hospital) the patient header shows the TeleClinic ID and UPI, and the teleconsultation is under All encounters.

Measured: decision to encounter in eBuzima, median 2.0 s, p95 3.8 s, max 4.9 s over 10 consultations; in OpenMRS, median 1.8 s and p95 2.3 s (target p95 under 5 s); a phone saved by the Kimironko nurse reached TeleClinic 2.3 to 4.3 s later, pushed by eBuzima's webhook; with OpenMRS stopped, writes waited and arrived once each after it came back (81 s for a 30 s outage plus OpenMRS's 48 s restart); the facility's four seeded records landed in the expected bands (review, blocked, automatic link, no match).

Known limits: the eBuzima stand-in models what is public about eBuzima (Frappe, ERPNext, Healthcare, one site, tenants per health centre), not the Ministry's customisations; patients are national records in Healthcare, so the tenant is on the encounter, not the patient. OpenMRS's FHIR `_lastUpdated` reports an edited phone but not one added where there was none (production adds a periodic reconciliation sweep of linked records by hash); the gateway writes teleconsultations as encounters without a visit (O3 lists them under All encounters, not Visits); conflicts on TeleClinic-authored fields are logged, not yet queued for a human.

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
