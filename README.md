# TeleClinic reference platform

A working implementation of the TeleClinic design (Irembo, Software Architect, Digital Health assignment). It runs
the whole design, from USSD booking to EMR sync, and its ops cockpit lets you break it on purpose and watch it
recover.
**All data is synthetic.** Where a national system could not be used, a stand-in is clearly labelled.

- **Design document:** [`docs/TeleClinic_Technical_Design_Document.docx`](docs/TeleClinic_Technical_Design_Document.docx)
- **Diagrams:** [`diagrams/`](diagrams/) (SVG and PNG, with their sources)
- **Live demo:** https://app.teleclinic-demo.dev, and the ops cockpit at https://ops.teleclinic-demo.dev

## Start with the ops cockpit

**https://ops.teleclinic-demo.dev** (user `ops`, password in the submission email)

The design document says the platform survives failures. The cockpit lets you cause them on the running system and
watch. Each button runs a real scenario: the left of the page shows the live state (writes through the database
proxy, requests through the edge, which node leads, whether payments reconcile), and the run panel on the right ticks
off the scenario's steps and ends with a pass or fail and the measured result. Failure scenarios put the system back
when they finish, so you can run them in any order. One runs at a time.

| Scenario | What it proves | Measured | Same check from a shell |
| --- | --- | --- | --- |
| Kill the database leader | Failover under load with zero data loss | New leader in 17 to 21 s, 0 acknowledged writes lost | `make scenario-kill-db-leader` |
| Cut the leader's node off the network | No split-brain: a minority stops writing | Never two writable leaders; same rows on every node after healing | `make scenario-partition` |
| Stop a Kafka broker | Events survive a broker loss | 50 of 50 messages written with acks=all | `make scenario-kill-kafka-broker` |
| The hospital record goes offline | EMR outages delay writes, never lose or duplicate them | Writes waited, then arrived once each, in order | `make scenario-emr-offline` |
| Sync without loops (eBuzima) | Two-way sync without echoes | One encounter in the right health centre; echo dropped; p95 3.8 s | `make scenario-sync` |
| Identity, safely | Link, never merge | Review, link, unlink, hard block and automatic link as designed | `make scenario-identity` |
| Replay and forge payment webhooks | Money cannot be double-counted or faked | One payment; the forged webhook refused | `make scenario-webhooks` |
| Purpose of use, consent and pgAudit | Every read is justified and recorded, even a DBA's | Refusals recorded; a psql query in the trail in about 1 s | `make audit` |
| Tamper with the audit trail | Tampering is detected and attributed | Edited entry found, role named, alert in about 10 s | `make scenario-tamper` |
| Release v1.5; rollback drill | Zero-downtime release without a cloud load balancer | 1,181 requests, 0 failed; a bad build rolled back automatically | `make release`, `make release-bad` |
| Kill switch: model off, back on | The platform runs safely without the AI | New cases rules-only at once; clinicians see a banner | `scripts/scenarios/model-kill-switch.sh` |
| Book from a basic phone | The USSD journey end to end | Slowest step 176 ms of a 1.5 s budget | `make ussd` |

Each alert in [Grafana](https://grafana.teleclinic-demo.dev) links to its runbook, also listed in the cockpit under
Runbooks. After killing the leader, "Database has no writable leader" fires within seconds and clears on its own.

## The rest of the platform

Every patient-facing user shares one demo password, sent with the submission.

| What to look at | Where | Sign in as |
| --- | --- | --- |
| Patient app: book, consent, pay, see who accessed your record | https://app.teleclinic-demo.dev/app/ | `patient2` (Eric, insured) or `patient1` (Uwase, books for her child) |
| A basic phone on USSD (simulator) | https://ussd.teleclinic-demo.dev | dial `*909*8#`, PIN `1234` |
| Clinician queue: AI-proposed cases, confirm or override | https://app.teleclinic-demo.dev/clinician/ | `clinician1` |
| Identity review: link or reject likely duplicates | https://app.teleclinic-demo.dev/clerk/ | `clerk1` |
| Audit explorer: hash chain, verify now | https://app.teleclinic-demo.dev/audit/ | `admin1` |
| eBuzima (health centres), stand-in on ERPNext + Healthcare | https://ebuzima.teleclinic-demo.dev | in the email |
| OpenMRS O3 (Kibagabaga District Hospital) | https://openmrs.teleclinic-demo.dev | in the email |
| OpenFn sync gateway: workflows and run history | https://openfn.teleclinic-demo.dev | in the email |
| IremboPay stand-in: every webhook and the core's answer | https://pay.teleclinic-demo.dev | none |
| Grafana: SLOs and alerts | https://grafana.teleclinic-demo.dev | none (read-only) |
| Load balancer and database proxy status | https://stats.teleclinic-demo.dev | none |

A short tour of the patient journey:

1. **Book from a basic phone.** In the USSD simulator, book for Uwase's daughter Keza. The safety advice appears in
   the same session; payment goes through a mobile-money prompt and ends with an SMS.
2. **Book on the web.** Sign in as `patient2`. The login is OpenID Connect with PKCE and no token ever reaches the
   browser. Eric's insurance is checked, so he only pays the co-payment.
3. **Review as a clinician.** The case arrives with an AI suggestion beside the rules result. Orders stay locked
   until you confirm or override (with a reason).
4. **See the record in the EMR.** Keza's teleconsultation appears in eBuzima under Kimironko Health Centre. Eric's
   appears in the hospital's OpenMRS. OpenFn shows the runs that carried them.
5. **Check the audit.** As `patient2`, open "Who accessed my record" and find the clinician's read. In the audit
   explorer, Verify now recomputes the hash chain.

## Run it yourself

Needs Docker with Compose v2, `make` and `curl`, and about 8 GB of free memory.

```bash
make up-lite    # the platform; creates .env from .env.example (first build takes a few minutes)
make emr-up     # once: eBuzima, OpenMRS and OpenFn (first start about 5 minutes)
make doctor     # every health check
```

The cockpit is then at http://127.0.0.1:9500, and every scenario in the table above also runs from the shell. Other
local addresses are in [`docs/URLS.md`](docs/URLS.md); to install on a server with a domain and HTTPS, see
[`docs/SERVER.md`](docs/SERVER.md). `make reset` brings everything back to the demo data in about a minute,
`make test` runs the core's unit and module-boundary tests, and `make capacity` runs the load test
(140 requests/s on one core instance with 0 failures; run `make reset` after it).

## What is real and what is a stand-in

Real: the core (Java 21, Spring Boot 3, Spring Modulith), HAProxy, APISIX, PostgreSQL 16 with Patroni and three
etcd members, Kafka in KRaft mode with three brokers, Valkey, the USSD gateway, Presidio-based anonymization, an
ONNX model service, OpenFn Lightning, OpenMRS O3, pgAudit with Vector, Prometheus and Grafana.

| Stand-in | For |
| --- | --- |
| Keycloak | IremboAccount |
| `irembopay-mock` | IremboPay and the national insurance coverage check |
| ERPNext 15 with the Frappe Healthcare app, one tenant per health centre | eBuzima (same software stack; not the Ministry's instance or its customisations) |
| Phone simulator | The telecom aggregator, a basic phone, mobile money and SMS |
| Triage model trained on synthetic data | A clinically validated model |

Not in this setup, although in the design: three separate fault domains (everything runs on one machine),
Keepalived, log and trace stores, and the write-once audit archive.

## Repository map

| Folder | Contents |
| --- | --- |
| `services/core` | The modular monolith: identity, consent, patient index, consultation, triage, payments, FHIR, audit, sync |
| `services/ussd-gateway`, `anonymizer`, `triage-model` | The separately deployed services |
| `services/ebuzima`, `irembopay-mock`, `scenario-runner` | The eBuzima stand-in, the IremboPay stand-in, the ops cockpit |
| `sync/` | OpenFn workflows: outbound and inbound for each EMR |
| `api/` | OpenAPI 3.1 contracts: Interoperability and Sync, AI Triage |
| `compose/`, `config/` | Docker Compose files and the configuration of every component |
| `scripts/` | Scenes, failure scenarios, release, load test, EMR seeding, server setup |
| `docs/` | Design document, URLs, server install, decisions, runbooks |
