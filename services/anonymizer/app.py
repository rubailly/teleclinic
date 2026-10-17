"""Anonymization gateway: the only way data reaches the AI model (Section 4.7.2).

Layers, each independent so one failure does not expose identity:
 1. Field allowlist: only what the model needs survives; everything else is removed.
 2. Structured masking: identifiers that must travel (the case id) become random tokens.
 3. Free-text detection: Presidio with Rwandan recognizers; detected entities are replaced.
 4. Pseudonym vault: tokens map back to cases here only, and only the core may read the map.
 5. Generalisation: values that could single someone out are coarsened.
 6. Output check: /scan lets the model service reject a result that contains an identifier.
Payloads are never logged.
"""

import logging
import os
import secrets
import sqlite3
import threading
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from presidio_anonymizer import AnonymizerEngine
from presidio_anonymizer.entities import OperatorConfig

from recognizers import ENTITIES, build_analyzer

VAULT_KEY = os.environ["VAULT_KEY"]
VAULT_PATH = os.environ.get("VAULT_PATH", "/data/vault.db")

# Layer 1: the model sees these fields and nothing else
ALLOWED = {"ageBand", "sex", "pregnant", "symptoms", "durationDays", "vitals", "rulesUrgency", "rulesetVersion",
           "channel", "freeText"}
# Layer 2: these travel as tokens
TOKENISED = {"caseId"}
ALLOWED_VITALS = {"temperatureC", "heartRate", "respiratoryRate", "spo2"}

log = logging.getLogger("anonymizer")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

app = FastAPI(title="TeleClinic anonymization gateway")
analyzer = build_analyzer()
anonymizer = AnonymizerEngine()
_db_lock = threading.Lock()


def _db() -> sqlite3.Connection:
    conn = sqlite3.connect(VAULT_PATH, check_same_thread=False)
    conn.execute("CREATE TABLE IF NOT EXISTS vault (token TEXT PRIMARY KEY, case_id TEXT NOT NULL, "
                 "created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
    return conn


db = _db()


def tokenise(value: str) -> str:
    token = "tok_" + secrets.token_hex(12)
    with _db_lock:
        db.execute("INSERT INTO vault (token, case_id) VALUES (?, ?)", (token, value))
        db.commit()
    return token


def redact_text(text: str) -> tuple[str, list[dict]]:
    results = analyzer.analyze(text=text, language="en", entities=ENTITIES)
    masked = anonymizer.anonymize(
        text=text, analyzer_results=results,
        operators={"DEFAULT": OperatorConfig("replace", {"new_value": "<REDACTED>"}),
                   **{e: OperatorConfig("replace", {"new_value": f"<{e}>"}) for e in ENTITIES}},
    ).text
    return masked, [{"entityType": r.entity_type, "score": round(r.score, 2)} for r in results]


@app.get("/health")
def health():
    return {"status": "ok", "service": "anonymizer"}


@app.post("/anonymize")
def anonymize(body: dict[str, Any]):
    payload = body.get("payload")
    if not isinstance(payload, dict):
        raise HTTPException(422, "payload must be an object")
    masked: dict[str, Any] = {}
    redactions: list[dict] = []
    token_map_id = None
    for field, value in payload.items():
        if field in TOKENISED and value is not None:
            token_map_id = tokenise(str(value))
            masked["caseToken"] = token_map_id
            redactions.append({"field": field, "entityType": "CASE_ID", "action": "TOKENISED"})
        elif field not in ALLOWED:
            redactions.append({"field": field, "entityType": "NOT_ALLOWLISTED", "action": "REMOVED"})
        elif field == "freeText":
            if value:
                text, found = redact_text(str(value))
                masked["freeText"] = text
                for f in found:
                    redactions.append({"field": "freeText", "entityType": f["entityType"], "action": "REMOVED"})
        elif field == "vitals" and isinstance(value, dict):
            vitals = {}
            for k, v in value.items():
                if k not in ALLOWED_VITALS:
                    redactions.append({"field": f"vitals.{k}", "entityType": "NOT_ALLOWLISTED", "action": "REMOVED"})
                # Vital signs are kept exactly: they do not identify anyone, and rounding them would move
                # clinical thresholds (39.4 rounded to 39.5 crosses the high-fever line)
                elif v is not None:
                    vitals[k] = v
            masked["vitals"] = vitals
        elif field == "durationDays" and isinstance(value, int) and value > 14:
            masked["durationDays"] = 14                      # layer 5: long durations are not distinctive
            redactions.append({"field": field, "entityType": "QUASI_IDENTIFIER", "action": "GENERALISED"})
        elif field == "pregnant" and payload.get("sex") != "F":
            masked["pregnant"] = False
        else:
            masked[field] = value
    log.info("anonymized one payload: %d fields in, %d out, %d redactions", len(payload), len(masked), len(redactions))
    return {"maskedPayload": masked, "redactions": redactions, "tokenMapId": token_map_id}


@app.post("/scan")
def scan(body: dict[str, Any]):
    """Output check: does this text contain anything that looks like an identifier?"""
    _, found = redact_text(str(body.get("text", "")))
    return {"findings": found, "clean": not found}


@app.get("/vault/{token}")
def resolve(token: str, x_vault_key: str = Header(default="")):
    """Re-identification for the core only (it alone holds the vault key)."""
    if not secrets.compare_digest(x_vault_key, VAULT_KEY):
        raise HTTPException(401, "vault key required")
    with _db_lock:
        row = db.execute("SELECT case_id FROM vault WHERE token = ?", (token,)).fetchone()
    if not row:
        raise HTTPException(404, "unknown token")
    return {"token": token, "caseId": row[0]}
