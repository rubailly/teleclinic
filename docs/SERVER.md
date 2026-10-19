# Running the stack on a server

The live demo at https://app.teleclinic-demo.dev is this repository on one Linux server: 6 vCPU, 11 GB of memory
plus 8 GB of swap, Ubuntu 24.04, Docker 29. There the first `make up-lite` takes about 5 minutes, the first
`make emr-up` about 17 (it builds the eBuzima image), and `make reset` about 2. Every scene passes there.

## Install (once, as root)

```bash
# Docker, make, Python with YAML, and swap as a safety net for builds
curl -fsSL https://get.docker.com | sh
apt-get install -y make python3-yaml python3-psycopg2 jq
fallocate -l 8G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo "/swapfile none swap sw 0 0" >> /etc/fstab

git clone <repository> /opt/teleclinic && cd /opt/teleclinic

# .env for this server: public URLs and a random value for every password and secret
python3 scripts/deploy/server-env.py --domain <domain>

# The EMRs first, so the core starts with the sync key they create; then the stack and the HTTPS proxy
make emr-up && make reset && make proxy-up
```

Point the domain and a wildcard at the server first (DNS `A` records for `@` and `*`). Caddy
(`compose/proxy.yml`, `config/caddy/Caddyfile`) listens on ports 80 and 443, gets a Let's Encrypt certificate for each
subdomain and forwards it to its service; the subdomains are listed in [`URLS.md`](URLS.md). The bare domain
redirects to `app.`. The stack's own ports then listen on 127.0.0.1 only, so only 22, 80 and 443 are open.

Without a domain, `python3 scripts/deploy/server-env.py <server address>` sets everything up for plain HTTP on the
server's address and the local ports in [`URLS.md`](URLS.md); skip `make proxy-up`. That is fine for synthetic data,
but not for anything longer-lived.

`make reset` restarts the stack with the values in `.env`; run it after any change there.
`python3 scripts/deploy/server-env.py --update --domain <domain>` changes the public settings and keeps the passwords.

## Passwords

They exist only in `/opt/teleclinic/.env` on the server, never in the repository: `DEMO_USER_PASSWORD` (the five demo
users), `COCKPIT_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `EBUZIMA_ADMIN_PASSWORD`,
`EBUZIMA_NURSE_PASSWORD`, `OPENMRS_ADMIN_PASSWORD` and `OPENFN_ADMIN_PASSWORD`. Read one with
`grep ^DEMO_USER_PASSWORD= /opt/teleclinic/.env`.

The ops cockpit can run failure scenarios and holds the Docker socket, so on a server it always asks for a password.

## Known behaviour

- Let's Encrypt allows ten new accounts per address in three hours. On its first start Caddy asks for all the
  certificates at once, so one or two may arrive a little later on its own retries.
- `make capacity` runs its load generators on the same machine as the stack; use a larger server for it, and run
  `make reset` afterwards.
