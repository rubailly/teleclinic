"""Scenario runner and ops cockpit backend.

Runs the scripts in scripts/scenarios (the same ones `make scenario-*` runs) one at a time and keeps their output,
and collects the live state of the stack for the cockpit: Patroni members, etcd, Kafka in-sync replicas, the HAProxy
pools, the AI model mode, a reconciliation of payments, and two probes that write through pgproxy and call the edge
a few times a second.

Demo tool: it holds the Docker socket, so it listens on 127.0.0.1 only. Standard library plus psycopg.
"""

import html
import json
import os
import re
import subprocess
import threading
import time
import urllib.request
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import psycopg

REPO = Path(os.environ.get("REPO_DIR", "/work/teleclinic"))
STATIC = Path(__file__).parent / "static"
EDGE = os.environ.get("EDGE_URL", "http://127.0.0.1:9080")
PAY = os.environ.get("IREMBOPAY_URL", "http://127.0.0.1:9280")
DB = (f"host={os.environ.get('PROBE_DB_HOST', '127.0.0.1')} port={os.environ.get('PROBE_DB_PORT', '9432')} "
      f"dbname=teleclinic user={os.environ['DB_USER']} password={os.environ['DB_PASSWORD']} connect_timeout=2")
ANSI = re.compile(r"\x1b\[[0-9;]*m")

SCENARIOS = [
    {"id": "kill-db-leader", "scene": "4a", "title": "Kill the database leader",
     "detail": "Writes run every 200 ms while the Patroni leader is killed. Pass: new leader under 60 s, no acknowledged write lost.",
     "cmd": ["scripts/scenarios/kill-db-leader.sh"], "kind": "failure"},
    {"id": "kill-kafka-broker", "scene": "4a", "title": "Stop a Kafka broker",
     "detail": "One of three brokers stops. Pass: in-sync replicas drop to 2 and 50 of 50 messages are written with acks=all.",
     "cmd": ["scripts/scenarios/kill-kafka-broker.sh"], "kind": "failure"},
    {"id": "partition-node", "scene": "4b", "title": "Cut the leader's node off the network",
     "detail": "docker network disconnect on one node's etcd, Patroni and Kafka. Pass: the minority stops accepting writes, one leader at every moment, rows match after healing.",
     "cmd": ["scripts/scenarios/partition-node.sh"], "kind": "failure"},
    {"id": "duplicate-webhooks", "scene": "1c", "title": "Replay and forge payment webhooks",
     "detail": "The last confirmed payment's webhook is sent twice more and once with a wrong signature. Pass: duplicates ignored, forged one rejected, still one payment.",
     "cmd": ["scripts/scenarios/duplicate-webhooks.sh"], "kind": "security"},
    {"id": "model-off", "scene": "2", "title": "Kill switch: model off",
     "detail": "An administrator switches the triage model off. New cases are rules-only and the clinician queue shows a banner.",
     "cmd": ["scripts/scenarios/model-kill-switch.sh", "off"], "kind": "control"},
    {"id": "model-on", "scene": "2", "title": "Model back on",
     "detail": "Reactivates the model after the investigation.",
     "cmd": ["scripts/scenarios/model-kill-switch.sh", "on", "investigation closed"], "kind": "control"},
    {"id": "release", "scene": "4d", "title": "Release v1.5 to the idle colour",
     "detail": "Starts v1.5 beside the live colour at weight 0, then 1, 10, 50 and 100% under k6 load. Each step is gated on the new colour's error rate. Pass: 0 failed requests.",
     "cmd": ["scripts/release/blue-green.sh", "v1.5"], "kind": "release"},
    {"id": "release-bad", "scene": "4d", "title": "Rollback drill: a bad build",
     "detail": "v1.6 passes its health checks but fails 30% of API calls (injected on purpose). Pass: the gate catches it in the canary and puts all traffic back automatically.",
     "cmd": ["scripts/release/blue-green.sh", "v1.6", "--fault-rate", "0.3", "--expect-rollback"], "kind": "release"},
    {"id": "switch-back", "scene": "4d", "title": "Switch back to the previous colour",
     "detail": "One command puts traffic back on the colour kept at weight 0, if Docker and HAProxy both report it healthy.",
     "cmd": ["scripts/release/blue-green.sh", "--switch-back"], "kind": "release"},
    {"id": "ussd-booking", "scene": "1", "title": "Book from a basic phone",
     "detail": "A full USSD booking for Keza, paid by mobile money, with the confirmation SMS. Adds a real payment to reconcile.",
     "cmd": ["scripts/smoke/ussd.sh"], "kind": "traffic"},
]
BY_ID = {s["id"]: s for s in SCENARIOS}


def sh(args: list[str], timeout: float = 5) -> str:
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def http_json(url: str, timeout: float = 2):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.loads(r.read())


def c(service: str) -> str:
    return f"teleclinic-{service}-1"


# ---------------------------------------------------------------- live state

state: dict = {"containers": {}, "patroni": None, "etcd": {}, "kafka": None, "edge": None, "pgproxy": None,
               "model": None, "recon": None, "events": []}
lock = threading.RLock()  # event() is also called by collectors that already hold it
probes = {"db": deque(maxlen=480), "edge": deque(maxlen=480)}  # (ms since epoch, ok, latency ms, extra)


def event(text: str, level: str = "info"):
    with lock:
        state["events"].insert(0, {"at": time.time() * 1000, "text": text, "level": level})
        del state["events"][30:]


def every(seconds: float, fn):
    def loop():
        while True:
            try:
                fn()
            except Exception as e:  # a collector must never die
                print(f"collector {fn.__name__}: {e}", flush=True)
            time.sleep(seconds)
    threading.Thread(target=loop, daemon=True).start()


def collect_containers():
    out = sh(["docker", "ps", "-a", "--filter", "label=com.docker.compose.project=teleclinic",
              "--format", "{{.Names}}|{{.State}}|{{.Status}}|{{.Networks}}"])
    found = {}
    for line in out.splitlines():
        name, st, status, nets = line.split("|")
        svc = name.removeprefix("teleclinic-").removesuffix("-1")
        found[svc] = {"state": st, "health": "healthy" if "(healthy)" in status else "unhealthy" if "unhealthy" in status
                      else "starting" if "starting" in status else "", "networked": "teleclinic" in nets or "ai-zone" in nets}
    with lock:
        before = state["containers"]
        for svc, now in found.items():
            was = before.get(svc)
            if was and was["state"] != now["state"]:
                event(f"{svc} {'is running again' if now['state'] == 'running' else 'stopped'}",
                      "ok" if now["state"] == "running" else "bad")
            if was and was["networked"] != now["networked"] and svc.startswith(("pg-", "etcd-", "kafka-")):
                event(f"{svc} {'reconnected to' if now['networked'] else 'cut off from'} the network",
                      "ok" if now["networked"] else "bad")
        state["containers"] = found


def collect_patroni():
    data = None
    for n in (1, 2, 3):
        raw = sh(["docker", "exec", c(f"pg-{n}"), "curl", "-fs", "--max-time", "1.5", "http://127.0.0.1:8008/cluster"], 3)
        if raw:
            d = json.loads(raw)
            # an isolated node only sees itself; prefer a view that has a leader
            if any(m["role"] == "leader" for m in d["members"]):
                data = d
                break
            data = data or d
    if not data:
        return
    members = [{"name": m["name"], "role": m["role"], "state": m["state"], "lag": m.get("lag", 0),
                "timeline": m.get("timeline")} for m in data["members"]]
    leader = next((m["name"] for m in members if m["role"] == "leader"), None)
    with lock:
        prev = state["patroni"]
        since = prev["since"] if prev and prev["leader"] == leader else time.time() * 1000
        if prev and prev["leader"] != leader:
            event(f"database leader {prev['leader'] or 'none'} → {leader or 'none'}", "ok" if leader else "bad")
        state["patroni"] = {"leader": leader, "since": since, "members": members}


def collect_etcd():
    health = {}
    for n in (1, 2, 3):
        out = sh(["docker", "exec", c(f"etcd-{n}"), "etcdctl", "--endpoints=127.0.0.1:2379",
                  "--command-timeout=1500ms", "endpoint", "health"], 4)
        health[f"etcd-{n}"] = "is healthy" in out
    with lock:
        state["etcd"] = health


kafka_seen = {"key": None, "at": 0.0}


def collect_kafka():
    """The Kafka CLI starts a JVM, so ask every 10 s, or at once when a broker stops, starts or is cut off."""
    key = tuple((state["containers"].get(f"kafka-{n}") or {}).get(k) for n in (1, 2, 3) for k in ("state", "networked"))
    if key == kafka_seen["key"] and time.time() - kafka_seen["at"] < 10:
        return
    kafka_seen.update(key=key, at=time.time())
    running = [n for n in (1, 2, 3) if state["containers"].get(f"kafka-{n}", {}).get("state") == "running"
               and state["containers"][f"kafka-{n}"]["networked"]]
    if not running:
        return
    out = sh(["docker", "exec", c(f"kafka-{running[0]}"), "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server",
              "kafka-1:9092,kafka-2:9092,kafka-3:9092", "--describe", "--topic", "consultation.paid"], 20)
    isr = [len(m.group(1).split(",")) for m in re.finditer(r"Isr:\s*([0-9,]+)", out)]
    if not isr:
        return
    with lock:
        prev = state["kafka"]
        if prev and min(prev["isr"]) != min(isr):
            event(f"Kafka in-sync replicas {min(prev['isr'])} → {min(isr)}", "ok" if min(isr) == 3 else "warn")
        state["kafka"] = {"isr": isr, "partitions": len(isr)}


def haproxy_rows(url: str) -> list[dict]:
    with urllib.request.urlopen(url, timeout=2) as r:
        lines = r.read().decode().splitlines()
    head = lines[0].lstrip("# ").split(",")
    return [dict(zip(head, ln.split(","))) for ln in lines[1:] if ln]


def collect_edge():
    pool = [{"name": r["svname"], "status": r["status"], "weight": int(r["weight"] or 0)}
            for r in haproxy_rows("http://127.0.0.1:9404/stats;csv") if r["pxname"] == "core" and r["svname"] != "BACKEND"]
    primary = next((r["svname"] for r in haproxy_rows("http://127.0.0.1:9405/stats;csv")
                    if r["pxname"] == "primary" and r["status"] == "UP"), None)
    with lock:
        state["edge"] = {"pool": pool}
        state["pgproxy"] = {"primary": primary}


def collect_model():
    out = sh(["docker", "exec", c("triage-model"), "python", "-c",
              "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8100/models/active').read().decode())"], 6)
    if not out.strip():
        return
    m = json.loads(out)
    with lock:
        prev = state["model"]
        if prev and prev["mode"] != m.get("mode"):
            event(f"triage model {m['modelVersion']}: {prev['mode']} → {m['mode']}", "warn" if m["mode"] != "ACTIVE" else "ok")
        state["model"] = {"version": m.get("modelVersion"), "mode": m.get("mode"), "reason": m.get("reason")}


RECON_SQL = """
select json_build_object(
  'consultations', (select coalesce(json_object_agg(state, n), '{}') from (select state, count(*) n from consultation.consultation group by 1) s),
  'payments', (select coalesce(json_object_agg(status, n), '{}') from (select status, count(*) n from payments.payment group by 1) p),
  'paidAmount', (select coalesce(sum(amount), 0) from payments.payment where status = 'SUCCEEDED'),
  'webhooks', (select count(*) from payments.webhook_event),
  'outboxPending', (select count(*) from public.event_publication where completion_date is null),
  'outboxDone', (select count(*) from public.event_publication where completion_date is not null))
"""


def collect_recon():
    with psycopg.connect(DB) as conn:
        core = conn.execute(RECON_SQL).fetchone()[0]
    try:
        settled = http_json(f"{PAY}/v1/settlements")
        deliveries = http_json(f"{PAY}/admin/deliveries")
    except Exception:
        settled, deliveries = None, []
    with lock:
        state["recon"] = {
            "core": core,
            "provider": {"count": settled["count"], "total": settled["total"]} if settled else None,
            "deliveries": {
                "duplicate": sum(1 for d in deliveries if d.get("label") == "duplicate"),
                "forged": sum(1 for d in deliveries if d.get("label") in ("forged", "spoofed")),
                "rejected": sum(1 for d in deliveries if d.get("status") in (401, 403)),
            },
        }


def probe_db():
    """Writes one row through pgproxy (the core's path) every 250 ms; a failed write reconnects, as the core's pool does."""
    conn = None
    created = False
    while True:
        t0 = time.time()
        ok, note = False, ""
        try:
            if conn is None or conn.closed:
                conn = psycopg.connect(DB, autocommit=True)
            if not created:
                conn.execute("CREATE TABLE IF NOT EXISTS ha_probe (id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(), note text)")
                created = True
            conn.execute("SET statement_timeout = 2000")
            conn.execute("INSERT INTO ha_probe (note) VALUES ('cockpit')")
            ok = True
        except Exception as e:
            note = str(e).splitlines()[0][:80]
            try:
                conn and conn.close()
            except Exception:
                pass
            conn = None
        probes["db"].append((t0 * 1000, ok, round((time.time() - t0) * 1000), note))
        time.sleep(max(0, 0.25 - (time.time() - t0)))


def probe_edge():
    # Startup is not an outage: record from the first answer on, then every failure counts
    while True:
        try:
            http_json(f"{EDGE}/api/public/version", 2)
            break
        except Exception:
            time.sleep(1)
    while True:
        t0 = time.time()
        ok, note = False, ""
        try:
            v = http_json(f"{EDGE}/api/public/version", 2)
            ok, note = True, f"{v.get('version')} {v.get('color')}"
        except Exception as e:
            note = str(e)[:80]
        probes["edge"].append((t0 * 1000, ok, round((time.time() - t0) * 1000), note))
        time.sleep(max(0, 0.25 - (time.time() - t0)))


# ---------------------------------------------------------------- scenario runs

runs: dict[str, dict] = {}
current: dict = {"id": None}


def start_run(sid: str) -> dict:
    s = BY_ID[sid]
    with lock:
        if current["id"] and runs[current["id"]]["status"] == "running":
            return {"error": f"{runs[current['id']]['title']} is still running"}
        rid = f"{sid}-{int(time.time())}"
        run = {"id": rid, "scenario": sid, "title": s["title"], "status": "running", "started": time.time() * 1000,
               "ended": None, "lines": []}
        runs[rid] = run
        current["id"] = rid
    event(f"started: {s['title']}", "info")

    def work():
        p = subprocess.Popen(["bash", *s["cmd"]], cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                             env={**os.environ, "TERM": "dumb"})
        for raw in p.stdout:
            kind = "step" if "\x1b[36m" in raw else "fail" if "\x1b[31m" in raw else "pass" if "\x1b[32m" in raw else "out"
            run["lines"].append({"at": time.time() * 1000, "kind": kind, "text": ANSI.sub("", raw.rstrip())})
        p.wait()
        run["status"] = "passed" if p.returncode == 0 else "failed"
        run["ended"] = time.time() * 1000
        event(f"{run['status']}: {s['title']}", "ok" if p.returncode == 0 else "bad")

    threading.Thread(target=work, daemon=True).start()
    return {"id": rid}


# ---------------------------------------------------------------- runbooks

RUNBOOKS = REPO / "docs" / "runbooks"


def inline(text: str) -> str:
    text = html.escape(text)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"\[([^\]]+)\]\((https?://[^)\s]+)\)", r'<a href="\2">\1</a>', text)
    return re.sub(r'(?<![">])(https?://[^\s<)]+)', r'<a href="\1">\1</a>', text)  # bare URLs


def markdown(md: str) -> str:
    """Enough Markdown for the runbooks: headings, paragraphs, lists, fenced code, inline code, bold, links."""
    out, para, lst, code = [], [], None, None
    def flush():
        nonlocal para, lst
        if para:
            out.append("<p>" + inline(" ".join(para)) + "</p>")
            para = []
        if lst:
            start = f' start="{lst[2]}"' if lst[0] == "ol" and lst[2] != 1 else ""
            out.append(f"<{lst[0]}{start}>" + "".join(f"<li>{inline(i)}</li>" for i in lst[1]) + f"</{lst[0]}>")
            lst = None
    for line in md.splitlines():
        stripped = line.strip()
        if code is not None:
            if stripped.startswith("```"):
                out.append("<pre>" + html.escape("\n".join(code)) + "</pre>")
                code = None
            else:
                code.append(stripped)
            continue
        if stripped.startswith("```"):
            flush(); code = []
        elif stripped.startswith("#"):
            flush(); level = len(stripped) - len(stripped.lstrip("#"))
            out.append(f"<h{level}>{inline(stripped[level:].strip())}</h{level}>")
        elif m := re.match(r"^(?:[-*]|\d+\.)\s+(.*)", stripped):
            kind = "ol" if stripped[0].isdigit() else "ul"
            if para:
                flush()
            if not lst or lst[0] != kind:
                # a numbered list keeps its numbers across a code block between items
                flush(); lst = (kind, [], int(stripped.split(".")[0]) if kind == "ol" else 1)
            lst[1].append(m.group(1))
        elif stripped == "":
            flush()
        elif lst:
            lst[1][-1] += " " + stripped
        else:
            para.append(stripped)
    flush()
    return "\n".join(out)


def runbook_page(name: str | None) -> bytes | None:
    if name:
        f = (RUNBOOKS / f"{name}.md").resolve()
        if f.parent != RUNBOOKS.resolve() or not f.is_file():
            return None
        body, title = markdown(f.read_text()), f.read_text().splitlines()[0].lstrip("# ")
        body += '<p class="back"><a href="/runbooks/">All runbooks</a> · <a href="http://localhost:9700">Dashboard</a> · <a href="/">Ops cockpit</a></p>'
    else:
        title = "Runbooks"
        items = sorted(RUNBOOKS.glob("*.md"))
        body = "<h1>Runbooks</h1><p>One page per alert. Each alert in Grafana links to its page.</p><ul>" + "".join(
            f'<li><a href="/runbooks/{f.stem}">{html.escape(f.read_text().splitlines()[0].lstrip("# "))}</a></li>' for f in items) + "</ul>"
    page = (STATIC / "runbook.html").read_text().replace("{{title}}", html.escape(title)).replace("{{body}}", body)
    return page.encode()


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code: int, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path == "/":
            return self.send(200, (STATIC / "index.html").read_bytes(), "text/html; charset=utf-8")
        if path.startswith("/static/"):
            f = (STATIC / path.removeprefix("/static/")).resolve()
            if f.parent != STATIC.resolve() or not f.is_file():
                return self.send(404, {"error": "not found"})
            ctype = {".css": "text/css", ".js": "text/javascript", ".svg": "image/svg+xml"}.get(f.suffix, "application/octet-stream")
            return self.send(200, f.read_bytes(), ctype + "; charset=utf-8")
        if path == "/api/state":
            with lock:
                snap = json.loads(json.dumps(state))
            snap["probes"] = {k: list(v)[-360:] for k, v in probes.items()}
            snap["run"] = summary(runs.get(current["id"])) if current["id"] else None
            snap["now"] = time.time() * 1000
            return self.send(200, snap)
        if path == "/api/scenarios":
            return self.send(200, [{k: v for k, v in s.items() if k != "cmd"} for s in SCENARIOS])
        if path.startswith("/api/runs/"):
            run = runs.get(path.removeprefix("/api/runs/"))
            if not run:
                return self.send(404, {"error": "no such run"})
            start = int(query.removeprefix("from=") or 0) if query.startswith("from=") else 0
            return self.send(200, {**summary(run), "lines": run["lines"][start:], "from": start})
        if path.startswith("/runbooks"):
            name = path.removeprefix("/runbooks").strip("/") or None
            page = runbook_page(name)
            return self.send(200, page, "text/html; charset=utf-8") if page else self.send(404, {"error": "no such runbook"})
        if path == "/health":
            return self.send(200, {"status": "ok"})
        self.send(404, {"error": "not found"})

    def do_POST(self):
        if self.path.startswith("/api/run/"):
            sid = self.path.removeprefix("/api/run/")
            if sid not in BY_ID:
                return self.send(404, {"error": "unknown scenario"})
            r = start_run(sid)
            return self.send(409 if "error" in r else 202, r)
        self.send(404, {"error": "not found"})


def summary(run):
    return run and {k: run[k] for k in ("id", "scenario", "title", "status", "started", "ended")}


if __name__ == "__main__":
    every(1, collect_containers)
    every(1.5, collect_patroni)
    every(3, collect_etcd)
    every(1, collect_kafka)
    every(1.5, collect_edge)
    every(5, collect_model)
    every(2, collect_recon)
    threading.Thread(target=probe_db, daemon=True).start()
    threading.Thread(target=probe_edge, daemon=True).start()
    port = int(os.environ.get("PORT", "9500"))
    print(f"scenario runner on 127.0.0.1:{port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
