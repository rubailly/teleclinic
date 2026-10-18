#!/usr/bin/env bash
# Checks every health endpoint the demo depends on. Exit code is the number of failed checks.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a

fails=0
check() { # name, command...
  local name=$1; shift
  if out=$("$@" 2>&1); then
    printf '  \e[32mOK\e[0m   %-34s %s\n' "$name" "${out:0:60}"
  else
    printf '  \e[31mFAIL\e[0m %-34s %s\n' "$name" "${out:0:80}"
    fails=$((fails + 1))
  fi
}

echo "TeleClinic doctor"
check "edge: gateway ping"            curl -fsS --max-time 3 "$PUBLIC_BASE_URL/apisix/ping"
check "edge -> core: public ping"     curl -fsS --max-time 3 "$PUBLIC_BASE_URL/api/public/ping"
check "edge -> core: release"         curl -fsS --max-time 3 "$PUBLIC_BASE_URL/api/public/version"
check "edge: actuator not exposed"    bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 $PUBLIC_BASE_URL/actuator/health)\" = 404 ] && echo 404"
check "IremboAccount stand-in (OIDC)" bash -c "curl -fsS --max-time 3 $KEYCLOAK_PUBLIC_URL/realms/$OIDC_REALM/.well-known/openid-configuration | grep -o '\"issuer\":\"[^\"]*\"'"
check "Ops cockpit (scenario runner)"   curl -fsS --max-time 3 http://127.0.0.1:9500/health
scrape() {  # every Prometheus target up, except a core colour that is stopped on purpose (no release, or rolled back)
  local stopped=""
  for colour in blue green; do
    [ "$(docker inspect --format '{{.State.Running}}' "teleclinic-core-$colour-1" 2>/dev/null)" = true ] || stopped="$stopped core-$colour:8080"
  done
  curl -fsS --max-time 3 http://127.0.0.1:9790/api/v1/targets | STOPPED="$stopped" python3 -c "
import json, os, sys
stopped = os.environ['STOPPED'].split()
ts = [t for t in json.load(sys.stdin)['data']['activeTargets'] if t['labels']['instance'] not in stopped]
down = [t['labels']['instance'] for t in ts if t['health'] != 'up']
if down:
    sys.exit('down: ' + ', '.join(down))
print(len(ts), 'targets up' + (' (stopped on purpose: ' + ' '.join(stopped) + ')' if stopped else ''))"; }
check "Prometheus scraping"            scrape
check "Grafana"                        bash -c "curl -fsS --max-time 3 http://localhost:9700/api/health | grep -o '\"database\": *\"ok\"'"
emr_check() {  # optional part of the stack: report, never fail, when it is not running at all
  if ! docker ps --format '{{.Names}}' | grep -q '^teleclinic-emr-'; then echo "not running (optional: make emr-up)"; return 0; fi
  curl -fsS --max-time 5 -u "admin:$OPENMRS_ADMIN_PASSWORD" http://localhost:9880/openmrs/ws/rest/v1/session | grep -q '"authenticated":true' \
    || { echo "OpenMRS REST API not answering"; return 1; }
  curl -fsS --max-time 3 http://localhost:9480/health_check >/dev/null || { echo "Lightning not answering"; return 1; }
  local wf; wf=$(curl -fsS --max-time 5 -H "Authorization: Bearer $OPENFN_API_TOKEN" http://localhost:9480/api/workflows \
    | python3 -c "import json,sys; print(len(json.load(sys.stdin)['workflows']))" 2>/dev/null)
  [ "${wf:-0}" -ge 2 ] || { echo "sync workflows not deployed (make emr-up)"; return 1; }
  echo "OpenMRS up, Lightning up, $wf workflows"
}
sync_queue() {
  if ! docker ps --format '{{.Names}}' | grep -q '^teleclinic-emr-'; then echo "EMR not running"; return 0; fi
  source scripts/lib/cluster.sh
  local dead pending; dead=$(pg_proxy 1 "SELECT count(*) FROM sync.outbound WHERE status = 'DEAD'"); pending=$(pg_proxy 1 "SELECT count(*) FROM sync.outbound WHERE status = 'PENDING'")
  [ "$dead" = 0 ] || { echo "$dead dead letter(s): see the emr-sync-backlog runbook"; return 1; }
  echo "$pending waiting, no dead letters"
}
check "Facility EMR and sync gateway"  emr_check
check "Sync queue"                     sync_queue
check "USSD gateway"                  curl -fsS --max-time 3 http://localhost:9380/health
haproxy_pool() { curl -fsS --max-time 3 "http://localhost:9404/stats;csv" | awk -F, '$1=="core" && $2!="BACKEND" {printf "%s=%s(w%s) ", $2, $18, $19}'; }
check "HAProxy blue/green pool"       haproxy_pool

source scripts/lib/cluster.sh
patroni() { cluster_json | python3 -c "
import json,sys
ms=json.load(sys.stdin)['members']
lead=[m['name'] for m in ms if m['role']=='leader']
stream=[m['name'] for m in ms if m['role']!='leader' and m['state']=='streaming']
assert len(lead)==1 and len(stream)==2, ms
print('leader', lead[0], '| streaming', ' '.join(stream))"; }
kafka_isr() { docker exec "$(c kafka-1)" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --describe --topic consultation.paid 2>/dev/null \
  | awk '/Isr:/{for(i=1;i<=NF;i++) if($i=="Isr:") {n=split($(i+1),a,","); if(n<3) bad++; tot++}} END{if(bad>0||tot==0) exit 1; print tot " partitions, all with 3 in-sync replicas"}'; }
etcd_quorum() { docker exec "$(c etcd-1)" etcdctl --endpoints=etcd-1:2379,etcd-2:2379,etcd-3:2379 endpoint health 2>&1 | grep -c "is healthy" | grep -qx 3 && echo "3 of 3 members healthy"; }
check "Patroni cluster"                patroni
check "etcd quorum"                    etcd_quorum
check "Kafka replication"              kafka_isr
check "Valkey (shared sessions)"       docker exec "$(c valkey)" valkey-cli ping
model_mode() { docker exec "$(c triage-model)" python -c "import json,urllib.request; d=json.load(urllib.request.urlopen('http://127.0.0.1:8100/models/active')); print(d['modelVersion'], d['mode'])"; }
no_egress() {  # the anonymizer must not reach the internet; succeeding here is a failure
  if docker exec "$(c anonymizer)" python -c "import urllib.request; urllib.request.urlopen('https://pypi.org', timeout=3)" >/dev/null 2>&1; then
    echo "anonymizer reached the internet"; return 1; fi
  echo "anonymizer has no route to the internet"; }
check "AI model"                       model_mode
check "AI zone isolation"              no_egress

for svc in $(docker compose --project-directory . --env-file .env -f compose/edge.yml -f compose/data.yml -f compose/ai.yml -f compose/apps.yml -f compose/obs.yml ps --services 2>/dev/null); do
  check "container: $svc" bash -c "docker inspect --format '{{.State.Health.Status}}' \$(docker compose --project-directory . --env-file .env -f compose/edge.yml -f compose/data.yml -f compose/ai.yml -f compose/apps.yml -f compose/obs.yml ps -q $svc) | grep -x healthy"
done

if [ "$fails" -eq 0 ]; then echo "All checks passed."; else echo "$fails check(s) failed."; fi
exit "$fails"
