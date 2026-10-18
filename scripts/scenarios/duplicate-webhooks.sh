#!/usr/bin/env bash
# Scene 1c: the last confirmed payment's webhook arrives twice more, then once with a wrong signature.
# Pass: duplicates are acknowledged and ignored (event id already seen), the forged one is rejected,
# and the core still has one payment, unchanged, confirmed by exactly one event.
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/cluster.sh
PAY=http://localhost:9280
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {}, {'d': d}))" "$1"; }
sql() {  # through pgproxy, like the core; retried so a cluster that is still settling does not fail the check
  local i k out
  for i in $(seq 1 15); do
    for k in 1 2 3; do out=$(pg_proxy "$k" "$1") && [ -n "$out" ] && ! grep -qE "ERROR|FATAL|could not|No such" <<<"$out" && { echo "$out"; return; }; done
    sleep 1
  done
  echo "unavailable"
}

step "1. the last payment IremboPay settled"
pid=$(curl -fsS "$PAY/v1/settlements" | json '(d["payments"] or [{}])[-1].get("paymentId", "")')
[ -n "$pid" ] || fail "no settled payment yet; book and pay one first (make journey or make ussd)"
note "$pid, core: $(sql "SELECT status FROM payments.payment WHERE provider_payment_id = '$pid'")"
confirmed() { sql "SELECT count(*) FROM payments.webhook_event WHERE provider_payment_id = '$pid' AND outcome = 'CONFIRMED'"; }
state() { sql "SELECT status || ':' || updated_at FROM payments.payment WHERE provider_payment_id = '$pid'"; }
before=$(state)

step "2. the same webhook twice more (same event id, valid signature)"
for i in 1 2; do
  r=$(curl -fsS -X POST "$PAY/admin/payments/$pid/webhook?mode=duplicate")
  note "duplicate $i -> HTTP $(json 'str(d["status"]) + " " + d["response"]' <<<"$r")"
  grep -q DUPLICATE <<<"$r" || fail "duplicate was not recognised"
done

step "3. a payment.succeeded signed with the wrong secret"
r=$(curl -fsS -X POST "$PAY/admin/payments/$pid/webhook?mode=forged")
note "forged -> HTTP $(json 'str(d["status"]) + " " + d["response"][:70]' <<<"$r")"
[ "$(json 'd["status"]' <<<"$r")" = 401 ] || fail "forged webhook was not rejected"

step "4. the core's records did not change"
rows=$(sql "SELECT count(*) FROM payments.payment WHERE provider_payment_id = '$pid'")
note "payments for $pid: $rows; confirming events: $(confirmed); payment row unchanged: $([ "$(state)" = "$before" ] && echo yes || echo no)"
[ "$rows" = 1 ] && [ "$(confirmed)" = 1 ] && [ "$(state)" = "$before" ] || fail "a replayed or forged webhook changed the records"
pass "2 duplicates ignored, 1 forged webhook rejected, still one payment"
