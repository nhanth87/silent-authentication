#!/usr/bin/env bash
# CAMARA Number Verification v2 conformance (request Phase 2) against the PACKAGED
# dists, driven by the shipped bank-backend-sdk (customer backend) and
# ue-sdk-android (device).
#
#   ./scripts/package-dist.sh all                       # dists with the current code
#   (cd sdk/bank-backend && mvn -o -q compile)
#   (cd sdk/ue-android && mvn -o -q compile)
#   ./scripts/camara-nv2-conformance.sh                 # exit 0 = every check passed
#
# The gateway runs in the prod-shaped configuration that matters here:
# private_key_jwt required, ES256 only (no HS256), connection-source network
# authentication (trust-src-header=false), the amr header not trusted, DPoP on,
# and a 20 s token lifetime so expiry is tested without a 5-minute wait.
# Everything runs in a private user + network namespace; lo also carries
# 10.20.30.40, the address the lab resolver binds to +251911111111.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "${NV2_INNER:-}" != 1 ]]; then
  exec env NV2_INNER=1 unshare -rn "$0" "$@"
fi
ip link set lo up
ip addr add 10.20.30.40/32 dev lo

LOGS="$ROOT/target/camara-nv2-conformance"
rm -rf "$LOGS"; mkdir -p "$LOGS"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
CP="$ROOT/sdk/bank-backend/target/classes:$ROOT/sdk/ue-android/target/classes"
RUNNER="$ROOT/scripts/sdk-e2e/Nv2Conformance.java"
TTL=20

CLIENTS=$(java -cp "$CP" "$RUNNER" --emit-clients "$LOGS/keys") || { echo "cannot generate client keys"; exit 2; }

pids=()
cleanup() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done
  sleep 3
  for p in "${pids[@]}"; do kill -9 "$p" 2>/dev/null; done
}
trap cleanup EXIT

(cd "$ROOT/dist-ecs" && exec ./run.sh) > "$LOGS/ecs.log" 2>&1 &
pids+=($!)
(cd "$ROOT/dist" && SAS_LOG_DIR="$LOGS/camara-logs" SAS_JAVA_OPTS="\
 -Dsas.security.token-validation-enabled=true\
 -Dsas.security.accept-hs256=false\
 -Dsas.security.trust-amr-header=false\
 -Dsas.oauth.require-client-auth=true\
 -Dsas.oauth.authorize.trust-src-header=false\
 -Dsas.oauth.access-token-ttl-seconds=$TTL\
 -Dsas.oauth.clients-json=$CLIENTS" exec ./run.sh) > "$LOGS/camara.log" 2>&1 &
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

java -cp "$CP" "$RUNNER" --run "$LOGS/keys" "$TTL"
rc=$?
echo
echo "logs: $LOGS"
exit $rc
