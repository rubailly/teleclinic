"""Feature encoding shared by training and serving. Input is the anonymised payload only."""

SYMPTOMS = ["fever", "cough", "fast_breathing", "difficulty_breathing", "chest_pain", "convulsions", "unconscious",
            "severe_bleeding", "bleeding", "diarrhoea", "vomiting", "headache", "rash", "abdominal_pain"]
AGE_BANDS = ["0-4", "5-11", "12-17", "18-39", "40-64", "65+"]
URGENCY = ["ROUTINE", "SEMI_URGENT", "URGENT", "EMERGENCY"]

NAMES = (
    [f"symptom_{s}" for s in SYMPTOMS]
    + [f"age_{b.replace('-', '_').replace('+', '_plus')}" for b in AGE_BANDS]
    + ["sex_female", "pregnant", "duration_3_plus_days", "duration_7_plus_days",
       "temperature_38_plus", "temperature_39_5_plus", "spo2_below_94", "spo2_below_92",
       # a few clinically meaningful combinations, so a linear model can express them
       "child_under_12", "older_adult", "diarrhoea_and_vomiting_in_young_child",
       "fever_headache_rash", "cough_fever_older_adult", "abdominal_pain_in_pregnancy"]
)


def encode(p: dict) -> list[float]:
    symptoms = set(p.get("symptoms") or [])
    band = p.get("ageBand")
    vitals = p.get("vitals") or {}
    temp = vitals.get("temperatureC")
    spo2 = vitals.get("spo2")
    days = p.get("durationDays") or 0
    child = band in ("0-4", "5-11")
    older = band == "65+"
    pregnant = bool(p.get("pregnant"))
    x = [1.0 if s in symptoms else 0.0 for s in SYMPTOMS]
    x += [1.0 if band == b else 0.0 for b in AGE_BANDS]
    x += [
        1.0 if p.get("sex") == "F" else 0.0,
        1.0 if pregnant else 0.0,
        1.0 if days >= 3 else 0.0,
        1.0 if days >= 7 else 0.0,
        1.0 if temp is not None and temp >= 38 else 0.0,
        1.0 if temp is not None and temp >= 39.5 else 0.0,
        1.0 if spo2 is not None and spo2 < 94 else 0.0,
        1.0 if spo2 is not None and spo2 < 92 else 0.0,
        1.0 if child else 0.0,
        1.0 if older else 0.0,
        1.0 if {"diarrhoea", "vomiting"} <= symptoms and band == "0-4" else 0.0,
        1.0 if {"fever", "headache", "rash"} <= symptoms else 0.0,
        1.0 if {"cough", "fever"} <= symptoms and older else 0.0,
        1.0 if "abdominal_pain" in symptoms and pregnant else 0.0,
    ]
    return x
