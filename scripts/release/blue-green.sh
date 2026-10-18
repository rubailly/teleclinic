#!/usr/bin/env bash
# Scene 4d: blue/green release under load, with HAProxy doing the shifting (no cloud load balancer).
# The new version starts on the idle colour at weight 0, then takes 1, 10, 50 and 100 percent of traffic while k6
# sends steady load through the public edge. At every step a gate reads the new colour's own error counters in
# HAProxy; a breach puts all traffic back on the live colour at once and stops the new one.
#
#   blue-green.sh [version]                          release (default v1.5)
#   blue-green.sh v1.6 --fault-rate 0.3 --expect-rollback   rollback drill: the new version fails 30% of API calls
#   blue-green.sh --switch-back                      put traffic back on the previous colour (kept at weight 0)
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/cluster.sh
export REPO_DIR=${REPO_DIR:-$PWD}
COMPOSE=(docker compose --project-directory . --env-file .env -f compose/edge.yml -f compose/data.yml
         -f compose/ai.yml -f compose/apps.yml -f compose/obs.yml --profile release)
version=v1.5; fault=0; expect=release; mode=release
while [ $# -gt 0 ]; do
  case $1 in
    --fault-rate) fault=$2; shift ;;
    --expect-rollback) expect=rollback ;;
    --switch-back) mode=switch-back ;;
    v*) version=$1 ;;
    *) fail "unknown argument $1" ;;
  esac
  shift
done
STEPS=${STEPS:-"1:30 10:20 50:20 100:15"}  # percent:seconds
MAX_ERROR_PCT=${MAX_ERROR_PCT:-1}
RATE=${RATE:-12}  # requests per second; the edge allows 20 per client and the cockpit probe uses 4
trap 'docker rm -f tc-release-load >/dev/null 2>&1' EXIT

hap() {  # one command on HAProxy's runtime API
  exec 3<>/dev/tcp/127.0.0.1/9999 || fail "HAProxy runtime API not reachable on 127.0.0.1:9999"
  printf '%s\n' "$1" >&3; timeout 3 cat <&3; exec 3<&-
}
weight() { hap "get weight core/core-$1" | cut -d' ' -f1; }
set_weights() { hap "set weight core/core-$1 $2" >/dev/null; hap "set weight core/core-$3 $4" >/dev/null; }
server() {  # responses errors status: counters for one colour, from HAProxy's own statistics
  hap "show stat" | python3 -c '
import csv, sys
rows = list(csv.reader(sys.stdin)); head = [h.lstrip("# ") for h in rows[0]]
for r in rows[1:]:
    d = dict(zip(head, r))
    if d.get("pxname") == "core" and d.get("svname") == "core-'"$1"'":
        n = lambda k: int(d.get(k) or 0)
        responses = sum(n(f"hrsp_{c}xx") for c in range(1, 6)) + n("hrsp_other")
        print(responses, n("hrsp_5xx") + n("eresp") + n("econ"), d["status"].split()[0])'
}
version_of() { docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$(c core-$1)" 2>/dev/null | sed -n 's/^RELEASE_VERSION=//p'; }

bw=$(weight blue); gw=$(weight green)
if [ "$bw" -gt 0 ] && [ "$gw" -gt 0 ]; then fail "both colours take traffic (blue $bw, green $gw): a release is in progress"; fi
if [ "$bw" -gt 0 ]; then live=blue; idle=green; else live=green; idle=blue; fi

if [ "$mode" = switch-back ]; then
  step "switch back: $idle ($(version_of $idle)) takes all traffic again, $live ($(version_of $live)) goes to weight 0"
  # HAProxy needs a few seconds to notice a stopped server, so ask Docker too
  health=$(docker inspect --format '{{if ne .State.Status "running"}}{{.State.Status}}{{else if .State.Health}}{{.State.Health.Status}}{{end}}' "$(c core-$idle)" 2>/dev/null)
  read -r _ _ st < <(server "$idle")
  [ "$health" = healthy ] && [ "$st" = UP ] || fail "$idle is not available (container: ${health:-missing}, HAProxy: $st); traffic stays on $live"
  set_weights "$idle" 100 "$live" 0
  pass "traffic is on $idle ($(version_of $idle)) again"
  exit 0
fi

step "1. $live ($(version_of $live)) is live; releasing $version to $idle"
[ "$fault" != 0 ] && note "rollback drill: $version fails $(awk -v f="$fault" 'BEGIN{print f*100}')% of API calls on purpose (health checks stay green)"
IDLE=$(tr a-z A-Z <<<"$idle")
# Always from the current source, so a tag left over from an older build can never be released by mistake
# (a quick cached build when nothing changed). REUSE_IMAGE=1 releases the existing image as it is.
if [ "${REUSE_IMAGE:-0}" != 1 ] || ! docker image inspect "teleclinic/core:$version" >/dev/null 2>&1; then
  note "building teleclinic/core:$version from the current source"
  env "${IDLE}_VERSION=$version" "${COMPOSE[@]}" build -q "core-$idle" || fail "build failed"
fi
t_start=$(now_ms)
env "${IDLE}_VERSION=$version" "${IDLE}_FAULT_RATE=$fault" "${COMPOSE[@]}" up -d --no-deps --wait "core-$idle" >/dev/null 2>&1 \
  || { hap "set server core/core-$idle state maint" >/dev/null; "${COMPOSE[@]}" stop -t 5 "core-$idle" >/dev/null 2>&1
         fail "core-$idle ($version) did not become healthy; it took no traffic and is stopped. Logs: docker logs $(c core-$idle)"; }
hap "set server core/core-$idle state ready" >/dev/null
for i in $(seq 1 30); do read -r _ _ st < <(server "$idle"); [ "$st" = UP ] && break; sleep 1; done
[ "$st" = UP ] || fail "HAProxy does not see core-$idle as up ($st)"
note "core-$idle runs $(version_of $idle), healthy, weight 0 (no traffic yet) after $(awk -v m=$(( $(now_ms) - t_start )) 'BEGIN{printf "%.0f", m/1000}') s"

step "2. steady load through the public edge: $RATE requests per second (k6)"
docker rm -f tc-release-load >/dev/null 2>&1
# Detached, with k6 as the container's main process, so it gets the stop signal and its summary lands in the logs
docker run -d --name tc-release-load --network host -e EDGE="$PUBLIC_BASE_URL" -e RATE="$RATE" \
  -e K6_SCRIPT="$(cat scripts/release/load.js)" --entrypoint sh grafana/k6:0.54.0 \
  -c 'printf "%s" "$K6_SCRIPT" > /tmp/load.js && exec k6 run --quiet --no-color /tmp/load.js' >/dev/null \
  || fail "could not start the load generator"
sleep 5
stop_load() {  # prints: requests failed p95 max
  docker kill -s INT tc-release-load >/dev/null 2>&1
  timeout 30 docker wait tc-release-load >/dev/null 2>&1
  docker logs tc-release-load 2>/dev/null | grep '^{' | tail -1 | python3 -c '
import json, sys
line = sys.stdin.read().strip()
d = json.loads(line) if line else {}
print(d.get("requests", "?"), d.get("failed", "?"), d.get("p95", "?"), d.get("max", "?"))'
}

step "3. shift traffic in steps; gate: under ${MAX_ERROR_PCT}% errors on $idle at each step"
outcome=released
for s in $STEPS; do
  pct=${s%:*}; secs=${s#*:}
  read -r r0 e0 _ < <(server "$idle")
  set_weights "$idle" "$pct" "$live" $((100 - pct))
  for i in $(seq 1 $((secs * 2))); do
    sleep 0.5
    read -r r1 e1 st < <(server "$idle")
    req=$((r1 - r0)); err=$((e1 - e0))
    if [ "$st" != UP ] || { [ "$err" -gt 0 ] && [ $((err * 100)) -gt $((req * MAX_ERROR_PCT)) ]; }; then
      outcome=rollback; break
    fi
  done
  note "$(printf '%3s%%' "$pct") on $idle for ${secs} s: $req requests, $err errors$([ "$outcome" = rollback ] && echo "  <- gate breached")"
  [ "$outcome" = rollback ] && break
done

if [ "$outcome" = rollback ]; then
  step "4. automatic rollback: all traffic back on $live"
  t_rb=$(now_ms)
  set_weights "$live" 100 "$idle" 0
  # Out of the pool for good before it stops, so nothing can switch traffic to it by mistake
  hap "set server core/core-$idle state maint" >/dev/null
  note "weights switched in $(( $(now_ms) - t_rb )) ms; $idle ($version) in maintenance and stopped"
  "${COMPOSE[@]}" stop -t 5 "core-$idle" >/dev/null 2>&1
  sleep 3
  read -r total failed p95 _ < <(stop_load)
  note "load: $total requests, $failed failed (served by $version during the canary), p95 $p95 ms"
  [ "$expect" = rollback ] || fail "release of $version rolled back at $pct%"
  pass "bad build $version caught at $pct% and rolled back automatically; $live ($(version_of $live)) kept serving"
  exit 0
fi

step "4. $live drained; kept at weight 0 for a one-command switch back"
sleep 3
read -r total failed p95 max < <(stop_load)
read -r _ _ st < <(server "$live")
note "$live ($(version_of $live)): weight $(weight "$live"), $st"
note "load: $total requests, $failed failed, p95 $p95 ms, max $max ms"
[ "$expect" = release ] || fail "the drill expected a rollback, but $version passed every gate"
[ "$failed" != "?" ] || fail "no load summary from k6 (docker logs tc-release-load)"
[ "$failed" = 0 ] || fail "$failed requests failed during the release"
pass "$version live on $idle in $(awk -v m=$(( $(now_ms) - t_start )) 'BEGIN{printf "%.0f", m/1000}') s with 0 failed requests; switch back: blue-green.sh --switch-back"
