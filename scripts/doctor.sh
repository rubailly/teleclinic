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

for svc in $(docker compose --project-directory . --env-file .env -f compose/edge.yml -f compose/data.yml -f compose/apps.yml ps --services 2>/dev/null); do
  check "container: $svc" bash -c "docker inspect --format '{{.State.Health.Status}}' \$(docker compose --project-directory . --env-file .env -f compose/edge.yml -f compose/data.yml -f compose/apps.yml ps -q $svc) | grep -x healthy"
done

if [ "$fails" -eq 0 ]; then echo "All checks passed."; else echo "$fails check(s) failed."; fi
exit "$fails"
