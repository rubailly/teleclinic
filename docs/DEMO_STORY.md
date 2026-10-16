# TeleClinic demo story

This is the narrative of the demo video. The platform is built so that every scene below can be performed live, on camera, without editing tricks. Read `docs/build-plan.md` alongside it. Where this document and the code disagree, fix the code or flag the gap, and never fake a scene. On priorities and cut order, this document is the reference (section 7).

## 1. Purpose and audience
The audience is the Irembo evaluators. They want to see four things: architectural depth (edge cases), domain expertise (FHIR, health-data security), pragmatism (on-premise, developing-market) and safety (AI triage). The goal of the video is one reaction: "he actually built the platform".
Tone: calm, specific, honest. Say what is real and what is a labelled stand-in. Never claim something that has not been run.

## 2. The story in one line
A parent triages her sick child from a basic phone, another citizen books from the web through IremboAccount, both pay, a clinician reviews, and the record lands in the facility EMR. Then we break the platform five ways, and nothing is lost, duplicated or hidden.

## 3. World and cast (all synthetic)
- **Uwase**, a parent on a feature phone, number +250 788 000 001 (fake), Keycloak user `patient1`, with a synthetic national ID. She books for her daughter **Keza** (age band 5-11, fever for 2 days, fast breathing). Keza has no national ID; she is identified by a synthetic UPI in the national format (`YYMMDD-FOSAID-RAND4`) and the guardian link. Keza is the FHIR `Patient`; Uwase is a `RelatedPerson` (relationship: mother) linked to Uwase's IremboAccount subject; the `Consent` is given by Uwase as guardian.
- **Eric**, an adult on a smartphone browser, Keycloak user `patient2`, with a synthetic national ID and Mutuelle (CBHI) coverage. He shows the web journey through IremboAccount (OIDC) and the insurance co-payment.
- **Dr Mukamana**, clinician (`clinician1` in Keycloak).
- **Clerk Habimana**, identity reviewer (`clerk1`).
- **Admin** (`admin1`) runs the ops cockpit.
- Facility: one OpenMRS O3 instance with seeded data, standing for an EMR that speaks FHIR. In Rwanda the first EMR integration is eBuzima, the national EMR (design document 4.5.2); the demo shows the FHIR path and says so. District names are synthetic.
- Price of a consultation: a fixed illustrative amount. Insured patients pay only an illustrative co-payment after a mocked coverage check. Rules and thresholds are illustrative; real clinical content comes from clinical leadership.

## 4. What is real and what is a stand-in (say this on slide one)
Real: the core, the saga and outbox, PostgreSQL with Patroni, Kafka, HAProxy blue/green, the anonymizer, the triage state machine, OpenFn sync, OpenMRS, the audit hash chain, the observability stack.
Stand-ins, labelled in the UI and README: Keycloak for IremboAccount, a small mock for IremboPay, a small mock for the national insurance coverage check (CBHI/Mutuelle, RSSB), the USSD simulator for a telecom aggregator, OpenMRS for a FHIR-speaking facility EMR, a small model for the AI triage model, synthetic data for everything (including national IDs and UPIs).
Documented only (do not demo): Keepalived VRRP failover, the Patroni hardware watchdog (not available inside Docker), the `full` profile, the DR site, the eBuzima adaptor (Frappe REST API and webhooks), the national health information exchange (OpenHIM, client registry, shared health record, NIDA checks) and the voice/IVR channel. Kubernetes is documented only unless optional Scene 6b is built.
Say on slide one: "In Rwanda, TeleClinic's first EMR integration would be eBuzima, the national EMR. Here you see the same sync mechanisms on a FHIR EMR, OpenMRS. The eBuzima adaptor is designed, not yet built."

## 5. Scenes
Each scene has: what the narrator says, what happens on screen, what the audience should conclude, and what must exist in the platform. Total running time is about 12 to 13 minutes (14 with Scene 6b).

### Scene 0, 0:00 to 0:45. The problem and the decision log
- **Say:** "TeleClinic depends on four things that fail on their own: identity, payments, the facility record and an AI model. The design rule is: assume every integration fails, and never let a machine decide alone."
- **On screen:** `make doctor` shows every component healthy. A diagram of the six deployables. Then a prepared branch where one module imports another module's repository; the build fails on the module-boundary check.
- **Conclusion:** modular monolith with enforced seams, not a distributed monolith.
- **Needs:** `make doctor`; Spring Modulith verification in CI; a demo branch `demo/boundary-violation`.

### Scene 1, 0:45 to 2:00. Golden path from a feature phone, on behalf of a child
- **Say:** "A mother dials from a basic phone for her daughter. There is no browser and no internet."
- **On screen:** USSD simulator: dial the code, enter PIN, choose "For: 2. My child Keza", confirm guardian consent, answer the questions (fever, fast breathing, 2 days). The screen shows the rules advice in the same session and the step latency in milliseconds. Choose pay; the simulator shows the mobile-money prompt; approve. END message and an SMS confirmation.
- **Conclusion:** the rules answer inside the USSD budget; the model is not in this path; a guardian can act for a dependent with recorded consent.
- **Needs:** ussd-gateway with a "who is this for" step; dependents in the seed (`RelatedPerson` link); guardian `Consent`; triage `/rules/evaluate` under 100 ms; irembopay-mock with signed webhook; saga to PAID; step latency exposed to the simulator.
- **Acceptance:** rules advice shown at p95 under 100 ms; consultation moves PENDING_PAYMENT to PAID once; the Encounter subject is Keza (identified by her UPI), the Consent performer is Uwase.

### Scene 1b, 2:00 to 2:45. The web journey through IremboAccount (OIDC)
- **Say:** "On the web, identity comes from IremboAccount over OpenID Connect. Tokens never reach the browser."
- **On screen:** patient app as Eric: "Book a consultation" redirects to the Keycloak login (labelled "stand-in for IremboAccount"); show the authorize URL with `code_challenge` and `state`. After login, open browser dev tools: only an HttpOnly, Secure session cookie, no token in storage. Consent screen (purpose, data shared, withdraw option); accept. Short intake. The coverage check (labelled mock) finds Eric's Mutuelle cover, so the checkout asks only for the co-payment. Redirect to the mock IremboPay checkout, approve, return to "Paid, you are in the queue".
- **Conclusion:** authorization code with PKCE, backend for frontend, consent captured before any clinical data moves, insured patients charged only their share, unauthenticated to paid in one flow.
- **Needs:** core as confidential OIDC client (BFF); Keycloak realm with `patient2`; consent module and screen; insurance coverage mock and co-payment amount; patient app intake and payment redirect and return.
- **Acceptance:** no access or refresh token visible in the browser; a `Consent` resource exists before the Encounter; the payment amount equals the co-payment; LOGIN and CONSENT audit events present.

### Scene 1c, 2:45 to 3:05. Payments that cannot be double-counted
- **Say:** "Webhooks arrive twice, late, or forged. None of that may change the money."
- **On screen:** cockpit button `duplicate-webhooks` replays the confirmed webhook for `c-demo-001` twice and sends one with a wrong signature. The log shows: duplicate acknowledged and ignored (event id already seen), forged rejected (HMAC check fails), server-side status query made. The payment table still shows one row, state PAID.
- **Conclusion:** the webhook is a hint, not proof; idempotency by event id; no double charge, no forged unlock.
- **Needs:** irembopay-mock duplicate and forged modes; webhook HMAC and timestamp check; event id dedupe table; status query before PAID.

### Scene 2, 3:05 to 4:35. The clinician, the human gate and the kill switch
- **Say:** "The model's answer arrives later and goes to a clinician. It cannot act on its own, and we can switch it off in one call."
- **On screen:** clinician screen: Keza's case appears with an AI-PROPOSED badge, rules result and model result side by side, contributing factors. Try to open orders while it is proposed: blocked. Override urgency to EMERGENCY with the reason "cyanosis reported on the call". State becomes FINAL. Then open Eric's case from Scene 1b and confirm it in one click (this read is the one Eric sees in Scene 5b). Open the trace in Grafana for this correlation id. Then the admin calls the model kill switch: the "rules-only mode" banner appears, and a new case submitted from the simulator enters the queue as RULES_ONLY.
- **Conclusion:** escalate-only merge, enforced gate, full traceability, and the platform fails safe without the model.
- **Needs:** triage orchestration, anonymizer, model service, review API and state machine, policy that blocks orders until FINAL, correlation id in traces, `/models/kill-switch` and the banner.
- **Acceptance:** orders endpoint returns 403 until FINAL; override recorded as Provenance and AuditEvent; after the kill switch no model call is made and new cases are RULES_ONLY.

### Scene 3a, 4:35 to 5:05. FHIR you can check
- **Say:** "Everything that leaves the core is a validated FHIR R4 resource."
- **On screen:** submit a profile-valid transaction Bundle (Patient, Encounter, Observation with LOINC): accepted. Submit one with a missing identifier: rejected with an OperationOutcome naming the profile rule.
- **Needs:** FHIR facade, profile package and validator, conditional create.

### Scene 3b, 5:05 to 5:50. Sync without loops
- **Say:** "Two systems write the same record. The danger is an echo that bounces forever."
- **On screen:** Keza's encounter from scene 2 is in OpenMRS exactly once. Edit the contact phone on Keza's record inside OpenMRS (contact details are EMR-authoritative in the design). Show it arriving in TeleClinic once, with the origin tag and ledger hash, and not bouncing back. Optional (if built): edit the same TeleClinic-authored encounter on both sides; the `If-Match` update fails and the change lands in the conflict queue instead of overwriting.
- **Needs:** OpenFn workflows (outbound, inbound polling), origin tag, sync ledger, echo drop; optionally the conflict queue.
- **Acceptance:** one inbound event, zero outbound echo; ledger shows the same hash.

### Scene 3c, 5:50 to 6:45. Identity, safely
- **Say:** "A wrong merge puts one person's history in another's chart, so we link, never merge."
- **On screen:** the pair "Uwimana Marie" and "Marie Uwimana" (birth date with day and month swapped, no national ID in the EMR) lands in the review queue with side-by-side evidence. Clerk links. The national ID is offered back to OpenMRS through the sync gateway. Then unlink. The audit shows both decisions. Show a hard-block pair with different national IDs that is never offered. Then a pair that shares the same UPI is auto-linked, because an agreeing national anchor counts as a strong identifier.
- **Say (once):** "In production the national ID is verified against NIDA and the national client registry is queried first; here those registries are seeded data."
- **Needs:** matching with blocking and scoring bands, review queue, Patient.link create and delete, identifier write-back workflow, `duplicates.json` seed.

### Scene 4a, 6:45 to 8:00. Kill the database leader, then a Kafka broker
- **Say:** "Fifty payments a minute are running. I am going to kill the leader, and then a message broker."
- **On screen:** k6 load running; ops cockpit shows the Patroni leader. Click kill-db-leader. A new leader appears; a failover timer runs; error spike is short. Then click kill-kafka-broker: the cockpit shows ISR dropping from 3 to 2 and producers continuing without errors. Afterwards a reconciliation panel shows zero lost and zero duplicated payments, and zero lost events.
- **Needs:** Patroni 3 nodes with etcd, HAProxy health checks, idempotent retries, `kill-db-leader` and `kill-kafka-broker` scenarios, Kafka RF 3 with min ISR 2 and acks=all, reconciliation query.
- **Acceptance:** failover under 60 s; payments count equals confirmations; no producer errors with one broker down.

### Scene 4b, 8:00 to 8:50. Split-brain: the leader is cut off alone
- **Say:** "A network cut isolates the database leader. Only the side with a majority may accept writes, so the old leader must step down by itself."
- **On screen:** run `partition-node` on the node holding the current leader. It runs `docker network disconnect` on that node's etcd, Patroni and Kafka containers, which is a real partition, not a simulation. The isolated Patroni logs that it cannot renew the leader key and demotes PostgreSQL to read-only. A write sent directly to the isolated node is refused ("read-only transaction"). The majority elects a new leader and keeps accepting writes. Heal: reconnect; the old leader rejoins as a replica after `pg_rewind`; row counts match on all three nodes.
- **Conclusion:** quorum prevents split-brain; there are no divergent writes to merge.
- **Needs:** `scripts/scenarios/partition-node.sh` (disconnect and reconnect per node); Patroni with `failsafe_mode` off and `use_pg_rewind` on; a direct-connection write probe for the isolated node; row-count check.
- **Acceptance:** zero writes accepted on the minority side; one leader at every moment; identical row counts after heal.

### Scene 4c, 8:50 to 9:15. The facility record goes offline
- **Say:** "Clinicians keep working. Writes wait."
- **On screen:** stop OpenMRS for two minutes while two consultations complete. The sync queue grows on the cockpit. Start OpenMRS; the backlog replays; no duplicates.
- **Needs:** Kafka retention, OpenFn retry and replay, conditional create.

### Scene 4d, 9:15 to 9:45. Release under load
- **Say:** "No cloud load balancer here, so HAProxy does the shifting."
- **On screen:** deploy v1.5 as green; weights move 1, 10, 50, 100 percent; the load test shows zero failed requests; blue drains. Show the automatic rollback by deploying a deliberately bad build in a second run (recorded).
- **Needs:** core images v1.4 and v1.5, HAProxy runtime API script, SLO gates.

### Scene 5a, 9:45 to 10:10. Privacy before the model
- **Say:** "The model never sees who the patient is."
- **On screen:** send a payload containing a national ID, a phone number and a name in free text. The anonymizer returns masked output and a redaction report. Show the model service logs: no identifiers.
- **Needs:** anonymizer with allowlist, Presidio custom recognizers, synthetic PII tests, canary tokens.

### Scene 5b, 10:10 to 11:00. Audit and access control you can verify
- **Say:** "Every read and write is recorded, even by a database administrator. Access needs a declared purpose and consent, and I can prove the record was not changed."
- **On screen:**
  1. A clinical read without `X-Purpose-Of-Use` is refused with 403 and an OperationOutcome; a read for a patient without consent is refused the same way.
  2. Patient app as Eric: "Who accessed my record" shows Dr Mukamana's read of his case (Scene 2) with purpose of use TREAT.
  3. Admin runs a direct `psql` SELECT on the patient table; the audit explorer shows it, captured by pgAudit with the database role.
  4. Admin runs verify chain: green. Tamper one row; verify again: red, showing the first broken index.
  5. Optional: a BREAK_GLASS read with a reason is allowed and fires an alert.
- **Conclusion:** three audit layers with no blind spot for administrators, purpose-of-use and consent enforced, tamper evidence.
- **Needs:** audit interceptor, policy check on purpose and consent, pgAudit shipped to the audit stream, hash chain, `/audit/verify`, `/demo/tamper` (demo profile only).

### Scene 6, 11:00 to 11:40. Operations
- **Say:** "A small team can run this."
- **On screen:** SLO dashboard; trigger one alert (for example sync lag above threshold) and open its runbook link. Show the OpenAPI docs page and a green contract-test run against both specs.
- **Needs:** Grafana dashboards and alert rule, runbook markdown, Schemathesis or equivalent run from `make test-contract`.

### Scene 6b (optional), 11:40 to 12:10. The stateless tier on Kubernetes
Build only if every must-have in section 7 is done and rehearsed.
- **Say:** "The stateless tier is ready for Kubernetes when the team is. The data tier stays on VMs."
- **On screen:** `make k8s-lite` starts a k3d cluster with the core and the USSD gateway from a Helm chart, connected to the same data tier. A rolling update of the core with zero failed requests under k6.
- **Needs:** Helm chart for core and ussd-gateway, k3d config, readiness and liveness probes.
- **If not built:** say "documented only" in Scene 7 and point to ADR-013.

### Scene 7, last 30 seconds. Close
- **Say:** "Depth, domain expertise, pragmatism and safety: here is where each one was shown, and what is still documented only."
- **On screen:** one slide mapping scenes to criteria, plus the measured numbers (failover time, USSD latency, sync lag, resource use of the lite profile).

## 6. Coverage
### The brief
| Brief item | Scene |
| --- | --- |
| Task 1 container diagram and decision log | 0 |
| Task 1 unauthenticated to paid, IremboAccount (OIDC) | 1b |
| Task 1 IremboPay integration and failure handling | 1, 1b, 1c |
| Task 2 FHIR mapping and validation | 3a |
| Task 2 write-back without loops | 3b, 4c |
| Task 2 identity matching and safe merge | 3c |
| Task 3 HA for the database | 4a |
| Task 3 HA for the message broker | 4a, 4b |
| Task 3 split-brain in a 3-node cluster | 4b |
| Task 3 zero-downtime deployment | 4d (6b) |
| Task 3 audit of every read and write | 5b |
| Task 4 sync vs async inference, USSD latency | 1, 2 |
| Task 4 PII anonymization gateway | 5a |
| Task 4 human in the loop and fail-safe | 2 |
| Deliverable 3 OpenAPI | 6 |
| Deliverable 4 monitoring and deployment | 4d, 6 |

### The evaluation criteria
| Criterion | Scenes |
| --- | --- |
| Architectural depth (partitions, inconsistency) | 1c, 3b, 4a, 4b, 4c |
| Domain expertise (FHIR, health-data security) | 1 (guardian), 1b (consent), 3a, 3c, 5b |
| Pragmatism (on-premise, developing market) | 0, 1 (feature phone), 4c, 6, one-command start, lite profile numbers |
| Safety (AI triage, privacy) | 1, 2 (gate and kill switch), 5a |

### The job description
| Job description item | Where |
| --- | --- |
| Microservices landscape, API gateway | 0, 1b, 6 |
| IremboAccount and IremboPay | 1, 1b, 1c |
| Web/mobile and USSD channels | 1, 1b (voice: document only) |
| HA distributed system, containers on-premise | 4a to 4d, 6b if built |
| OpenMRS and FHIR read/write | 3a, 3b, 3c (eBuzima adaptor and national exchange: document only, stated on slide one) |
| Payments and reimbursements | 1, 1b (insurance co-payment), 1c |
| On-premise AI inference, MLOps | 2, 5a |
| Data protection and regulatory audit | 1b, 5b |

## 7. Priorities and cut order
This is the cut order for the build.

**Must-have, in build order:**
1. Golden path on USSD (Scene 1) and on the web through OIDC (Scene 1b).
2. Clinician gate and kill switch (Scene 2).
3. Database leader kill (Scene 4a, without the broker step if needed).
4. Split-brain by network disconnect (Scene 4b). It answers a question the brief asks word for word, and the disconnect approach is cheap once Patroni runs.
5. Audit tamper check (Scene 5b, steps 2 and 4).

**Cut in this order if time runs short:** Scene 6b, then the `full` profile, then the optional conflict in 3b, then the Kafka broker step in 4a, then Scene 4d's bad-build rollback, then Scene 3c, then Scene 4c. Never cut a must-have.

## 8. Highlights cut (about 3 minutes)
Evaluators may not watch 12 minutes. Put a short cut at the top of the shared folder, made from the same takes:
1. Feature phone triage with rules advice and latency (Scene 1), 40 s.
2. AI-PROPOSED, orders blocked, override, FINAL (Scene 2), 30 s.
3. Kill the database leader under load, zero lost payments (Scene 4a), 40 s.
4. Leader isolated, steps down, majority continues (Scene 4b), 40 s.
5. Tamper one audit row, chain turns red (Scene 5b), 30 s.

## 9. Fixed demo state
- `make reset` restores: seeded patients (including Keza as Uwase's dependent with a synthetic UPI, and Eric with Mutuelle coverage in the insurance mock), the duplicate pairs (including one pair sharing a UPI), empty queues, both core versions built, Keycloak users (`patient1`, `patient2`, `clinician1`, `clerk1`, `admin1`), model enabled, all containers connected, load profile ready. Under one minute.
- Fixed case id for the golden path: `c-demo-001`. Fixed correlation id prefix: `demo-`.
- URLs and ports go in `docs/URLS.md` once chosen; credentials are demo-only and live in `.env.example`.

## 10. Recording plan
Record scene by scene and edit. Rehearse each break at least five times before recording. Keep a clean take of every break scenario as a backup, and never run a break for the first time on camera. Use the same browser profile and window layout throughout.

## 11. Honesty phrases to use
"Real and running", "labelled stand-in", "designed, shown here", "designed, not yet built". Replace every proposed target in the document with the measured number from this video.
