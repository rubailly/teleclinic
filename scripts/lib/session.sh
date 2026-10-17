# Shared helpers for the end-to-end scripts. Source after loading .env.
# A "session" is a curl cookie jar, exactly what a browser keeps: no tokens, only cookies.

step() { printf '\e[36m> %s\e[0m\n' "$*"; }
fail() { printf '\e[31mFAIL: %s\e[0m\n' "$*"; exit 1; }
pass() { printf '\e[32mPASS: %s\e[0m\n' "$*"; }
location() { grep -i '^location:' | head -1 | sed 's/^[Ll]ocation: *//' | tr -d '\r'; }

# login <user> <jar>: browser-style authorization code login through the public edge
login() {
  local user=$1 jar=$2 start auth action callback
  start=$(curl -s -D - -o /dev/null -c "$jar" -b "$jar" "$PUBLIC_BASE_URL/login" | location)
  case $start in http*) ;; *) start="$PUBLIC_BASE_URL$start" ;; esac
  auth=$(curl -s -D - -o /dev/null -c "$jar" -b "$jar" "$start" | location)
  action=$(curl -s -c "$jar" -b "$jar" "$auth" | grep -o 'id="kc-form-login"[^>]*action="[^"]*"' \
    | sed 's/.*action="//; s/"$//; s/&amp;/\&/g')
  [ -n "$action" ] || fail "login form not found for $user"
  callback=$(curl -s -D - -o /dev/null -c "$jar" -b "$jar" --data-urlencode "username=$user" \
    --data-urlencode "password=$DEMO_USER_PASSWORD" "$action" | location)
  curl -s -o /dev/null -c "$jar" -b "$jar" "$callback"
  # First API call also sets the XSRF-TOKEN cookie the apps echo back on writes
  curl -s -o /dev/null -c "$jar" -b "$jar" "$PUBLIC_BASE_URL/api/me"
}

xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$1" | tail -1; }

# api <jar> <method> <path> [json body] [extra curl args...]: prints the body.
# The HTTP status goes to <jar>.status (read it with `status <jar>`), because callers use $(api ...),
# which runs in a subshell where a variable would be lost.
api() {
  local jar=$1 method=$2 path=$3 body=${4:-}; shift 4 2>/dev/null || shift $#
  local out; out=$(mktemp)
  curl -s -o "$out" -w '%{http_code}' -X "$method" -c "$jar" -b "$jar" \
    -H "X-XSRF-TOKEN: $(xsrf "$jar")" -H 'Content-Type: application/json' \
    ${body:+--data "$body"} "$@" "$PUBLIC_BASE_URL$path" > "$jar.status"
  cat "$out"; rm -f "$out"
}

status() { cat "$1.status"; }

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {}, {'d': d}))" "$1"; }
note() { printf '   %s\n' "$*"; }
