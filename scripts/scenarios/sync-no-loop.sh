#!/usr/bin/env bash
# Scene 3b: two systems write the same record without an echo that bounces forever, on eBuzima.
# Keza is registered at Kimironko Health Centre, a tenant of the central eBuzima instance. Her encounter (from Scene 2)
# is in eBuzima exactly once, in that tenant; TeleClinic's own write comes back as an echo and is dropped; a phone
# changed by a Kimironko nurse (contact details are EMR-authoritative) is pushed by eBuzima, arrives in TeleClinic
# once and does not bounce back.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
source scripts/lib/cluster.sh
source scripts/lib/sync.sh
require_ebuzima
KEZA=10000000-0000-4000-b000-000000000003
NURSE=nurse.kimironko@ebuzima.local
adm=$(mktemp); erp=$(mktemp); nurse=$(mktemp); trap 'rm -f "$adm" "$adm.status" "$erp" "$nurse"' EXIT
login admin1 "$adm"
ebz_login Administrator "$EBUZIMA_ADMIN_PASSWORD" "$erp"
ebz_login "$NURSE" "$EBUZIMA_NURSE_PASSWORD" "$nurse"
mine="SELECT count(*) FROM sync.outbound WHERE target = 'ebuzima' AND kind = 'ENCOUNTER' AND patient_id = '$KEZA'"

step "1. Keza's encounter from the clinician's decision is in eBuzima exactly once, in Kimironko's tenant"
note "Keza's home facility: $(sq "SELECT f.name || ' (' || f.level || ', EMR: ' || f.emr_system || ', tenant: ' || f.emr_tenant || ')'
  FROM patient_index.patient p JOIN patient_index.facility f ON f.code = p.home_facility WHERE p.id = '$KEZA'")"
if [ "$(sq "$mine")" = 0 ]; then
  note "no finalised case for Keza yet: running Scene 2 first (make clinician)"
  ./scripts/smoke/clinician.sh >/dev/null
fi
wait_for 120 "$mine AND status = 'DONE'" || fail "the encounter did not reach eBuzima"
EMR=$(ebz "$erp" "$EBUZIMA/api/resource/Patient?filters=$(urlq "[[\"custom_teleclinic_id\",\"=\",\"$KEZA\"]]")" | json 'd["data"][0]["name"]')
for c in $(sq "SELECT string_agg(reference, ' ') FROM sync.outbound WHERE target = 'ebuzima' AND kind = 'ENCOUNTER' AND patient_id = '$KEZA'"); do
  found=$(ebz "$erp" "$EBUZIMA/api/resource/Patient%20Encounter?filters=$(urlq "[[\"custom_teleclinic_consultation\",\"=\",\"$c\"]]")&fields=$(urlq '["name","company","owner"]')")
  n=$(json 'len(d["data"])' <<<"$found")
  note "consultation ${c:0:8}: $n encounter(s) in eBuzima: $(json '", ".join(e["name"] + " in " + e["company"] + " by " + e["owner"] for e in d["data"])' <<<"$found")"
  [ "$n" = 1 ] || fail "expected exactly one encounter for consultation $c"
  [ "$(json 'd["data"][0]["company"]' <<<"$found")" = "Kimironko Health Centre" ] || fail "the encounter is not in Kimironko's tenant"
done
note "eBuzima patient $EMR: $(ebz "$erp" "$EBUZIMA/api/resource/Patient/$EMR" | json '"TeleClinic ID " + d["data"]["custom_teleclinic_id"] + ", UPI " + d["data"]["custom_upi"] + ", phone " + (d["data"]["mobile"] or "-")')"

step "2. TeleClinic's own write comes back (eBuzima's webhook): same content hash, dropped as an echo"
written=$(sq "SELECT coalesce(max(id), 0) FROM sync.event WHERE system = 'ebuzima' AND direction = 'OUTBOUND' AND emr_uuid = '$EMR'")
echo_q="SELECT count(*) FROM sync.event WHERE id > $written AND system = 'ebuzima' AND teleclinic_id = '$KEZA' AND outcome = 'ECHO_DROPPED'"
if [ "$(sq "$echo_q")" = 0 ]; then kick "$KICK_EBUZIMA_INBOUND"; fi
wait_for 90 "$echo_q" || fail "the write did not come back as an echo"
sq "SELECT to_char(at, 'HH24:MI:SS') || '  ' || direction || ' ' || outcome || coalesce(' (' || detail || ')', '') FROM sync.event
    WHERE system = 'ebuzima' AND emr_uuid = '$EMR' AND id >= $written ORDER BY id LIMIT 3" | sed 's/^/   /'
hash=$(api "$adm" GET "/sync/ledger/Patient/$KEZA?externalSystem=ebuzima" | json 'd["contentHash"][:16]')
note "ledger: Patient/$KEZA <-> eBuzima $EMR, hash $hash"

step "3. a nurse at Kimironko Health Centre changes Keza's contact phone in eBuzima"
note "$NURSE sees $(ebz "$nurse" "$EBUZIMA/api/resource/Company" | json '", ".join(c["name"] for c in d["data"])') (one tenant of the shared instance)"
phone="+25078$(shuf -i 1000000-9999999 -n 1)"
mark=$(last_event)
saved=$(ebz "$nurse" -X PUT "$EBUZIMA/api/resource/Patient/$EMR" -d "{\"mobile\":\"$phone\"}")
[ "$(json 'd["data"]["mobile"]' <<<"$saved")" = "$phone" ] || fail "the nurse could not save the phone: ${saved:0:300}"
note "eBuzima phone is now $phone (modified by $(json 'd["data"]["modified_by"]' <<<"$saved"))"
outbound_before=$(sq "SELECT count(*) FROM sync.outbound WHERE patient_id = '$KEZA'")

step "4. eBuzima pushes the change; it arrives in TeleClinic once, applied and audited"
t0=$(date +%s%N)
wait_for 90 "SELECT count(*) FROM sync.event WHERE id > $mark AND system = 'ebuzima' AND teleclinic_id = '$KEZA' AND outcome = 'APPLIED'" \
  || fail "the phone change did not arrive"
note "applied $(( ($(date +%s%N) - t0) / 1000000 )) ms after the nurse saved, without a manual trigger (document webhook)"
events_since "$mark" | sed 's/^/   /'
[ "$(sq "SELECT count(*) FROM sync.event WHERE id > $mark AND teleclinic_id = '$KEZA' AND outcome = 'APPLIED'")" = 1 ] \
  || fail "the phone change was not applied once"
[ "$(sq "SELECT phone FROM patient_index.patient WHERE id = '$KEZA'")" = "$phone" ] || fail "TeleClinic does not have the new phone"
note "TeleClinic patient phone: $phone (audit: $(sq "SELECT action || ' by ' || actor_name FROM audit.event WHERE patient_id = '$KEZA' AND actor = 'ebuzima' ORDER BY seq DESC LIMIT 1"))"
note "ledger hash now $(api "$adm" GET "/sync/ledger/Patient/$KEZA?externalSystem=ebuzima" | json 'd["contentHash"][:16]') (was $hash)"

step "5. it does not bounce: nothing goes back out, and the next poll applies nothing again"
mark=$(last_event); cursor=$(sq "SELECT value FROM sync.cursor WHERE name = 'ebuzima-patients'")
kick "$KICK_EBUZIMA_INBOUND"
wait_for 90 "SELECT count(*) FROM sync.cursor WHERE name = 'ebuzima-patients' AND value > '$cursor'" || fail "the next inbound poll did not complete"
seen=$(sq "SELECT coalesce(string_agg(outcome, ' '), 'not returned (unchanged since the last poll)') FROM sync.event
           WHERE id > $mark AND teleclinic_id = '$KEZA'")
note "next poll, Keza: $seen"
[ "$(sq "SELECT count(*) FROM sync.event WHERE id > $mark AND teleclinic_id = '$KEZA' AND outcome = 'APPLIED'")" = 0 ] \
  || fail "the same change was applied twice"
[ "$(sq "SELECT count(*) FROM sync.outbound WHERE patient_id = '$KEZA'")" = "$outbound_before" ] || fail "the change was sent back out"
note "outbound writes for Keza: $outbound_before before and after"

pass "one encounter per consultation in the right tenant, echoes dropped, the nurse's phone applied once and not sent back"
