#!/usr/bin/env bash
# Silent Auth — layered E2E on the PACKAGED dists (layered ADR §8 M6, §9).
#
#   HLR simulator (sas-jss7-testapp)   SCTP :2906 ↔ sas-ecs :2907 (MAP: SAI, SendIMSI, PSI)
#   Diameter testapp (HSS)             SCTP :3868 ↔ sas-ecs (S6a peer, CER/CEA)
#   sas-ecs   dist (:8095)             TS.43 + token redemption + subscriber exposure
#   sas-camara dist (:8085)            CAMARA API gateway — HTTP to sas-ecs only
#
# Phase LIVE  — sas-ecs answers from the live MAP core. No SIM-change source exists on live
#               transports yet, so every decision that needs one fails closed.
# Phase LAB   — sas-ecs restarted with lab evidence for monitoring/SIM change: the same
#               gateway flows approve.
# Both phases: GSMA-sandbox-style flows (NV Authorization Code + PKCE; CIBA with
# login_hint=operatortoken from a real EAP-AKA run; SIM Swap check), replay refusal, and
# the runtime boundary (gateway owns no SCTP socket; MAP + Diameter only from sas-ecs).
#
# Usage: scripts/layered-e2e.sh <dist-camara> <dist-ecs> [out-dir]
#   build the dists first: SAS_DIST_DIR=… SAS_ECS_DIST_DIR=… scripts/package-dist.sh all
#   and the simulators: (cd sas-jss7-testapp && mvn -o package -DskipTests),
#                       (cd sas-diameter-testapp && mvn -o package)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
CAM_DIST="$(cd "${1:?dist-camara}" && pwd)"
ECS_DIST="$(cd "${2:?dist-ecs}" && pwd)"
OUT="${3:-$REPO/target/layered-e2e}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}"
JAVA="$JAVA_HOME/bin/java"
mkdir -p "$OUT/hlr" "$OUT/hss" "$OUT/logs-ecs" "$OUT/logs-camara"
OUT="$(cd "$OUT" && pwd)"

if [[ "${LAYERED_E2E_INNER:-}" != 1 ]]; then
  exec env LAYERED_E2E_INNER=1 unshare -rn "$0" "$CAM_DIST" "$ECS_DIST" "$OUT"
fi

ip link set lo up
tcpdump -i lo -s 0 -U -w "$OUT/layered-e2e.pcap" \
  'sctp or tcp port 8085 or tcp port 8095' >"$OUT/tcpdump.log" 2>&1 &
TCPDUMP=$!
sleep 1

CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["authorization_code","urn:openid:params:grant-type:ciba"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","sim-swap:check"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'

(cd "$OUT/hlr" && exec "$JAVA" --add-modules jdk.sctp -Dsas.log.dir="$OUT/hlr" \
  -jar "$REPO/sas-jss7-testapp/target/sas-jss7-testapp.jar" \
  --listen-port 2906 --peer-port 2907 --http-port 8087 >"$OUT/hlr/stdout.log" 2>&1) &
HLR_PID=$!
(cd "$OUT/hss" && exec "$JAVA" -Dsas.log.dir="$OUT/hss" \
  -jar "$REPO/sas-diameter-testapp/target/sas-diameter-testapp.jar" >"$OUT/hss/stdout.log" 2>&1) &
HSS_PID=$!

start_ecs() {  # $1 = live | lab
  local map=jss7
  [[ "$1" == lab ]] && map=memory
  (cd "$ECS_DIST" && SAS_LOG_DIR="$OUT/logs-ecs" SAS_XMX=1g \
    SAS_TRANSPORT_AUTHVECTOR=map SAS_TRANSPORT_MAP="$map" SAS_TRANSPORT_S6A=corsac \
    SAS_BINDING_SOURCE_ORDER=map-smi SAS_TRANSPORT_JSS7_CONFIG="$ECS_DIST/configs/ss7-ecs.json" \
    exec ./run.sh >"$OUT/ecs-$1.log" 2>&1) &
  ECS_PID=$!
}
(cd "$CAM_DIST" && SAS_LOG_DIR="$OUT/logs-camara" SAS_XMX=1g \
  SAS_JAVA_OPTS="-Dsas.security.token-validation-enabled=true -Dsas.security.accept-hs256=false -Dsas.oauth.clients-json=$CLIENTS" \
  exec ./run.sh >"$OUT/camara.log" 2>&1) &
CAM_PID=$!
start_ecs live

cleanup() {
  kill "${ECS_PID:-}" "$CAM_PID" "$HLR_PID" "$HSS_PID" 2>/dev/null || true
  # wait for the JVMs only — a bare `wait` would also wait for tcpdump and hang
  wait "${ECS_PID:-}" "$CAM_PID" "$HLR_PID" "$HSS_PID" 2>/dev/null || true
  pkill -f -- "-Dsas.log.dir=$OUT" 2>/dev/null || true
  kill "$TCPDUMP" 2>/dev/null || true
  wait "$TCPDUMP" 2>/dev/null || true
}
trap cleanup EXIT

wait_up() {
  for _ in $(seq 1 90); do curl -fs -o /dev/null "$1" 2>/dev/null && return 0; sleep 1; done
  echo "   FAIL $1 never came up"; return 1
}
wait_ecs() {
  wait_up http://127.0.0.1:8095/entitlement/status
  for _ in $(seq 1 60); do
    grep -qiE 'ASP.*ACTIVE|AS is active|stack started' "$1" 2>/dev/null && break; sleep 1
  done
  sleep 5
}
wait_up http://127.0.0.1:8087/subscribers
wait_up http://127.0.0.1:8085/camara/health
wait_ecs "$OUT/ecs-live.log"

fails=0
ok()   { echo "   OK   $*"; }
bad()  { echo "   FAIL $*"; fails=1; }
expect_eq() { [[ "$2" == "$3" ]] && ok "$1 ($2)" || bad "$1: expected $3, got $2"; }

ECS=http://127.0.0.1:8095
CAM=http://127.0.0.1:8085

operator_token() {  # real EAP-AKA over MAP SAI + SendIMSI, lab SIM card
  local cj r
  cj=$(curl -sS "$ECS/ts43/challenge?imsi=655010000000001&msisdn=%2B251911111111")
  r=$("$JAVA" -cp "$REPO/sas-entitlement/target/classes" et.restlink.sas.entitlement.lab.LabSimCardMain \
      655010000000001 "$(jq -r .challenge <<<"$cj")")
  curl -sS -X POST "$ECS/ts43/respond" -H 'Content-Type: application/json' \
    -d "{\"reqId\":\"$(jq -r .reqId <<<"$cj")\",\"imsi\":\"655010000000001\",\"msisdn\":\"+251911111111\",\"response\":\"$r\"}" \
    | jq -r .token
}

ciba_token() {  # $1 = operator token → prints the CIBA access token or nothing
  local bc
  bc=$(curl -sS -X POST "$CAM/bc-authorize" --data-urlencode "login_hint=operatortoken:$1" \
    --data-urlencode "scope=dpv:FraudPreventionAndDetection sim-swap:check" \
    --data-urlencode client_id=bank-lab)
  [[ "$(jq -r .auth_req_id <<<"$bc")" == null ]] && return 0
  curl -sS -X POST "$CAM/token" --data-urlencode grant_type=urn:openid:params:grant-type:ciba \
    --data-urlencode "auth_req_id=$(jq -r .auth_req_id <<<"$bc")" --data-urlencode client_id=bank-lab \
    | jq -r .access_token
}

nv_verify() {  # NV Authorization Code + PKCE from the lab tuple, then /verify → prints body
  local verifier challenge loc code at
  verifier=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')
  challenge=$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=\n')
  loc=$(curl -sS -o /dev/null -w '%{redirect_url}' -G "$CAM/authorize" \
    -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
    --data-urlencode response_type=code --data-urlencode client_id=bank-lab \
    --data-urlencode redirect_uri=https://bank.example/cb \
    --data-urlencode "scope=openid dpv:FraudPreventionAndDetection number-verification:verify" \
    --data-urlencode state=s --data-urlencode nonce=n \
    --data-urlencode "code_challenge=$challenge" --data-urlencode code_challenge_method=S256)
  code=$(sed -E 's/.*[?&]code=([^&]+).*/\1/' <<<"$loc")
  at=$(curl -sS -X POST "$CAM/token" --data-urlencode grant_type=authorization_code \
    --data-urlencode "code=$code" --data-urlencode redirect_uri=https://bank.example/cb \
    --data-urlencode "code_verifier=$verifier" --data-urlencode client_id=bank-lab | jq -r .access_token)
  curl -sS -X POST "$CAM/camara/number-verification/v2/verify" -H "Authorization: Bearer $at" \
    -H 'Content-Type: application/json' -H "x-correlator: $(cat /proc/sys/kernel/random/uuid)" \
    -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
    -d '{"phoneNumber":"+251911111111"}'
}

sim_swap() {  # $1 = CIBA access token → prints "<status> <body>"
  curl -sS -o "$OUT/simswap.json" -w '%{http_code}' -X POST "$CAM/camara/sim-swap/v2/check" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -H "x-correlator: $(cat /proc/sys/kernel/random/uuid)" -d '{"maxAge":240}'
  printf ' %s' "$(cat "$OUT/simswap.json")"
}

echo "== phase LIVE — sas-ecs answers from the live MAP core"
TOK=$(operator_token)
[[ "$(awk -F. '{print NF}' <<<"$TOK")" == 5 ]] && ok "EAP-AKA over MAP issued an operator token (JWE)" \
  || bad "no operator token from /ts43: $TOK"
AT=$(ciba_token "$TOK")
[[ -n "$AT" && "$AT" != null ]] && ok "CIBA with login_hint=operatortoken — redeemed at sas-ecs" || bad "CIBA failed"
expect_eq "replay of the same operator token" "$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$CAM/bc-authorize" \
  --data-urlencode "login_hint=operatortoken:$TOK" --data-urlencode "scope=dpv:FraudPreventionAndDetection sim-swap:check" \
  --data-urlencode client_id=bank-lab)" 403
R=$(sim_swap "$AT")
[[ "$R" == 404* ]] && ok "SIM Swap on live core: no SIM-change evidence → 404, never swapped:false" \
  || bad "SIM Swap live: $R"
NV=$(nv_verify)
expect_eq "NV on live core (PSI reachable, no SIM-change evidence → fail closed)" \
  "$(jq -r .devicePhoneNumberVerified <<<"$NV")" false
grep -q 'SIM_SWAP_SUSPECT' "$OUT/logs-camara"/*.log "$OUT/camara.log" 2>/dev/null \
  && ok "gateway recorded SIM_SWAP_SUSPECT (no SIM-change source on live transports)" \
  || bad "expected SIM_SWAP_SUSPECT in the gateway log"

echo "== runtime boundary (live phase)"
"$REPO/scripts/check-no-sctp.sh" "$CAM_PID" sas-camara || fails=1
ECS_JVM=$(pgrep -f -- "-Dsas.log.dir=$OUT/logs-ecs" | head -1)
if "$REPO/scripts/check-no-sctp.sh" "$ECS_JVM" sas-ecs >/dev/null; then
  bad "sas-ecs shows no SCTP socket although it talks MAP + Diameter"
else
  ok "sas-ecs owns the SCTP associations (MAP to the HLR, Diameter to the HSS)"
fi

echo "== phase LAB — sas-ecs restarted with lab evidence for monitoring / SIM change"
kill "$ECS_JVM" 2>/dev/null || true
wait "$ECS_PID" 2>/dev/null || true
sleep 2
start_ecs lab
wait_ecs "$OUT/ecs-lab.log"
NV=$(nv_verify)
expect_eq "NV Authorization Code → /verify approves on lab evidence" "$(jq -r .devicePhoneNumberVerified <<<"$NV")" true
grep -q 'LAB_HLR\|MAP_PSI\|evidenceSource' <<<"$NV" && bad "internal evidence source leaked into the CAMARA answer" \
  || ok "no internal evidence source in the CAMARA answer"
TOK=$(operator_token)
AT=$(ciba_token "$TOK")
R=$(sim_swap "$AT")
[[ "$R" == 200* && "$(jq -r .swapped "$OUT/simswap.json")" == false ]] \
  && ok "SIM Swap check on lab evidence → swapped:false" || bad "SIM Swap lab: $R"

echo "== wire evidence"
sleep 1
if command -v tshark >/dev/null 2>&1; then
  map_ops=$(tshark -r "$OUT/layered-e2e.pcap" -Y gsm_map -T fields -e _ws.col.Info 2>/dev/null \
    | { grep -oE 'invoke [A-Za-z]+' || true; } | sort | uniq -c | tr -s ' ')
  echo "$map_ops" | sed 's/^/     /'
  for op in sendAuthenticationInfo sendIMSI provideSubscriberInfo; do
    [[ "$map_ops" == *"$op"* ]] && ok "MAP $op on the wire" || bad "MAP $op missing from the pcap"
  done
  dia=$( (tshark -r "$OUT/layered-e2e.pcap" -Y 'diameter.cmd.code == 257' 2>/dev/null || true) | wc -l)
  [[ "$dia" -gt 0 ]] && ok "Diameter CER/CEA on the wire ($dia frames)" || bad "no Diameter capabilities exchange"
  hits=$( (tshark -r "$OUT/layered-e2e.pcap" -Y 'http.request.uri contains "/sas-subscriber-exposure/v1/"' 2>/dev/null || true) | wc -l)
  [[ "$hits" -gt 0 ]] && ok "gateway → sas-ecs exposure requests on the wire" || bad "no exposure request captured"
fi

echo "== artifact boundary"
"$REPO/scripts/check-dependency-boundary.sh" "$CAM_DIST" "$ECS_DIST" || fails=1

echo
echo "pcap: $OUT/layered-e2e.pcap"
exit $fails
