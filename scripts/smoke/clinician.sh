#!/usr/bin/env bash
# Scene 2: a mother books for her child, the model's suggestion arrives later and stays AI-PROPOSED,
# orders are refused until a clinician decides, the clinician overrides with a reason, then the kill switch.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
mom=$(mktemp); doc=$(mktemp); adm=$(mktemp); trap 'rm -f "$mom" "$doc" "$adm" "$mom.status" "$doc.status" "$adm.status"' EXIT

book_and_pay() {  # prints the consultation id
  local c id checkout
  c=$(api "$mom" POST /api/consultations "{\"patientId\":\"$keza\",\"channel\":\"USSD\",\"symptoms\":[\"fever\",\"fast_breathing\"],\"durationDays\":2,\"vitals\":{\"temperatureC\":39.4}}")
  id=$(json 'd["id"]' <<<"$c")
  checkout=$(api "$mom" POST "/api/consultations/$id/payment" | json 'd["checkoutUrl"]')
  curl -s -o /dev/null -X POST "$checkout/approve"
  for i in $(seq 1 20); do [ "$(api "$mom" GET "/api/consultations/$id" | json 'd["state"]')" = QUEUED ] && break; sleep 0.5; done
  echo "$id"
}
case_field() { api "$doc" GET "/api/clinician/cases/$1" "" -H "X-Purpose-Of-Use: TREAT" | json "d['case']['$2']"; }

step "1. Uwase (patient1) books for her daughter Keza with guardian consent, and pays"
login patient1 "$mom"
keza=$(api "$mom" GET /api/me/patients | json '[p["patientId"] for p in d if p["relation"]=="GUARDIAN"][0]')
api "$mom" POST "/api/patients/$keza/consents" >/dev/null
id=$(book_and_pay)

note "consultation $id queued"

step "2. the model's suggestion arrives asynchronously and the case becomes AI-PROPOSED"
login clinician1 "$doc"
for i in $(seq 1 30); do st=$(case_field "$id" status); [ "$st" = AI_PROPOSED ] && break; sleep 0.5; done
[ "$st" = AI_PROPOSED ] || fail "case is $st, expected AI_PROPOSED"
note "rules $(case_field "$id" rulesUrgency), model $(case_field "$id" modelUrgency) ($(case_field "$id" modelConfidence)), proposed $(case_field "$id" mergedUrgency)"
note "contributing factors: $(api "$doc" GET "/api/clinician/cases/$id" "" -H "X-Purpose-Of-Use: TREAT" | json "', '.join(d['case']['contributingFactors'])")"

step "2b. the model can raise urgency above the rules (never lower it)"
eric=$(mktemp); login patient2 "$eric"
ericid=$(api "$eric" GET /api/me/patients | json 'd[0]["patientId"]')
api "$eric" POST "/api/patients/$ericid/consents" >/dev/null
c2=$(api "$eric" POST /api/consultations "{\"patientId\":\"$ericid\",\"channel\":\"WEB\",\"symptoms\":[\"fever\",\"headache\",\"rash\"],\"durationDays\":1,\"vitals\":{\"temperatureC\":38.6}}")
eid=$(json 'd["id"]' <<<"$c2")
for i in $(seq 1 30); do [ "$(case_field "$eid" status)" = AI_PROPOSED ] && break; sleep 0.5; done
note "fever, headache and rash: rules $(case_field "$eid" rulesUrgency), model $(case_field "$eid" modelUrgency), proposed $(case_field "$eid" mergedUrgency)"
[ "$(case_field "$eid" mergedUrgency)" = URGENT ] || fail "the model did not escalate the fever, headache and rash case"
rm -f "$eric" "$eric.status"

step "3. an order while the case is AI-PROPOSED is refused"
r=$(api "$doc" POST "/api/clinician/consultations/$id/orders" '{"kind":"PRESCRIPTION","detail":"illustrative"}')
[ "$(status "$doc")" = 403 ] || fail "order was not refused: HTTP $(status "$doc")"
note "HTTP 403 $(json 'd["issue"][0]["code"]' <<<"$r")"

step "4. Dr Mukamana overrides to EMERGENCY with a reason; the case is FINAL"
r=$(api "$doc" POST "/triage/v1/cases/$id/review" '{"decision":"OVERRIDE","finalUrgency":"EMERGENCY","reason":"cyanosis reported on the call"}')
[ "$(json 'd["status"]' <<<"$r")" = FINAL ] || fail "review did not finalise: $r"
note "$(json 'd["reviewDecision"] + " to " + d["finalUrgency"] + " by " + d["reviewedBy"] + ": " + d["reviewReason"]' <<<"$r")"
api "$doc" POST "/api/clinician/consultations/$id/orders" '{"kind":"PRESCRIPTION","detail":"illustrative"}' >/dev/null
[ "$(status "$doc")" = 201 ] || fail "order after FINAL failed: HTTP $(status "$doc")"
note "order accepted after FINAL"
note "provenance: $(api "$doc" GET "/api/clinician/cases/$id" "" -H "X-Purpose-Of-Use: TREAT" | json "', '.join(p['activity'] + ' by ' + p['agent_id'] for p in d['provenance'])")"

step "5. kill switch: the administrator turns the model off"
./scripts/scenarios/model-kill-switch.sh off "drift alert under investigation"
id2=$(book_and_pay)
sleep 4
st2=$(case_field "$id2" status)
banner=$(api "$doc" GET /api/clinician/queue | json 'd["rulesOnlyMode"]')
note "new case $id2 is $st2; clinician queue rules-only banner: $banner"
[ "$st2" = RULES_ONLY ] && [ "$banner" = True ] || fail "kill switch did not take effect"
./scripts/scenarios/model-kill-switch.sh on "investigation closed"

step "6. nothing identifying reached the AI trust zone's logs"
leaks=$(docker logs teleclinic-triage-model-1 2>&1; docker logs teleclinic-anonymizer-1 2>&1) 
hits=$(grep -ciE "keza|uwase|0000199200000001|\+2507880|180514-0000|$keza" <<<"$leaks" || true)
note "identifier matches in model and anonymizer logs: $hits"
[ "$hits" = 0 ] || fail "identifiers found in AI zone logs"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$PUBLIC_BASE_URL/internal/triage/suggestions")
note "suggestion callback through the public edge: HTTP $code (internal only)"
[ "$code" = 404 ] || fail "internal callback is reachable from the edge"

pass "AI-proposed, orders gated, clinician override, kill switch, no identifiers in the AI zone"
