#!/usr/bin/env bash
# Scene 2 (end): an administrator switches the model off in one call; new cases are rules-only and the
# clinician queue shows the banner. Usage: model-kill-switch.sh [off|on] [reason]
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a
source scripts/lib/session.sh
jar=$(mktemp); trap 'rm -f "$jar" "$jar.status"' EXIT
action=${1:-off}; reason=${2:-"drift alert under investigation"}
login admin1 "$jar"
if [ "$action" = off ]; then path=/triage/v1/models/kill-switch; else path=/triage/v1/models/activate; fi
r=$(api "$jar" POST "$path" "{\"reason\":\"$reason\"}")
[ "$(status "$jar")" = 200 ] || fail "HTTP $(status "$jar") $r"
echo "   model $(json 'd["modelVersion"] + " is now " + d["mode"] + ": " + str(d["reason"])' <<<"$r")"
