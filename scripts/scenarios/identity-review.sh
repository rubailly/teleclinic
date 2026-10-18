#!/usr/bin/env bash
# Scene 3c: identity, safely. Link, never merge. The worked example (design 4.5.3) goes to a clerk; the link offers the
# national ID to OpenMRS through the sync gateway; the unlink takes it back. A pair with different national IDs is never
# offered; a pair sharing a UPI was linked automatically. The audit trail shows both decisions.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
source scripts/lib/cluster.sh
source scripts/lib/sync.sh
require_emr
MARIE=10000000-0000-4000-b000-000000000011
clerk=$(mktemp); adm=$(mktemp); trap 'rm -f "$clerk" "$adm" "$clerk.status" "$adm.status"' EXIT
login clerk1 "$clerk"; login admin1 "$adm"
emr_nid() { omrs "$OPENMRS_REST/patient/$1/identifier?v=custom:(display)" | json '", ".join(i["display"] for i in d["results"] if "National" in i["display"]) or "none"'; }

step "1. the facility's records have been read and scored (inbound poll)"
if [ "$(sq "SELECT count(*) FROM matching.candidate")" = 0 ]; then kick "$KICK_INBOUND"; fi
wait_for 90 "SELECT count(*) FROM matching.candidate WHERE status IN ('PENDING','LINKED') AND band = 'REVIEW'" \
  || fail "the worked example is not in the review queue (is the EMR seeded? make emr-reset)"
sq "SELECT '   ' || outcome || '  ' || coalesce(teleclinic_id, '') FROM sync.event WHERE direction = 'INBOUND'
    AND outcome IN ('REVIEW','BLOCKED','AUTO_LINKED','NO_MATCH') ORDER BY id LIMIT 4"

step "2. the review queue: side-by-side evidence for the clerk"
q=$(api "$clerk" GET /identity/review-queue)
python3 -c '
import json, sys
for i in json.load(sys.stdin):
    l, r = i["left"], i["right"]
    name = lambda p: " ".join(p["name"][0]["given"]) + " " + p["name"][0]["family"]
    ids = lambda p: ", ".join(x["type"]["text"] + " " + x["value"] for x in p["identifier"]) or "no identifiers"
    print("   TeleClinic: %-16s born %s, %s" % (name(l), l["birthDate"], ids(l)))
    print("   OpenMRS:    %-16s born %s, %s" % (name(r), r["birthDate"], ids(r)))
    print("   score %s; agree: %s; conflict: %s" % (i["score"], ", ".join(i["agreeingFields"]), ", ".join(i["conflictingFields"]) or "none"))' <<<"$q"
EMR=$(json "[i['right']['id'] for i in d if i['left']['id'] == '$MARIE'][0]" <<<"$q")
note "OpenMRS national ID for this record: $(emr_nid "$EMR")"

step "3. never offered: same name and birth date, different national IDs"
api "$clerk" GET /identity/blocked | json '"\n".join("   " + " ".join(i["left"]["name"][0]["given"]) + " " + i["left"]["name"][0]["family"] + ": " + ", ".join(i["conflictingFields"]) for i in d)'
step "4. linked automatically: two strong identifiers agree"
api "$clerk" GET /identity/links | json '"\n".join("   " + " ".join(l["patient"]["name"][0]["given"]) + " " + l["patient"]["name"][0]["family"] + ": " + l["link"]["reason"] for l in d if l["link"]["decidedBy"] == "auto")'

step "5. the clerk links the worked example; the national ID is offered to OpenMRS"
r=$(api "$clerk" POST /identity/links "{\"sourcePatient\":\"Patient/$MARIE\",\"targetPatient\":\"Patient/$EMR\",\"linkType\":\"seealso\",\"decidedBy\":\"clerk1\",\"reason\":\"same mother's name and village, confirmed by phone\"}")
[ "$(status "$clerk")" = 201 ] || fail "link failed: HTTP $(status "$clerk") $r"
LINK=$(json 'd["id"]' <<<"$r")
note "link ${LINK:0:8} decided by clerk1"
wait_for 90 "SELECT count(*) FROM sync.outbound WHERE kind = 'ADD_IDENTIFIER' AND reference = '$LINK' AND status = 'DONE'" \
  || fail "the national ID did not reach OpenMRS"
note "OpenMRS national ID now: $(emr_nid "$EMR")"

step "6. the clerk unlinks (a wrong link is corrected); the national ID is taken back"
api "$clerk" DELETE "/identity/links/$LINK?reason=a+second+visit+showed+two+different+women" >/dev/null
[ "$(status "$clerk")" = 204 ] || fail "unlink failed: HTTP $(status "$clerk")"
wait_for 90 "SELECT count(*) FROM sync.outbound WHERE kind = 'REMOVE_IDENTIFIER' AND reference = '$LINK' AND status = 'DONE'" \
  || fail "the national ID was not removed from OpenMRS"
note "OpenMRS national ID now: $(emr_nid "$EMR")"
[ "$(emr_nid "$EMR")" = none ] || fail "the national ID is still on the OpenMRS record"
note "back in the review queue: $(api "$clerk" GET /identity/review-queue | json "len([i for i in d if i['left']['id'] == '$MARIE'])") pair"

step "7. the audit trail shows both decisions"
api "$adm" GET "/api/audit/events?patientId=$MARIE&limit=10" \
  | json '"\n".join("   " + e["action"] + " by " + (e["actor_name"] or e["actor"]) + ": " + (e["reason"] or "") for e in d["entries"] if e["action"] in ("LINK", "UNLINK"))'

pass "worked example reviewed, linked and unlinked with write-back; hard block never offered; UPI pair auto-linked"
