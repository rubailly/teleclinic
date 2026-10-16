# TeleClinic reference platform

A runnable reference implementation of the TeleClinic Technical Design Document (Irembo, Software Architect, Digital Health).
It exists to show the design working, including when parts of it fail. **Synthetic data only.**

## Start

```bash
make up-lite     # build and start; waits until the edge serves the core
make doctor      # check every health endpoint
make smoke       # end-to-end login as patient1 through the edge
make journey     # web journey: consent, triage, co-payment, webhook, queue, Kafka, webhook attacks
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
| PostgreSQL 16 (one schema per module), Kafka (KRaft) | Real, single node for now; HA clusters come next |
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

## Demo users (synthetic)

`patient1` (Uwase), `patient2` (Eric), `clinician1` (Dr Mukamana), `clerk1` (Habimana), `admin1`. One demo password for all of them, set in `.env.example`.

## Ports

See `docs/URLS.md`.

## Measured (after step 2)

| Container | Memory in use | Limit |
| --- | --- | --- |
| keycloak | 552 MiB | 768 MiB |
| kafka-1 | 344 MiB | 768 MiB |
| core-blue | 215 MiB | 512 MiB |
| apisix | 161 MiB | 256 MiB |
| postgres | 42 MiB | 256 MiB |
| irembopay-mock | 38 MiB | 128 MiB |
| haproxy | 13 MiB | 128 MiB |

About 1.4 GB in total. `make reset` to a working edge: 35 s on a 12-core, 16 GB laptop.
