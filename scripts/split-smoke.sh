#!/usr/bin/env bash
# Silent Auth SAS — layered two-binary smoke test with packet capture (lab only).
#
# Boots sas-ecs (:8095) and sas-camara (:8085) inside a private user+network namespace
# (no root needed), captures loopback traffic to a pcap, and drives (layered ADR §6):
#   1. ECS  /jwks.json                         — gone (D8): 404
#   2. ECS  POST /entitlement/issue            — operator token (JWE to the ECS itself)
#   3. CAMARA /jwks.json                       — signing keys only, no encryption key
#   4. CAMARA /bc-authorize login_hint=operatortoken:<JWE> → the gateway forwards it to
#      the ECS POST /operator-token/redeem (visible in the pcap), never reads it
#   5. CAMARA /token (CIBA)                    — ES256 access token
#   6. replay of the same operator token       — refused by the ECS ledger
#   7. CAMARA /authorize → UE ID at the ECS → /token authorization_code + PKCE, then
#      /verify: monitoring + SIM change at the ECS, scored by the gateway
#   8-9. code replay, cross-binary surfaces
#  10. the gateway owns no SCTP socket; all its subscriber traffic is HTTP to the ECS
#
# Usage: scripts/split-smoke.sh [out-dir]    (run after `mvn package`)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$REPO/target/split-smoke}"
JAVA="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}/bin/java"
mkdir -p "$OUT/camara" "$OUT/ecs"
OUT="$(cd "$OUT" && pwd)"

if [[ "${SPLIT_SMOKE_INNER:-}" != 1 ]]; then
  exec env SPLIT_SMOKE_INNER=1 unshare -rn "$0" "$OUT"
fi

ip link set lo up
tcpdump -i lo -s 0 -U -w "$OUT/split-smoke.pcap" 'tcp port 8085 or tcp port 8095' \
  >"$OUT/tcpdump.log" 2>&1 &
TCPDUMP=$!
sleep 1

CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none",
  "grant_types":["authorization_code","urn:openid:params:grant-type:ciba"],
  "redirect_uris":["https://bank.example/cb"],
  "scopes":["number-verification:verify","sim-swap:check"],
  "purposes":["dpv:FraudPreventionAndDetection"]}]}'

(cd "$OUT/ecs" && exec "$JAVA" --add-modules jdk.sctp -Dquarkus.profile=lab \
  -Dsas.log.dir="$OUT/ecs" -jar "$REPO/sas-ecs-host/target/quarkus-app/quarkus-run.jar" \
  >"$OUT/ecs/stdout.log" 2>&1) &
ECS_PID=$!
(cd "$OUT/camara" && exec "$JAVA" -Dquarkus.profile=lab \
  -Dsas.log.dir="$OUT/camara" -Dsas.security.token-validation-enabled=true \
  -Dsas.security.accept-hs256=false \
  "-Dsas.oauth.clients-json=$(echo "$CLIENTS" | tr -d '\n ')" \
  -jar "$REPO/sas-host/target/quarkus-app/quarkus-run.jar" \
  >"$OUT/camara/stdout.log" 2>&1) &
CAM_PID=$!
cleanup() {
  kill "$ECS_PID" "$CAM_PID" 2>/dev/null || true
  wait "$ECS_PID" "$CAM_PID" 2>/dev/null || true
  # safety net: every JVM of this run carries -Dsas.log.dir=$OUT/…
  pkill -f -- "-Dsas.log.dir=$OUT" 2>/dev/null || true
  kill "$TCPDUMP" 2>/dev/null || true
  wait "$TCPDUMP" 2>/dev/null || true
}
trap cleanup EXIT

for port in 8095 8085; do
  for _ in $(seq 1 60); do
    curl -fs -o /dev/null "http://127.0.0.1:$port/q/health/live" 2>/dev/null && break
    curl -fs -o /dev/null "http://127.0.0.1:$port/camara/health" 2>/dev/null && break
    curl -fs -o /dev/null "http://127.0.0.1:$port/entitlement/status" 2>/dev/null && break
    sleep 1
  done
done

step() { printf '\n== %s\n' "$*"; }
fail=0
check() { if [[ "$1" == "$2" ]]; then echo "   OK ($3)"; else echo "   FAIL: expected $2 got $1 ($3)"; fail=1; fi; }

step "1. ECS /jwks.json (D8: no issuer key published any more)"
check "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8095/jwks.json)" 404 "ECS publishes no operator-token key"

step "2. ECS POST /entitlement/issue (lab: open API)"
TOK=$(curl -fsS -X POST http://127.0.0.1:8095/entitlement/issue -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"636010000000001","eapMethod":"EAP-AKA"}' | jq -r .token)
echo "   token parts: $(awk -F. '{print NF}' <<<"$TOK") (5 = JWE), header: $(cut -d. -f1 <<<"$TOK" | base64 -d 2>/dev/null | head -c 200)"

step "3. CAMARA /jwks.json (signing keys only)"
curl -fsS http://127.0.0.1:8085/jwks.json | jq -c '.keys[] | {kid,use,alg,has_d:(has("d"))}'
check "$(curl -fsS http://127.0.0.1:8085/jwks.json | jq '[.keys[] | select(.use=="enc")] | length')" 0 "no operator-token decryption key on the gateway"

step "4. CAMARA CIBA /bc-authorize with login_hint=operatortoken:<JWE> (redeemed at the ECS)"
BC=$(curl -sS -X POST http://127.0.0.1:8085/bc-authorize \
  --data-urlencode "login_hint=operatortoken:$TOK" \
  --data-urlencode "scope=dpv:FraudPreventionAndDetection sim-swap:check" \
  --data-urlencode "client_id=bank-lab")
echo "   $BC"
AUTH_REQ=$(jq -r .auth_req_id <<<"$BC")
[[ "$AUTH_REQ" != null && -n "$AUTH_REQ" ]] && echo "   OK (auth_req_id issued)" || { echo "   FAIL"; fail=1; }

step "5. CAMARA /token (CIBA grant)"
AT=$(curl -sS -X POST http://127.0.0.1:8085/token \
  --data-urlencode "grant_type=urn:openid:params:grant-type:ciba" \
  --data-urlencode "auth_req_id=$AUTH_REQ" --data-urlencode "client_id=bank-lab" | jq -r .access_token)
echo "   access token alg: $(cut -d. -f1 <<<"$AT" | base64 -d 2>/dev/null)"

step "6. replay the same operator token (must be refused)"
code=$(curl -sS -o "$OUT/replay.json" -w '%{http_code}' -X POST http://127.0.0.1:8085/bc-authorize \
  --data-urlencode "login_hint=operatortoken:$TOK" \
  --data-urlencode "scope=dpv:FraudPreventionAndDetection sim-swap:check" \
  --data-urlencode "client_id=bank-lab")
echo "   $(cat "$OUT/replay.json")"
check "$code" 403 "replay refused"

step "7. NV Authorization Code + PKCE (network auth from the lab resolver tuple)"
VERIFIER=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=\n')
LOC=$(curl -sS -o /dev/null -w '%{redirect_url}' -G http://127.0.0.1:8085/authorize \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  --data-urlencode response_type=code --data-urlencode client_id=bank-lab \
  --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode "scope=openid dpv:FraudPreventionAndDetection number-verification:verify" \
  --data-urlencode state=st1 --data-urlencode nonce=n1 \
  --data-urlencode "code_challenge=$CHALLENGE" --data-urlencode code_challenge_method=S256)
echo "   redirect: ${LOC%%code=*}code=<redacted>&state=..."
CODE=$(sed -E 's/.*[?&]code=([^&]+).*/\1/' <<<"$LOC")
TR=$(curl -sS -X POST http://127.0.0.1:8085/token --data-urlencode grant_type=authorization_code \
  --data-urlencode "code=$CODE" --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode "code_verifier=$VERIFIER" --data-urlencode client_id=bank-lab)
NVAT=$(jq -r .access_token <<<"$TR")
echo "   id_token present: $(jq -r 'has("id_token")' <<<"$TR"), access alg: $(cut -d. -f1 <<<"$NVAT" | base64 -d 2>/dev/null)"
code=$(curl -sS -o "$OUT/nv.json" -w '%{http_code}' -X POST \
  http://127.0.0.1:8085/camara/number-verification/v2/verify \
  -H "Authorization: Bearer $NVAT" -H 'Content-Type: application/json' \
  -H 'x-correlator: 3f1c9e2a-0b7d-4e55-9a51-2c1f0d6b8e11' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d '{"phoneNumber":"+251911111111"}')
echo "   $(cat "$OUT/nv.json")"
check "$code" 200 "NV verify with ES256 auth-code token"
check "$(jq -r .devicePhoneNumberVerified "$OUT/nv.json")" true "number verified from ECS evidence"
check "$(grep -c 'evidenceSource\|LAB_HLR\|MAP_PSI' "$OUT/nv.json" || true)" 0 "no internal evidence source in the CAMARA answer"

step "8. code replay (must be invalid_grant)"
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8085/token \
  --data-urlencode grant_type=authorization_code --data-urlencode "code=$CODE" \
  --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode "code_verifier=$VERIFIER" --data-urlencode client_id=bank-lab)
check "$code" 400 "authorization code is single-use"

step "9. surfaces do not leak across binaries"
check "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8085/entitlement/status)" 404 "CAMARA has no /entitlement"
check "$(curl -s -o /dev/null -w '%{http_code}' 'http://127.0.0.1:8085/ts43/challenge?imsi=1')" 404 "CAMARA has no /ts43"
check "$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8095/token)" 404 "ECS has no /token"
check "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8095/.well-known/openid-configuration)" 404 "ECS has no OIDC discovery"

step "10. the gateway signals nothing (layered ADR §9)"
if "$REPO/scripts/check-no-sctp.sh" "$CAM_PID" sas-camara; then :; else fail=1; fi
sleep 1
if command -v tshark >/dev/null 2>&1; then
  check "$(tshark -r "$OUT/split-smoke.pcap" -Y sctp 2>/dev/null | wc -l)" 0 "no SCTP on the wire in this run"
  calls=$(tshark -r "$OUT/split-smoke.pcap" -Y 'http.request.uri contains "/sas-subscriber-exposure/v1/"' \
    -T fields -e http.request.uri 2>/dev/null | sort | uniq -c | tr -s ' ')
  echo "   gateway -> ECS exposure calls:"; echo "$calls" | sed 's/^/     /'
  [[ "$calls" == *"/operator-token/redeem"* && "$calls" == *"/ue-id/retrieve"* ]] \
    && echo "   OK (redeem + UE ID seen on the wire)" || { echo "   FAIL: exposure calls missing"; fail=1; }
fi

echo
echo "pcap: $OUT/split-smoke.pcap"
exit $fail
