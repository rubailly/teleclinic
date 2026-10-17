"""Synthetic PII suite with canary tokens (Section 4.7.2, layer 8). Runs at image build; any miss fails the build.
All values are synthetic and shaped like Rwandan identifiers so the recognizers are exercised realistically."""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from fastapi.testclient import TestClient  # noqa: E402

import app as gateway  # noqa: E402

client = TestClient(gateway.app)

# Canary values: if any of these ever shows up in a masked payload, something leaked
CANARIES = {
    "name": "Uwase Mukamana",
    "nationalId": "1199270012345012",
    "phone": "+250788123456",
    "patientId": "10000000-0000-4000-b000-000000000003",
    "district": "Gasabo",
    "email": "uwase.canary@example.org",
    "upi": "180514-0000-0001",
}


def anonymize(payload):
    r = client.post("/anonymize", json={"payload": payload})
    assert r.status_code == 200, r.text
    return r.json()


def test_fields_outside_the_allowlist_never_reach_the_model():
    out = anonymize({"caseId": "case-1", "ageBand": "5-11", "sex": "F", "symptoms": ["fever"], **CANARIES})
    dumped = json.dumps(out["maskedPayload"])
    for value in CANARIES.values():
        assert value not in dumped
    removed = {r["field"] for r in out["redactions"] if r["action"] == "REMOVED"}
    assert set(CANARIES) <= removed
    assert out["maskedPayload"]["symptoms"] == ["fever"]


def test_the_case_id_travels_only_as_a_token_the_core_can_resolve():
    out = anonymize({"caseId": "c-7f3a", "ageBand": "18-39", "sex": "M", "symptoms": ["cough"]})
    token = out["maskedPayload"]["caseToken"]
    assert token.startswith("tok_") and "c-7f3a" not in json.dumps(out["maskedPayload"])
    assert client.get(f"/vault/{token}").status_code == 401
    r = client.get(f"/vault/{token}", headers={"X-Vault-Key": os.environ["VAULT_KEY"]})
    assert r.status_code == 200 and r.json()["caseId"] == "c-7f3a"


FREE_TEXT_CASES = [
    ("My national ID is 1199270012345012 and my child is sick", "RW_NATIONAL_ID", "1199270012345012"),
    ("ID 1 1992 7 0012345 0 12, please call back", "RW_NATIONAL_ID", "0012345"),
    ("Call me on +250788123456 tonight", "RW_PHONE", "788123456"),
    ("my number is 0788 123 456", "RW_PHONE", "123 456"),
    ("reach me at 250-728-555-111", "RW_PHONE", "555"),
    ("Keza has had fever since Monday", "PERSON", "Keza"),
    ("Uwimana Marie booked this", "PERSON", "Uwimana"),
    ("We live in Gasabo near Kimironko market", "LOCATION", "Gasabo"),
    ("email uwase@example.org if needed", "EMAIL_ADDRESS", "uwase@example.org"),
    ("her UPI is 180514-0000-0001", "RW_UPI", "180514-0000-0001"),
]


def test_free_text_identifiers_are_detected_and_removed():
    missed = []
    for text, entity, secret in FREE_TEXT_CASES:
        out = anonymize({"caseId": "c", "ageBand": "18-39", "sex": "F", "symptoms": ["fever"], "freeText": text})
        masked = out["maskedPayload"]["freeText"]
        if secret in masked:
            missed.append((text, masked))
    recall = 1 - len(missed) / len(FREE_TEXT_CASES)
    assert recall == 1.0, f"recall {recall:.2f}, missed: {missed}"


def test_redaction_report_names_what_was_found():
    out = anonymize({"caseId": "c", "ageBand": "18-39", "sex": "F", "symptoms": ["fever"],
                     "freeText": "Uwase on +250788123456, ID 1199270012345012"})
    found = {r["entityType"] for r in out["redactions"] if r["field"] == "freeText"}
    assert {"PERSON", "RW_PHONE", "RW_NATIONAL_ID"} <= found


def test_generalisation_and_pregnancy_flag():
    out = anonymize({"caseId": "c", "ageBand": "40-64", "sex": "M", "pregnant": True, "symptoms": ["cough"],
                     "durationDays": 40, "vitals": {"temperatureC": 38.26, "bloodGroup": "O+"}})
    m = out["maskedPayload"]
    assert m["durationDays"] == 14
    assert m["vitals"] == {"temperatureC": 38.26}   # clinical values are not altered
    assert m["pregnant"] is False


def test_output_scan_flags_identifiers_in_a_model_result():
    assert client.post("/scan", json={"text": "urgency URGENT for +250788123456"}).json()["clean"] is False
    assert client.post("/scan", json={"text": "urgency URGENT, factors fast_breathing age_5_11"}).json()["clean"] is True
