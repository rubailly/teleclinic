#!/usr/bin/env bash
# Scene 3b: two systems write the same record without an echo that bounces forever.
# Keza's encounter (from Scene 2) is in OpenMRS exactly once; her own write comes back as an echo and is dropped; a
# phone edited in OpenMRS (contact details are EMR-authoritative) arrives in TeleClinic once and does not bounce back.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
source scripts/lib/cluster.sh
source scripts/lib/sync.sh
require_emr
KEZA=10000000-0000-4000-b000-000000000003
adm=$(mktemp); trap 'rm -f "$adm" "$adm.status"' EXIT
login admin1 "$adm"

step "1. Keza's encounter from the clinician's decision is in OpenMRS exactly once"
if [ "$(sq "SELECT count(*) FROM sync.outbound WHERE kind = 'ENCOUNTER' AND patient_id = '$KEZA'")" = 0 ]; then
  note "no finalised case for Keza yet: running Scene 2 first (make clinician)"
  ./scripts/smoke/clinician.sh >/dev/null
fi
wait_for 120 "SELECT count(*) FROM sync.outbound WHERE kind = 'ENCOUNTER' AND patient_id = '$KEZA' AND status = 'DONE'" \
  || fail "the encounter did not reach OpenMRS"
EMR=$(omrs "$OPENMRS_REST/patient?identifier=$KEZA&v=custom:(uuid,display)" | json 'd["results"][0]["uuid"]')
consultations=$(sq "SELECT string_agg(reference, ' ') FROM sync.outbound WHERE kind = 'ENCOUNTER' AND patient_id = '$KEZA'")
for c in $consultations; do
  n=$(omrs "$OPENMRS_REST/obs?patient=$EMR&concept=d2d722a6-0ea7-51be-ab7b-8f0c673e0093&v=custom:(value)" \
      | python3 -c "import json,sys; print(sum(1 for o in json.load(sys.stdin)['results'] if o['value'] == sys.argv[1]))" "$c")
  note "consultation ${c:0:8}: $n encounter(s) in OpenMRS"
  [ "$n" = 1 ] || fail "expected exactly one encounter for consultation $c"
done
note "OpenMRS patient $EMR carries: $(omrs "$OPENMRS_REST/patient/$EMR/identifier?v=custom:(display)" | json '", ".join(i["display"] for i in d["results"])')"

step "2. TeleClinic's own write comes back on the next poll: same content hash, dropped as an echo"
written=$(sq "SELECT coalesce(max(id), 0) FROM sync.event WHERE direction = 'OUTBOUND' AND emr_uuid = '$EMR'")
echo_q="SELECT count(*) FROM sync.event WHERE id > $written AND teleclinic_id = '$KEZA' AND outcome = 'ECHO_DROPPED'"
if [ "$(sq "$echo_q")" = 0 ]; then kick "$KICK_INBOUND"; fi
wait_for 90 "$echo_q" || fail "the write did not come back as an echo"
sq "SELECT to_char(at, 'HH24:MI:SS') || '  ' || direction || ' ' || outcome || coalesce(' (' || detail || ')', '') FROM sync.event
    WHERE emr_uuid = '$EMR' AND id >= $written ORDER BY id LIMIT 3" | sed 's/^/   /'
hash=$(api "$adm" GET "/sync/ledger/Patient/$KEZA" | json 'd["contentHash"][:16]')
note "ledger: Patient/$KEZA <-> OpenMRS $EMR, hash $hash"

step "3. a nurse at the facility changes Keza's contact phone in OpenMRS"
phone="+25078$(shuf -i 1000000-9999999 -n 1)"
attr=$(omrs "$OPENMRS_REST/person/$EMR/attribute?v=custom:(uuid,attributeType:(uuid))" \
       | python3 -c "import json,sys; print(next((a['uuid'] for a in json.load(sys.stdin)['results'] if a['attributeType']['uuid'] == '14d4f066-15f5-102d-96e4-000c29c2a5d7'), ''))")
# An edit of the existing contact phone, as the O3 registration screen does (OpenMRS reports an edited attribute as a
# change; one added where there was none is not seen by _lastUpdated polling: see the README)
[ -n "$attr" ] || fail "Keza's OpenMRS record has no contact phone to edit"
omrs -X POST "$OPENMRS_REST/person/$EMR/attribute/$attr" -d "{\"value\":\"$phone\"}" >/dev/null
note "OpenMRS phone is now $phone"
outbound_before=$(sq "SELECT count(*) FROM sync.outbound WHERE patient_id = '$KEZA'")

step "4. the change arrives in TeleClinic once, applied and audited"
mark=$(last_event); kick "$KICK_INBOUND"
wait_for 90 "SELECT count(*) FROM sync.event WHERE id > $mark AND teleclinic_id = '$KEZA'" || fail "no inbound poll result"
events_since "$mark" | sed 's/^/   /'
[ "$(sq "SELECT count(*) FROM sync.event WHERE id > $mark AND teleclinic_id = '$KEZA' AND outcome = 'APPLIED'")" = 1 ] \
  || fail "the phone change was not applied once"
[ "$(sq "SELECT phone FROM patient_index.patient WHERE id = '$KEZA'")" = "$phone" ] || fail "TeleClinic does not have the new phone"
note "TeleClinic patient phone: $phone (audit: $(sq "SELECT action || ' by ' || actor_name FROM audit.event WHERE patient_id = '$KEZA' AND actor = 'openmrs-sync' ORDER BY seq DESC LIMIT 1"))"
note "ledger hash now $(api "$adm" GET "/sync/ledger/Patient/$KEZA" | json 'd["contentHash"][:16]') (was $hash)"

step "5. it does not bounce: nothing goes back out, and the next poll applies nothing again"
mark=$(last_event); cursor=$(sq "SELECT coalesce(max(value), 'epoch') FROM sync.cursor")
kick "$KICK_INBOUND"
wait_for 90 "SELECT count(*) FROM sync.cursor WHERE value > '$cursor'" || fail "the next inbound poll did not complete"
seen=$(sq "SELECT coalesce(string_agg(outcome, ' '), 'not returned (unchanged since the last poll)') FROM sync.event
           WHERE id > $mark AND teleclinic_id = '$KEZA'")
note "next poll, Keza: $seen"
[ "$(sq "SELECT count(*) FROM sync.event WHERE id > $mark AND teleclinic_id = '$KEZA' AND outcome = 'APPLIED'")" = 0 ] \
  || fail "the same change was applied twice"
[ "$(sq "SELECT count(*) FROM sync.outbound WHERE patient_id = '$KEZA'")" = "$outbound_before" ] || fail "the change was sent back out"
note "outbound writes for Keza: $outbound_before before and after"

pass "one encounter per consultation, echoes dropped, the EMR's phone applied once and not sent back"
