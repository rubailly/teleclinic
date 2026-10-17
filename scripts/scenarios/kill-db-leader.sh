#!/usr/bin/env bash
# Scene 4a: kill the PostgreSQL leader while writes are running.
# Pass criteria (Section 7.4): failover under 60 s, and every write acknowledged before or after the kill is present.
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/cluster.sh
log=$(mktemp); trap 'rm -f "$log"' EXIT

step "1. cluster before"
old=$(leader); [ -n "$old" ] || fail "no leader"
members
client=$(for n in 1 2 3; do [ "pg-$n" != "$old" ] && { echo $n; break; }; done)
ensure_probe_table "$client"

step "2. writes through pgproxy every 200 ms (from pg-$client), as the core does"
( while true; do
    t=$(now_ms); id=$(pg_proxy "$client" "INSERT INTO ha_probe (note) VALUES ('kill-db-leader') RETURNING id")
    if [[ $id =~ ^[0-9]+$ ]]; then echo "$t ok $id"; else echo "$t err"; fi
    sleep 0.2
  done ) > "$log" &
writer=$!
sleep 3

step "3. kill the leader ($old)"
t_kill=$(now_ms)
docker kill "$(c "$old")" >/dev/null
note "killed at $(date +%T)"

step "4. wait for a new leader and the first successful write after the kill"
for i in $(seq 1 120); do
  new=$(leader "$(echo 1 2 3 | tr ' ' '\n' | grep -v "${old#pg-}" | tr '\n' ' ')" 2>/dev/null)
  first_ok=$(awk -v k="$t_kill" '$1>k && $2=="ok"{print $1; exit}' "$log")
  [ -n "$new" ] && [ "$new" != "$old" ] && [ -n "$first_ok" ] && break
  sleep 0.5
done
sleep 2; kill "$writer" 2>/dev/null; wait "$writer" 2>/dev/null
[ -n "${first_ok:-}" ] || fail "no successful write after the kill"
failover_ms=$(( first_ok - t_kill ))
note "new leader: $new"
note "writes resumed after $(awk -v m=$failover_ms 'BEGIN{printf "%.1f", m/1000}') s"
ok=$(grep -c ' ok ' "$log"); err=$(grep -c ' err' "$log")
note "write attempts: $((ok + err)), acknowledged: $ok, failed during the switch: $err (the caller retries these)"

step "5. every acknowledged write is still there (zero lost committed writes)"
ids=$(awk '$2=="ok"{print $3}' "$log" | paste -sd, -)
found=$(pg_proxy "$client" "SELECT count(*) FROM ha_probe WHERE id IN ($ids)")
note "acknowledged: $ok, found after failover: $found"
[ "$found" = "$ok" ] || fail "lost $((ok - found)) committed writes"

step "6. restart the old leader: it rejoins as a replica (pg_rewind if needed)"
docker start "$(c "$old")" >/dev/null
for i in $(seq 1 60); do
  role=$(cluster_json "$client" | python3 -c "import json,sys; print(next((m['role']+'/'+m['state'] for m in json.load(sys.stdin)['members'] if m['name']=='$old'), ''))")
  [[ $role == *replica/streaming* || $role == *sync_standby/streaming* || $role == *quorum_standby/streaming* ]] && break
  sleep 1
done
members "$client"

[ "$failover_ms" -lt 60000 ] || fail "failover took longer than 60 s"
pass "leader $old killed, $new took over in $(awk -v m=$failover_ms 'BEGIN{printf "%.1f", m/1000}') s, 0 of $ok acknowledged writes lost"
