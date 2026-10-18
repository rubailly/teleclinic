#!/usr/bin/env python3
"""Writes .env for the stack on a shared server, reached by a public address instead of localhost.

  server-env.py <host>   e.g. 203.0.113.10 or demo.example.org

Starts from .env.example, points every browser-facing URL at the host, opens the ops cockpit on all addresses behind a
password, and replaces every demo password and shared secret with a random value (the ones in .env.example are public).
Refuses to overwrite an existing .env. Prints names, never values: read them in .env on the server.
"""
import os
import re
import secrets
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
ENV = os.path.join(ROOT, ".env")

if len(sys.argv) != 2 or not re.fullmatch(r"[A-Za-z0-9.-]+", sys.argv[1]):
    sys.exit(__doc__)
host = sys.argv[1]
if os.path.exists(ENV):
    sys.exit(f"{ENV} exists; move it away first if you mean to start over")

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
PUBLIC = {
    "PUBLIC_BASE_URL": f"http://{host}:9080",
    "KEYCLOAK_PUBLIC_URL": f"http://{host}:9180",
    "IREMBOPAY_PUBLIC_URL": f"http://{host}:9280",
    "PUBLIC_HOST": host,
    "GRAFANA_PUBLIC_URL": f"http://{host}:9700",
    "COCKPIT_PUBLIC_URL": f"http://{host}:9500",
    "COCKPIT_BIND": "0.0.0.0",
}

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
print(f".env written for http://{host}: public URLs set; random values for "
      + ", ".join(SECRETS) + ". The EMR secrets come with make emr-up.")
