#!/usr/bin/env bash
# Scene 5b, part 1: access control you can verify. A clinical read needs a declared purpose and the patient's
# consent; refusals are recorded; Eric sees who read his record and why; an administrator's direct query in
# psql lands in the same trail through pgAudit.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
source scripts/lib/cluster.sh
ERIC=10000000-0000-4000-b000-000000000002
eric=$(mktemp); doc=$(mktemp); adm=$(mktemp)
trap 'rm -f "$eric" "$doc" "$adm" "$eric.status" "$doc.status" "$adm.status"' EXIT
login patient2 "$eric"; login clinician1 "$doc"; login admin1 "$adm"
issue() { json 'd["issue"][0]["code"] + ": " + d["issue"][0]["diagnostics"]'; }

step "1. a clinical read without X-Purpose-Of-Use is refused"
r=$(api "$doc" GET "/fhir/Patient/$ERIC")
[ "$(status "$doc")" = 403 ] || fail "expected 403, got $(status "$doc")"
note "HTTP 403 $(issue <<<"$r")"

step "2. a read to treat a patient who has not consented is refused"
api "$eric" DELETE "/api/patients/$ERIC/consents/active" >/dev/null
r=$(api "$doc" GET "/fhir/Patient/$ERIC" "" -H "X-Purpose-Of-Use: TREAT")
[ "$(status "$doc")" = 403 ] || fail "expected 403, got $(status "$doc")"
note "HTTP 403 $(issue <<<"$r")"

step "3. Eric consents; Dr Mukamana reads his record to treat him"
api "$eric" POST "/api/patients/$ERIC/consents" >/dev/null
r=$(api "$doc" GET "/fhir/Patient/$ERIC" "" -H "X-Purpose-Of-Use: TREAT")
[ "$(status "$doc")" = 200 ] || fail "expected 200, got $(status "$doc")"
note "HTTP 200 $(json 'd["resourceType"] + " " + d["name"][0]["given"][0] + " " + d["name"][0]["family"] + ", identifiers: " + ", ".join(i["system"].rsplit("/",1)[1] for i in d["identifier"])' <<<"$r")"

step "4. Eric opens \"Who accessed my record\""
log=$(api "$eric" GET "/api/me/patients/$ERIC/access-log")
python3 -c '
import json, sys
for e in [x for x in json.load(sys.stdin)["entries"] if not x["you"]][:4]:
    print("   %-15s %-10s %-7s %-17s %-8s %s" % (e["who"], e["role"], e["action"], e["resource"], e["outcome"], e["purpose"]))' <<<"$log"
python3 -c '
import json, sys
es = json.load(sys.stdin)["entries"]
assert any(e["role"] == "clinician" and e["purpose"] == "TREAT" and e["outcome"] == "SUCCESS" and e["action"] == "READ" for e in es)
assert any(e["outcome"] == "DENIED" for e in es)' <<<"$log" || fail "the access log does not show the clinician's read and the refusals"

step "5. an administrator reads the patient table directly in psql (bypassing the application)"
n=$(leader | sed 's/pg-//')
since=$(date -u +%Y-%m-%dT%H:%M:%S)
pg_local "$n" "SELECT given_name, national_id FROM patient_index.patient WHERE id = '$ERIC'" >/dev/null
note "psql -U postgres on pg-$n at $(date +%T)"
found=""
for i in $(seq 1 30); do
  found=$(api "$adm" GET "/api/audit/events?source=DATABASE&limit=50" | python3 -c '
import json, sys
since = sys.argv[1]
for e in json.load(sys.stdin)["entries"]:
    if e["actor"] == "postgres" and "patient_index.patient" in (e["resource_id"] or "") and e["recorded_at"][:19] >= since:
        print("entry %s: %s by role %s on %s, %s" % (e["seq"], e["action"], e["actor"], e["detail"]["node"], e["detail"]["statement"][:60])); break' "$since")
  [ -n "$found" ] && break; sleep 1
done
[ -n "$found" ] || fail "the direct query did not reach the audit trail"
note "in the trail after ${i} s: $found"

step "6. the chain is intact"
v=$(api "$adm" GET /api/audit/verify)
note "$(json 'd["status"] + ", " + str(d["checked"]) + " entries recomputed"' <<<"$v")"
[ "$(json 'd["status"]' <<<"$v")" = INTACT ] || fail "chain is not intact"

pass "purpose and consent enforced, refusals recorded, Eric sees who read his record, DBA access captured by pgAudit"
