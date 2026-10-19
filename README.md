# TeleClinic reference platform

A working implementation of the TeleClinic design (Irembo, Software Architect, Digital Health assignment). It runs
the whole design, from USSD booking to EMR sync, and lets you break it on purpose to watch how it recovers.
**All data is synthetic.** Where a national system could not be used, a stand-in is clearly labelled.

- **Design document:** [`docs/TeleClinic_Technical_Design_Document.docx`](docs/TeleClinic_Technical_Design_Document.docx)
- **Diagrams:** [`diagrams/`](diagrams/) (SVG and PNG, with their sources)
- **Live demo:** https://app.teleclinic-demo.dev

## Try it online

Sign-in details are in the submission email. Every patient-facing user shares one demo password.

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
| Ops cockpit: run a failure scenario and watch it live | https://ops.teleclinic-demo.dev | `ops` |
| Load balancer and database proxy status | https://stats.teleclinic-demo.dev | none |

### A 15-minute tour

1. **Book from a basic phone.** In the USSD simulator, book for Uwase's daughter Keza. The safety advice appears in
   the same session; payment goes through a mobile-money prompt and ends with an SMS.
2. **Book on the web.** Sign in as `patient2`. The login is OpenID Connect with PKCE and no token ever reaches the
   browser. Eric's insurance is checked, so he only pays the co-payment.
3. **Review as a clinician.** The case arrives with an AI suggestion beside the rules result. Orders stay locked
   until you confirm or override (with a reason).
4. **See the record in the EMR.** Keza's teleconsultation appears in eBuzima under Kimironko Health Centre. Eric's
   appears in the hospital's OpenMRS. OpenFn shows the runs that carried them.
5. **Break something.** In the ops cockpit, kill the database leader or cut its node off the network. Writes resume
   on a new leader in about 20 seconds and nothing acknowledged is lost. Grafana raises the alert, which links to
   its runbook.
6. **Check the audit.** As `patient2`, open "Who accessed my record" and find the clinician's read. In the audit
   explorer, Verify now recomputes the hash chain.

## Run it yourself

Needs Docker with Compose v2, `make` and `curl`, and about 8 GB of free memory.

```bash
make up-lite    # the platform; creates .env from .env.example (first build takes a few minutes)
make emr-up     # once: eBuzima, OpenMRS and OpenFn (first start about 5 minutes)
make doctor     # every health check
```

Local addresses are in [`docs/URLS.md`](docs/URLS.md). To install on a server with a domain and HTTPS, see
[`docs/SERVER.md`](docs/SERVER.md).

Each scene is a script that passes or fails and prints what it measured:

| Command | What it shows | Measured |
| --- | --- | --- |
| `make journey`, `make ussd` | Web and USSD booking, guardian consent, co-payment, SMS | Slowest USSD step 176 ms (budget 1.5 s) |
| `make scenario-webhooks` | A payment webhook replayed twice and forged once | One payment; the forged one refused |
| `make clinician` | Human gate, escalate-only merge, kill switch, no identifiers in the AI zone | Orders refused until review |
| `make scenario-kill-db-leader` | PostgreSQL leader killed during writes | Back in 17 to 21 s, 0 writes lost |
| `make scenario-partition` | The leader's node cut off the network | Never two writable leaders |
| `make scenario-kill-kafka-broker` | One of three brokers stopped | 50 of 50 messages written |
| `make scenario-sync` | TeleClinic and eBuzima write the same record | One encounter, echoes dropped, p95 3.8 s |
| `make scenario-identity` | Review, link, unlink, hard block, automatic link | National ID written back, then removed |
| `make scenario-emr-offline` | The hospital's OpenMRS down mid-shift | Writes waited, then arrived once each |
| `make audit`, `make scenario-tamper` | Purpose of use, consent, a hand-edited audit row | Edit found and attributed in about 10 s |
| `make release`, `make release-bad` | Blue/green release under load, then a bad build | 0 failed requests; bad build rolled back |
| `make capacity` | Web and USSD load, then a noisy client (run `make reset` after) | 140 requests/s on one core instance, 0 failed |

`make reset` brings everything back to the demo data in about a minute. `make test` runs the core's unit and
module-boundary tests.

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
