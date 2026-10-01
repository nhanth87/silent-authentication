#!/usr/bin/env bash
# Silent Auth SAS — TS.43 entitlement lab demo (end-to-end, camera-visible).
#
# Drives the whole chain over HTTP with curl and prints every hop, so you can watch
# the CAMARA call, the TS.43 EAP-AKA exchange, and the MAP messages to the core
# network as they happen:
#
#   UE (this script)            SAS (Quarkus)              HLR simulator (jSS7)
#   ───────────────             ────────────              ────────────────────
#   GET  /ts43/challenge ──────► EntitlementSbb
#                                │ MAP SAI (TS 29.002) ────────► sendAuthenticationInfo
#                                │◄──────────────────────────── UMTS quintuplet
#   ◄── EAP-Request/AKA-Challenge
#   POST /ts43/respond ───────► AT_MAC + AT_RES verified
#                                │ MAP SendIMSI ────────────────► sendImsi
#                                │◄──────────────────────────── IMSI
#   ◄── entitlement token
#   POST /entitlement/exchange ► the number the network confirmed
#   POST /number-verification/v2/verify (CAMARA NV)
#
# Prerequisites (see AGENTS.md §1):
#   1. HLR simulator running:
#        (cd sas-jss7-testapp && mvn -o package -DskipTests)
#        java --add-modules jdk.sctp -jar sas-jss7-testapp/target/sas-jss7-testapp.jar
#   2. The ENTITLEMENT SERVER (sas-ecs, :8095 — /ts43 no longer lives in sas-camara)
#      running with MAP transports:
#        ./scripts/package-dist.sh ecs
#        (cd dist-ecs && SAS_TRANSPORT_AUTHVECTOR=map SAS_BINDING_SOURCE_ORDER=map-smi \
#           SAS_TRANSPORT_JSS7_CONFIG=$PWD/configs/ss7-ecs.json ./run.sh)
#      sas-ecs uses its own SCTP association (local port 2907, ss7-ecs.json); the HLR
#      simulator must accept that peer. The token it returns is an operator token
#      (JWE) that sas-camara accepts as operatortoken:<token>.
#
#   NOTE: the EAP-AKA wire codec is a known P1 blocker (see
#   docs/design/sas-two-binary-architecture.md §9) — this demo exercises the lab card
#   against the same codec, so it cannot prove interoperability with a real UE.
#   3. This script (mvn-compiles the lab card on demand).
#
# Usage: scripts/ts43-lab-demo.sh [ecs-base] [hlr-control-base] [imsi] [msisdn] [camara-base]
#   ecs-base    sas-ecs (/ts43)                      default http://127.0.0.1:8095
#   camara-base sas-camara (redeems the token)        default http://127.0.0.1:8085
#               pass "-" to stop after the token (no CAMARA running)
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APP_HOME"

SAS="${1:-http://127.0.0.1:8095}"
HLR="${2:-http://127.0.0.1:8087}"
IMSI="${3:-655010000000001}"
MSISDN="${4:-+251911111111}"
CAMARA="${5:-http://127.0.0.1:8085}"
ENT_CLASS=et.restlink.sas.entitlement.lab.LabSimCardMain

step() { printf '\n\033[1m── %s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
die()  { printf '\033[31m!! %s\033[0m\n' "$*" >&2; exit 1; }

# Extract one top-level JSON string field without needing jq.
jfield() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p" | head -1; }

step "UE → SAS    GET /ts43/challenge"
CHALLENGE_JSON="$(curl -sS --max-time 10 \
  "$SAS/ts43/challenge?imsi=$IMSI&msisdn=$(printf '%s' "$MSISDN" | sed 's/+/%2B/')")"
REQ_ID="$(jfield "$CHALLENGE_JSON" reqId)"
CHALLENGE="$(jfield "$CHALLENGE_JSON" challenge)"
[[ -n "$REQ_ID" && -n "$CHALLENGE" ]] || die "no challenge: $CHALLENGE_JSON"
note "reqId=$REQ_ID"
note "challenge=${CHALLENGE:0:64}…"

step "HLR sim     seeded subscriber (lab control UI only — no UE/bank ever calls this)"
note "$(curl -sS --max-time 5 "$HLR/subscribers")"

step "UE          EAP-Response/AKA-Challenge (AT_MAC + AT_RES)"
if [[ ! -d sas-entitlement/target/classes ]]; then
  note "compiling the lab card…"
  mvn -o -q compile -pl sas-entitlement -am
fi
RESPONSE="$(java -cp sas-entitlement/target/classes "$ENT_CLASS" "$IMSI" "$CHALLENGE")"
[[ -n "$RESPONSE" ]] || die "the lab card refused to answer the challenge"
note "$RESPONSE"

step "UE → SAS    POST /ts43/respond"
RESPONDED="$(curl -sS --max-time 10 -X POST "$SAS/ts43/respond" \
  -H 'Content-Type: application/json' \
  -d "{\"reqId\":\"$REQ_ID\",\"imsi\":\"$IMSI\",\"msisdn\":\"$MSISDN\",\"response\":\"$RESPONSE\"}")"
TOKEN="$(jfield "$RESPONDED" token)"
[[ -n "$TOKEN" ]] || die "entitlement refused: $RESPONDED"
note "token=${TOKEN:0:64}…"
note "→ the HLR simulator log now shows sendAuthenticationInfo then sendImsi:"
note "  curl -s $HLR/messages"

if [[ "$CAMARA" == "-" ]]; then
  step "DEMO OK (token only) — EAP-AKA over /ts43, vectors over MAP SAI, number over MAP SendIMSI"
  exit 0
fi

step "bank → CAMARA  POST /camara/number-verification/v2/verify (Wi-Fi path, operator token)"
note "sas-camara forwards the token unread to the ECS /operator-token/redeem, which decrypts,"
note "verifies and spends it once — the number comes from the ECS answer, never the body"
VERIFIED="$(curl -sS --max-time 10 -X POST "$CAMARA/camara/number-verification/v2/verify" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'X-Sas-Access-Tech: WIFI' -H 'x-correlator: ts43-demo-1' \
  -d "{\"phoneNumber\":\"$MSISDN\"}")"
note "$VERIFIED"

step "bank → CAMARA  replay the same operator token (single use)"
REPLAY="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -X POST \
  "$CAMARA/camara/number-verification/v2/verify" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'X-Sas-Access-Tech: WIFI' -H 'x-correlator: ts43-demo-2' \
  -d "{\"phoneNumber\":\"$MSISDN\"}")"
note "HTTP $REPLAY (expected 401)"
[[ "$REPLAY" == 401 ]] || die "replay was not refused"

step "DEMO OK — EAP-AKA on sas-ecs (MAP SAI + SendIMSI), operator token redeemed once on sas-camara"