"""eBuzima configuration (idempotent; safe to run again), as Administrator through the REST API.

eBuzima is one central ERPNext site for every health centre: each health centre is a tenant, an ERPNext Company, and
its staff only see their own company's documents (User Permissions). Patients are national and shared by all tenants.
This sets up what TeleClinic needs on that site: the setup wizard, the Healthcare domain, the tenants of the demo,
custom fields for TeleClinic's identifiers, a teleconsultation practitioner, the gateway's integration user (API key),
a nurse at Kimironko Health Centre, and a document webhook that tells the gateway a patient record changed.
"""
import json

from emr import ebuzima_admin, env, lightning_id, request, EBUZIMA, Frappe

# The same facilities as services/core/src/main/resources/db/demo/V1003__demo_facilities.sql (the health centres)
TENANTS = [("Kimironko Health Centre", "KIM", "0000-HC-0101"), ("Remera Health Centre", "REM", "0000-HC-0102")]
DEPARTMENT = "Teleconsultation"
PRACTITIONER = ("TeleClinic", "Teleconsultation")
SYNC_USER = "teleclinic-sync@ebuzima.local"
NURSE = ("nurse.kimironko@ebuzima.local", "Kimironko Health Centre")

CUSTOM_FIELDS = [
    ("Company", "custom_facility_code", "Facility registry code", "Data", {"unique": 1, "insert_after": "abbr"}),
    ("Patient", "custom_teleclinic_id", "TeleClinic ID", "Data", {"unique": 1, "read_only": 1, "insert_after": "uid"}),
    ("Patient", "custom_upi", "UPI", "Data", {"insert_after": "custom_teleclinic_id"}),
    ("Patient Encounter", "custom_teleclinic_consultation", "TeleClinic consultation", "Data",
     {"unique": 1, "read_only": 1, "insert_after": "practitioner_name"}),
    ("Patient Encounter", "custom_urgency", "Triage urgency", "Data", {"read_only": 1, "insert_after": "custom_teleclinic_consultation"}),
    ("Patient Encounter", "custom_channel", "Channel", "Data", {"read_only": 1, "insert_after": "custom_urgency"}),
]


def wait_ready():
    return request("GET", f"{EBUZIMA}/api/method/ping")[1]["message"] == "pong"


def setup_wizard():
    erp = ebuzima_admin()
    if erp.list("Company"):
        return "complete"
    args = {"language": "English", "country": "Rwanda", "timezone": "Africa/Kigali", "time_zone": "Africa/Kigali",
            "currency": "RWF", "full_name": "Administrator", "email": "admin@ebuzima.local",
            "company_name": "Ministry of Health (eBuzima)", "company_abbr": "MOH", "chart_of_accounts": "Standard",
            "fy_start_date": "2026-07-01", "fy_end_date": "2027-06-30", "setup_demo": 0}
    out = erp.call("POST", "method/frappe.desk.page.setup_wizard.setup_wizard.setup_complete", {"args": json.dumps(args)},
                   timeout=900)
    if (out or {}).get("message", {}).get("status") != "ok":
        raise RuntimeError(f"setup wizard: {out}")
    return "completed"


def healthcare_domain():
    erp = ebuzima_admin()
    settings = erp.find("Domain Settings", "Domain Settings")
    if any(d["domain"] == "Healthcare" for d in settings["active_domains"]):
        return "active"
    erp.update("Domain Settings", "Domain Settings", {"active_domains": settings["active_domains"] + [{"domain": "Healthcare"}]})
    return "activated"


def healthcare_settings():
    """Patients get registry-style ids (HLC-PAT-YYYY-nnnnn) rather than their names; no billing customer per patient."""
    erp = ebuzima_admin()
    erp.update("Healthcare Settings", "Healthcare Settings", {"patient_name_by": "Naming Series", "link_customer_to_patient": 0})
    return "patient ids by naming series"


def custom_fields():
    erp, made = ebuzima_admin(), 0
    for dt, fieldname, label, fieldtype, extra in CUSTOM_FIELDS:
        if erp.find("Custom Field", f"{dt}-{fieldname}"):
            continue
        erp.create("Custom Field", {"dt": dt, "fieldname": fieldname, "label": label, "fieldtype": fieldtype, **extra})
        made += 1
    return f"{len(CUSTOM_FIELDS)} fields, {made} created"


def tenants():
    erp, made = ebuzima_admin(), 0
    for name, abbr, code in TENANTS:
        if not erp.find("Company", name):
            erp.create("Company", {"company_name": name, "abbr": abbr, "custom_facility_code": code, "country": "Rwanda",
                                   "default_currency": "RWF"})
            made += 1
    return f"{len(TENANTS)} health centres, {made} created"


def practitioner():
    erp = ebuzima_admin()
    if not erp.find("Medical Department", DEPARTMENT):
        erp.create("Medical Department", {"department": DEPARTMENT})
    found = erp.list("Healthcare Practitioner", [["first_name", "=", PRACTITIONER[0]], ["last_name", "=", PRACTITIONER[1]]])
    if found:
        return found[0]["name"] + ", exists"
    return erp.create("Healthcare Practitioner", {"first_name": PRACTITIONER[0], "last_name": PRACTITIONER[1],
                                                  "department": DEPARTMENT})["name"] + ", created"


def ensure_user(erp, email, first, last, roles, extra):
    body = {"roles": [{"role": r} for r in roles], **extra}
    if erp.find("User", email):
        erp.update("User", email, body)
        return "exists"
    erp.create("User", {"email": email, "first_name": first, "last_name": last, "user_type": "System User",
                        "send_welcome_email": 0, **body})
    return "created"


def integration_user():
    """The gateway's user: an API key from .env, so the OpenFn credential never changes; Frappe logs it as the author."""
    e = env()
    state = ensure_user(ebuzima_admin(), SYNC_USER, "TeleClinic", "Sync gateway", ["Physician", "Healthcare Administrator"],
                        {"api_key": e["EBUZIMA_API_KEY"], "api_secret": e["EBUZIMA_API_SECRET"]})
    who = Frappe(token=f"{e['EBUZIMA_API_KEY']}:{e['EBUZIMA_API_SECRET']}").call("GET", "method/frappe.auth.get_logged_user")
    if who["message"] != SYNC_USER:
        raise RuntimeError("the API key does not authenticate")
    return state + ", API key verified"


def nurse():
    """A nurse who works at one health centre: User Permission on her company, so eBuzima shows her only that tenant."""
    e, erp = env(), ebuzima_admin()
    email, company = NURSE
    state = ensure_user(erp, email, "Demo", "Nurse (Kimironko)", ["Desk User", "Nursing User", "Physician"], {"new_password": e["EBUZIMA_NURSE_PASSWORD"]})
    if not erp.list("User Permission", [["user", "=", email], ["allow", "=", "Company"]]):
        erp.create("User Permission", {"user": email, "allow": "Company", "for_value": company, "apply_to_all_doctypes": 1})
    return f"{email}, {state}, tenant {company}"


def webhook():
    """A changed patient record kicks the gateway's inbound workflow, which then reads every change since its cursor."""
    erp = ebuzima_admin()
    url = f"http://openfn:4000/i/{lightning_id('ebuzima-inbound:trigger:kick')}"
    name = "TeleClinic: patient changed"
    body = {"webhook_doctype": "Patient", "webhook_docevent": "on_update", "request_url": url, "request_method": "POST",
            "request_structure": "JSON", "webhook_json": '{"doctype": "Patient", "name": "{{ doc.name }}", "modified": "{{ doc.modified }}"}',
            "enabled": 1}
    existing = erp.list("Webhook", [["webhook_doctype", "=", "Patient"], ["request_url", "=", url]])
    if existing:
        erp.update("Webhook", existing[0]["name"], body)
        return "exists, Patient on_update -> gateway"
    erp.create("Webhook", {"__newname": name, **body})
    return "created, Patient on_update -> gateway"


STEPS = [("eBuzima setup wizard", setup_wizard), ("eBuzima Healthcare domain", healthcare_domain),
         ("eBuzima Healthcare settings", healthcare_settings), ("eBuzima custom fields", custom_fields), ("eBuzima tenants", tenants),
         ("eBuzima practitioner", practitioner), ("eBuzima integration user", integration_user),
         ("eBuzima nurse", nurse), ("eBuzima webhook", webhook)]
