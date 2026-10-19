# Addresses

The live demo runs each service on its own subdomain of `teleclinic-demo.dev`. Locally, the same services listen
on ports in the 9xxx range (to stay clear of ports that are often taken). Passwords are never in the repository:
locally they are in `.env`, created from `.env.example`.

| What | Live | Local | Sign in as |
| --- | --- | --- | --- |
| Patient app | https://app.teleclinic-demo.dev/app/ | http://localhost:9080/app/ | `patient1`, `patient2` |
| Who accessed my record | https://app.teleclinic-demo.dev/app/#/access | http://localhost:9080/app/#/access | `patient2` |
| Clinician queue | https://app.teleclinic-demo.dev/clinician/ | http://localhost:9080/clinician/ | `clinician1` |
| Identity review | https://app.teleclinic-demo.dev/clerk/ | http://localhost:9080/clerk/ | `clerk1` |
| Audit explorer | https://app.teleclinic-demo.dev/audit/ | http://localhost:9080/audit/ | `admin1` |
| USSD phone simulator | https://ussd.teleclinic-demo.dev | http://localhost:9380 | +250788000001 or +250788000002, PIN `1234` |
| IremboAccount stand-in (Keycloak) | https://login.teleclinic-demo.dev | http://localhost:9180 | realm `irembo-mock` |
| IremboPay stand-in, webhook log | https://pay.teleclinic-demo.dev | http://localhost:9280/admin/deliveries | |
| eBuzima stand-in | https://ebuzima.teleclinic-demo.dev | http://localhost:9580 | `Administrator`; nurse `nurse.kimironko@ebuzima.local` |
| OpenMRS O3 | https://openmrs.teleclinic-demo.dev | http://localhost:9880/openmrs/spa | `admin`, location Kibagabaga District Hospital |
| OpenFn Lightning | https://openfn.teleclinic-demo.dev | http://localhost:9480 | `admin@teleclinic.local` |
| Grafana (SLOs, alerts) | https://grafana.teleclinic-demo.dev | http://localhost:9700 | read-only without login |
| Ops cockpit and runbooks | https://ops.teleclinic-demo.dev | http://127.0.0.1:9500 | `ops` on the server; none locally |
| HAProxy and database proxy stats | https://stats.teleclinic-demo.dev/stats, `/db/stats` | http://localhost:9404/stats, http://localhost:9405/stats | |
| Release behind the edge | https://app.teleclinic-demo.dev/api/public/version | http://localhost:9080/api/public/version | |

APIs on the same edge: `/fhir/Patient/{id}` (needs `X-Purpose-Of-Use` and consent), `/fhir/AuditEvent?entity=Patient/{id}`
(auditors), and the USSD aggregator callback at `/ussd/callback` on the simulator's address.

Local only, never published: Prometheus (127.0.0.1:9790), the HAProxy runtime API (127.0.0.1:9999), PostgreSQL,
Kafka and Valkey.
