# Design decisions

The short form of the trade-off analysis. Section 7 of the design document gives the alternatives and costs of each.

| ID | Decision | Main reason |
| --- | --- | --- |
| 001 | Modular monolith core plus five separate services: edge, USSD gateway, AI zone, OpenFn sync, data tier | Lowest on-premise operating cost; module seams enforced in the build for later extraction |
| 002 | Saga with compensations for booking and payment, not two-phase commit | IremboPay is external and cannot join a database transaction |
| 003 | Transactional outbox with a relay to Kafka | The state change and its event commit together |
| 004 | Kafka in KRaft mode: 3 brokers, replication factor 3, min ISR 2, acks=all | Replay for audit and sync; explicit behaviour under partition |
| 005 | The saga is orchestrated inside the core | One place knows where every case is |
| 006 | PostgreSQL with Patroni, 3 etcd members and HAProxy | One writer; consensus prevents split-brain |
| 007 | Synchronous standby in non-strict mode | Committed data on two nodes; still writable if one replica is lost |
| 008 | Relational model inside, FHIR R4 facade at the boundary | Fast operational queries; FHIR changes stay in one module |
| 009 | OpenFn workflows as the sync gateway, kept off the real-time USSD path | Ready adaptors, run history and replay |
| 010 | Outbound EMR writes from a per-EMR queue in the core that OpenFn pulls (started at once by a webhook) | An EMR outage means writes wait in order, not failed runs |
| 011 | EMR chosen by the patient's home facility: eBuzima (one central instance, a tenant per health centre) or the hospital's OpenMRS | Matches how Rwanda deploys EMRs; one inbound FHIR contract in the core |
| 012 | Probabilistic matching with deterministic anchors; link, never merge | A wrong merge is a patient-safety event; links can be undone |
| 013 | HAProxy and Keepalived blue/green with weighted steps and SLO gates | No cloud load balancer needed; rollback in seconds |
| 014 | Containers everywhere; stateful tier on VMs, Kubernetes later for the stateless tier only | A small operations team |
| 015 | Rules in the session, the model asynchronous and escalate-only | USSD latency and a safety floor for emergencies |
| 016 | Tokenisation and data minimisation before the model | Simple and testable; re-identification only in the core |
| 017 | Hash-chained audit with a write-once archive | Tamper evidence with tools the team already runs |
| 018 | In this reference setup: Prometheus and Grafana, metrics only, dashboards and alerts as code | Fits one machine; logs and traces stay in the production design |
