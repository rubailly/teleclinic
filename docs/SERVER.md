# Running the stack on a shared server

The same Docker Compose stack as on the demo machine, on one Linux server reached by its address. Measured on a
6 vCPU, 11 GB VPS (Ubuntu 24.04, Docker 29) with 8 GB of swap: first `make up-lite` 4.5 min, first `make emr-up`
17 min (it builds the eBuzima image), `make reset` 2 min; every scene and the 74 core tests pass.

## Install (once, as root)

```bash
# 1. Docker, make, Python with YAML, and swap as a safety net for builds
curl -fsSL https://get.docker.com | sh
apt-get install -y make python3-yaml python3-psycopg2 jq
fallocate -l 8G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo "/swapfile none swap sw 0 0" >> /etc/fstab

# 2. The code (a clone, or a git bundle copied from a machine that has it)
git clone <repository> /opt/teleclinic && cd /opt/teleclinic

# 3. .env for this server: public URLs at its address, every password and secret random
python3 scripts/deploy/server-env.py <server address>

# 4. The EMRs first, so the core starts with the sync key they generate, then the stack
make emr-up && make reset
```

`make reset` restarts the stack with every value in `.env`; run it again after any change there.

## Addresses

| What | URL |
| --- | --- |
| Patient web app (patient1, patient2) | `http://<server>:9080/app/` |
| Clinician queue (clinician1), clerk (clerk1), audit explorer (admin1) | `http://<server>:9080/clinician/`, `/clerk/`, `/audit/` |
| USSD phone simulator | `http://<server>:9380` |
| IremboPay mock (checkout, webhook log) | `http://<server>:9280/admin/deliveries` |
| Keycloak, the IremboAccount stand-in | `http://<server>:9180` |
| eBuzima (Administrator; nurse.kimironko@ebuzima.local) | `http://<server>:9580` |
| OpenMRS O3, Kibagabaga District Hospital (admin) | `http://<server>:9880/openmrs/spa` |
| OpenFn Lightning (admin@teleclinic.local) | `http://<server>:9480` |
| Grafana (read-only without login) | `http://<server>:9700` |
| Ops cockpit and runbooks (user `ops`) | `http://<server>:9500` |
| HAProxy and database proxy stats | `http://<server>:9404/stats`, `http://<server>:9405/stats` |

Passwords are in `/opt/teleclinic/.env` on the server, never in the repository: `DEMO_USER_PASSWORD` (all five demo
users), `COCKPIT_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `EBUZIMA_ADMIN_PASSWORD`,
`EBUZIMA_NURSE_PASSWORD`, `OPENMRS_ADMIN_PASSWORD`, `OPENFN_ADMIN_PASSWORD`. Read one with
`grep ^DEMO_USER_PASSWORD= /opt/teleclinic/.env`.

## What is and is not public

Public: the ports above. The ops cockpit can run the failure scenarios and holds the Docker socket, so on a server it
asks for a password (`COCKPIT_BIND=0.0.0.0` with `COCKPIT_PASSWORD`). PostgreSQL, Kafka, Valkey, Prometheus
(`127.0.0.1:9790`), the database proxy (`127.0.0.1:9432`) and HAProxy's runtime API (`127.0.0.1:9999`) stay on the
server. Scenes and `make doctor` run on the server itself.

Plain HTTP on the server's address, as on the demo machine: fine for a demo with synthetic data. A longer-lived
deployment puts TLS in front (a domain with Caddy, or a Cloudflare Tunnel), sets `SESSION_COOKIE_SECURE=true` and
replaces SSH password login with keys.

Known limit: OpenMRS's home page (Service queues) shows an error in the distribution without demo data, here as on the
demo machine; the scenes use the patient chart, which works. `make capacity` is for a larger machine: its generators
run on the same server as the stack.
