# Build plan

## Build order (stop and test at each step)
1. Repo skeleton, Makefile, Keycloak realm, HAProxy and APISIX, core behind the gateway with BFF login. **Done.**
2. Core journey: consent (including guardian consent), rules triage, consultation saga, irembopay-mock with signed, duplicate and forged webhooks and the coverage check, webhook dedupe and status check, outbox to Kafka. Web journey for `patient2`. **Done** (API and `make journey`; the patient web app itself comes with the other apps).
3. Patroni with 3 etcd and HAProxy for Postgres; Kafka KRaft x3; Valkey for shared sessions. Scenarios `kill-db-leader`, `kill-kafka-broker`, `partition-node`.
4. Clinician review (state machine, API, UI). Anonymizer and model service. Kill switch and rules-only banner.
5. OpenMRS O3 (seeded) and OpenFn workflows: outbound, inbound polling, echo drop, conflict queue, identifier write-back.
6. Identity matching with the duplicates seed; review queue; link and unlink.
7. Audit: interceptor, purpose-of-use and consent policy (403), pgAudit into `audit.events`, hash chain, tamper check.
8. USSD gateway and simulator; blue/green release script; ops cockpit; Grafana dashboards and one alert with a runbook link.
9. Scenario scripts, load test (k6), reset, doctor, README, rehearsal.
10. Optional: Helm chart and `make k8s-lite` (k3d) for core and ussd-gateway.

## Containers by compose file
| File | Containers | Notes |
| --- | --- | --- |
| edge.yml | haproxy (blue/green weights, runtime API), apisix, keycloak (realm import) | Keycloak realm `irembo-mock`: users patient1 (Uwase, USSD, guardian of Keza), patient2 (Eric, web), clinician1, clerk1, admin1 (demo passwords only, in .env.example) |
| data.yml | etcd x3, patroni x3 (use a maintained image), pgbouncer, kafka x3 (KRaft), valkey | Patroni is the riskiest piece; budget half a day. `failsafe_mode: false`, `use_pg_rewind: true`. Group containers per "node" (etcd-N, patroni-N, kafka-N) so `partition-node N` can disconnect one node |
| apps.yml | core-blue, core-green, ussd-gateway, irembopay-mock, triage, anonymizer, scenario-runner | core images tagged v1.4 and v1.5 for the release demo |
| interop.yml | openmrs-o3 (seeded image), openfn-lightning (web + worker + postgres) | Workflows imported at start from `config/openfn/` |
| obs.yml | grafana otel-lgtm single image, k6 (on demand) | One dashboard per demo scene |
| UI containers | ui-ussd-sim, ui-patient, ui-clinician, ui-ops (nginx static) | API base URL from `/config.js`, set at container start |

## Services to hand-write
**core (Spring Boot 3, Java 21, Spring Modulith)**
- Modules: identity, consent, patient-index, consultation, triage, payments, fhir, audit, notifications.
- Identity: confidential OIDC client (BFF) against Keycloak with PKCE; HttpOnly, Secure, SameSite session cookie; no tokens sent to the browser. Dependents: guardian `RelatedPerson` linked to the IremboAccount subject; bookings "for me" or "for a dependent".
- Consent: FHIR `Consent` created before any Encounter; guardian as performer for dependents; policy check returns 403 with OperationOutcome when consent or `X-Purpose-Of-Use` is missing; BREAK_GLASS allowed with a reason and raises an alert.
- Payments: webhook HMAC and timestamp check, event id dedupe table, server-side status query before PAID.
- Saga states: DRAFT, TRIAGED, PENDING_PAYMENT (10 min timer), PAID, QUEUED, IN_CONSULTATION, COMPLETED, SYNC_PENDING, SYNCED; failures EXPIRED, REFUND_PENDING, REFUNDED, SYNC_FAILED.
- Outbox table + relay to Kafka topics: `consultation.paid`, `fhir.outbound`, `audit.events`, `triage.requested`.
- Audit: interceptor on every PHI endpoint; pgAudit output shipped into `audit.events` so direct `psql` access by an admin appears in the audit explorer; hash-chain writer (sequence + SHA-256 of previous); `GET /audit/verify` returns chain status and the first broken index; `POST /demo/tamper` (demo profile only) edits one row.
- FHIR facade: Patient, Encounter, Observation, AuditEvent, `Patient/$match`, transaction Bundle with conditional create; validate with HAPI FHIR validator against the profiles in `config/fhir/`.
- Triage state machine: RULES_ONLY, AI_PROPOSED, UNDER_REVIEW, CONFIRMED, OVERRIDDEN, FINAL, UNREVIEWED_TIMEOUT; policy blocks orders/referrals/prescriptions until FINAL.

**ussd-gateway**: stateless; Africa's-Talking-style callback (sessionId, msisdn, text); session in Valkey; menu state machine with a "who is this for" step (me or a linked dependent) and guardian consent; calls rules evaluate in-step; returns `CON` or `END` under about 160 characters; exposes step latency to the simulator.

**irembopay-mock**: create payment (Idempotency-Key), hosted checkout page, "approve" button, signed webhooks (HMAC, event id), status endpoint, delayed, duplicate and forged (bad signature) webhook modes, settlement report. Also a labelled coverage-check endpoint standing in for the national insurance portal (CBHI/Mutuelle, RSSB) that returns covered or not and the co-payment amount.

**triage**: `/rules/evaluate` (deterministic rules in `config/rules/`, illustrative only, versioned) and a small model (ONNX or simple classifier) behind `/cases/{id}/inference`; `/models/kill-switch`.

**anonymizer**: Presidio with custom recognizers (16-digit national ID format, +250 phone numbers, Kinyarwanda and French names); field allowlist; tokens in a vault table; `/anonymize` returns masked payload and a redaction report; test suite with synthetic PII and canary tokens.

**scenario-runner**: small HTTP service the ops UI calls; runs `scripts/scenarios/*.sh` and streams output. Scenarios: `kill-db-leader`, `kill-kafka-broker`, `partition-node` (`docker network disconnect` of one node's etcd, Patroni and Kafka containers, plus `heal`; replaces `partition-2-1`), `openmrs-offline`, `duplicate-webhooks` (two duplicates plus one forged), `deploy-green`, `tamper-audit`, `model-kill-switch`. A direct-connection write probe shows the isolated node refusing writes.

**Optional (step 10)**: Helm chart for core and ussd-gateway; `make k8s-lite` starts k3d connected to the compose data tier; rolling update under k6.

## Frontend apps (static builds, no backend of their own)
Common rules: React + TypeScript + Tailwind; typed client generated from `api/*.yaml`; MSW mock mode; login: the patient app uses the core's BFF login (redirect to the core's `/login`, session cookie, no tokens in the browser); clinician and ops UIs may use `oidc-client-ts` (PKCE) against Keycloak; runtime config from `/config.js`; static build served by nginx; synthetic data only.

## Seed data
- `patients.json`: 200 synthetic patients with Kinyarwanda, French and English name variants, including Uwase (`patient1`, synthetic national ID) with her dependent Keza (age band 5-11, no national ID, synthetic UPI in the `YYMMDD-FOSAID-RAND4` format) as `RelatedPerson` link, and Eric (`patient2`, synthetic national ID, Mutuelle coverage in the coverage mock). National IDs and UPIs are synthetic: use obviously fake values (for example a fixed test prefix and a fictional facility code) so none can belong to a real person.
- `duplicates.json`: 20 pairs across "IremboAccount" and "OpenMRS", including the worked example (Uwimana Marie vs Marie Uwimana, birth date day/month swapped), twins, a hard-block pair with different national IDs, and a pair sharing the same UPI that auto-links.
- `practitioners.json`: 10 clinicians.

## Schedule (rebased Friday 16 Oct; submit Monday evening)
- Fri: steps 1 and 2 (skeleton, OIDC BFF, consent, saga, mock payment); USSD simulator and clinician screen.
- Sat: steps 3 and 4 (Patroni/Kafka failover and partition, triage, anonymizer, review, kill switch).
- Sun: steps 7 and 8, then 5 and 6 if time allows (audit chain, USSD, blue/green, cockpit; OpenMRS sync, identity).
- Mon: step 9 (rehearse five times, record, put measured numbers into the document, submit).
- Tue: buffer only.

## Acceptance tests (each maps to a demo scene in `docs/DEMO_STORY.md`)
1. Journey (Scenes 1, 2): patient1 books for dependent Keza on USSD with guardian consent, pays, case appears AI_PROPOSED; clinician overrides with a reason; Encounter (subject Keza) exists in OpenMRS exactly once.
2. Failover (4a): kill the Patroni leader during 50 payments per minute; recovery under 60 s; zero lost and zero duplicated payments.
3. Split-brain (4b): isolate the leader's node with `docker network disconnect`; it demotes itself and refuses writes; the majority elects a new leader; after heal it rejoins via pg_rewind and row counts match on all nodes.
4. OpenMRS offline 2 minutes: events queue; replay creates no duplicates.
5. Release under load: green deployed and shifted 1/10/50/100 with zero failed requests.
6. Loop test: edit a record in OpenMRS; it arrives once and is not echoed back.
7. Identity: the worked example lands in the review queue; link, then unlink; audit shows both.
8. Audit: a read appears in "who accessed my record"; tampering one row turns the chain check red.
9. PII: payload containing a national ID and phone shows masked output and a redaction report.
10. USSD: simulator shows rules advice inside the session; model result arrives later in the clinician queue.
11. Web OIDC (1b): patient2 logs in through Keycloak with PKCE; no access or refresh token in browser storage; Consent exists before the Encounter; the coverage mock returns Mutuelle cover and the payment equals the co-payment; LOGIN and CONSENT audit events present.
12. Webhooks (1c): two duplicate webhooks are ignored, one forged webhook is rejected; one payment row, state PAID.
13. Kill switch (2): after `/models/kill-switch`, no model call is made, the banner shows, new cases are RULES_ONLY.
14. Broker loss (4a): one Kafka broker stopped; ISR 2; zero producer errors; zero lost events.
15. Access control and DBA audit (5b): a read without `X-Purpose-Of-Use` or without consent returns 403; a direct `psql` SELECT appears in the audit explorer via pgAudit.
