#!/usr/bin/env python3
"""Writes .env for the stack on a shared server, reached by a public address instead of localhost.

  server-env.py <address>                     plain HTTP on the server's ports, e.g. 203.0.113.10
  server-env.py --domain <domain>             HTTPS on subdomains (app., login., pay., ...) through the TLS proxy
                                              (compose/proxy.yml); the stack's ports listen on 127.0.0.1 only
  server-env.py --update [--domain] <...>     only rewrite the public settings of an existing .env, keep its secrets

Starts from .env.example, points every browser-facing URL at the server, puts a password on the ops cockpit, and
replaces every demo password and shared secret with a random value (the ones in .env.example are public). Prints
names, never values: read them in .env on the server.
"""
import os
import re
import secrets
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
ENV = os.path.join(ROOT, ".env")

args = sys.argv[1:]
update = "--update" in args
domain = "--domain" in args
rest = [a for a in args if not a.startswith("--")]
if len(rest) != 1 or not re.fullmatch(r"[A-Za-z0-9.-]+", rest[0]):
    sys.exit(__doc__)
host = rest[0]
if os.path.exists(ENV) and not update:
    sys.exit(f"{ENV} exists; use --update to change only its public settings")
if update and not os.path.exists(ENV):
    sys.exit(f"{ENV} does not exist; run without --update first")

word = lambda n=8: secrets.token_hex(n)
SECRETS = {
    "KEYCLOAK_ADMIN_PASSWORD": lambda: "kc-" + word(),
    "OIDC_CLIENT_SECRET": lambda: "oidc-" + word(12),
    "DEMO_USER_PASSWORD": lambda: "demo-" + word(5),
    "DB_PASSWORD": lambda: "db-" + word(),
    "PG_SUPERUSER_PASSWORD": lambda: "pgsu-" + word(),
    "PG_REPLICATION_PASSWORD": lambda: "pgrepl-" + word(),
    "IREMBOPAY_WEBHOOK_SECRET": lambda: "whsec-" + word(12),
    "VAULT_KEY": lambda: "vault-" + word(12),
    "AI_CALLBACK_SECRET": lambda: "aicb-" + word(12),
    "USSD_GATEWAY_KEY": lambda: "ussd-" + word(12),
    "GRAFANA_ADMIN_PASSWORD": lambda: "gf-" + word(),
    # OpenMRS asks for upper and lower case and a digit; set when its database is first created
    "OPENMRS_ADMIN_PASSWORD": lambda: "Admin" + word(5) + "9",
    "COCKPIT_PASSWORD": lambda: "ops-" + word(),
}
if domain:
    PUBLIC = {
        "PUBLIC_BASE_URL": f"https://app.{host}",
        "KEYCLOAK_PUBLIC_URL": f"https://login.{host}",
        "IREMBOPAY_PUBLIC_URL": f"https://pay.{host}",
        "GRAFANA_PUBLIC_URL": f"https://grafana.{host}",
        "COCKPIT_PUBLIC_URL": f"https://ops.{host}",
        "OPENFN_SCHEME": "https", "OPENFN_HOST": f"openfn.{host}", "OPENFN_PORT": "443",
        "SESSION_COOKIE_SECURE": "true",
        # Only the TLS proxy reaches the stack, so its forwarded headers can be trusted
        "PUBLISH_IP": "127.0.0.1", "TRUST_PROXY_HEADERS": "1", "COCKPIT_BIND": "127.0.0.1",
        "DOMAIN": host,
    }
else:
    PUBLIC = {
        "PUBLIC_BASE_URL": f"http://{host}:9080",
        "KEYCLOAK_PUBLIC_URL": f"http://{host}:9180",
        "IREMBOPAY_PUBLIC_URL": f"http://{host}:9280",
        "GRAFANA_PUBLIC_URL": f"http://{host}:9700",
        "COCKPIT_PUBLIC_URL": f"http://{host}:9500",
        "OPENFN_SCHEME": "http", "OPENFN_HOST": host, "OPENFN_PORT": "9480",
        "SESSION_COOKIE_SECURE": "false",
        "PUBLISH_IP": "0.0.0.0", "TRUST_PROXY_HEADERS": "0", "COCKPIT_BIND": "0.0.0.0",
    }

if update:
    lines, seen = [], set()
    for line in open(ENV):
        key = line.split("=", 1)[0] if "=" in line and not line.startswith("#") else None
        if key in PUBLIC:
            line = f"{key}={PUBLIC[key]}\n"
            seen.add(key)
        if key == "PUBLIC_HOST" or (key == "DOMAIN" and not domain):
            continue   # older or other-mode settings
        lines.append(line)
    added = [k for k in PUBLIC if k not in seen]
    if added:
        lines.append("\n# Shared server (scripts/deploy/server-env.py)\n")
        lines.extend(f"{k}={PUBLIC[k]}\n" for k in added)
    with open(ENV, "w") as f:
        f.writelines(lines)
    os.chmod(ENV, 0o600)
    where = f"https://*.{host}" if domain else f"http://{host}"
    sys.exit(print(f".env public settings updated for {where}: " + ", ".join(PUBLIC) + "; secrets kept.") or 0)

lines, seen = [], set()
for line in open(os.path.join(ROOT, ".env.example")):
    key = line.split("=", 1)[0] if "=" in line and not line.startswith("#") else None
    if key in PUBLIC:
        line = f"{key}={PUBLIC[key]}\n"
    elif key in SECRETS:
        line = f"{key}={SECRETS[key]()}\n"
    elif key and line.rstrip("\n").endswith("=<generated>"):
        continue   # the EMR secrets: make emr-up generates them (scripts/emr/env.py)
    if key:
        seen.add(key)
    lines.append(line)
lines.append("\n# Shared server (scripts/deploy/server-env.py)\n")
for key, value in PUBLIC.items():
    if key not in seen:
        lines.append(f"{key}={value}\n")
for key in ("OPENMRS_ADMIN_PASSWORD", "COCKPIT_PASSWORD"):
    if key not in seen:
        lines.append(f"{key}={SECRETS[key]()}\n")
fd = os.open(ENV, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w") as f:
    f.writelines(lines)
print(f".env written for {'https://*.' + host if domain else 'http://' + host}: public URLs set; random values for "
      + ", ".join(SECRETS) + ". The EMR secrets come with make emr-up.")
