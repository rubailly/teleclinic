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

for svc in $(docker compose --project-directory . --env-file .env -f compose/edge.yml -f compose/data.yml -f compose/apps.yml ps --services 2>/dev/null); do
  check "container: $svc" bash -c "docker inspect --format '{{.State.Health.Status}}' \$(docker compose --project-directory . --env-file .env -f compose/edge.yml -f compose/data.yml -f compose/apps.yml ps -q $svc) | grep -x healthy"
done

if [ "$fails" -eq 0 ]; then echo "All checks passed."; else echo "$fails check(s) failed."; fi
exit "$fails"
