#!/usr/bin/env bash
# Bring the lab stack up for the QR hand-off contract probes.
#
#   ./scripts/qr-lab.sh up live             # real MAP transport (verdict = FALLBACK)
#   ./scripts/qr-lab.sh up live enforced    # + sas.security.enforce-api-keys (prod shape)
#   ./scripts/qr-lab.sh up lab              # lab evidence (verdict = APPROVED)
#   ./scripts/qr-lab.sh down
#
# Then: python3 scripts/qr-request-matrix.py
#       python3 scripts/qr-tenant-probe.py     # only in the "enforced" shape
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/target/qr-contract"
mkdir -p "$LOGS"

CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["urn:openid:params:grant-type:ciba"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","sim-swap:check"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'

kill_tree() {   # $1 = log basename whose process we want gone
  local pid
  for pid in $(pgrep -f 'dist/quarkus-run.jar|dist-ecs/quarkus-run.jar|sas-jss7-testapp' 2>/dev/null); do
    [ "$pid" = "$$" ] && continue
    if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q 'silent-authentication'; then
      kill "$pid" 2>/dev/null
    fi
  done
  sleep 5
}

wait_up() {     # $1 = url, $2 = label
  local i
  for i in $(seq 1 40); do
    if [ "$(curl -s --max-time 3 -o /dev/null -w '%{http_code}' "$1")" = "200" ]; then
      echo "  $2 up"
      return 0
    fi
    sleep 2
  done
  echo "  $2 DID NOT COME UP"
  return 1
}

case "${1:-up}" in
  down)
    kill_tree
    echo "lab stack stopped"
    ;;
  up)
    kill_tree
    if [ "${2:-live}" = "live" ]; then
      ( cd "$ROOT/dist-ecs" && setsid env \
          SAS_TRANSPORT_MAP=jss7 SAS_TRANSPORT_AUTHVECTOR=map \
          SAS_BINDING_SOURCE_ORDER=map-smi \
          SAS_TRANSPORT_JSS7_CONFIG="$ROOT/dist-ecs/configs/ss7-ecs.json" \
          ./run.sh > "$LOGS/ecs.log" 2>&1 < /dev/null & )
      sleep 1
      ( cd "$LOGS" && setsid "$HOME/.local/share/mise/installs/java/zulu-25/bin/java" \
          --add-modules jdk.sctp \
          -jar "$ROOT/sas-jss7-testapp/target/sas-jss7-testapp.jar" \
          --listen-port 2906 --peer-port 2907 --http-port 8087 \
          > "$LOGS/sim.log" 2>&1 < /dev/null & )
    else
      ( cd "$ROOT/dist-ecs" && setsid env \
          SAS_TRANSPORT_AUTHVECTOR=map SAS_BINDING_SOURCE_ORDER=map-smi \
          SAS_TRANSPORT_JSS7_CONFIG="$ROOT/dist-ecs/configs/ss7-ecs.json" \
          ./run.sh > "$LOGS/ecs.log" 2>&1 < /dev/null & )
    fi
    # "enforced" turns on sas.security.enforce-api-keys with two tenants, the
    # shape application-prod.properties runs in.
    CAM_OPTS="-Dsas.security.token-validation-enabled=true -Dsas.oauth.clients-json=$CLIENTS"
    if [ "${3:-}" = "enforced" ]; then
      CAM_OPTS="$CAM_OPTS -Dsas.security.enforce-api-keys=true \
        -Dsas.security.api-key=own-key,stranger-key \
        -Dsas.tenants.api-keys=bank-a=own-key,bank-b=stranger-key"
    fi
    ( cd "$ROOT/dist" && setsid env SAS_JAVA_OPTS="$CAM_OPTS" \
        ./run.sh > "$LOGS/camara.log" 2>&1 < /dev/null & )
    wait_up http://127.0.0.1:8095/entitlement/status sas-ecs
    wait_up http://127.0.0.1:8085/camara/health   sas-camara
    [ "${2:-live}" = "live" ] && wait_up http://127.0.0.1:8087/health hlr-sim
    ;;
esac