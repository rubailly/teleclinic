"""Trains the demo triage model on SYNTHETIC data and writes model.onnx, explain.json and model_card.json.

The labels come from an illustrative clinician-style scoring function, not from real patients. It covers some
patterns the red-flag rules do not (dehydration in a young child, fever with headache and rash), so the model has
something to add. Real training data, labels and thresholds belong to clinical leadership (assumption A7).
"""

import json
import random
from datetime import date

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, recall_score
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

from features import AGE_BANDS, NAMES, SYMPTOMS, URGENCY, encode

VERSION = "tc-triage-2026.10-1"
SEED = 20261017
random.seed(SEED)
np.random.seed(SEED)


def synthetic_case() -> dict:
    band = random.choice(AGE_BANDS)
    sex = random.choice(["F", "M"])
    pregnant = sex == "F" and band in ("18-39", "40-64") and random.random() < 0.15
    symptoms = [s for s in SYMPTOMS if random.random() < (0.04 if s in ("unconscious", "convulsions", "severe_bleeding") else 0.16)]
    if not symptoms:
        symptoms = [random.choice(SYMPTOMS[:2] + SYMPTOMS[9:])]
    temp = round(random.gauss(38.6, 0.8), 1) if "fever" in symptoms else round(random.gauss(36.9, 0.3), 1)
    spo2 = random.choice([None, None, 98, 97, 96, 95, 93, 91])
    return {"ageBand": band, "sex": sex, "pregnant": pregnant, "symptoms": symptoms,
            "durationDays": random.randint(1, 14), "vitals": {"temperatureC": temp, "spo2": spo2}}


def label(c: dict) -> int:
    s, band, v = set(c["symptoms"]), c["ageBand"], c["vitals"]
    child, older, days = band in ("0-4", "5-11"), band == "65+", c["durationDays"]
    temp, spo2 = v.get("temperatureC") or 0, v.get("spo2")
    if s & {"unconscious", "convulsions", "severe_bleeding", "difficulty_breathing"} or (spo2 and spo2 < 92) \
            or ("chest_pain" in s and band in ("40-64", "65+")) or ("bleeding" in s and c["pregnant"]):
        return 3
    if ("fast_breathing" in s and child) or temp >= 39.5 or ("fever" in s and days >= 3) \
            or ({"diarrhoea", "vomiting"} <= s and band == "0-4") or {"fever", "headache", "rash"} <= s \
            or ({"cough", "fever"} <= s and older) or "chest_pain" in s \
            or ("abdominal_pain" in s and c["pregnant"]) or (spo2 and spo2 < 94):
        return 2
    if "fever" in s or s & {"diarrhoea", "vomiting"} or ("cough" in s and days >= 7) or ("headache" in s and days >= 3):
        return 1
    return 0


cases = [synthetic_case() for _ in range(24000)]
X = np.array([encode(c) for c in cases], dtype=np.float32)
y = np.array([label(c) for c in cases])
noise = np.random.rand(len(y)) < 0.03          # label noise, as real labels are never perfect
y[noise] = np.clip(y[noise] + np.random.choice([-1, 1], noise.sum()), 0, 3)

split = int(len(y) * 0.8)
model = LogisticRegression(max_iter=3000, C=2.0, class_weight="balanced")
model.fit(X[:split], y[:split])
pred = model.predict(X[split:])
test_y, test_cases = y[split:], cases[split:]


def under_triage(truth, predicted):
    serious = truth >= 2
    return float(((predicted < truth) & serious).sum() / max(serious.sum(), 1))


def stratum(name, keep):
    idx = np.array([i for i, c in enumerate(test_cases) if keep(c)])
    t, p = test_y[idx], pred[idx]
    return name, {"n": int(len(idx)), "recallUrgentOrHigher": round(float(((p >= 2) & (t >= 2)).sum() / max((t >= 2).sum(), 1)), 3)}


validation = {
    "accuracy": round(float(accuracy_score(test_y, pred)), 3),
    "macroRecall": round(float(recall_score(test_y, pred, average="macro")), 3),
    "underTriageRate": round(under_triage(test_y, pred), 3),
    "testCases": int(len(test_y)),
}
strata = dict([
    stratum("children 0-11", lambda c: c["ageBand"] in ("0-4", "5-11")),
    stratum("adults 12-64", lambda c: c["ageBand"] in ("12-17", "18-39", "40-64")),
    stratum("older adults 65+", lambda c: c["ageBand"] == "65+"),
    stratum("female", lambda c: c["sex"] == "F"),
    stratum("male", lambda c: c["sex"] == "M"),
])

# Release gates (Section 4.7.4): the build fails rather than ship a model below them
assert validation["underTriageRate"] <= 0.10, validation
for name, s in strata.items():
    assert s["recallUrgentOrHigher"] >= 0.85, (name, s)

onnx_model = convert_sklearn(model, initial_types=[("features", FloatTensorType([None, len(NAMES)]))],
                             options={id(model): {"zipmap": False}}, target_opset=17)
with open("model.onnx", "wb") as f:
    f.write(onnx_model.SerializeToString())

with open("explain.json", "w") as f:
    json.dump({"features": NAMES, "classes": URGENCY, "coef": model.coef_.round(4).tolist()}, f)

card = {
    "modelVersion": VERSION,
    "trainedOn": f"synthetic cases generated {date.today().isoformat()}, seed {SEED}; NOT real patient data",
    "intendedUse": "Second opinion on triage urgency for a clinician. Escalate-only; never shown to patients.",
    "inputs": "Anonymised features only: age band, sex, pregnancy, coded symptoms, duration, temperature, SpO2",
    "algorithm": "Multinomial logistic regression, exported to ONNX, served with ONNX Runtime",
    "validation": validation,
    "stratifiedValidation": strata,
    "releaseGates": {"underTriageRate": "<= 0.10", "recallUrgentOrHigherPerGroup": ">= 0.85"},
    "limitations": ["Labels are illustrative, from a synthetic scoring function",
                    "Not validated on Rwandan clinical data", "Kinyarwanda free text is not used as input"],
}
with open("model_card.json", "w") as f:
    json.dump(card, f, indent=2)
print(json.dumps({"version": VERSION, **validation, "strata": strata}))
