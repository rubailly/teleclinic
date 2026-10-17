"""Model service in the AI trust zone.

Reads triage.requested from Kafka, sends each payload through the anonymization gateway, runs the ONNX model,
checks the result for identifiers, and posts a signed suggestion to the core. The core alone turns the token back
into a case, merges with the rules floor and puts the case in front of a clinician as AI_PROPOSED.

Modes: ACTIVE (suggestions shown to clinicians), SHADOW (recorded, never shown), DISABLED (kill switch: nothing is
inferred, cases stay rules-only). The mode survives restarts.
"""

import hashlib
import hmac
import json
import logging
import os
import threading
import time
from datetime import datetime, timezone

import httpx
import numpy as np
import onnxruntime as ort
from confluent_kafka import Consumer
from fastapi import FastAPI

from features import NAMES, URGENCY, encode

ANONYMIZER = os.environ.get("ANONYMIZER_URL", "http://anonymizer:8200")
CALLBACK = os.environ.get("CORE_CALLBACK_URL", "http://haproxy:9100/internal/triage/suggestions")
SECRET = os.environ["AI_CALLBACK_SECRET"].encode()
BOOTSTRAP = os.environ.get("KAFKA_BOOTSTRAP", "kafka-1:9092")
MODE_FILE = os.environ.get("MODE_FILE", "/data/mode.json")

log = logging.getLogger("triage-model")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

session = ort.InferenceSession("model.onnx", providers=["CPUExecutionProvider"])
explain = json.load(open("explain.json"))
card = json.load(open("model_card.json"))
VERSION = card["modelVersion"]
stats = {"inferred": 0, "skippedDisabled": 0, "rejectedOutput": 0, "callbackFailures": 0, "lastLatencyMs": None}


def load_mode() -> dict:
    try:
        return json.load(open(MODE_FILE))
    except (OSError, ValueError):
        return {"mode": os.environ.get("MODEL_MODE", "ACTIVE"), "reason": "default", "changedAt": None}


state = load_mode()


def save_mode(mode: str, reason: str) -> dict:
    global state
    state = {"mode": mode, "reason": reason, "changedAt": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    with open(MODE_FILE, "w") as f:
        json.dump(state, f)
    log.warning("model mode set to %s: %s", mode, reason)
    return info()


def info() -> dict:
    return {"modelVersion": VERSION, "mode": state["mode"], "trainedOn": card["trainedOn"],
            "validation": card["validation"], "modelCardUrl": "/model-card", "reason": state.get("reason"),
            "changedAt": state.get("changedAt"), "stats": stats}


def predict(masked: dict) -> dict:
    x = np.array([encode(masked)], dtype=np.float32)
    label, proba = session.run(None, {"features": x})
    k = int(label[0])
    coef = np.array(explain["coef"][k])
    contributions = coef * x[0]
    top = [NAMES[i] for i in np.argsort(-contributions)[:3] if contributions[i] > 0]
    return {"urgency": URGENCY[k], "confidence": round(float(proba[0][k]), 3), "contributingFactors": top}


def sign(body: bytes) -> str:
    ts = int(time.time())
    return f"t={ts},v1=" + hmac.new(SECRET, f"{ts}.".encode() + body, hashlib.sha256).hexdigest()


def handle(event: dict, http: httpx.Client):
    if state["mode"] == "DISABLED":
        stats["skippedDisabled"] += 1
        return
    started = time.perf_counter()
    anon = http.post(f"{ANONYMIZER}/anonymize", json={"payload": event}).raise_for_status().json()
    masked = anon["maskedPayload"]
    result = predict(masked)
    # Output check: a result that contains anything identifier-like is dropped, never sent on
    scan = http.post(f"{ANONYMIZER}/scan", json={"text": json.dumps(result)}).raise_for_status().json()
    if not scan["clean"]:
        stats["rejectedOutput"] += 1
        log.error("result for %s rejected by the output check", masked.get("caseToken"))
        return
    body = json.dumps({"caseToken": masked["caseToken"], "modelVersion": VERSION, "mode": state["mode"], **result},
                      separators=(",", ":")).encode()
    latency = round((time.perf_counter() - started) * 1000)
    stats["lastLatencyMs"] = latency
    for attempt in range(1, 4):
        try:
            r = http.post(CALLBACK, content=body, headers={"Content-Type": "application/json", "X-Signature": sign(body)})
            if r.status_code < 500:
                stats["inferred"] += 1
                # Only the token, never anything that identifies the patient
                log.info("case %s -> %s (%.2f) in %d ms, core: %s", masked["caseToken"], result["urgency"],
                         result["confidence"], latency, r.text[:60])
                return
        except httpx.HTTPError as e:
            log.warning("callback attempt %d failed: %s", attempt, e)
        time.sleep(attempt)
    stats["callbackFailures"] += 1


stopping = threading.Event()


def consume():
    # Short session timeout: if an instance dies without leaving the group, its partitions move to the next
    # instance within seconds instead of the 45 s default
    consumer = Consumer({"bootstrap.servers": BOOTSTRAP, "group.id": "ai-triage", "auto.offset.reset": "earliest",
                         "enable.auto.commit": False, "session.timeout.ms": 10000, "heartbeat.interval.ms": 3000})
    consumer.subscribe(["triage.requested"])
    log.info("model %s consuming triage.requested in mode %s", VERSION, state["mode"])
    try:
        consume_loop(consumer)
    finally:
        consumer.close()   # leave the group cleanly so a restart takes over at once
        log.info("consumer closed")


def consume_loop(consumer):
    with httpx.Client(timeout=5) as http:
        while not stopping.is_set():
            msg = consumer.poll(1.0)
            if msg is None:
                continue
            if msg.error():
                log.warning("kafka: %s", msg.error())
                continue
            try:
                handle(json.loads(msg.value()), http)
            except Exception as e:  # a failed case stays rules-only in the core; never block the stream
                log.error("inference failed: %s", type(e).__name__)
            consumer.commit(msg, asynchronous=False)


app = FastAPI(title="TeleClinic triage model")


consumer_thread = threading.Thread(target=consume, daemon=True)


@app.on_event("startup")
def start_consumer():
    consumer_thread.start()


@app.on_event("shutdown")
def stop_consumer():
    stopping.set()
    consumer_thread.join(timeout=8)


@app.get("/health")
def health():
    return {"status": "ok", "modelVersion": VERSION, "mode": state["mode"]}


@app.get("/models/active")
def active():
    return info()


@app.post("/models/kill-switch")
def kill_switch(body: dict):
    return save_mode("DISABLED", body.get("reason", "no reason given"))


@app.post("/models/activate")
def activate(body: dict):
    return save_mode(body.get("mode", "ACTIVE") if body.get("mode") in ("ACTIVE", "SHADOW") else "ACTIVE",
                     body.get("reason", "no reason given"))


@app.get("/model-card")
def model_card():
    return card
