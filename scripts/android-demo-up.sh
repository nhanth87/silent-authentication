#!/usr/bin/env bash
# Server side of the Android emulator demo (demo-android/). LAB ONLY.
#
#   ./scripts/android-demo-up.sh        # needs dist/ + dist-ecs/ + bank-backend-sdk built; Ctrl-C stops all
#
#   sas-ecs      :8095  lab resolver: 10.20.30.40:55555 -> +251911111111
#   sas-camara   :8085  OAuth AS + CAMARA NV v2, client bank-lab
#   edge-sim     :8084  stamps X-Sas-Src-Ip/Port (the emulator NATs everything as 127.0.0.1)
#   demo-bank    :8092  /nv/start, /nv/complete (bank-backend-sdk)
#
# The emulator reaches these as 10.0.2.2:<port>. All listen on 127.0.0.1 only.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/target/android-demo"
mkdir -p "$LOGS"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
BANK_JAR="$ROOT/bank-backend-sdk/target/bank-backend-sdk-0.1.0-SNAPSHOT.jar"
[[ -f "$BANK_JAR" ]] || { echo "build bank-backend-sdk first: (cd bank-backend-sdk && mvn package)"; exit 2; }

for p in 8084 8085 8092 8095; do
  if ss -ltn "sport = :$p" | grep -q LISTEN; then echo "port $p is already in use"; exit 2; fi
done

CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["authorization_code","urn:openid:params:grant-type:ciba"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","number-verification:device-phone-number:read","sim-swap:check"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'

pids=()
cleanup() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done
  sleep 3
  for p in "${pids[@]}"; do kill -9 "$p" 2>/dev/null; done
}
trap cleanup EXIT
trap 'exit 130' INT TERM

(cd "$ROOT/dist-ecs" && exec ./run.sh) > "$LOGS/ecs.log" 2>&1 &
pids+=($!)
(cd "$ROOT/dist" && SAS_LOG_DIR="$LOGS/camara-logs" \
   SAS_JAVA_OPTS="-Dsas.security.token-validation-enabled=true -Dsas.oauth.authorize.trust-src-header=true -Dsas.oauth.clients-json=$CLIENTS" \
   exec ./run.sh) > "$LOGS/camara.log" 2>&1 &
pids+=($!)

up() {
  for _ in $(seq 1 90); do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' "$1")" == 200 ]] && return 0
    sleep 1
  done
  echo "DOWN $1 (logs: $LOGS)"; exit 2
}
up http://127.0.0.1:8095/entitlement/status
up http://127.0.0.1:8085/camara/health

java "$ROOT/demo-android/server/EdgeSim.java" 8084 http://127.0.0.1:8085 > "$LOGS/edge-sim.log" 2>&1 &
pids+=($!)
java -cp "$BANK_JAR" "$ROOT/demo-android/server/DemoBank.java" 8092 > "$LOGS/demo-bank.log" 2>&1 &
pids+=($!)
up_post() {
  for _ in $(seq 1 30); do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$1")" == 200 ]] && return 0
    sleep 1
  done
  echo "DOWN $1"; exit 2
}
up_post http://127.0.0.1:8092/nv/start

echo
echo "ready: emulator -> http://10.0.2.2:8092 (demo-bank). Logs: $LOGS. Ctrl-C stops everything."
tail -n +1 -f "$LOGS/edge-sim.log" "$LOGS/demo-bank.log" &
pids+=($!)
wait
