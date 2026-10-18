# Helpers for the EMR sync scenes. Source after loading .env, scripts/lib/session.sh and scripts/lib/cluster.sh.
OPENMRS_REST=http://localhost:9880/openmrs/ws/rest/v1
OPENMRS_FHIR=http://localhost:9880/openmrs/ws/fhir2/R4
# Lightning webhook triggers (deterministic ids, see scripts/emr/bootstrap.py)
KICK_OUTBOUND=http://localhost:9480/i/614a751c-7c83-56bc-9747-1d574e225443
KICK_INBOUND=http://localhost:9480/i/96f76e15-65f7-55e3-84a6-ca5e32247472
KICK_EBUZIMA_OUTBOUND=http://localhost:9480/i/50f69c1f-df83-53c8-bdc2-9391826d02c6
KICK_EBUZIMA_INBOUND=http://localhost:9480/i/ca422855-5b33-55a4-b2fc-7a9cc019b566
EBUZIMA=http://localhost:9580

emr_up() { [ "$(docker inspect -f '{{.State.Health.Status}}' teleclinic-emr-openmrs-backend-1 2>/dev/null)" = healthy ]; }
require_emr() { emr_up || fail "OpenMRS is not running: make emr-up"; }
ebuzima_up() { [ "$(docker inspect -f '{{.State.Health.Status}}' teleclinic-emr-ebuzima-1 2>/dev/null)" = healthy ]; }
require_ebuzima() { ebuzima_up || fail "eBuzima is not running: make emr-up"; }
# ebz_login <user> <password> <cookie jar>; ebz <cookie jar> <curl args>: eBuzima's REST API as that user
ebz_login() { curl -s -o /dev/null -c "$3" -H 'Content-Type: application/json' -d "{\"usr\":\"$1\",\"pwd\":\"$2\"}" "$EBUZIMA/api/method/login"; }
ebz() { local jar=$1; shift; curl -s -b "$jar" -H 'Content-Type: application/json' -H 'Accept: application/json' "$@"; }
urlq() { python3 -c "import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))" "$1"; }
omrs() { curl -s -u "admin:$OPENMRS_ADMIN_PASSWORD" -H 'Content-Type: application/json' "$@"; }
sq() { pg_proxy 1 "$1"; }   # SQL through pgproxy as the application (not audited by pgAudit)
last_event() { sq "SELECT coalesce(max(id), 0) FROM sync.event"; }
kick() { curl -s -o /dev/null -X POST -H 'Content-Type: application/json' -d '{"reason":"demo"}' "$1"; }

# wait_for <seconds> <sql returning a number>: until the number is at least 1
wait_for() {
  local limit=$1 query=$2 s; s=$(date +%s)
  until [ "$(sq "$query" 2>/dev/null)" -ge 1 ] 2>/dev/null; do
    [ $(( $(date +%s) - s )) -ge "$limit" ] && return 1
    sleep 2
  done
}

events_since() {  # events after id $1, one per line
  sq "SELECT direction || ' ' || outcome || coalesce(' patient ' || teleclinic_id, '') || coalesce(' (' || detail || ')', '') FROM sync.event WHERE id > $1 ORDER BY id"
}

# A finalised consultation for Eric: consent, booking, clinician confirmation. Prints the consultation id.
finalised_consultation() {  # <patient jar> <clinician jar> <patient id>
  local pj=$1 cj=$2 pid=$3 c id
  api "$pj" POST "/api/patients/$pid/consents" >/dev/null
  c=$(api "$pj" POST /api/consultations "{\"patientId\":\"$pid\",\"channel\":\"WEB\",\"symptoms\":[\"cough\"],\"durationDays\":3,\"vitals\":{\"temperatureC\":37.8}}")
  id=$(json 'd["id"]' <<<"$c")
  api "$cj" POST "/triage/v1/cases/$id/review" '{"decision":"CONFIRM"}' >/dev/null
  [ "$(status "$cj")" = 200 ] || fail "review of $id failed: HTTP $(status "$cj")"
  echo "$id"
}
