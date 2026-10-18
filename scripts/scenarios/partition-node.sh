#!/usr/bin/env bash
# Scene 4b: split-brain protection. Cut one node (etcd + Patroni + Kafka) off the network.
# By default the node holding the database leader, the most interesting case: it must demote itself,
# refuse writes, and the majority must elect a new leader. Usage: partition-node.sh [node number]
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/cluster.sh

old=$(leader); [ -n "$old" ] || fail "no leader"
n=${1:-${old#pg-}}
majority=$(echo 1 2 3 | tr ' ' '\n' | grep -v "^$n$" | tr '\n' ' ')
first=${majority%% *}
ensure_probe_table "$first"

step "1. cluster before (leader $old); isolating node $n: etcd-$n, pg-$n, kafka-$n"
members
before=$(pg_proxy "$first" "SELECT count(*) FROM ha_probe")

step "2. network cut: docker network disconnect"
t_cut=$(now_ms)
for s in etcd-$n pg-$n kafka-$n; do docker network disconnect "$NET" "$(c $s)"; done
note "node $n is isolated at $(date +%T)"

step "3. the isolated node stops accepting writes; then the majority elects a leader"
# Writable means: Postgres answers and is not in recovery. Stopping, restarting or read-only all count as demoted.
was_leader=$([ "$old" = "pg-$n" ] && echo yes || echo no)
t_demote=""; t_new=""; new=""
for i in $(seq 1 240); do
  if [ -z "$t_demote" ] && [ "$(pg_local "$n" "SELECT pg_is_in_recovery()")" != f ]; then t_demote=$(now_ms); fi
  if [ -z "$t_new" ]; then cand=$(leader "$majority"); [ -n "$cand" ] && [ "$cand" != "pg-$n" ] && { new=$cand; t_new=$(now_ms); }; fi
  [ -n "$t_demote" ] && [ -n "$t_new" ] && break
  sleep 0.5
done
[ -n "$t_new" ] || fail "majority did not elect a leader"
secs() { awk -v m="$1" 'BEGIN{printf "%.1f", m/1000}'; }
if [ "$was_leader" = yes ]; then
  [ -n "$t_demote" ] || fail "isolated leader never stopped accepting writes"
  note "pg-$n (old leader) stopped accepting writes after $(secs $((t_demote - t_cut))) s"
  note "$new became leader on the majority side after $(secs $((t_new - t_cut))) s"
  [ "$t_demote" -lt "$t_new" ] || fail "overlap: a new leader existed while the old one still accepted writes"
  note "no overlap: the old leader stepped down $(secs $((t_new - t_demote))) s before the new one took over"
else
  note "pg-$n was a replica; leader $new on the majority side is unaffected"
fi

step "4. a write sent directly to the isolated node is refused"
for i in $(seq 1 120); do
  refused=$(pg_local "$n" "INSERT INTO ha_probe (note) VALUES ('written on the minority side')")
  [[ $refused == *read-only* ]] && break; sleep 1   # wait until Patroni has it running again as a replica
done
note "${refused:0:90}"
[[ $refused == *read-only* || $refused == *"could not connect"* || $refused == *"No such file"* ]] || fail "the isolated node accepted a write"

step "5. the majority keeps accepting writes through pgproxy"
for i in $(seq 1 30); do ok=$(pg_proxy "$first" "INSERT INTO ha_probe (note) VALUES ('majority during partition') RETURNING id"); [[ $ok =~ ^[0-9]+$ ]] && break; sleep 1; done
[[ $ok =~ ^[0-9]+$ ]] || fail "write through pgproxy failed on the majority side"
note "write through pgproxy returned id $ok"
members "$majority"

step "6. heal the network"
for s in etcd-$n pg-$n kafka-$n; do docker network connect --alias "$s" "$NET" "$(c $s)"; done
for i in $(seq 1 90); do
  st=$(cluster_json "$first" | python3 -c "import json,sys; print(next((m['role']+'/'+m['state'] for m in json.load(sys.stdin)['members'] if m['name']=='pg-$n'), ''))")
  [[ $st == *standby/streaming* || $st == replica/streaming ]] && break; sleep 1
done
note "pg-$n is back as $st"
members "$first"

step "7. no divergent rows: every node has the same rows"
# Writes may still be running (the cockpit probes all the time), so compare a fixed range: every row up to the
# leader's current highest id, once each node has replayed that far.
lead=$(leader "$first" | sed 's/pg-//')
cut=$(pg_local "$lead" "SELECT max(id) FROM ha_probe")
q="SELECT count(*) || ':' || md5(string_agg(id || note, ',' ORDER BY id)) FROM ha_probe WHERE id <= $cut"
for i in $(seq 1 30); do
  counts=$(for k in 1 2 3; do pg_local "$k" "$q"; done | sort -u)
  [ "$(echo "$counts" | wc -l)" = 1 ] && break; sleep 1
done
note "rows up to id $cut, count:checksum per node -> $(echo $counts | cut -c1-60)"
[ "$(echo "$counts" | wc -l)" = 1 ] || fail "nodes differ after heal"
[ "$(pg_local "$n" "SELECT count(*) FROM ha_probe WHERE note = 'written on the minority side'")" = 0 ] || fail "minority write survived"

pass "minority refused writes, never two writable leaders ($old -> $new), identical rows on all nodes after heal"
