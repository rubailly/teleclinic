#!/usr/bin/env python3
"""The facilities' own patients (synthetic): the district hospital's in OpenMRS, for identity matching, and the health
centres' in eBuzima.

  seed.py          adds them if missing
  seed.py --reset  back to the demo's starting point: every patient purged (OpenMRS: observations, encounters, then the
                   patient, so unique identifiers are free again; eBuzima: encounters cancelled and deleted, the
                   patient's contact, then the patient), then the seed added. Seconds, no restart.
"""
import sys

from emr import META, OPENMRS, ebuzima_admin, env, openmrs, request

# The TeleClinic side is services/core/src/main/resources/db/demo/V1002__demo_identity.sql
FACILITY = [
    # Design 4.5.3 worked example: names in the other order, day and month swapped, no national ID here
    {"given": "Uwimana", "family": "Marie", "gender": "F", "birthdate": "1990-03-04", "identifiers": {}},
    # Same name and birth date as a TeleClinic patient, different national ID: a hard block
    {"given": "Jean", "family": "Habimana", "gender": "M", "birthdate": "1985-06-10",
     "identifiers": {"nationalIdType": "0000198500000077"}},
    # Same UPI as a TeleClinic patient: linked automatically (two strong identifiers agree)
    {"given": "Aline", "family": "Mukeshimana", "gender": "F", "birthdate": "2016-09-21",
     "identifiers": {"upiType": "160921-0000-0042"}},
    # Nobody TeleClinic knows
    {"given": "Pacifique", "family": "Nkurunziza", "gender": "M", "birthdate": "1975-11-02",
     "identifiers": {}, "phone": "+250788000310"},
]
# Registered at the health centres; nobody TeleClinic knows
HEALTH_CENTRES = [
    {"first_name": "Claudine", "last_name": "Mukamana", "sex": "Female", "dob": "1979-02-11", "mobile": "+250788000401"},
    {"first_name": "Emmanuel", "last_name": "Ndayisaba", "sex": "Male", "dob": "1966-08-30", "mobile": "+250788000402"},
]
FACILITY_ID_TYPE = "8d79403a-c2cc-11de-8d13-0010c6dffd0f"   # the distribution's "Old Identification Number" (no check digit)


def patients():
    """Every (non-voided) patient, through the FHIR API, which lists without a search term."""
    e = env()
    out, url = [], f"{OPENMRS}/ws/fhir2/R4/Patient?_count=100"
    while url:
        bundle = request("GET", url, headers={"Accept": "application/fhir+json"}, auth=("admin", e["OPENMRS_ADMIN_PASSWORD"]))[1]
        out.extend(entry["resource"]["id"] for entry in bundle.get("entry", []))
        url = next((l["url"] for l in bundle.get("link", []) if l["relation"] == "next"), None)
    return out


def purge(uuid):
    for o in openmrs("GET", f"obs?patient={uuid}&v=custom:(uuid)&limit=100")["results"]:
        openmrs("DELETE", f"obs/{o['uuid']}?purge=true")
    for e in openmrs("GET", f"encounter?patient={uuid}&v=custom:(uuid)&limit=100")["results"]:
        openmrs("DELETE", f"encounter/{e['uuid']}?purge=true")
    openmrs("DELETE", f"patient/{uuid}?purge=true")


def add(p, n):
    identifiers = [{"identifier": f"FAC-{1000 + n}", "identifierType": FACILITY_ID_TYPE, "location": META["location"],
                    "preferred": True}]
    for key, value in p["identifiers"].items():
        identifiers.append({"identifier": value, "identifierType": META[key], "location": META["location"]})
    attributes = [{"attributeType": META["phoneAttribute"], "value": p["phone"]}] if p.get("phone") else []
    openmrs("POST", "patient", {"person": {"names": [{"givenName": p["given"], "familyName": p["family"]}],
                                           "gender": p["gender"], "birthdate": p["birthdate"], "attributes": attributes},
                                "identifiers": identifiers})


def ebuzima(reset):
    erp = ebuzima_admin()
    existing = erp.list("Patient")
    if reset:
        for enc in erp.list("Patient Encounter", fields=("name", "docstatus")):
            if enc["docstatus"] == 1:
                erp.call("POST", "method/frappe.client.cancel", {"doctype": "Patient Encounter", "name": enc["name"]})
            erp.delete("Patient Encounter", enc["name"])
        for c in erp.list("Contact", [["Dynamic Link", "link_doctype", "=", "Patient"]]):
            erp.delete("Contact", c["name"])
        for p in existing:
            erp.delete("Patient", p["name"])
        existing = []
    if existing:
        return f"eBuzima patients: {len(existing)} present, seed left as is"
    for p in HEALTH_CENTRES:
        erp.create("Patient", p)
    return f"eBuzima patients: {'purged and ' if reset else ''}seeded {len(HEALTH_CENTRES)}"


def main():
    reset = "--reset" in sys.argv
    if "--openmrs-only" not in sys.argv:
        print(ebuzima(reset))
    if "--ebuzima-only" in sys.argv:
        return
    existing = patients()
    if reset:
        for u in existing:
            purge(u)
        existing = []
    if existing:
        print(f"OpenMRS patients: {len(existing)} present, seed left as is")
        return
    for n, p in enumerate(FACILITY):
        add(p, n + 1)
    print(f"OpenMRS patients: {'purged and ' if reset else ''}seeded {len(FACILITY)}")


if __name__ == "__main__":
    main()
