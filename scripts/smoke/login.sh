#!/usr/bin/env bash
# End-to-end login through the public edge, the way a browser does it:
#   unauthenticated -> 401, authorize with PKCE at IremboAccount (stand-in), callback, session cookie,
#   /api/me returns the identity, and no token ever reaches the client.
# Usage: scripts/smoke/login.sh [username]
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a

user=${1:-patient1}
jar=$(mktemp); trap 'rm -f "$jar" "$jar.html" "$jar.body"' EXIT
base=$PUBLIC_BASE_URL
step() { printf '\e[36m> %s\e[0m\n' "$*"; }
fail() { printf '\e[31mFAIL: %s\e[0m\n' "$*"; exit 1; }
location() { grep -i '^location:' | head -1 | sed 's/^[Ll]ocation: *//' | tr -d '\r'; }

step "1. /api/me without a session answers 401"
code=$(curl -s -o /dev/null -w '%{http_code}' "$base/api/me")
[ "$code" = 401 ] || fail "expected 401, got $code"

step "2. /login redirects to the authorization endpoint with PKCE, state and nonce"
start=$(curl -s -D - -o /dev/null -c "$jar" -b "$jar" "$base/login" | location)
case $start in http*) ;; *) start="$base$start" ;; esac
auth=$(curl -s -D - -o /dev/null -c "$jar" -b "$jar" "$start" | location)
for p in "$KEYCLOAK_PUBLIC_URL/realms/$OIDC_REALM/protocol/openid-connect/auth" code_challenge= code_challenge_method=S256 state= nonce=; do
  [[ $auth == *"$p"* ]] || fail "authorization URL lacks $p: $auth"
done
echo "   $auth" | cut -c1-140

step "3. sign in at IremboAccount (stand-in) as $user"
curl -s -c "$jar" -b "$jar" "$auth" -o "$jar.html"
action=$(grep -o 'id="kc-form-login"[^>]*action="[^"]*"' "$jar.html" | sed 's/.*action="//; s/"$//; s/&amp;/\&/g')
[ -n "$action" ] || fail "login form not found"
callback=$(curl -s -D - -o /dev/null -c "$jar" -b "$jar" --data-urlencode "username=$user" \
  --data-urlencode "password=$DEMO_USER_PASSWORD" "$action" | location)
[[ $callback == "$base/login/oauth2/code/iremboaccount?"* ]] || fail "unexpected redirect after login: $callback"

step "4. callback: the core exchanges the code on the back channel and starts a session"
headers=$(curl -s -D - -o /dev/null -c "$jar" -b "$jar" "$callback")
grep -qi '^set-cookie: TCSESSION=.*HttpOnly' <<<"$headers" || fail "no HttpOnly TCSESSION cookie"
grep -qi 'access_token\|id_token\|refresh_token' <<<"$headers" && fail "a token appeared in the callback response"

step "5. /api/me with the session cookie"
resp=$(curl -s -D "$jar.body" -c "$jar" -b "$jar" -H 'X-Correlation-Id: demo-smoke-0001' "$base/api/me")
echo "   $resp"
grep -q "\"username\":\"$user\"" <<<"$resp" || fail "/api/me did not return $user"
grep -qi 'token' <<<"$resp" && fail "/api/me leaked a token"
grep -qi '^x-correlation-id: demo-smoke-0001' "$jar.body" || fail "correlation id not echoed"
grep -qi '"issuer":"'"$KEYCLOAK_PUBLIC_URL/realms/$OIDC_REALM"'"' <<<"$resp" || fail "issuer is not the public issuer"

step "6. TeleClinic's cookies hold a session id only, never a token"
# Keycloak's own SSO cookies (KEYCLOAK_*, AUTH_SESSION_ID*, KC_*) belong to the identity provider, not to TeleClinic.
grep -vE 'KEYCLOAK_|AUTH_SESSION_ID|KC_' "$jar" | grep -qE 'eyJ[A-Za-z0-9_-]{20,}' && fail "a JWT is stored in a TeleClinic cookie"
grep -q TCSESSION "$jar" || fail "no TCSESSION cookie in the jar"

printf '\e[32mPASS: BFF login for %s through the edge\e[0m\n' "$user"
