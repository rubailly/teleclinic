#!/usr/bin/env bash
# Scene 5b, part 2: tamper evidence. Dr Mukamana opens Eric's record with break-glass. A database administrator
# then tries to hide it by editing the audit row directly. The chain check finds the edited entry, and pgAudit
# shows who did it. The original values are put back at the end so the demo can run again; that is recorded too.
# Usage: tamper-audit.sh [seconds to keep the chain broken before restoring, so the alert can be seen firing]
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
source scripts/lib/cluster.sh
ERIC=10000000-0000-4000-b000-000000000002
HOLD=${1:-0}
doc=$(mktemp); adm=$(mktemp); trap 'rm -f "$doc" "$adm" "$doc.status" "$adm.status"' EXIT
login clinician1 "$doc"; login admin1 "$adm"
verify() { api "$adm" GET /api/audit/verify; }

step "1. Dr Mukamana opens Eric's record with break-glass (an alert fires)"
api "$doc" GET "/fhir/Patient/$ERIC" "" -H "X-Purpose-Of-Use: BREAK_GLASS" -H "X-Break-Glass-Reason: unconscious on arrival, no consent possible" >/dev/null
[ "$(status "$doc")" = 200 ] || fail "break-glass read failed: HTTP $(status "$doc")"
n=$(leader | sed 's/pg-//')
seq=""
for i in $(seq 1 20); do
  seq=$(pg_local "$n" "SELECT max(seq) FROM audit.event WHERE patient_id = '$ERIC' AND purpose = 'BREAK_GLASS' AND outcome = 'SUCCESS'")
  [ -n "$seq" ] && break; sleep 0.5
done
[ -n "$seq" ] || fail "break-glass read not in the trail"
note "entry $seq: $(pg_local "$n" "SELECT actor_name || ', ' || purpose || ', reason: ' || reason FROM audit.event WHERE seq = $seq")"
v=$(verify); note "chain: $(json 'd["status"] + ", " + str(d["checked"]) + " entries"' <<<"$v")"

step "2. a database administrator rewrites that entry to hide the break-glass (psql as postgres on pg-$n)"
original=$(pg_local "$n" "SELECT purpose || '|' || consent_basis || '|' || reason FROM audit.event WHERE seq = $seq")
t_tamper=$(date -u +%Y-%m-%dT%H:%M:%S)
pg_local "$n" "ALTER TABLE audit.event DISABLE TRIGGER audit_event_append_only;
  UPDATE audit.event SET purpose = 'TREAT', consent_basis = 'consent:on-file', reason = NULL WHERE seq = $seq;
  ALTER TABLE audit.event ENABLE TRIGGER audit_event_append_only;" >/dev/null
note "entry $seq now reads: $(pg_local "$n" "SELECT purpose || ', basis ' || consent_basis || ', reason ' || coalesce(reason, 'none') FROM audit.event WHERE seq = $seq")"

step "3. verify the chain"
v=$(verify)
note "$(json 'd["status"] + " at entry " + str(d["firstBrokenSeq"]) + ": " + d["problem"]' <<<"$v")"
[ "$(json 'd["status"]' <<<"$v")" = BROKEN ] && [ "$(json 'd["firstBrokenSeq"]' <<<"$v")" = "$seq" ] || fail "the change was not detected"

step "4. pgAudit recorded who did it"
found=""
for i in $(seq 1 30); do
  found=$(api "$adm" GET "/api/audit/events?source=DATABASE&limit=100" | python3 -c '
import json, sys
hits = [e for e in json.load(sys.stdin)["entries"] if e["actor"] == "postgres" and e["recorded_at"][:19] >= sys.argv[1]
        and "audit.event" in (e["resource_id"] or e["detail"].get("statement", ""))]
for e in sorted(hits, key=lambda e: e["seq"]):
    print("entry %s: %s by %s on %s" % (e["seq"], e["action"], e["actor"], e["detail"]["node"]))' "$t_tamper")
  echo "$found" | grep -q "UPDATE" && break; sleep 1
done
echo "$found" | grep -q "UPDATE" || fail "pgAudit entry for the UPDATE not found"
while read -r line; do note "$line"; done <<<"$found"

if [ "$HOLD" -gt 0 ]; then
  note "keeping the chain broken for $HOLD s: the \"Audit chain broken\" alert fires in Grafana"
  sleep "$HOLD"
fi

step "5. put the original values back (recorded again by pgAudit) so the demo can run again"
IFS='|' read -r p b r <<<"$original"
pg_local "$n" "ALTER TABLE audit.event DISABLE TRIGGER audit_event_append_only;
  UPDATE audit.event SET purpose = '$p', consent_basis = '$b', reason = '$r' WHERE seq = $seq;
  ALTER TABLE audit.event ENABLE TRIGGER audit_event_append_only;" >/dev/null
v=$(verify); note "chain: $(json 'd["status"] + ", " + str(d["checked"]) + " entries"' <<<"$v")"
[ "$(json 'd["status"]' <<<"$v")" = INTACT ] || fail "chain not intact after restoring"

pass "tampering with entry $seq was detected by the hash chain and attributed by pgAudit"
