#!/usr/bin/env bash
# Silent Auth — entitlement server ↔ core network over MAP, with packet capture (lab only).
#
# Boots, inside a private user + network namespace (no root needed):
#   * the HLR simulator (sas-jss7-testapp)  SCTP :2906, control :8087
#   * sas-ecs  (:8095) with MAP transports  own association from SCTP :2907 (ss7-ecs.json)
#   * sas-camara (:8085, lab)               redeems the operator token
# then runs scripts/ts43-lab-demo.sh and captures everything to a pcap:
#   SCTP (M3UA → SCCP → TCAP → MAP sendAuthenticationInfo / sendImsi) + HTTP 8085/8095/8087.
#
# Usage: scripts/ecs-map-smoke.sh [out-dir]    (after `mvn package` of the root reactor
#        and `(cd sas-jss7-testapp && mvn -o package -DskipTests)`)
#
# NOTE: the EAP-AKA codec is still a P1 blocker (docs/design/sas-two-binary-architecture.md
# §9). The lab card shares it, so this proves the MAP legs and the token path, not
# interoperability with a real handset.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$REPO/target/ecs-map-smoke}"
JAVA="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}/bin/java"
mkdir -p "$OUT/hlr" "$OUT/ecs" "$OUT/camara"
OUT="$(cd "$OUT" && pwd)"

if [[ "${ECS_MAP_SMOKE_INNER:-}" != 1 ]]; then
  exec env ECS_MAP_SMOKE_INNER=1 unshare -rn "$0" "$OUT"
fi

ip link set lo up
tcpdump -i lo -s 0 -U -w "$OUT/ecs-map-smoke.pcap" \
  'sctp or tcp port 8085 or tcp port 8095 or tcp port 8087' >"$OUT/tcpdump.log" 2>&1 &
TCPDUMP=$!
sleep 1

(cd "$OUT/hlr" && exec "$JAVA" --add-modules jdk.sctp \
  -jar "$REPO/sas-jss7-testapp/target/sas-jss7-testapp.jar" \
  --listen-port 2906 --peer-port 2907 --http-port 8087 >"$OUT/hlr/stdout.log" 2>&1) &
HLR_PID=$!
(cd "$OUT/ecs" && SAS_TRANSPORT_AUTHVECTOR=map SAS_BINDING_SOURCE_ORDER=map-smi \
  SAS_TRANSPORT_JSS7_CONFIG="$REPO/sas-ecs-host/src/main/resources/ss7-ecs.json" \
  exec "$JAVA" --add-modules jdk.sctp -Dquarkus.profile=lab -Dsas.log.dir="$OUT/ecs" \
  -jar "$REPO/sas-ecs-host/target/quarkus-app/quarkus-run.jar" >"$OUT/ecs/stdout.log" 2>&1) &
ECS_PID=$!
(cd "$OUT/camara" && exec "$JAVA" -Dquarkus.profile=lab \
  -Dsas.log.dir="$OUT/camara" \
  -jar "$REPO/sas-host/target/quarkus-app/quarkus-run.jar" >"$OUT/camara/stdout.log" 2>&1) &
CAM_PID=$!
cleanup() {
  kill "$ECS_PID" "$CAM_PID" "$HLR_PID" 2>/dev/null || true
  wait "$ECS_PID" "$CAM_PID" "$HLR_PID" 2>/dev/null || true
  # safety net: every JVM of this run carries -Dsas.log.dir=$OUT/…
  pkill -f -- "-Dsas.log.dir=$OUT" 2>/dev/null || true
  kill "$TCPDUMP" 2>/dev/null || true
  wait "$TCPDUMP" 2>/dev/null || true
}
trap cleanup EXIT

# Readiness probes only: /health and /status carry no subscriber data, so nobody
# reading the pcap mistakes the harness for a client of the core network.
for url in http://127.0.0.1:8087/health http://127.0.0.1:8095/entitlement/status \
           http://127.0.0.1:8085/camara/health; do
  for _ in $(seq 1 60); do curl -fs -o /dev/null "$url" 2>/dev/null && break; sleep 1; done
done
# wait for the M3UA association sas-ecs → HLR simulator
for _ in $(seq 1 60); do
  grep -qiE 'ASP.*ACTIVE|AS is active|stack started' "$OUT/ecs/stdout.log" 2>/dev/null && break
  sleep 1
done
sleep 5

cd "$REPO"
set +e
scripts/ts43-lab-demo.sh http://127.0.0.1:8095 http://127.0.0.1:8087 \
  655010000000001 +251911111111 http://127.0.0.1:8085
rc=$?

# ---- fail-closed matrix (docs/test/testflow.*.md §0f.4) ----------------------------
ECS_URL=http://127.0.0.1:8095
HLRC=http://127.0.0.1:8087
fails=0
expect() {  # name, haystack, needle
  if [[ "$2" == *"$3"* ]]; then echo "   OK   $1"; else echo "   FAIL $1 -> $2"; fails=1; fi
}
field() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p" | head -1; }
card() { "$JAVA" -cp sas-entitlement/target/classes et.restlink.sas.entitlement.lab.LabSimCardMain "$1" "$2"; }
respond() {
  curl -sS -X POST "$ECS_URL/ts43/respond" -H 'Content-Type: application/json' \
    -d "{\"reqId\":\"$1\",\"imsi\":\"655010000000001\",\"msisdn\":\"$3\",\"response\":\"$2\"}"
}
echo
echo "== fail-closed matrix on sas-ecs"
CJ=$(curl -sS "$ECS_URL/ts43/challenge?imsi=655010000000001&msisdn=%2B251911111111")
R=$(card 655010000000002 "$(field "$CJ" challenge)")
expect "(a) another SIM answers -> AT_MAC mismatch" "$(respond "$(field "$CJ" reqId)" "$R" +251911111111)" "AT_MAC mismatch"
expect "(b1) same reqId again -> the recorded answer (idempotent, no new dialog)" \
  "$(respond "$(field "$CJ" reqId)" "$R" +251911111111)" "AT_MAC mismatch"
expect "(b2) reqId with no challenge -> no outstanding challenge" \
  "$(respond "ts43-00000000-0000-0000-0000-000000000000" "$R" +251911111111)" \
  "no outstanding challenge"
T0=$(date +%s%3N)
expect "(c) unknown IMSI -> no vector" \
  "$(curl -sS "$ECS_URL/ts43/challenge?imsi=655010000009999&msisdn=%2B251911111111")" \
  "no authentication vector available"
MS=$(( $(date +%s%3N) - T0 ))
# returnError must end the request at once, not after the 2 s MAP budget
expect "(c') SAI returnError answered in ${MS} ms (< 1000)" "$(( MS < 1000 ))" "1"
CJ=$(curl -sS "$ECS_URL/ts43/challenge?imsi=655010000000001&msisdn=%2B251900000000")
R=$(card 655010000000001 "$(field "$CJ" challenge)")
expect "(d) number the HLR does not know -> not confirmed, no token" \
  "$(respond "$(field "$CJ" reqId)" "$R" +251900000000)" "network did not confirm the claimed number"
curl -sS -o /dev/null -X POST -d '{"vectors":0}' "$HLRC/state"
expect "(e) SAI starved -> no vector" \
  "$(curl -sS "$ECS_URL/ts43/challenge?imsi=655010000000001&msisdn=%2B251911111111")" \
  "no authentication vector available"
curl -sS -o /dev/null -X POST -d '{"vectors":1}' "$HLRC/state"
curl -sS -o /dev/null -X POST -d '{"attached":false}' "$HLRC/state"
expect "(f) detached -> no vector" \
  "$(curl -sS "$ECS_URL/ts43/challenge?imsi=655010000000001&msisdn=%2B251911111111")" \
  "no authentication vector available"
curl -sS -o /dev/null -X POST -d '{"attached":true}' "$HLRC/state"
# layered ADR §9: the ECS holds the SCTP association; the gateway owns none.
echo "== runtime signalling boundary"
"$REPO/scripts/check-no-sctp.sh" "$CAM_PID" sas-camara || fails=1
if "$REPO/scripts/check-no-sctp.sh" "$ECS_PID" sas-ecs >/dev/null; then
  echo "   FAIL sas-ecs shows no SCTP socket although it talks MAP"; fails=1
else
  echo "   OK   sas-ecs owns the SCTP association (MAP to the HLR)"
fi
[[ $fails == 0 ]] || rc=1
set -e
echo
echo "HLR simulator message log:"
curl -s http://127.0.0.1:8087/messages | head -c 1200; echo
echo "pcap: $OUT/ecs-map-smoke.pcap"
exit $rc
