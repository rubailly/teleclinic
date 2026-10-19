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

## With a domain: HTTPS on subdomains

Point the domain and a wildcard at the server (DNS `A` records for `@` and `*`), then on the server:

```bash
python3 scripts/deploy/server-env.py --update --domain <domain>   # public settings only; passwords kept
make reset && make emr-up && make proxy-up
```

Caddy (`compose/proxy.yml`, `config/caddy/Caddyfile`) listens on ports 80 and 443, gets a Let's Encrypt certificate
for each name and forwards it to the matching service. The stack's ports then listen on 127.0.0.1 only
(`PUBLISH_IP`), HAProxy keeps the proxy's forwarded scheme and client address (`TRUST_PROXY_HEADERS`), and the session
cookie is Secure. Live at https://app.teleclinic-demo.dev:

| What | URL |
| --- | --- |
| Patient web app; clinician, clerk and audit pages | `https://app.<domain>/app/`, `/clinician/`, `/clerk/`, `/audit/` |
| Keycloak, the IremboAccount stand-in | `https://login.<domain>` |
| IremboPay mock | `https://pay.<domain>/admin/deliveries` |
| USSD phone simulator | `https://ussd.<domain>` |
| eBuzima | `https://ebuzima.<domain>` |
| OpenMRS O3 | `https://openmrs.<domain>` |
| OpenFn Lightning | `https://openfn.<domain>` |
| Grafana | `https://grafana.<domain>` |
| Ops cockpit and runbooks (user `ops`) | `https://ops.<domain>` |
| HAProxy stats; database proxy stats | `https://stats.<domain>/stats`; `https://stats.<domain>/db/stats` |

The bare domain and `www.` redirect to `app.`; plain HTTP redirects to HTTPS. Let's Encrypt allows ten new accounts per
address in three hours: Caddy asks for all twelve certificates at once on its first start, so one or two can wait a
while and arrive on its own retries.

## Addresses without a domain

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

OpenMRS opens on Patient lists for every role (config/openmrs/frontend): its usual home page, Service queues, fails
in the distribution without demo data, which sets up no queues. `make capacity` is for a larger machine: its
generators run on the same server as the stack.
