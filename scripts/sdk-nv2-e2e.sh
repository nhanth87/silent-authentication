#!/usr/bin/env bash
# CAMARA NV v2 device SDKs end to end against the PACKAGED dists.
#
#   ./scripts/sdk-nv2-e2e.sh            # needs dist/ + dist-ecs/ + ue-sdk-android built
#
# Runs in a private user + network namespace (no root, no port clash with
# anything else on the host). Inside it, lo also carries 10.20.30.40 — the
# address the lab resolver binds to +251911111111 — so a socket bound to
# 10.20.30.40:55555 stands in for the cellular bearer and sas-camara identifies
# the subscriber from the CONNECTION source (trust-src-header is forced off).
#
#   Android SDK (JVM): scripts/sdk-e2e/Nv2AndroidE2E.java   cases A-E
#   Web SDK (node):    scripts/sdk-e2e/nv2-web-e2e.mjs      cases A-D
# The iOS SDK has no runtime here (Network.framework is Apple-only); its unit
# tests run on Linux with `swift test` (see sdk/ue-ios/README.md).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "${SDK_E2E_INNER:-}" != 1 ]]; then
  exec env SDK_E2E_INNER=1 unshare -rn "$0" "$@"
fi
ip link set lo up
ip addr add 10.20.30.40/32 dev lo

LOGS="$ROOT/target/sdk-nv2-e2e"
mkdir -p "$LOGS"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["authorization_code","urn:openid:params:grant-type:ciba"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","sim-swap:check"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'

pids=()
cleanup() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done
  sleep 3
  for p in "${pids[@]}"; do kill -9 "$p" 2>/dev/null; done
}
trap cleanup EXIT

(cd "$ROOT/dist-ecs" && exec ./run.sh) > "$LOGS/ecs.log" 2>&1 &
pids+=($!)
(cd "$ROOT/dist" && SAS_LOG_DIR="$LOGS/camara-logs" \
   SAS_JAVA_OPTS="-Dsas.security.token-validation-enabled=true -Dsas.oauth.authorize.trust-src-header=false -Dsas.oauth.clients-json=$CLIENTS" \
   exec ./run.sh) > "$LOGS/camara.log" 2>&1 &
pids+=($!)

up() {
  for _ in $(seq 1 90); do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' "$1")" == 200 ]] && return 0
    sleep 1
  done
  echo "DOWN $1"; exit 2
}
up http://127.0.0.1:8095/entitlement/status
up http://127.0.0.1:8085/camara/health

rc=0
echo "######## Android SDK (ue-sdk-android on the JVM)"
java -cp "$ROOT/sdk/ue-android/target/classes" "$ROOT/scripts/sdk-e2e/Nv2AndroidE2E.java" || rc=1
echo
echo "######## Web SDK (ue-sdk-web on node)"
node "$ROOT/scripts/sdk-e2e/nv2-web-e2e.mjs" || rc=1
echo
echo "######## server side, as logged by sas-camara"
grep -hoE '/authorize (issued a code|refused)[^"]*' "$LOGS/camara.log" "$LOGS"/camara-logs/*.log 2>/dev/null | sort | uniq -c
exit $rc
