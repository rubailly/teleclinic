"""USSD gateway: telecom aggregator callbacks in, one menu step per request, session state in Valkey.

Stateless itself: any instance can serve any step, so instances can be added or replaced during a release.
Each step answers with CON (menu continues) or END (session over) within one USSD page. The safety rules run
inside the booking step, so advice appears in the same session. Payment is a mobile-money push: the session ends,
the core confirms the payment later and sends an SMS.

Also stands in for the aggregator's SMS API (/sms/send) and serves the phone simulator (/), both labelled.
"""

import json
import logging
import os
import time
from datetime import datetime, timezone

import httpx
import redis
from fastapi import FastAPI, Form, Header, HTTPException
from fastapi.responses import FileResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from prometheus_client import Counter, Histogram, make_asgi_app

from menus import DANGER_CODES, DAYS, SYMPTOM_CODES, URGENCY, fit, t

CORE = os.environ.get("CORE_URL", "http://haproxy:9100")
KEY = os.environ["USSD_GATEWAY_KEY"]
PAY = os.environ.get("IREMBOPAY_URL", "http://irembopay-mock:8000")
SESSION_TTL = 180

log = logging.getLogger("ussd-gateway")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
store = redis.Redis(host=os.environ.get("VALKEY_HOST", "valkey"), port=6379, decode_responses=True)
core = httpx.Client(base_url=CORE, timeout=5, headers={"X-Gateway-Key": KEY})
pay = httpx.Client(base_url=PAY, timeout=5)
app = FastAPI(title="TeleClinic USSD gateway")
# Target: every step under 1.5 s at p95 (design document, Section 1); buckets are dense around it
STEP_SECONDS = Histogram("ussd_step_seconds", "Time to answer one USSD step", ["step"],
                         buckets=(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 5.0))
SESSIONS_ENDED = Counter("ussd_sessions_ended_total", "USSD sessions that ended, by the step they ended on", ["step"])


class CoreError(Exception):
    def __init__(self, status: int, code: str, detail: str):
        self.status, self.code, self.detail = status, code, detail


def call(path: str, body: dict) -> dict:
    r = core.post(path, json=body)
    if r.status_code >= 400:
        issue = (r.json().get("issue") or [{}])[0] if "json" in r.headers.get("content-type", "") else {}
        raise CoreError(r.status_code, issue.get("code", ""), issue.get("diagnostics", r.text[:80]))
    return r.json()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def con(text: str) -> str:
    return "CON " + fit(text)


def end(text: str) -> str:
    return "END " + fit(text)


def step(s: dict, entry: str, msisdn: str) -> str:
    """One menu step. `s` is the session state, changed in place."""
    lang = s.get("lang", "en")
    at = s.get("at", "welcome")

    if at == "welcome":
        if entry == "":
            return con(t("welcome", "en"))
        if entry not in ("1", "2"):
            return con(t("invalid", "en") + "\n" + t("welcome", "en"))
        s["lang"], s["at"] = ("rw" if entry == "1" else "en"), "pin"
        return con(t("pin", s["lang"]))

    if at == "pin":
        try:
            r = call("/internal/ussd/unlock", {"msisdn": msisdn, "pin": entry})
        except CoreError as e:
            if e.status == 404:
                return end(t("not_linked", lang))
            if e.code == "locked" or e.status == 423:
                return end(t("locked", lang))
            if e.code == "wrong-pin":
                left = "".join(ch for ch in e.detail if ch.isdigit()) or "?"
                return con(t("wrong_pin", lang, left=left))
            raise
        s["token"], s["people"] = r["token"], r["people"]
        if len(r["people"]) == 1:
            return choose_person(s, 0)
        s["at"] = "who"
        lines = [t("who", lang)] + [f"{i + 1}. {p['firstName']} ({t('me' if p['relation'] == 'SELF' else 'child', lang)})"
                                   for i, p in enumerate(r["people"])]
        return con("\n".join(lines))

    if at == "who":
        if not entry.isdigit() or not 1 <= int(entry) <= len(s["people"]):
            return con(t("invalid", lang) + "\n" + t("who", lang))
        return choose_person(s, int(entry) - 1)

    if at == "consent":
        p = s["people"][s["person"]]
        if entry == "3":
            return end(t("bye", lang))
        if entry not in ("1", "2"):
            return con(t("consent", lang, name=p["firstName"]))
        call("/internal/ussd/consent", {"token": s["token"], "patientId": p["patientId"], "aiSupport": entry == "2"})
        return symptom_menu(s)

    if at == "symptoms":
        if entry == "0":
            if not s["symptoms"]:
                return con(t("need_one", lang) + "\n" + t("symptoms", lang, head=head(s)))
            s["at"] = "days"
            return con(t("days", lang))
        if entry == "6":
            s["at"] = "danger"
            return con(t("danger", lang))
        code = SYMPTOM_CODES.get(entry)
        if code and code not in s["symptoms"]:
            s["symptoms"].append(code)
        return symptom_menu(s)

    if at == "danger":
        code = DANGER_CODES.get(entry)
        if code and code not in s["symptoms"]:
            s["symptoms"].append(code)
        return symptom_menu(s)

    if at == "days":
        if entry not in DAYS:
            return con(t("invalid", lang) + "\n" + t("days", lang))
        p = s["people"][s["person"]]
        r = call("/internal/ussd/book", {"token": s["token"], "patientId": p["patientId"], "symptoms": s["symptoms"],
                                        "durationDays": DAYS[entry]})
        s["consultationId"], s["at"] = r["consultationId"], "advice"
        return con(t("advice", lang, urgency=URGENCY[r["urgency"]][lang], advice=r["advice"]))

    if at == "advice":
        if entry != "1":
            return end(t("bye", lang))
        r = call("/internal/ussd/pay", {"token": s["token"], "consultationId": s["consultationId"]})
        amount = f"{int(float(r['amount'])):,}"
        return end(t("paid_prompt", lang, amount=amount, cover=t("cover", lang) if r.get("insured") else ""))

    return end(t("busy", lang))


def head(s: dict) -> str:
    lang = s["lang"]
    if s["symptoms"]:
        return t("symptoms_added", lang, n=len(s["symptoms"]))
    return t("symptoms_head", lang, name=s["people"][s["person"]]["firstName"])


def symptom_menu(s: dict) -> str:
    s["at"] = "symptoms"
    return con(t("symptoms", s["lang"], head=head(s)))


def choose_person(s: dict, idx: int) -> str:
    s["person"], s["symptoms"] = idx, []
    p = s["people"][idx]
    if not p["hasConsent"]:
        s["at"] = "consent"
        return con(t("consent", s["lang"], name=p["firstName"]))
    return symptom_menu(s)


@app.post("/ussd/callback", response_class=PlainTextResponse)
def callback(sessionId: str = Form(...), phoneNumber: str = Form(...), text: str = Form(""), serviceCode: str = Form("")):
    """Africa's Talking style: `text` holds every answer so far joined by '*'; the last part is this step's input."""
    started = time.perf_counter()
    key = f"ussd:session:{sessionId}"
    raw = store.get(key)
    s = json.loads(raw) if raw else {}
    entry = text.split("*")[-1] if text else ""
    at_before = s.get("at", "welcome")
    try:
        reply = step(s, entry.strip(), phoneNumber)
    except (CoreError, httpx.HTTPError) as e:
        log.warning("step %s failed: %s", s.get("at"), getattr(e, "detail", e))
        reply = end(t("busy", s.get("lang", "en")))
    if reply.startswith("CON"):
        store.set(key, json.dumps(s), ex=SESSION_TTL)
    else:
        store.delete(key)
    elapsed = time.perf_counter() - started
    ms = round(elapsed * 1000)
    STEP_SECONDS.labels(at_before).observe(elapsed)
    if reply.startswith("END"):
        SESSIONS_ENDED.labels(at_before).inc()
    record = {"at": now_iso(), "step": at_before, "input": "****" if at_before == "pin" and entry else entry,
              "ms": ms, "chars": len(reply) - 4, "end": reply.startswith("END"), "reply": reply[4:64]}
    store.lpush(f"ussd:log:{phoneNumber}", json.dumps(record))
    store.ltrim(f"ussd:log:{phoneNumber}", 0, 49)
    log.info("ussd step %s in %d ms (%d chars)", record["step"], ms, record["chars"])
    return reply


@app.post("/sms/send")
def sms(body: dict, x_gateway_key: str = Header(default="")):
    """Stand-in for the aggregator's SMS API: messages land in the simulator's inbox."""
    if x_gateway_key != KEY:
        raise HTTPException(401, "unknown caller")
    store.lpush(f"sms:{body['to']}", json.dumps({"at": now_iso(), "text": body["text"]}))
    store.ltrim(f"sms:{body['to']}", 0, 19)
    return {"queued": True}


@app.get("/sim/state")
def sim_state(msisdn: str):
    logs = [json.loads(x) for x in store.lrange(f"ussd:log:{msisdn}", 0, 49)]
    inbox = [json.loads(x) for x in store.lrange(f"sms:{msisdn}", 0, 19)]
    try:
        pending = pay.get("/v1/mobile-money/pending", params={"msisdn": msisdn}).json()
    except httpx.HTTPError:
        pending = []
    return {"log": logs, "sms": inbox, "pending": pending}


@app.post("/sim/approve/{pid}")
def sim_approve(pid: str):
    pay.post(f"/checkout/{pid}/approve")
    return {"approved": pid}


@app.get("/health")
def health():
    store.ping()
    return {"status": "ok", "service": "ussd-gateway"}


app.mount("/static", StaticFiles(directory="static"), name="static")
app.mount("/metrics", make_asgi_app())


@app.get("/")
def simulator():
    return FileResponse("static/index.html")
