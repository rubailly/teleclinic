#!/usr/bin/env bash
# Scene 4c: a hospital's EMR goes offline. Eric is followed at Kibagabaga District Hospital, which runs its own OpenMRS.
# Clinicians keep working; writes wait in the queue with backoff; when OpenMRS is back the backlog drains in order,
# each encounter exactly once. Other EMRs (eBuzima) are not held up: the queue is per EMR. Usage: emr-offline.sh [seconds offline]
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
source scripts/lib/cluster.sh
source scripts/lib/sync.sh
require_emr
OFFLINE=${1:-30}
ERIC=10000000-0000-4000-b000-000000000002
eric=$(mktemp); doc=$(mktemp); trap 'rm -f "$eric" "$doc" "$eric.status" "$doc.status"; docker start teleclinic-emr-openmrs-backend-1 >/dev/null 2>&1 || true' EXIT
login patient2 "$eric"; login clinician1 "$doc"

step "1. Kibagabaga District Hospital's OpenMRS goes offline"
docker stop -t 5 teleclinic-emr-openmrs-backend-1 >/dev/null
t_down=$(date +%s)
note "stopped at $(date -u +%T) UTC"

step "2. two consultations are decided while it is down (clinicians keep working)"
c1=$(finalised_consultation "$eric" "$doc" "$ERIC"); c2=$(finalised_consultation "$eric" "$doc" "$ERIC")
note "consultations ${c1:0:8} and ${c2:0:8} finalised"
wait_for 60 "SELECT count(*) FROM sync.outbound WHERE reference IN ('$c1', '$c2') AND attempts > 0" || fail "no delivery was attempted"
sq "SELECT '   ' || left(reference, 8) || '  ' || status || ', attempt ' || attempts || ', next try at ' || to_char(next_attempt_at, 'HH24:MI:SS')
    || ': ' || left(coalesce(last_error, ''), 60) FROM sync.outbound WHERE reference IN ('$c1', '$c2') ORDER BY created_at"
note "queue: $(sq "SELECT count(*) FROM sync.outbound WHERE status = 'PENDING'") pending"
while [ $(( $(date +%s) - t_down )) -lt "$OFFLINE" ]; do sleep 2; done

step "3. OpenMRS is back after $OFFLINE s; the backlog drains"
docker start teleclinic-emr-openmrs-backend-1 >/dev/null
s=$(date +%s)
until emr_up; do sleep 3; [ $(( $(date +%s) - s )) -gt 300 ] && fail "OpenMRS did not come back"; done
note "OpenMRS healthy again after $(( $(date +%s) - s )) s"
# Waiting out the backoff is fine in production; here the queue is retried at once to keep the demo short
sq "UPDATE sync.outbound SET next_attempt_at = now() WHERE status = 'PENDING'" >/dev/null
kick "$KICK_OUTBOUND"
wait_for 120 "SELECT (count(*) = 2)::int FROM sync.outbound WHERE reference IN ('$c1', '$c2') AND status = 'DONE'" \
  || fail "the backlog did not drain"
sq "SELECT '   ' || left(reference, 8) || '  ' || status || ' (' || outcome || ') after ' || attempts || ' failed attempt(s)'
    FROM sync.outbound WHERE reference IN ('$c1', '$c2') ORDER BY created_at"

step "4. no duplicates: each consultation is one encounter in OpenMRS"
EMR=$(omrs "$OPENMRS_REST/patient?identifier=$ERIC&v=custom:(uuid)" | json 'd["results"][0]["uuid"]')
for c in "$c1" "$c2"; do
  n=$(omrs "$OPENMRS_REST/obs?patient=$EMR&concept=d2d722a6-0ea7-51be-ab7b-8f0c673e0093&v=custom:(value)" \
      | python3 -c "import json,sys; print(sum(1 for o in json.load(sys.stdin)['results'] if o['value'] == sys.argv[1]))" "$c")
  note "consultation ${c:0:8}: $n encounter(s)"
  [ "$n" = 1 ] || fail "expected one encounter for $c"
done
pass "writes waited while OpenMRS was down and arrived once each when it came back"
