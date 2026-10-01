#!/usr/bin/env bash
# Silent Auth — subscriber exposure API on sas-ecs, against the HLR simulator, with pcap.
#
# docs/design/sas-layered-architecture.md §4 / §9.1 (migration step M2, ECS side):
#   * HLR simulator (sas-jss7-testapp)  SCTP :2906, control :8087
#   * sas-ecs (:8095), live MAP transports on its own association (SCTP :2907):
#       monitoring = SendIMSI (SSN 3) then PSI (+SAI) on the one jSS7 stack;
#       sas.ecs.token.redeem-at-ecs=true so /operator-token/redeem is exercised.
# Runs inside a private user + network namespace (no root). Captures SCTP + HTTP 8095.
#
# Usage: scripts/exposure-smoke.sh [out-dir]   (after the root reactor `mvn package` and
#        `(cd sas-jss7-testapp && mvn -o package -DskipTests)`)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$REPO/target/exposure-smoke}"
JAVA="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}/bin/java"
mkdir -p "$OUT/hlr" "$OUT/ecs"
OUT="$(cd "$OUT" && pwd)"
: >"$OUT/all-responses.txt"

if [[ "${EXPOSURE_SMOKE_INNER:-}" != 1 ]]; then
  exec env EXPOSURE_SMOKE_INNER=1 unshare -rn "$0" "$OUT"
fi

ip link set lo up
tcpdump -i lo -s 0 -U -w "$OUT/exposure-smoke.pcap" 'sctp or tcp port 8095' \
  >"$OUT/tcpdump.log" 2>&1 &
TCPDUMP=$!
sleep 1

(cd "$OUT/hlr" && exec "$JAVA" --add-modules jdk.sctp -Dsas.log.dir="$OUT/hlr" \
  -jar "$REPO/sas-jss7-testapp/target/sas-jss7-testapp.jar" \
  --listen-port 2906 --peer-port 2907 --http-port 8087 >"$OUT/hlr/stdout.log" 2>&1) &
HLR_PID=$!
(cd "$OUT/ecs" && SAS_TRANSPORT_AUTHVECTOR=map SAS_TRANSPORT_MAP=jss7 \
  SAS_BINDING_SOURCE_ORDER=map-smi \
  SAS_TRANSPORT_JSS7_CONFIG="$REPO/sas-ecs-host/src/main/resources/ss7-ecs.json" \
  exec "$JAVA" --add-modules jdk.sctp -Dquarkus.profile=lab -Dsas.log.dir="$OUT/ecs" \
  -Dsas.ecs.token.redeem-at-ecs=true \
  -jar "$REPO/sas-ecs-host/target/quarkus-app/quarkus-run.jar" >"$OUT/ecs/stdout.log" 2>&1) &
ECS_PID=$!
cleanup() {
  kill "$ECS_PID" "$HLR_PID" 2>/dev/null || true
  wait "$ECS_PID" "$HLR_PID" 2>/dev/null || true
  pkill -f -- "-Dsas.log.dir=$OUT" 2>/dev/null || true
  kill "$TCPDUMP" 2>/dev/null || true
  wait "$TCPDUMP" 2>/dev/null || true
}
trap cleanup EXIT

for url in http://127.0.0.1:8087/subscribers http://127.0.0.1:8095/entitlement/status; do
  for _ in $(seq 1 60); do curl -fs -o /dev/null "$url" 2>/dev/null && break; sleep 1; done
done
for _ in $(seq 1 60); do
  grep -qiE 'ASP.*ACTIVE|AS is active|stack started' "$OUT/ecs/stdout.log" 2>/dev/null && break
  sleep 1
done
sleep 5

E=http://127.0.0.1:8095/sas-subscriber-exposure/v1
fails=0
n=0
# call <path> <body> [budget] → prints "<status> <body>"
call() {
  n=$((n + 1))
  curl -sS -o "$OUT/resp.json" -w '%{http_code}' -X POST "$E$1" \
    -H 'Content-Type: application/json' -H "X-Request-Id: smoke-$(date +%s%N | tail -c 13)-${3:-x}" \
    -H "X-Sas-Budget-Ms: ${4:-2500}" -d "$2"
  cat "$OUT/resp.json" >>"$OUT/all-responses.txt"; echo >>"$OUT/all-responses.txt"
  printf ' %s' "$(cat "$OUT/resp.json")"
}
# replay with an explicit request id
call_id() {
  curl -sS -o "$OUT/resp.json" -w '%{http_code}' -X POST "$E$1" \
    -H 'Content-Type: application/json' -H "X-Request-Id: $3" -H 'X-Sas-Budget-Ms: 2500' -d "$2"
  cat "$OUT/resp.json" >>"$OUT/all-responses.txt"; echo >>"$OUT/all-responses.txt"
  printf ' %s' "$(cat "$OUT/resp.json")"
}
expect() {  # name, haystack, needle...
  local name="$1" hay="$2"; shift 2
  for needle in "$@"; do
    if [[ "$hay" != *"$needle"* ]]; then echo "   FAIL $name -> $hay"; fails=1; return; fi
  done
  echo "   OK   $name"
}

echo "== subscriber exposure on sas-ecs (HLR simulator over MAP)"
UE='{"afId":"sas-camara","ueIpAddr":{"ipv4Addr":"%s"},"portNumber":55555,"observedAt":"2026-10-01T06:00:00Z"}'
expect "UE ID: bound tuple -> GPSI" "$(call /ue-id/retrieve "$(printf "$UE" 10.20.30.40)")" \
  "200 " '"gpsi":"msisdn-251911111111"' '"evidenceSource":"LAB_RESOLVER"'
expect "UE ID: unknown tuple -> 404 NO_BINDING" "$(call /ue-id/retrieve "$(printf "$UE" 10.99.99.99)")" \
  "404 " '"cause":"NO_BINDING"'

MON='{"msisdn":"%s","monitoringType":"UE_REACHABILITY","addnMonTypes":["LOCATION_REPORTING"],"maximumNumberOfReports":1,"immediateRep":true}'
expect "monitoring: live MAP SendIMSI + PSI -> reachable + PLMN" \
  "$(call /monitoring-event/sas-camara/subscriptions "$(printf "$MON" 251911111111)")" \
  "200 " '"ueReachable":true' '"plmnId":' '"evidenceSource":"MAP_PSI"'
expect "monitoring: number the HLR does not know -> 404 SUBSCRIBER_NOT_FOUND" \
  "$(call /monitoring-event/sas-camara/subscriptions "$(printf "$MON" 251900000000)")" \
  "404 " '"cause":"SUBSCRIBER_NOT_FOUND"'
expect "monitoring: unsupported type -> 400" \
  "$(call /monitoring-event/sas-camara/subscriptions '{"msisdn":"251911111111","monitoringType":"ROAMING_STATUS","maximumNumberOfReports":1,"immediateRep":true}')" \
  "400 " '"cause":"UNSUPPORTED_MONITORING_TYPE"'

expect "SIM change on live MAP: no evidence source wired -> 404 NO_EVIDENCE" \
  "$(call /sim-change/retrieve '{"afId":"sas-camara","msisdn":"251911111111"}')" \
  "404 " '"cause":"NO_EVIDENCE"'

TOKEN=$(curl -sS -X POST http://127.0.0.1:8095/entitlement/issue -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
RED="{\"afId\":\"sas-camara\",\"operatorToken\":\"$TOKEN\",\"clientId\":\"bank-a\"}"
expect "redeem: first use -> 200 subscriber verification result" \
  "$(call_id /operator-token/redeem "$RED" redeem-0001)" \
  "200 " '"msisdn":"251911111111"' '"authMethod":"EAP-AKA"' '"simVerified":true'
expect "redeem: same request retried -> same 200 (not a burn)" \
  "$(call_id /operator-token/redeem "$RED" redeem-0001)" "200 " '"simVerified":true'
expect "redeem: another request -> 401 INVALID_TOKEN" \
  "$(call_id /operator-token/redeem "$RED" redeem-0002)" "401 " '"cause":"INVALID_TOKEN"'
expect "legacy exchange while the ECS redeems -> 404" \
  "$(curl -sS -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8095/entitlement/exchange \
     -H 'Content-Type: application/json' -d "{\"token\":\"$TOKEN\"}")" "404"

expect "contract: unknown field -> 400" \
  "$(call /sim-change/retrieve '{"afId":"sas-camara","msisdn":"251911111111","imsi":"1"}')" \
  "400 " '"cause":"INVALID_ARGUMENT"'
expect "contract: MSISDN with + -> 400" \
  "$(call /sim-change/retrieve '{"afId":"sas-camara","msisdn":"+251911111111"}')" "400 "
expect "contract: budget that cannot cover a stage -> 504 BUDGET_EXHAUSTED" \
  "$(call /monitoring-event/sas-camara/subscriptions "$(printf "$MON" 251911111111)" b 50)" \
  "504 " '"cause":"BUDGET_EXHAUSTED"'
imsi_hits=$(grep -c '655010000000001' "$OUT/all-responses.txt" || true)
if [[ "$imsi_hits" == 0 ]]; then echo "   OK   no IMSI in any of $(grep -c . "$OUT/all-responses.txt") exposure answers"
else echo "   FAIL IMSI appears in $imsi_hits exposure answer(s)"; fails=1; fi

echo
echo "HLR simulator message log:"
curl -s http://127.0.0.1:8087/messages | head -c 1500; echo
echo "pcap: $OUT/exposure-smoke.pcap"
exit $fails
