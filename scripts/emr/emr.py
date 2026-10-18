"""Shared helpers for the EMR scripts: .env, HTTP with basic auth, OpenMRS REST, Lightning, deterministic ids."""
import base64
import json
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
OPENMRS = "http://localhost:9880/openmrs"
LIGHTNING = "http://localhost:9480"
# The same namespace as config/openmrs/teleclinic-metadata.json; Lightning ids come from it too
NAMESPACE = uuid.UUID("6f2b1f4e-3c1d-4b8e-9a52-7e0c4d1a9b30")
META = json.load(open(os.path.join(ROOT, "config", "openmrs", "teleclinic-metadata.json")))


def env():
    values = {}
    for line in open(os.path.join(ROOT, ".env")):
        if "=" in line and not line.startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            values[k] = v
    return values


def save_env(name, value):
    path = os.path.join(ROOT, ".env")
    lines = [l for l in open(path).read().splitlines() if not l.startswith(name + "=")]
    lines.append(f"{name}={value}")
    open(path, "w").write("\n".join(lines) + "\n")


def lightning_id(name):
    return str(uuid.uuid5(NAMESPACE, "lightning:" + name))


def request(method, url, body=None, headers=None, auth=None, ok=(200, 201, 204), timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json", "Accept": "application/json", **(headers or {})}
    if auth:
        h["Authorization"] = "Basic " + base64.b64encode(f"{auth[0]}:{auth[1]}".encode()).decode()
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        raw = e.read()
        status = e.code
    if status not in ok:
        raise RuntimeError(f"{method} {url} -> {status}: {raw[:300].decode(errors='replace')}")
    return status, (json.loads(raw) if raw and raw.strip()[:1] in (b"{", b"[") else None)


def openmrs(method, path, body=None, ok=(200, 201, 204)):
    e = env()
    return request(method, f"{OPENMRS}/ws/rest/v1/{path}", body, auth=("admin", e["OPENMRS_ADMIN_PASSWORD"]), ok=ok)[1]


def wait(what, check, timeout=1800):
    start = time.time()
    while time.time() - start < timeout:
        try:
            if check():
                return
        except Exception:
            pass
        time.sleep(3)
    raise RuntimeError(f"{what} not ready after {timeout} s")


def lightning_rpc(code):
    r = subprocess.run(["docker", "exec", "teleclinic-emr-openfn-1", "/app/bin/lightning", "rpc", code],
                       capture_output=True, text=True, timeout=120)
    return r.stdout + r.stderr
