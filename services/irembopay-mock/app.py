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
from fastapi.responses import HTMLResponse, RedirectResponse, Response

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


@app.get("/favicon.ico", include_in_schema=False)
def favicon():
    # Browsers ask for it on the hosted checkout; the mock has no icon
    return Response(status_code=204)


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
        "checkoutUrl": None if body.get("channel") == "MOBILE_MONEY" else f"{PUBLIC_URL}/checkout/{pid}",
        "msisdn": body.get("msisdn"),
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


@app.get("/v1/mobile-money/pending")
def pending_pushes(msisdn: str):
    """Approval prompts waiting on this phone (what the phone simulator shows as the mobile-money pop-up)."""
    return [public_view(p) for p in payments.values()
            if p.get("msisdn") == msisdn and p["status"] == "PENDING" and p["channel"] == "MOBILE_MONEY"]


@app.get("/v1/payments/{pid}")
def payment_status(pid: str):
    return public_view(get_payment(pid))


CHECKOUT_CSS = """
@import url("https://fonts.googleapis.com/css2?family=Nunito+Sans:wght@300;400;600;700;800&display=swap");
*{box-sizing:border-box}body{margin:0;font-family:"Nunito Sans",system-ui,sans-serif;color:#212529;background:#f3f5f6}
.top{background:linear-gradient(115deg,#0052ad,#0063cf 45%,#1f8bf0);color:#fff;position:relative;overflow:hidden;padding:0 20px}
.top:after{content:"";position:absolute;width:380px;height:380px;right:-90px;top:-170px;border:44px solid rgba(255,255,255,.07);border-radius:50%}
.bar{max-width:980px;margin:0 auto;height:64px;display:flex;align-items:center;justify-content:space-between;position:relative;z-index:1}
.mark{font-size:25px;letter-spacing:-.5px;font-weight:300}.mark b{font-weight:800}
.secure{font-size:13px;font-weight:700;opacity:.9;display:flex;gap:6px;align-items:center}
.mock{background:#ffc726;color:#3a2600;font-size:13px;font-weight:700;text-align:center;padding:8px}
.wrap{max-width:980px;margin:26px auto;padding:0 20px;display:grid;grid-template-columns:1fr 1.2fr;gap:20px}
@media(max-width:760px){.wrap{grid-template-columns:1fr}}
.card{background:#fff;border-radius:10px;box-shadow:0 2px 12px rgba(16,42,84,.08);padding:22px}
h2{font-size:17px;color:#34495e;margin:0 0 14px;font-weight:800}.muted{color:#7c91a1}.small{font-size:13px}
.row{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #e7edf3;font-size:14.5px}.row:last-of-type{border:0}
.row span:first-child{color:#7c91a1}.row b{font-weight:700}
.total{margin-top:14px;padding:16px;border-radius:8px;background:#eef5fc;display:flex;justify-content:space-between;align-items:center}
.total .amt{font-size:30px;font-weight:800;letter-spacing:-.5px}.total .amt small{font-size:15px;color:#7c91a1}
.merchant{display:flex;gap:12px;align-items:center;margin-bottom:16px}
.logo{width:44px;height:44px;border-radius:10px;background:linear-gradient(135deg,#1f8bf0,#0052ad);display:grid;place-items:center;color:#fff;font-weight:800}
.method{display:flex;align-items:center;gap:14px;border:1px solid #cfdae5;border-radius:8px;padding:13px 14px;margin-bottom:10px;cursor:pointer}
.method input{accent-color:#0063cf;width:18px;height:18px}.method.on{border-color:#0063cf;box-shadow:0 0 0 3px #e0edf9}
.pill{font-size:12px;font-weight:800;color:#fff;border-radius:6px;padding:5px 8px;min-width:58px;text-align:center}
.mtn{background:#ffcb05;color:#1b1b1b}.airtel{background:#e40000}.card-pill{background:#34495e}
label.f{display:block;font-weight:700;font-size:14px;color:#34495e;margin:14px 0 6px}
.in{width:100%;height:46px;border:1px solid #cfdae5;border-radius:5px;padding:0 12px;font:15px "Nunito Sans",system-ui}
.btn{width:100%;height:50px;border:0;border-radius:5px;font:700 16px "Nunito Sans",system-ui;cursor:pointer;margin-top:16px;color:#fff;
background:linear-gradient(100deg,#1f8bf0,#0063cf);box-shadow:0 2px 8px rgba(0,99,207,.25)}
.link{background:none;border:0;color:#7c91a1;font:600 14px "Nunito Sans",system-ui;cursor:pointer;width:100%;margin-top:10px}
.state{text-align:center;padding:26px 10px}.ring{width:64px;height:64px;border-radius:50%;margin:0 auto 14px;display:grid;place-items:center;font-size:30px}
.ok{background:#e3f5ee;color:#0f996d}.no{background:#fdecee;color:#dc3545}.wait{background:#e0edf9;color:#0063cf}
.spin{width:28px;height:28px;border:4px solid #e0edf9;border-top-color:#0063cf;border-radius:50%;animation:s .8s linear infinite}
@keyframes s{to{transform:rotate(360deg)}}
"""


@app.get("/checkout/{pid}", response_class=HTMLResponse)
def checkout(pid: str):
    p = get_payment(pid)
    amount = f"{int(float(p['amount'])):,}"
    summary = f"""
      <div class="card"><div class="merchant"><div class="logo">TC</div><div><b>TeleClinic</b><div class="muted small">Ministry of Health service on IremboHub</div></div></div>
        <h2>Order summary</h2>
        <div class="row"><span>Service</span><b>{escape(p['description'] or 'TeleClinic consultation')}</b></div>
        <div class="row"><span>Reference</span><b>{escape(p['reference'][:8])}</b></div>
        <div class="row"><span>Payment ID</span><b>{escape(p['paymentId'])}</b></div>
        <div class="total"><span class="muted">Amount to pay</span><span class="amt">{amount} <small>{escape(p['currency'])}</small></span></div>
      </div>"""
    if p["status"] == "PENDING":
        right = f"""
      <div class="card" id="pay"><h2>Choose how to pay</h2>
        <label class="method on"><input type="radio" name="m" value="mtn" checked><span class="pill mtn">MoMo</span><div><b>MTN Mobile Money</b><div class="muted small">Approve the prompt on your phone</div></div></label>
        <label class="method"><input type="radio" name="m" value="airtel"><span class="pill airtel">airtel</span><div><b>Airtel Money</b><div class="muted small">Approve the prompt on your phone</div></div></label>
        <label class="method"><input type="radio" name="m" value="card"><span class="pill card-pill">CARD</span><div><b>Bank card</b><div class="muted small">Visa or Mastercard</div></div></label>
        <label class="f" for="msisdn">Mobile money number</label><input class="in" id="msisdn" value="078 800 0002" inputmode="tel">
        <form method="post" action="/checkout/{pid}/approve" id="approve"></form>
        <button class="btn" id="go">Pay {amount} {escape(p['currency'])}</button>
        <form method="post" action="/checkout/{pid}/decline"><button class="link">Cancel payment</button></form>
      </div>
      <div class="card state" id="prompt" hidden><div class="ring wait"><div class="spin"></div></div>
        <h2>Approve the payment on your phone</h2><p class="muted">A prompt was sent to <b id="num"></b>. Enter your PIN to confirm.</p>
        <p class="muted small">Mock: the prompt is approved automatically in a moment.</p></div>"""
        script = """<script>
document.querySelectorAll('.method').forEach(m=>m.onclick=()=>{document.querySelectorAll('.method').forEach(x=>x.classList.remove('on'));m.classList.add('on');m.querySelector('input').checked=true;});
document.getElementById('go').onclick=()=>{document.getElementById('pay').hidden=true;document.getElementById('prompt').hidden=false;
document.getElementById('num').textContent=document.getElementById('msisdn').value;setTimeout(()=>document.getElementById('approve').submit(),2200);};
</script>"""
    else:
        good = p["status"] == "SUCCEEDED"
        right = f"""
      <div class="card state"><div class="ring {'ok' if good else 'no'}">{'&#10003;' if good else '&#10007;'}</div>
        <h2>{'Payment received' if good else 'Payment not completed'}</h2>
        <p class="muted">{'TeleClinic has been notified.' if good else 'No money was taken.'}</p></div>"""
        script = ""
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>IremboPay (mock) checkout</title><style>{CHECKOUT_CSS}</style></head><body>
<div class="mock">MOCK: labelled stand-in for IremboPay. No real money moves.</div>
<div class="top"><div class="bar"><div class="mark"><b>irembo</b>Pay</div><div class="secure">&#128274; Secure checkout</div></div></div>
<div class="wrap">{summary}{right}</div>{script}</body></html>"""


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
    if label == "normal":  # only genuine deliveries can be replayed as duplicates
        last_event[p["paymentId"]] = event
    if respect_config:
        if config["dropWebhooks"]:
            log.info("webhook %s for %s dropped (scenario: lost webhook)", event["eventId"], p["paymentId"])
            deliveries.append({"eventId": event["eventId"], "paymentId": p["paymentId"], "type": event.get("type"), "label": "dropped", "status": None, "at": now_iso()})
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
                deliveries.append({"eventId": event["eventId"], "paymentId": p["paymentId"], "type": event.get("type"), "label": label, "status": r.status_code,
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


LABELS = {"normal": ("Webhook", "b-ok"), "duplicate": ("Duplicate (scenario)", "b-warn"),
          "forged": ("Forged signature (scenario)", "b-bad"), "spoofed": ("False claim (scenario)", "b-bad"),
          "dropped": ("Not delivered (scenario)", "b-muted")}
STATUS_BADGE = {"SUCCEEDED": "b-ok", "PENDING": "b-warn", "FAILED": "b-bad", "REFUNDED": "b-muted"}
LOG_CSS = """
.page{max-width:980px;margin:26px auto;padding:0 20px;display:grid;gap:20px}
.lead{margin:0 0 4px;color:#34495e;font-size:14.5px;line-height:1.5}
table{width:100%;border-collapse:collapse;font-size:14px}th{text-align:left;font-size:12px;text-transform:uppercase;letter-spacing:.05em;
color:#7c91a1;font-weight:800;padding:8px 10px;border-bottom:1px solid #e7edf3}td{padding:9px 10px;border-bottom:1px solid #e7edf3;vertical-align:top}
tr:last-child td{border-bottom:0}.mono{font-family:ui-monospace,monospace;font-size:12.5px}
.badge{display:inline-block;font-size:11.5px;font-weight:800;padding:2px 9px;border-radius:10px;white-space:nowrap}
.b-ok{background:#e3f5ee;color:#0f996d}.b-warn{background:#fff4dc;color:#b86e00}.b-bad{background:#fdecee;color:#dc3545}.b-muted{background:#f3f5f6;color:#7c91a1}
.empty{text-align:center;padding:26px 10px;color:#7c91a1}.answer{color:#34495e}
"""


@app.get("/admin/deliveries")
def list_deliveries(request: Request):
    # The ops cockpit and the scripts read JSON; a person opening the page in a browser gets a readable log
    if "text/html" not in request.headers.get("accept", ""):
        return deliveries[-50:]
    rows = "".join(
        f"""<tr><td class="mono">{escape(d['at'][11:19])}</td><td class="mono">{escape(d.get('paymentId') or '')}</td>
          <td><span class="badge {LABELS.get(d['label'], (d['label'], 'b-muted'))[1]}">{escape(LABELS.get(d['label'], (d['label'], ''))[0])}</span>
          <div class="muted small">{escape(d.get('type') or '')}</div></td>
          <td class="answer">{'' if d['status'] is None else f"<b>{d['status']}</b> "}<span class="small">{escape((d.get('response') or '')[:120])}</span></td></tr>"""
        for d in reversed(deliveries[-50:]))
    pays = "".join(
        f"""<tr><td class="mono">{escape(p['createdAt'][11:19])}</td><td class="mono">{escape(p['paymentId'])}</td>
          <td>{int(float(p['amount'])):,} {escape(p['currency'])}</td><td>{escape('Mobile money' if p['channel'] == 'MOBILE_MONEY' else 'Web checkout')}</td>
          <td><span class="badge {STATUS_BADGE.get(p['status'], 'b-muted')}">{escape(p['status'])}</span></td></tr>"""
        for p in reversed(list(payments.values())))
    empty = ('<div class="empty">No payments yet. Book a consultation in the patient app or on the USSD simulator: '
             'the payment and every webhook IremboPay sends for it appear here.</div>')
    return HTMLResponse(f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="5"><title>IremboPay (mock): payments and webhooks</title><style>{CHECKOUT_CSS}{LOG_CSS}</style></head><body>
<div class="mock">MOCK: labelled stand-in for IremboPay. No real money moves.</div>
<div class="top"><div class="bar"><div class="mark"><b>irembo</b>Pay</div><div class="secure">Payments and webhooks · refreshes every 5 s</div></div></div>
<div class="page">
  <div class="card"><h2>Payments</h2><p class="lead">Payments TeleClinic created here, with the patient's share after the insurance check.</p>
    {f'<table><tr><th>Created (UTC)</th><th>Payment</th><th>Amount</th><th>Channel</th><th>Status</th></tr>{pays}</table>' if payments else empty}</div>
  <div class="card"><h2>Webhooks sent to TeleClinic</h2><p class="lead">Every webhook and what TeleClinic answered. The cockpit's webhook
    scenario adds duplicates and a forged one: TeleClinic acknowledges the duplicates without acting twice, and refuses the forged one.</p>
    {f'<table><tr><th>Time (UTC)</th><th>Payment</th><th>Sent</th><th>TeleClinic answered</th></tr>{rows}</table>' if deliveries else '<div class="empty">No webhooks yet.</div>'}</div>
</div></body></html>""")


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
