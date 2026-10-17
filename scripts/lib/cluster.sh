# Helpers for the data-tier scenarios. Source after loading .env.
NET=teleclinic
c() { echo "teleclinic-$1-1"; }                       # compose service -> container name
now_ms() { date +%s%3N; }
note() { printf '   %s\n' "$*"; }
step() { printf '\e[36m> %s\e[0m\n' "$*"; }
fail() { printf '\e[31mFAIL: %s\e[0m\n' "$*"; exit 1; }
pass() { printf '\e[32mPASS: %s\e[0m\n' "$*"; }

# Patroni's view of the cluster, asked from node $1 (default: first node that answers)
cluster_json() {
  local nodes=${1:-"1 2 3"} n
  for n in $nodes; do
    docker exec "$(c pg-$n)" curl -fs --max-time 2 http://127.0.0.1:8008/cluster 2>/dev/null && return 0
  done
  return 1
}
leader() { cluster_json "${1:-}" | python3 -c "import json,sys; print(next((m['name'] for m in json.load(sys.stdin)['members'] if m['role']=='leader'), ''))"; }
members() { cluster_json "${1:-}" | python3 -c "
import json,sys
for m in json.load(sys.stdin)['members']:
    print('   %-5s %-13s %-10s lag=%s' % (m['name'], m['role'], m['state'], m.get('lag', 0)))"; }

# SQL on one node over its local socket (works even while the node is cut off from the network)
pg_local() { docker exec "$(c pg-$1)" psql -U postgres -d teleclinic -Atq -c "$2" 2>&1; }
# SQL through pgproxy (the path the core uses), run from node $1's container as the application user
pg_proxy() { docker exec -e PGPASSWORD="$DB_PASSWORD" "$(c pg-$1)" psql -h pgproxy -p 5000 -U "$DB_USER" -d teleclinic -Atq -c "$2" 2>&1; }

ensure_probe_table() {
  pg_proxy "$1" "CREATE TABLE IF NOT EXISTS ha_probe (id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(), note text)" >/dev/null
}
