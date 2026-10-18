# URLs and ports (local demo)

| What | URL | Notes |
| --- | --- | --- |
| Public edge (HAProxy → APISIX → core) | http://localhost:9080 | Sign in at `/login`; `/api/me` shows the session identity |
| Patient web app | http://localhost:9080/app/ | `/` redirects here; sign in as `patient1` or `patient2` |
| Clinician queue | http://localhost:9080/clinician/ | Sign in as `clinician1` |
| Ops cockpit | http://127.0.0.1:9500 | Runs the failure scenarios; live probes, nodes, reconciliation. Local only |
| USSD phone simulator | http://localhost:9380 | Labelled simulator; demo numbers +250788000001 (Uwase) and +250788000002 (Eric), PIN `1234` |
| USSD aggregator callback | http://localhost:9380/ussd/callback | Form post: `sessionId`, `phoneNumber`, `serviceCode`, `text` |
| Release running behind the edge | http://localhost:9080/api/public/version | `{"version":"v1.4","color":"blue"}` |
| IremboAccount stand-in (Keycloak) | http://localhost:9180 | Realm `irembo-mock`; admin console at `/admin` |
| HAProxy stats | http://localhost:9404/stats | Blue/green pool weights and health |
| Postgres proxy stats (which node is leader) | http://localhost:9405/stats | `primary` shows the leader UP; `replicas` the standbys |
| IremboPay mock: hosted checkout | http://localhost:9280/checkout/{paymentId} | Labelled mock; scenario controls under `/admin` |
| IremboPay mock: webhook log | http://localhost:9280/admin/deliveries | Every webhook sent and what the core answered |

Internal only (not published on the host): HAProxy `core` pool on `:9100`, HAProxy runtime API on `:9999`, core on `:8080`, Postgres on `:5432`, Kafka on `:9092`.

Ports 80, 8080, 5432 and 6379 are often taken on developer machines, so the demo stays in the 9xxx range.
Credentials are demo-only and live in `.env.example`.
