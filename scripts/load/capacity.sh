#!/usr/bin/env bash
# Capacity test through the real paths, at fixed arrival rates (web intake per second : USSD sessions per second).
# Eight k6 generators (GEN=) run inside the Docker network, each with its own address, so the edge's per-client quota (1,200
# requests a minute) does not cap the total; then one noisy client is pushed past its quota on its own.
# Usage: capacity.sh [web:ussd ...]   default: 25:1 50:2 100:4, 60 s each (make capacity adds 200:8 with GEN=16).
# It leaves thousands of test consultations behind: run `make reset` afterwards for the demo.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
source scripts/lib/cluster.sh
LEVELS=${*:-"25:1 50:2 100:4"}
GEN=${GEN:-8}; DURATION=${DURATION:-60}
ERIC=10000000-0000-4000-b000-000000000002
RUN=$(date +%s)
out=$(mktemp -d); trap 'rm -rf "$out"; docker rm -f $(docker ps -aq --filter name=tc-load-) >/dev/null 2>&1 || true' EXIT
sq() { pg_proxy 1 "$1"; }

step "0. a signed-in patient (Eric) with consent, as the web app has"
jar="$out/jar"; login patient2 "$jar"
api "$jar" POST "/api/patients/$ERIC/consents" >/dev/null
COOKIE=$(awk '$6 ~ /^(TCSESSION|XSRF-TOKEN)$/ {printf "%s%s=%s", (n++ ? "; " : ""), $6, $7}' "$jar")
XSRF=$(xsrf "$jar")
[ -n "$COOKIE" ] && [ -n "$XSRF" ] || fail "no session cookie"
note "live colour: $(curl -s "$PUBLIC_BASE_URL/api/public/version")"

run_level() {  # web per second, USSD sessions per second
  local web=$1 ussd=$2 i
  for i in $(seq 1 $GEN); do
    docker run -d --name "tc-load-$i" --network teleclinic \
      -e EDGE=http://haproxy:9080 -e GW=http://ussd-gateway:8300 -e PATIENT=$ERIC -e PHONE=+250788000001 \
      -e COOKIE="$COOKIE" -e XSRF="$XSRF" -e RUN="$RUN-$web" -e GEN=$i -e DURATION="${DURATION}s" \
      -e WEB_PER_MIN=$(( web * 60 / GEN )) -e USSD_PER_MIN=$(( ussd * 60 / GEN )) \
      -e K6_SCRIPT="$(cat scripts/load/capacity.js)" --entrypoint sh grafana/k6:0.54.0 \
      -c 'printf "%s" "$K6_SCRIPT" > /tmp/c.js && exec k6 run --quiet --no-color /tmp/c.js' >/dev/null
  done
  sleep $(( DURATION / 2 ))
  docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' \
    $(docker ps --format '{{.Names}}' | grep -E '^teleclinic-(core-blue|core-green|pg-[123]|kafka-1|ussd-gateway|apisix|haproxy|triage-model|anonymizer)-1$') \
    | awk '{printf "   %-28s cpu %7s  mem %s\n", $1, $2, $3}' > "$out/stats-$web"
  for i in $(seq 1 $GEN); do docker wait "tc-load-$i" >/dev/null; docker logs "tc-load-$i" 2>/dev/null | grep '^SUMMARY ' | cut -c9- >> "$out/sum-$web"; docker rm "tc-load-$i" >/dev/null; done
  python3 - "$out/sum-$web" "$web" "$ussd" "$DURATION" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
web, ussd, dur = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
for s, label, unit in (("web", f"web intake  {web}/s", "requests"), ("ussd", f"USSD        {ussd} sessions/s", "steps")):
    req = sum(r[s]["requests"] for r in rows); bad = sum(r[s]["failed"] for r in rows)
    chk_bad = sum(r[s]["checksFailed"] for r in rows)
    worst = lambda k: max((r[s][k] or 0) for r in rows)
    print(f"   {label:<28} {req:>6} {unit} ({req / dur:6.1f}/s), {bad} failed, {chk_bad} failed checks; "
          f"p50 {worst('p50'):.0f} ms, p95 {worst('p95'):.0f} ms, p99 {worst('p99'):.0f} ms, max {worst('max'):.0f} ms")
print(f"   generators: {len(rows)}, dropped iterations (generator could not keep the rate): {sum(r['dropped'] for r in rows)}")
PY
  cat "$out/stats-$web"
}

for level in $LEVELS; do
  web=${level%%:*}; ussd=${level##*:}
  step "level: $web web intakes/s and $ussd USSD sessions/s for ${DURATION} s (worst generator's percentiles)"
  before=$(sq "SELECT count(*) FROM consultation.consultation")
  run_level "$web" "$ussd"
  note "consultations stored: $(( $(sq "SELECT count(*) FROM consultation.consultation") - before ))"
  note "outbox waiting right after: $(sq "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")"
  sleep 10
  note "outbox waiting 10 s later:  $(sq "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")"
done

step "protection: one noisy client at 80 requests/s while another at 5/s is served (edge quota 20/s per client)"
cat > "$out/p.js" <<'JS'
import http from "k6/http";
import { Counter } from "k6/metrics";
const ok = new Counter("served"), limited = new Counter("limited");
export const options = { scenarios: { s: { executor: "constant-arrival-rate", rate: Number(__ENV.RATE), timeUnit: "1s",
  duration: "30s", preAllocatedVUs: 20, maxVUs: 60 } } };
export default function () {
  const r = http.get("http://haproxy:9080/api/me", { headers: { Cookie: __ENV.COOKIE },
    responseCallback: http.expectedStatuses(200, 429) });
  (r.status === 200 ? ok : limited).add(1);
}
export function handleSummary(d) {
  const v = (k) => (d.metrics[k] ? d.metrics[k].values.count : 0);
  return { stdout: "SUMMARY " + JSON.stringify({ served: v("served"), limited: v("limited") }) + "\n" };
}
JS
for who in noisy:80 calm:5; do
  docker run -d --name "tc-load-${who%%:*}" --network teleclinic -e RATE=${who##*:} -e COOKIE="$COOKIE" \
    -e K6_SCRIPT="$(cat "$out/p.js")" --entrypoint sh grafana/k6:0.54.0 \
    -c 'printf "%s" "$K6_SCRIPT" > /tmp/p.js && exec k6 run --quiet --no-color /tmp/p.js' >/dev/null
done
for who in noisy:80 calm:5; do
  n=${who%%:*}; docker wait "tc-load-$n" >/dev/null
  r=$(docker logs "tc-load-$n" 2>/dev/null | grep '^SUMMARY ' | cut -c9-); docker rm "tc-load-$n" >/dev/null
  note "$n client (${who##*:}/s for 30 s): $(json '"%d served, %d refused with 429" % (d["served"], d["limited"])' <<<"$r")"
done
pass "capacity levels measured; see the numbers above"
