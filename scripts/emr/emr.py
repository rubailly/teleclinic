"""Shared helpers for the EMR scripts: .env, HTTP with basic auth, OpenMRS REST, eBuzima (Frappe) REST, Lightning, ids."""
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
EBUZIMA = "http://localhost:9580"
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


class Frappe:
    """eBuzima's REST API (Frappe), signed in with a password (session cookie) or an API key (token)."""

    def __init__(self, user=None, password=None, token=None):
        self.headers = {"Authorization": f"token {token}"} if token else {}
        if user:
            jar = urllib.request.HTTPCookieProcessor()
            opener = urllib.request.build_opener(jar)
            req = urllib.request.Request(f"{EBUZIMA}/api/method/login", method="POST",
                                         data=json.dumps({"usr": user, "pwd": password}).encode(),
                                         headers={"Content-Type": "application/json", "Accept": "application/json"})
            opener.open(req, timeout=60).read()
            self.headers = {"Cookie": "; ".join(f"{c.name}={c.value}" for c in jar.cookiejar)}

    def call(self, method, path, body=None, ok=(200, 201, 202), timeout=120):
        return request(method, f"{EBUZIMA}/api/{path}", body, headers=self.headers, ok=ok, timeout=timeout)[1]

    @staticmethod
    def resource(doctype, name=""):
        return "resource/" + urllib.parse.quote(doctype) + ("/" + urllib.parse.quote(name, safe="") if name else "")

    def find(self, doctype, name):
        status, data = request("GET", f"{EBUZIMA}/api/{self.resource(doctype, name)}", headers=self.headers, ok=(200, 404))
        return data["data"] if status == 200 else None

    def list(self, doctype, filters=None, fields=("name",)):
        q = urllib.parse.urlencode({"filters": json.dumps(filters or []), "fields": json.dumps(list(fields)),
                                    "limit_page_length": 0})
        return self.call("GET", f"{self.resource(doctype)}?{q}")["data"]

    def create(self, doctype, body):
        return self.call("POST", self.resource(doctype), body)["data"]

    def update(self, doctype, name, body):
        return self.call("PUT", self.resource(doctype, name), body)["data"]

    def delete(self, doctype, name):
        self.call("DELETE", self.resource(doctype, name))


def ebuzima_admin():
    return Frappe("Administrator", env()["EBUZIMA_ADMIN_PASSWORD"])
