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
#   2. SAS lab dist running with MAP transports:
#        ./scripts/package-dist.sh
#        (cd dist && SAS_TRANSPORT_MAP=jss7 SAS_TRANSPORT_AUTHVECTOR=jss7 \
#           SAS_BINDING_SOURCE_ORDER=map-smi \
#           SAS_TRANSPORT_JSS7_CONFIG=$PWD/../sas-host/src/main/resources/ss7-sas.json \
#           SAS_ENTITLEMENT_HMAC_SECRET=lab-demo ./run.sh)
#   3. This script (mvn-compiles the lab card on demand).
#
# Usage: scripts/ts43-lab-demo.sh [sas-base] [hlr-control-base] [imsi] [msisdn]
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APP_HOME"

SAS="${1:-http://127.0.0.1:8085}"
HLR="${2:-http://127.0.0.1:18087}"
IMSI="${3:-655010000000001}"
MSISDN="${4:-+251911111111}"
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

step "HLR sim     the subscribers the HLR simulator is serving"
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

step "bank        POST /entitlement/exchange (token → the confirmed number)"
EXCHANGED="$(curl -sS --max-time 10 -X POST "$SAS/entitlement/exchange" \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -d "{\"token\":\"$TOKEN\"}")"
note "$EXCHANGED"
[[ "$EXCHANGED" == *'"valid":true'* ]] || die "the token did not redeem: $EXCHANGED"

step "bank        POST /number-verification/v2/verify (CAMARA NV)"
VERIFIED="$(curl -sS --max-time 10 -X POST "$SAS/number-verification/v2/verify" \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -d "{\"phoneNumber\":\"$MSISDN\",\"hashedPhoneNumber\":\"\"}")"
note "$VERIFIED"
if [[ "$VERIFIED" != *'"devicePhoneNumberVerified":true'* ]]; then
  note "(false here is CORRECT and fail-closed: CAMARA NV resolves a cellular"
  note " bearer IP:port → MSISDN, and this demo has no bearer tuple. The /ts43"
  note " path above is the Wi-Fi/TS.43 story; feed /session-tuple a real tuple"
  note " from the UE SDK and CAMARA flips to true.)"
fi

step "DEMO OK — EAP-AKA over TS.43, vectors over MAP SAI, number over MAP SendIMSI"