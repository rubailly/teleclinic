#!/usr/bin/env bash
# Scene 1: Uwase books for Keza from a basic phone. Acts as the telecom aggregator: posts the same form
# callbacks it would, approves the mobile-money prompt, then waits for the confirmation SMS.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
GW=http://localhost:9380
PHONE=+250788000001
sid="smoke-$RANDOM$RANDOM"
answers=""

dial() {  # one USSD step; prints the reply and its time
  [ $# -gt 0 ] && answers="${answers:+$answers*}$1"
  curl -fsS --max-time 5 -o "$tmp" -w '%{time_total}' "$GW/ussd/callback" \
    --data-urlencode "sessionId=$sid" --data-urlencode "phoneNumber=$PHONE" \
    --data-urlencode "serviceCode=*909*8#" --data-urlencode "text=$answers" > "$tmp.t"
  local chars; chars=$(($(wc -m < "$tmp") - 4))
  [ "$chars" -le 182 ] || fail "page is $chars characters, more than one USSD page"
  printf '   %4d ms  %3d ch  %s\n' "$(python3 -c "print(round($(cat "$tmp.t")*1000))")" "$chars" "$(head -c 70 "$tmp" | tr '\n' '|')"
}
tmp=$(mktemp); trap 'rm -f "$tmp" "$tmp.t"' EXIT
sms_before=$(curl -fsS "$GW/sim/state?msisdn=%2B250788000001" | json 'len(d["sms"])')

step "1. dial *909*8#, English, PIN"
dial; dial 2; dial 1234
grep -q "Who is sick" "$tmp" || fail "expected the who-is-sick menu: $(cat "$tmp")"

step "2. choose Keza, give consent with AI support, fever + fast breathing for 2 days"
keza=$(grep -o '^[0-9]\. Keza' "$tmp" | cut -c1)
dial "$keza"
grep -q "Agree" "$tmp" && dial 2
dial 1; dial 3; dial 0; dial 2
grep -q "^CON URGENT" "$tmp" || fail "expected URGENT advice in the session: $(cat "$tmp")"

step "3. book; the session ends with a mobile-money prompt"
dial 1
grep -q "^END" "$tmp" || fail "expected END: $(cat "$tmp")"
pid=""
for i in $(seq 1 10); do
  pid=$(curl -fsS "$GW/sim/state?msisdn=%2B250788000001" | json '(d["pending"] or [{}])[0].get("paymentId", "")')
  [ -n "$pid" ] && break; sleep 0.5
done
[ -n "$pid" ] || fail "no mobile-money prompt for $PHONE"
note "prompt $pid; approving on the phone"
curl -fsS -o /dev/null -X POST "$GW/sim/approve/$pid"

step "4. the payment webhook queues the consultation and the core sends an SMS"
for i in $(seq 1 30); do
  n=$(curl -fsS "$GW/sim/state?msisdn=%2B250788000001" | json 'len(d["sms"])')
  [ "$n" -gt "$sms_before" ] && break; sleep 0.5
done
[ "$n" -gt "$sms_before" ] || fail "no SMS after the payment"
note "SMS: $(curl -fsS "$GW/sim/state?msisdn=%2B250788000001" | json 'd["sms"][0]["text"]')"

step "5. a number not linked to IremboAccount is told how to link it"
sid="smoke-$RANDOM$RANDOM"; answers=""; PHONE=+250788000099
dial; dial 2; dial 1234
grep -q "not linked" "$tmp" || fail "expected not-linked message: $(cat "$tmp")"

pass "USSD booking: advice in session, one page per step, paid by mobile money, SMS sent"
