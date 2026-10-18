# URLs and ports (local demo)

| What | URL | Notes |
| --- | --- | --- |
| Public edge (HAProxy → APISIX → core) | http://localhost:9080 | Sign in at `/login`; `/api/me` shows the session identity |
| Patient web app | http://localhost:9080/app/ | `/` redirects here; sign in as `patient1` or `patient2` |
| Who accessed my record | http://localhost:9080/app/#/access | Patient app; sign in as `patient2` (Eric) |
| Audit explorer | http://localhost:9080/audit/ | Sign in as `admin1`; Verify now recomputes the chain |
| FHIR Patient read | http://localhost:9080/fhir/Patient/{id} | Needs `X-Purpose-Of-Use`; consent for TREAT and PAYMENT |
| FHIR AuditEvent search | http://localhost:9080/fhir/AuditEvent?entity=Patient/{id} | Auditors (`admin1`) |
| Clinician queue | http://localhost:9080/clinician/ | Sign in as `clinician1` |
| Grafana SLO dashboard | http://localhost:9700 | Read-only without login; admin password in `.env` |
| Runbooks | http://127.0.0.1:9500/runbooks/ | One per alert; linked from each alert rule |
| Prometheus | http://127.0.0.1:9790 | Targets, raw queries. Local only |
| Identity review (clerk) | http://localhost:9080/clerk/ | Sign in as `clerk1` |
| eBuzima stand-in (health centres; ERPNext + Healthcare) | http://localhost:9580 | `Administrator`, password `EBUZIMA_ADMIN_PASSWORD` in `.env`; the Kimironko nurse is `nurse.kimironko@ebuzima.local`, password `EBUZIMA_NURSE_PASSWORD` |
| OpenMRS O3 (Kibagabaga District Hospital) | http://localhost:9880/openmrs/spa | `admin`, password `OPENMRS_ADMIN_PASSWORD` in `.env`; location Kibagabaga District Hospital |
| OpenFn Lightning (sync gateway) | http://localhost:9480 | `admin@teleclinic.local`, password `OPENFN_ADMIN_PASSWORD` in `.env`; run history |
| Ops cockpit | http://127.0.0.1:9500 | Runs the failure scenarios; live probes, nodes, reconciliation. Local only |
| USSD phone simulator | http://localhost:9380 | Labelled simulator; demo numbers +250788000001 (Uwase) and +250788000002 (Eric), PIN `1234` |
| USSD aggregator callback | http://localhost:9380/ussd/callback | Form post: `sessionId`, `phoneNumber`, `serviceCode`, `text` |
| Release running behind the edge | http://localhost:9080/api/public/version | `{"version":"v1.4","color":"blue"}` |
| IremboAccount stand-in (Keycloak) | http://localhost:9180 | Realm `irembo-mock`; admin console at `/admin` |
| HAProxy runtime API | 127.0.0.1:9999 (TCP) | Weights for the release script; local only |
| HAProxy stats | http://localhost:9404/stats | Blue/green pool weights and health |
| Postgres proxy stats (which node is leader) | http://localhost:9405/stats | `primary` shows the leader UP; `replicas` the standbys |
| IremboPay mock: hosted checkout | http://localhost:9280/checkout/{paymentId} | Labelled mock; scenario controls under `/admin` |
| IremboPay mock: webhook log | http://localhost:9280/admin/deliveries | Every webhook sent and what the core answered |

Internal only (not published on the host): HAProxy `core` pool on `:9100`, HAProxy runtime API on `:9999`, core on `:8080`, Postgres on `:5432`, Kafka on `:9092`.

Ports 80, 8080, 5432 and 6379 are often taken on developer machines, so the demo stays in the 9xxx range.
Credentials are demo-only and live in `.env.example`.
