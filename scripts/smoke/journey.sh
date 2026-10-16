#!/usr/bin/env bash
# Web journey (demo Scenes 1b and 1c): sign in, consent, book with in-session rules triage, insurance
# co-payment at the IremboPay mock, webhook confirmed server to server, queued, event on Kafka.
# Then the webhook attacks: a duplicate and a forged delivery change nothing.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
jar=$(mktemp); trap 'rm -f "$jar" "$jar.status"' EXIT
pay_base=$IREMBOPAY_PUBLIC_URL

step "1. Eric (patient2) signs in through IremboAccount (stand-in)"
login patient2 "$jar"
me=$(api "$jar" GET /api/me/patients)
eric=$(json 'd[0]["patientId"]' <<<"$me")
echo "   patient $eric ($(json 'd[0]["relation"]' <<<"$me"))"

step "2. booking without consent is refused"
api "$jar" DELETE "/api/patients/$eric/consents/active" >/dev/null   # start from no consent, so reruns are deterministic
refused=$(api "$jar" POST /api/consultations "{\"patientId\":\"$eric\",\"symptoms\":[\"cough\"]}")
[ "$(status "$jar")" = 403 ] || fail "expected 403 before consent, got $(status "$jar")"
echo "   HTTP 403 $(json 'd["issue"][0]["code"] + ": " + d["issue"][0]["diagnostics"]' <<<"$refused")"

step "3. consent (purposes: treatment, payment, AI triage support)"
consent=$(api "$jar" POST "/api/patients/$eric/consents")
[ "$(status "$jar")" = 201 ] || fail "consent: HTTP $(status "$jar") $consent"
echo "   $(json 'd["performerType"] + " " + d["status"] + " " + d["policyVersion"]' <<<"$consent")"

step "4. intake: rules answer in the same request"
key=$(cat /proc/sys/kernel/random/uuid)
c=$(api "$jar" POST /api/consultations "{\"patientId\":\"$eric\",\"channel\":\"WEB\",\"symptoms\":[\"cough\",\"fever\"],\"durationDays\":1}" \
  -H "Idempotency-Key: $key")
[ "$(status "$jar")" = 201 ] || fail "booking: HTTP $(status "$jar") $c"
id=$(json 'd["id"]' <<<"$c")
echo "   $id $(json 'd["state"] + " " + d["urgency"] + ": " + d["patientAdvice"]' <<<"$c")"
again=$(api "$jar" POST /api/consultations "{\"patientId\":\"$eric\",\"channel\":\"WEB\",\"symptoms\":[\"cough\",\"fever\"],\"durationDays\":1}" \
  -H "Idempotency-Key: $key")
[ "$(json 'd["id"]' <<<"$again")" = "$id" ] || fail "same Idempotency-Key created a second consultation"
echo "   retry with the same Idempotency-Key returned the same consultation"

step "5. payment request: coverage check, then IremboPay (mock) for the patient's share only"
p=$(api "$jar" POST "/api/consultations/$id/payment")
[ "$(status "$jar")" = 200 ] || fail "payment: HTTP $(status "$jar") $p"
checkout=$(json 'd["checkoutUrl"]' <<<"$p")
pid=${checkout##*/}
echo "   $(json 'str(d["amount"]) + " " + d["currency"] + " insured=" + str(d["insured"]) + " " + str(d["coverageScheme"])' <<<"$p") -> $checkout"
[ "$(json 'd["insured"]' <<<"$p")" = True ] || fail "Eric's Mutuelle cover was not applied"

step "6. the patient approves on the hosted checkout"
ret=$(curl -s -D - -o /dev/null -X POST "$checkout/approve" | location)
echo "   redirected back to $ret"

step "7. webhook verified, confirmed server to server, consultation queued"
for i in $(seq 1 20); do
  state=$(api "$jar" GET "/api/consultations/$id" | json 'd["state"]')
  [ "$state" = QUEUED ] && break; sleep 0.5
done
[ "$state" = QUEUED ] || fail "consultation is $state, expected QUEUED"
api "$jar" GET "/api/consultations/$id" | json '"\n".join("   " + str(t["from"]) + " -> " + t["to"] + ": " + t["reason"] for t in d["history"])'

step "8. the outbox delivered consultation.paid to Kafka, keyed by patient id"
docker exec teleclinic-kafka-1-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic consultation.paid --from-beginning --timeout-ms 8000 --property print.key=true 2>/dev/null \
  | grep "$id" | head -1 | sed 's/^/   /' | grep -q . || fail "no consultation.paid event for $id"
echo "   found on topic consultation.paid"

step "9. Scene 1c: the same webhook again, then a forged one"
dup=$(curl -s -X POST "$pay_base/admin/payments/$pid/webhook?mode=duplicate")
forged=$(curl -s -X POST "$pay_base/admin/payments/$pid/webhook?mode=forged")
echo "   duplicate -> $(json 'str(d["status"]) + " " + d["response"]' <<<"$dup")"
echo "   forged    -> $(json 'str(d["status"]) + " " + d["response"][:60]' <<<"$forged")"
grep -q DUPLICATE <<<"$dup" || fail "duplicate webhook was not recognised"
[ "$(json 'd["status"]' <<<"$forged")" = 401 ] || fail "forged webhook was not rejected"
paid=$(api "$jar" GET "/api/consultations/$id" | json 'sum(1 for t in d["history"] if t["to"] == "PAID")')
[ "$paid" = 1 ] || fail "consultation was marked PAID $paid times"
echo "   still exactly one PAID transition"

pass "web journey, insurance co-payment, outbox and webhook protection"
