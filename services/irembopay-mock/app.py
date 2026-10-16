"""IremboPay mock: a labelled stand-in for the real payment platform in the TeleClinic demo.

It behaves like the interface the design assumes (assumption A2): payment creation with idempotency keys,
a hosted checkout, signed webhooks with event ids, a status endpoint and a settlement report. Scenario
controls make webhooks arrive twice, late, never, forged or with a false claim.

It also answers the national insurance coverage check (assumption A12), equally labelled as a mock.
All data is synthetic and in memory: a restart is a reset.
"""

import asyncio
import hashlib
import hmac
import json
import logging
import os
import secrets
import time
from datetime import datetime, timezone
from html import escape

import httpx
from fastapi import FastAPI, Form, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse

PUBLIC_URL = os.environ.get("PUBLIC_URL", "http://localhost:9280").rstrip("/")
WEBHOOK_SECRET = os.environ["WEBHOOK_SECRET"].encode()

log = logging.getLogger("irembopay-mock")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

app = FastAPI(title="IremboPay (mock)")

payments: dict[str, dict] = {}
by_idempotency_key: dict[str, str] = {}
last_event: dict[str, dict] = {}
deliveries: list[dict] = []
config = {"webhookDelaySeconds": 0, "dropWebhooks": False}

# Synthetic coverage records, keyed by "system|value" (the identifier slicing used by TeleClinic)
COVERAGE = {
    "national-id|0000198800000002": {"covered": True, "scheme": "CBHI", "copayment": 200, "currency": "RWF"},
}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def sign(body: bytes, secret: bytes = WEBHOOK_SECRET, ts: int | None = None) -> str:
    ts = int(time.time()) if ts is None else ts
    mac = hmac.new(secret, f"{ts}.".encode() + body, hashlib.sha256).hexdigest()
    return f"t={ts},v1={mac}"


@app.get("/health")
def health():
    return {"status": "ok", "service": "irembopay-mock", "label": "MOCK, not the real IremboPay"}


@app.post("/v1/payments", status_code=201)
async def create_payment(request: Request, idempotency_key: str = Header(...)):
    if idempotency_key in by_idempotency_key:
        return payments[by_idempotency_key[idempotency_key]]
    body = await request.json()
    for field in ("amount", "currency", "reference", "callbackUrl"):
        if field not in body:
            raise HTTPException(422, f"missing {field}")
    pid = "pay_" + secrets.token_hex(8)
    payment = {
        "paymentId": pid,
        "status": "PENDING",
        "amount": body["amount"],
        "currency": body["currency"],
        "reference": body["reference"],
        "description": body.get("description", ""),
        "channel": body.get("channel", "WEB"),
        "callbackUrl": body["callbackUrl"],
        "returnUrl": body.get("returnUrl"),
        "checkoutUrl": f"{PUBLIC_URL}/checkout/{pid}",
        "createdAt": now_iso(),
        "updatedAt": now_iso(),
    }
    payments[pid] = payment
    by_idempotency_key[idempotency_key] = pid
    log.info("payment created %s amount=%s %s reference=%s", pid, payment["amount"], payment["currency"], payment["reference"])
    return public_view(payment)


def public_view(p: dict) -> dict:
    return {k: v for k, v in p.items() if k != "callbackUrl"}


def get_payment(pid: str) -> dict:
    if pid not in payments:
        raise HTTPException(404, "payment not found")
    return payments[pid]


@app.get("/v1/payments/{pid}")
def payment_status(pid: str):
    return public_view(get_payment(pid))


@app.get("/checkout/{pid}", response_class=HTMLResponse)
def checkout(pid: str):
    p = get_payment(pid)
    done = p["status"] != "PENDING"
    buttons = "" if done else f"""
      <form method="post" action="/checkout/{pid}/approve"><button class="ok">Approve payment</button></form>
      <form method="post" action="/checkout/{pid}/decline"><button class="no">Decline</button></form>"""
    return f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>IremboPay (mock) checkout</title>
<style>body{{font-family:sans-serif;max-width:26rem;margin:2rem auto;padding:0 1rem}}
.banner{{background:#fff4d6;border:1px solid #e0b100;padding:.5rem;border-radius:6px;font-size:.9rem}}
button{{width:100%;padding:.8rem;margin:.4rem 0;font-size:1rem;border-radius:6px;border:0;cursor:pointer}}
.ok{{background:#0b5cc0;color:#fff}}.no{{background:#eee}}</style></head><body>
<p class="banner">MOCK: labelled stand-in for IremboPay. No real money moves.</p>
<h1>Pay {escape(str(p['amount']))} {escape(p['currency'])}</h1>
<p>{escape(p['description'] or 'TeleClinic consultation')}<br><small>Reference {escape(p['reference'])}</small></p>
<p>Status: <b>{escape(p['status'])}</b></p>{buttons}</body></html>"""


async def settle(pid: str, status: str):
    p = get_payment(pid)
    if p["status"] == "PENDING":
        p["status"] = status
        p["updatedAt"] = now_iso()
        log.info("payment %s -> %s", pid, status)
        event_type = "payment.succeeded" if status == "SUCCEEDED" else "payment.failed"
        asyncio.create_task(send_event(p, event_type))
    return p


@app.post("/checkout/{pid}/approve")
async def approve(pid: str):
    p = await settle(pid, "SUCCEEDED")
    if p.get("returnUrl"):
        return RedirectResponse(p["returnUrl"], status_code=303)
    return RedirectResponse(f"/checkout/{pid}", status_code=303)


@app.post("/checkout/{pid}/decline")
async def decline(pid: str):
    p = await settle(pid, "FAILED")
    if p.get("returnUrl"):
        return RedirectResponse(p["returnUrl"], status_code=303)
    return RedirectResponse(f"/checkout/{pid}", status_code=303)


def build_event(p: dict, event_type: str) -> dict:
    return {
        "eventId": "evt_" + secrets.token_hex(8),
        "type": event_type,
        "paymentId": p["paymentId"],
        "reference": p["reference"],
        "amount": p["amount"],
        "currency": p["currency"],
        "occurredAt": now_iso(),
    }


async def send_event(p: dict, event_type: str, *, event: dict | None = None, secret: bytes = WEBHOOK_SECRET,
                     respect_config: bool = True, label: str = "normal"):
    event = event or build_event(p, event_type)
    last_event[p["paymentId"]] = event
    if respect_config:
        if config["dropWebhooks"]:
            log.info("webhook %s for %s dropped (scenario: lost webhook)", event["eventId"], p["paymentId"])
            deliveries.append({"eventId": event["eventId"], "label": "dropped", "status": None, "at": now_iso()})
            return
        if config["webhookDelaySeconds"]:
            await asyncio.sleep(config["webhookDelaySeconds"])
    body = json.dumps(event, separators=(",", ":")).encode()
    headers = {"Content-Type": "application/json", "X-IremboPay-Signature": sign(body, secret)}
    # Like a real provider: retry with backoff until the receiver acknowledges with 2xx
    async with httpx.AsyncClient(timeout=5) as client:
        for attempt in range(1, 5):
            try:
                r = await client.post(p["callbackUrl"], content=body, headers=headers)
                deliveries.append({"eventId": event["eventId"], "label": label, "status": r.status_code,
                                   "response": r.text[:200], "attempt": attempt, "at": now_iso()})
                log.info("webhook %s (%s) -> %s %s", event["eventId"], label, r.status_code, r.text[:120])
                if r.status_code < 500:
                    return
            except httpx.HTTPError as e:
                log.warning("webhook %s attempt %d failed: %s", event["eventId"], attempt, e)
            await asyncio.sleep(2 ** attempt)


# ---- scenario controls (used by the demo cockpit and scripts) ----

@app.post("/admin/config")
async def set_config(request: Request):
    body = await request.json()
    for key in ("webhookDelaySeconds", "dropWebhooks"):
        if key in body:
            config[key] = body[key]
    return config


@app.post("/admin/payments/{pid}/webhook")
async def scenario_webhook(pid: str, mode: str):
    """mode=duplicate: send the last event again with the same event id.
    mode=forged: a payment.succeeded signed with the wrong secret.
    mode=spoofed: correctly signed but claims success while the payment is not paid (stolen secret)."""
    p = get_payment(pid)
    if mode == "duplicate":
        if pid not in last_event:
            raise HTTPException(409, "no event sent yet for this payment")
        await send_event(p, "", event=last_event[pid], respect_config=False, label="duplicate")
    elif mode == "forged":
        await send_event(p, "payment.succeeded", event=build_event(p, "payment.succeeded"),
                         secret=b"not-the-shared-secret", respect_config=False, label="forged")
    elif mode == "spoofed":
        await send_event(p, "payment.succeeded", event=build_event(p, "payment.succeeded"),
                         respect_config=False, label="spoofed")
    else:
        raise HTTPException(422, "mode must be duplicate, forged or spoofed")
    return deliveries[-1]


@app.get("/admin/deliveries")
def list_deliveries():
    return deliveries[-50:]


@app.get("/v1/settlements")
def settlements():
    paid = [public_view(p) for p in payments.values() if p["status"] == "SUCCEEDED"]
    return {"generatedAt": now_iso(), "count": len(paid), "total": sum(p["amount"] for p in paid), "payments": paid}


# ---- national insurance coverage check (mock, assumption A12) ----

@app.get("/insurance/v1/coverage")
def coverage(identifier: str):
    record = COVERAGE.get(identifier)
    if record:
        return {"identifier": identifier, **record, "label": "MOCK coverage check"}
    return {"identifier": identifier, "covered": False, "label": "MOCK coverage check"}
