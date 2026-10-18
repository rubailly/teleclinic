# Design decisions (short form)
Full reasoning is in section 5 of the design document. Do not reverse these without a written reason.

| ID | Decision | Key reason |
| --- | --- | --- |
| 001 | Modular monolith core + 5 separate services (edge, USSD gateway, triage/anonymizer, OpenFn sync, data tier) | Lowest on-premise operating cost; seams enforced for later extraction |
| 002 | Saga with compensations for booking and payment, not 2PC | IremboPay is external; 2PC blocks and does not scale |
| 003 | Transactional outbox + relay | State change and event commit together; consumers are idempotent |
| 004 | Kafka KRaft (3 brokers, RF 3, min ISR 2, acks=all) | Replay for audit and sync; explicit split-brain behaviour |
| 005 | Orchestrated saga inside the core | One place owns booking state; easier to audit |
| 006 | PostgreSQL with Patroni + 3 etcd + HAProxy | Single-writer consistency; consensus prevents split-brain |
| 007 | Synchronous standby, non-strict mode | Zero loss in normal operation; stays writable if one replica is lost |
| 008 | Relational canonical model + FHIR facade | Simple queries; FHIR contained at the boundary |
| 009 | OpenFn workflows as the sync gateway, outside the real-time USSD path | Adaptors, replay, run history |
| 010 | Inbound changes by polling `_lastUpdated` every 30 to 60 s | Works with every OpenMRS version |
| 011 | Probabilistic matching with deterministic anchors; link, never merge | Wrong merges are patient-safety events; links are reversible |
| 012 | HAProxy + Keepalived blue/green with weights | No cloud load balancer needed; fast rollback |
| 013 | Containers on VMs via Ansible first; Kubernetes for stateless later | Small ops team |
| 014 | Hybrid triage: rules synchronous, model asynchronous | USSD latency; safety floor |
| 015 | Tokenisation + data minimisation before the model | Simple, testable; re-identification only in the core |
| 016 | Hash-chained audit + write-once archive | Tamper evidence with tools the team already runs |
| 017 | Demo observability: Prometheus + Grafana, metrics only; alert rules and dashboards as code | Lighter than the single LGTM image on a 16 GB host; logs and traces stay documented for production |
| 018 | Outbound EMR writes: a queue in the core that the OpenFn workflow pulls (started at once by a webhook), not a Kafka trigger | A Kafka trigger consumes events at once even when the EMR is down, so 'writes wait' becomes failed runs; the pulled queue keeps order, backs off and dead-letters |
| 019 | Two kinds of EMR targets, chosen by the patient's home facility: eBuzima (one central multi-tenant instance; the health centre is the tenant) and each hospital's own OpenMRS. Every EMR is FHIR at the core boundary (the gateway maps eBuzima's documents); eBuzima pushes a change notice (document webhook) and the gateway pulls since its cursor | Matches how Rwanda deploys EMRs by level; one inbound contract in the core; push for latency, pull for completeness, schedule as safety net |

## Targets (proposed, replace with measured numbers)
Availability 99.95%; DB failover under 60 s with zero lost committed writes; USSD step p95 under 1.5 s; rules p95 under 100 ms; sync lag p95 under 5 s; DR RTO under 4 h and RPO under 5 min.

## Honest scope notes
Keepalived/VRRP does not work properly in Compose: show HAProxy weights and call VRRP documented-only. Keycloak, irembopay-mock and the USSD simulator are labelled stand-ins.
