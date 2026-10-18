#!/usr/bin/env python3
"""Configures both EMRs and the sync gateway (idempotent; safe to run again).

eBuzima: see ebuzima.py (setup wizard, tenants, custom fields, integration user, nurse, webhook).
OpenMRS: TeleClinic's metadata is Initializer config (config/openmrs/initializer); this adds the integration user the
gateway writes as, so OpenMRS's own audit shows the source. Then both EMRs' own patients are seeded.
Lightning: the admin user and API token, the project, its two credentials and the workflows in sync/project.yaml, through
the provisioning API with ids derived from names, then one run at a time per workflow. Prints names, never secrets.
"""
import json
import os
import re
import subprocess

import yaml

import ebuzima
from emr import LIGHTNING, OPENMRS, ROOT, env, lightning_id, lightning_rpc, openmrs, request, save_env, wait

SYNC_USER = "teleclinic-sync"
# The core runs behind HAProxy's core pool on the interop network; the gateway reaches the EMRs on the EMR network
CORE_URL = "http://haproxy:9100"
OPENMRS_INTERNAL = "http://openmrs-backend:8080/openmrs"
EBUZIMA_INTERNAL = "http://ebuzima:8080"


def step(name, fn):
    print(f"  {name:<34} {fn()}")


def ensure_sync_user():
    e = env()
    found = openmrs("GET", f"user?username={SYNC_USER}&v=custom:(uuid,username)")["results"]
    if any(u["username"] == SYNC_USER for u in found):
        return "exists"
    roles = openmrs("GET", "role?v=custom:(uuid,display)")["results"]
    full = next(r["uuid"] for r in roles if r["display"] == "Privilege Level: Full")
    openmrs("POST", "user", {"username": SYNC_USER, "password": e["OPENMRS_SYNC_PASSWORD"],
                             "person": {"names": [{"givenName": "TeleClinic", "familyName": "Sync gateway"}], "gender": "M"},
                             "roles": [full]})
    return "created"


def api(method, path, body=None, ok=(200, 201, 204)):
    return request(method, f"{LIGHTNING}{path}", body, headers={"Authorization": f"Bearer {env().get('OPENFN_API_TOKEN', '')}"},
                   ok=ok, timeout=120)


def token_valid():
    if not env().get("OPENFN_API_TOKEN"):
        return False
    status, _ = api("GET", "/api/projects", ok=(200, 401, 403))
    return status == 200


def ensure_lightning_user():
    if token_valid():
        return "exists, token valid"
    e = env()
    q = json.dumps  # JSON strings are valid Elixir strings
    out = lightning_rpc(f"""
        alias Lightning.{{Repo, Accounts}}
        email = {q("admin@teleclinic.local")}
        user =
          case Repo.get_by(Accounts.User, email: email) do
            nil ->
              Lightning.Setup.setup_user(%{{first_name: "TeleClinic", last_name: "Admin", email: email,
                password: {q(e["OPENFN_ADMIN_PASSWORD"])}, role: :superuser}})
              Repo.get_by!(Accounts.User, email: email)
            user -> user
          end
        IO.puts("token:" <> Accounts.generate_api_token(user))""")
    m = re.search(r"^token:(\S+)$", out, re.M)
    if not m:
        raise RuntimeError("could not create the Lightning user or token: " + out.strip()[-400:])
    save_env("OPENFN_API_TOKEN", m.group(1))
    if not token_valid():
        raise RuntimeError("the issued token is not accepted")
    return "token issued, saved to .env"


PROJECT = yaml.safe_load(open(os.path.join(ROOT, "sync", "project.yaml")))
PROJECT_ID = lightning_id("project:" + PROJECT["name"])


def provisioned():
    status, data = api("GET", f"/api/provision/{PROJECT_ID}", ok=(200, 404))
    return data["data"] if status == 200 else None


def ensure_project():
    if provisioned():
        return "exists"
    api("POST", "/api/provision", {"id": PROJECT_ID, "name": PROJECT["name"], "description": PROJECT["description"],
                                   "workflows": []})
    return "created"


def credentials():
    e = env()
    return {
        "TeleClinic core": {"baseUrl": CORE_URL, "syncKey": e["SYNC_KEY"]},
        "OpenMRS": {"baseUrl": OPENMRS_INTERNAL, "username": SYNC_USER, "password": e["OPENMRS_SYNC_PASSWORD"]},
        "eBuzima": {"baseUrl": EBUZIMA_INTERNAL, "apiKey": e["EBUZIMA_API_KEY"], "apiSecret": e["EBUZIMA_API_SECRET"]},
    }


def ensure_credentials():
    _, data = api("GET", "/api/credentials")
    existing = {c["name"] for c in data["credentials"]}
    created = []
    for name, body in credentials().items():
        if name in existing:
            continue
        api("POST", "/api/credentials", {"name": name, "schema": "raw", "credential_bodies": [{"name": "main", "body": body}],
                                         "project_credentials": [{"project_id": PROJECT_ID}]})
        created.append(name)
    return "created " + ", ".join(created) if created else f"{len(existing)} exist"


def webhook_url(workflow_key, trigger_key="kick"):
    return f"http://openfn:4000/i/{lightning_id(f'{workflow_key}:trigger:{trigger_key}')}"


def deploy_workflows():
    current = provisioned()
    project_credentials = {pc["name"]: pc["id"] for pc in current.get("project_credentials", [])}
    workflows = []
    for key, wf in PROJECT["workflows"].items():
        wid = lambda part, k=key: lightning_id(f"{k}:{part}")
        # The workflow's EMR, for the steps shared by every EMR's workflows
        header = f"const SYSTEM = {json.dumps(wf['system'])};   // set by scripts/emr/bootstrap.py from sync/project.yaml\n\n"
        jobs = [{"id": wid(f"job:{jk}"), "name": j["name"], "adaptor": j["adaptor"],
                 "body": header + open(os.path.join(ROOT, "sync", j["body"])).read(),
                 "project_credential_id": project_credentials[j["credential"]]} for jk, j in wf["jobs"].items()]
        triggers = [{"id": wid(f"trigger:{tk}"), "type": t["type"], "enabled": True,
                     **({"cron_expression": t["cron_expression"]} if t["type"] == "cron" else {})}
                    for tk, t in wf["triggers"].items()]
        edges = []
        for e in wf["edges"]:
            source, target = [s.strip() for s in e["from_to"].split("->")]
            edge = {"id": wid(f"edge:{source}->{target}"), "target_job_id": wid(f"job:{target}"), "enabled": True,
                    # Lightning: an edge from a trigger is "always" (or a JS expression); between jobs, "on success" by default
                    "condition_type": e.get("condition", "always" if source in wf["triggers"] else "on_job_success")}
            edge.update({"source_trigger_id": wid(f"trigger:{source}")} if source in wf["triggers"]
                        else {"source_job_id": wid(f"job:{source}")})
            if e.get("condition") == "js_expression":
                edge.update({"condition_expression": e["expression"], "condition_label": e["label"]})
            edges.append(edge)
        workflows.append({"id": wid("workflow"), "name": wf["name"], "jobs": jobs, "triggers": triggers, "edges": edges})
    api("POST", "/api/provision", {"id": PROJECT_ID, "name": PROJECT["name"], "description": PROJECT["description"],
                                   "workflows": workflows})
    ids = ", ".join(json.dumps(w["id"]) for w in workflows)
    out = lightning_rpc(f"""
        for id <- [{ids}] do
          Lightning.Workflows.Workflow |> Lightning.Repo.get!(id) |> Ecto.Changeset.change(concurrency: 1) |> Lightning.Repo.update!()
        end
        IO.puts("updated:{len(workflows)}")""")
    if f"updated:{len(workflows)}" not in out:
        raise RuntimeError("could not set one run at a time: " + out.strip()[-300:])
    return f"{len(workflows)} workflows, {sum(len(w['jobs']) for w in workflows)} steps, one run at a time"


def check_hooks():
    expected = {"OPENMRS_OUTBOUND_HOOK": webhook_url("outbound"), "OPENMRS_INBOUND_HOOK": webhook_url("inbound"),
                "EBUZIMA_OUTBOUND_HOOK": webhook_url("ebuzima-outbound"), "EBUZIMA_INBOUND_HOOK": webhook_url("ebuzima-inbound")}
    compose = open(os.path.join(ROOT, "compose", "apps.yml")).read()
    missing = [k for k, v in expected.items() if f"{k}: {v}" not in compose]
    if missing:
        raise RuntimeError(f"compose/apps.yml does not point at the gateway's webhooks: {expected}")
    return "core points at all four webhook triggers"


def main():
    print("EMRs and sync gateway")
    wait("eBuzima REST API", ebuzima.wait_ready)
    wait("OpenMRS REST API", lambda: openmrs("GET", "session")["authenticated"])
    wait("OpenFn Lightning", lambda: request("GET", f"{LIGHTNING}/health_check")[0] == 200)
    for name, fn in ebuzima.STEPS:
        step(name, fn)
    step("OpenMRS integration user", ensure_sync_user)
    step("Facility patients (both EMRs)", lambda: subprocess.run(["python3", os.path.join(ROOT, "scripts/emr/seed.py")],
                                                      capture_output=True, text=True, check=True).stdout.strip().replace("\n", "; "))
    step("Lightning admin and API token", ensure_lightning_user)
    step("Lightning project", ensure_project)
    step("Lightning credentials", ensure_credentials)
    step("Workflows", deploy_workflows)
    step("Core webhooks", check_hooks)
    print(f"  Lightning: {LIGHTNING} (admin@teleclinic.local, password OPENFN_ADMIN_PASSWORD in .env)")
    print(f"  OpenMRS:   http://localhost:9880/openmrs/spa (admin, password OPENMRS_ADMIN_PASSWORD in .env)")
    print(f"  eBuzima:   http://localhost:9580 (Administrator, password EBUZIMA_ADMIN_PASSWORD in .env; "
          f"{ebuzima.NURSE[0]}, password EBUZIMA_NURSE_PASSWORD)")


if __name__ == "__main__":
    main()
