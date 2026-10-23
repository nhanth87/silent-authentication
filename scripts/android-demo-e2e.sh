#!/usr/bin/env bash
# Android emulator demo, end to end against the core-network SIMULATORS, with pcaps.
#
#   ./scripts/android-demo-e2e.sh            # CORE=both (default): gx, then full, one emulator boot
#   CORE=gx ./scripts/android-demo-e2e.sh    # PCRF simulator only (Gx/Sd), HLR/HSS in-memory
#   CORE=full ./scripts/android-demo-e2e.sh  # + HLR (MAP) and HSS (S6a) simulators
#   SHOW_EMULATOR=1 ...                      # show the emulator window (default: headless)
#   FRESH_SNAPSHOT=1 ...                     # cold boot and re-save the emulator snapshot
#
# Needs: dist/ + dist-ecs/, sas-jss7-testapp + sas-diameter-testapp + bank-backend-sdk
# jars, the demo APK (demo-android/apk/, or a local build), AVD "sa-demo", and no other
# emulator running that AVD. No root: everything runs in a private user + network
# namespace, so tcpdump works and nothing clashes with ports on the host.
#
#   PCRF sim  Diameter Gx/Sd over SCTP :3870, control :28086 — binds 10.20.30.40 -> +251911111111
#   HLR sim   MAP over SCTP :2906 (ECS peer :2907), control :8087          (full)
#   HSS sim   Diameter S6a over SCTP :3868, control :8086                  (full)
#   sas-ecs   :8095  resolver = Gx/Sd probe to the PCRF sim; MAP/S6a live (full) or in-memory (gx)
#   sas-camara :8085, edge-sim :8084 (stamps X-Sas-Src-Ip/Port), demo-bank :8092
#   emulator  in the same namespace; 10.0.2.2 = this namespace's lo
#
# Why two profiles: /verify also checks SIM-change freshness. Only the in-memory HLR/HSS
# backends can date a SIM change (EcsBootstrap.labSimChange); on live MAP/S6a there is no
# source yet, so the ECS answers NO_EVIDENCE and /verify fails closed with
# SIM_SWAP_SUSPECT. gx shows the happy path with the subscriber identified by the PCRF
# simulator over Gx/Sd; full records that fail-closed answer on the full core.
#
# Scenarios (the app is driven by intent extras, its verdict read from logcat; Wi-Fi stays ON):
#   A  SIM number, mobile data on      -> gx: verified via the mobile network
#                                         full: not verified (SIM_SWAP_SUSPECT, fail closed)
#   B  another number                  -> not verified
#   C  mobile data off                 -> no cellular, OTP fallback, nothing sent over Wi-Fi
#   D  PCRF simulator down             -> fail closed, never verified
#
# Speed: the emulator boots from snapshot "sa-e2e" (made on the first run) in parallel with
# the server stack, and stays up across profiles; only the server side restarts.
#
# Output: target/android-demo-e2e/<profile>/
#   host-lo.pcap    every hop on the host side: HTTP, Diameter (Gx/Sd, S6a), SCTP/M3UA/MAP
#   emulator.pcap   the emulator's cellular LAN (guest eth0), this profile only
#   scenario-*.png, logs/, summary.txt          and target/android-demo-e2e/timing.txt
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="${CORE:-both}"
case "$CORE" in
  both) PROFILES=(gx full) ;;
  gx|full) PROFILES=("$CORE") ;;
  *) echo "CORE must be gx, full or both"; exit 2 ;;
esac
OUT="$ROOT/target/android-demo-e2e"
if [[ "${ANDROID_DEMO_E2E_INNER:-}" != 1 ]]; then
  # One emulator per AVD: wait for a previous run's emulator to finish shutting down.
  for _ in $(seq 1 30); do pgrep -f 'qemu-system.*sa-demo' > /dev/null || break; sleep 1; done
  pgrep -f 'qemu-system.*sa-demo' > /dev/null && { echo "an emulator is still running AVD sa-demo"; exit 2; }
  rm -rf "$OUT"
  mkdir -p "$OUT"
  exec env ANDROID_DEMO_E2E_INNER=1 unshare -rn "$0" "$@"
fi

JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME ANDROID_HOME PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
# A local build of the app wins; otherwise the APK published in the repository.
APK="$ROOT/demo-android/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || APK="$ROOT/demo-android/apk/silent-auth-demo-0.1.0-debug.apk"
BANK_JAR="$ROOT/bank-backend-sdk/target/bank-backend-sdk-0.1.0-SNAPSHOT.jar"
SIM_JAR="$ROOT/sas-diameter-testapp/target/sas-diameter-testapp.jar"
HLR_JAR="$ROOT/sas-jss7-testapp/target/sas-jss7-testapp.jar"
for f in "$APK" "$BANK_JAR" "$SIM_JAR" "$HLR_JAR" "$ROOT/dist/run.sh" "$ROOT/dist-ecs/run.sh"; do
  [[ -f "$f" ]] || { echo "missing $f"; exit 2; }
done
# Every adb call is bounded: a crashed emulator must fail the run, never hang it.
adb() { timeout "${ADB_TIMEOUT:-20}" "$ANDROID_HOME/platform-tools/adb" "$@"; }
export -f adb
# Software GPU: host-GPU gfxstream crashed the emulator mid-run ("null ctx", segfault).
SNAPSHOT=sa-e2e-sw
SNAPSHOT_DIR="$HOME/.android/avd/sa-demo.avd/snapshots/$SNAPSHOT"
T0=$(date +%s)
declare -A RESULT
phase() { echo "$(printf '%4ss' $(( $(date +%s) - T0 )))  $*" | tee -a "$OUT/timing.txt"; }

ip link set lo up
# glibc's AI_ADDRCONFIG ignores loopback: with lo alone, "::1" does not resolve and the
# emulator's modem simulator (radio, i.e. the CELLULAR network) never connects.
ip link add dummy0 type dummy
ip addr add 10.99.0.1/24 dev dummy0
ip addr add fd00:5a::1/64 dev dummy0 nodad
ip link set dummy0 up

pids=()
LOGS="$OUT"
start() { # name, command...
  local name=$1; shift
  ( exec "$@" ) > "$LOGS/$name.log" 2>&1 &
  pids+=($!)
  eval "PID_${name//-/_}=$!"
}
stop_pids() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done
  for p in "${pids[@]}"; do
    for _ in $(seq 1 50); do kill -0 "$p" 2>/dev/null || break; sleep 0.1; done
    kill -9 "$p" 2>/dev/null
  done
  (( ${#pids[@]} )) && wait "${pids[@]}" 2>/dev/null
  pids=()
}
cleanup() {
  adb -s emulator-5554 emu kill > /dev/null 2>&1
  adb kill-server > /dev/null 2>&1
  [[ -n "${TCPDUMP:-}" ]] && kill "$TCPDUMP" 2>/dev/null
  [[ -n "${PID_EMU:-}" ]] && kill "$PID_EMU" 2>/dev/null
  [[ -n "${WATCHDOG:-}" ]] && kill "$WATCHDOG" 2>/dev/null
  stop_pids
}
on_exit() {
  local k
  cleanup
  if (( ${#RESULT[@]} )); then
    echo "results so far:"; for k in $(printf '%s\n' "${!RESULT[@]}" | sort); do echo "  $k  ${RESULT[$k]}"; done
  fi
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'phase "ABORTED: over the ${MAX_SECONDS}s limit (MAX_SECONDS)"; exit 124' TERM
# Hard limit for the whole run; each adb call is capped too, so the TERM lands within seconds.
MAX_SECONDS="${MAX_SECONDS:-360}"
( sleep "$MAX_SECONDS"; kill -TERM $$ 2>/dev/null ) &
WATCHDOG=$!

# ---------------------------------------------------------------- emulator (in parallel)
adb start-server > /dev/null 2>&1
EMU_ARGS=(-avd sa-demo -no-boot-anim -no-audio -gpu swiftshader_indirect -netdelay none -netspeed full -no-snapshot-save)
if [[ -d "$SNAPSHOT_DIR" && "${FRESH_SNAPSHOT:-0}" != 1 ]]; then
  EMU_ARGS+=(-snapshot "$SNAPSHOT"); EMU_FROM=snapshot
else
  EMU_ARGS+=(-no-snapshot-load); EMU_FROM=cold
fi
[[ "${SHOW_EMULATOR:-0}" == 1 ]] || EMU_ARGS+=(-no-window)
( exec env QT_QPA_PLATFORM=xcb "$ANDROID_HOME/emulator/emulator" "${EMU_ARGS[@]}" ) > "$OUT/emulator.log" 2>&1 &
PID_EMU=$!
phase "emulator starting ($EMU_FROM boot)"

mobile_up() { adb shell dumpsys connectivity 2>/dev/null | grep -q 'ni{MOBILE[^}]*CONNECTED'; }
# Wi-Fi is the active default network ("Active default network: <id>" is a WIFI agent).
wifi_default() {
  local d id
  d=$(adb shell dumpsys connectivity 2>/dev/null | tr -d '\r')
  id=$(sed -n 's/.*Active default network: \([0-9][0-9]*\).*/\1/p' <<< "$d" | head -1)
  [[ -n "$id" ]] && grep -q "network{$id}.*ni{WIFI[^}]*CONNECTED" <<< "$d"
}
# ...and cellular is gone: the bank calls of scenario C must not race the route switch.
wifi_only_default() { wifi_default && ! mobile_up; }
# The demo's premise: Wi-Fi is the default route AND cellular is up. After a snapshot
# restore the emulator's Wi-Fi is enabled but not associated; toggling it reconnects.
wifi_and_cellular() {
  until_true 20 wifi_default || { adb shell svc wifi disable; adb shell svc wifi enable; }
  until_true 160 wifi_default || { phase "Wi-Fi never became the default network"; exit 1; }
  until_true 160 mobile_up || { phase "cellular never came up"; exit 1; }
}
until_true() { # tries(×0.25 s), command... — and stop the run at once if the emulator died
  local n=$1; shift
  for _ in $(seq 1 "$n"); do
    "$@" && return 0
    kill -0 "$PID_EMU" 2>/dev/null || { phase "emulator crashed; stopping (see $OUT/emulator.log)"; exit 1; }
    sleep 0.25
  done
  return 1
}
EMU_READY=0
emulator_ready() { # once: boot, radio, app; saves the snapshot after a cold boot
  (( EMU_READY )) && return 0
  until_true 960 bash -c '[[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" == 1 ]]' \
    || { echo "emulator did not boot (log: $OUT/emulator.log)"; exit 2; }
  kill -0 "$PID_EMU" 2>/dev/null || { echo "emulator exited (log: $OUT/emulator.log)"; exit 2; }
  phase "emulator booted"
  adb shell settings put global window_animation_scale 0
  adb shell settings put global transition_animation_scale 0
  adb shell settings put global animator_duration_scale 0
  adb shell svc wifi enable
  adb shell svc data enable
  until_true 240 mobile_up || { adb shell dumpsys connectivity | grep -oE 'ni\{[^}]*\}' > "$OUT/networks.txt";
                                echo "no CELLULAR network in the emulator (see $OUT/networks.txt)"; exit 2; }
  wifi_and_cellular
  if [[ "$EMU_FROM" == cold ]]; then
    adb emu avd snapshot save "$SNAPSHOT" > "$OUT/snapshot-save.log" 2>&1
    phase "snapshot $SNAPSHOT saved"
  fi
  ADB_TIMEOUT=90 adb install -r "$APK" > "$OUT/adb-install.log" 2>&1 || { cat "$OUT/adb-install.log"; exit 2; }
  EMU_READY=1
  phase "emulator ready: cellular up, app installed"
}

# ---------------------------------------------------------------- server side
CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["authorization_code","urn:openid:params:grant-type:ciba"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","sim-swap:check"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'

up() { # url [seconds]
  until_true $(( ${2:-90} * 4 )) curl -fs -o /dev/null "$1" && return 0
  echo "DOWN $1 (logs: $LOGS)"; exit 2
}

stack_up() { # profile
  local core=$1
  mkdir -p "$LOGS/hlr" "$LOGS/hss" "$LOGS/pcrf"
  # The Gx/Sd probe comes from Origin-Host sas.restlink.et (PcrfSdResolverBackend), not ecs.restlink.et.
  start pcrf env -C "$LOGS/pcrf" java -jar "$SIM_JAR" --diameter-port 3870 --web-port 28086 --client-host sas.restlink.et
  local ecs_core=()
  if [[ "$core" == full ]]; then
    # Simulators write their stack state into the working directory: one directory each.
    start hlr env -C "$LOGS/hlr" java --add-modules jdk.sctp -jar "$HLR_JAR" \
      --listen-port 2906 --peer-port 2907 --http-port 8087
    start hss env -C "$LOGS/hss" java -jar "$SIM_JAR" --diameter-port 3868 --web-port 8086
    ecs_core=(SAS_TRANSPORT_AUTHVECTOR=map SAS_TRANSPORT_MAP=jss7 SAS_TRANSPORT_S6A=corsac
              SAS_BINDING_SOURCE_ORDER=map-smi SAS_TRANSPORT_JSS7_CONFIG="$ROOT/dist-ecs/configs/ss7-ecs.json")
  fi
  # sas-camara and the two demo servers do not depend on the simulators: start them now.
  start camara env -C "$ROOT/dist" SAS_LOG_DIR="$LOGS/camara-logs" \
    SAS_JAVA_OPTS="-Dsas.security.token-validation-enabled=true -Dsas.security.accept-hs256=false -Dsas.oauth.authorize.trust-src-header=true -Dsas.oauth.clients-json=$CLIENTS" \
    ./run.sh
  start edge-sim java "$ROOT/demo-android/server/EdgeSim.java" 8084 http://127.0.0.1:8085
  start demo-bank java -cp "$BANK_JAR" "$ROOT/demo-android/server/DemoBank.java" 8092
  up http://127.0.0.1:28086/
  if [[ "$core" == full ]]; then up http://127.0.0.1:8087/health; up http://127.0.0.1:8086/; fi
  # stale-after-ms=5000 (default 60000): scenario D must not be answered from A's cached binding.
  start ecs env -C "$ROOT/dist-ecs" SAS_LOG_DIR="$LOGS/ecs-logs" "${ecs_core[@]}" \
    SAS_JAVA_OPTS="-Dsas.transport.resolver=sd -Dsas.transport.sd.peer-port=3870 -Dsas.transport.sd.stale-after-ms=5000" \
    ./run.sh
  up http://127.0.0.1:8095/entitlement/status 120
  up http://127.0.0.1:8085/camara/health 120
  until_true 240 bash -c '[[ "$(curl -s -o /dev/null -w "%{http_code}" -X POST http://127.0.0.1:8092/nv/health)" == 404 ]]'
  # Diameter peers (Gx/Sd to the PCRF sim, S6a to the HSS sim) come up after the HTTP side.
  peers() { grep -q 'Peer is up for Association \[name=sd-sas' "$LOGS/ecs.log" \
              && { [[ "$core" == gx ]] || grep -q 'Peer is up for Association \[name=s6a-sas' "$LOGS/ecs.log"; }; }
  until_true 720 peers || { echo "Diameter peers never came up (log: $LOGS/ecs.log)"; exit 2; }
  phase "[$core] servers up, Diameter peers up"
  # Warm-up. sas-camara gives the ue-id lookup a 400 ms budget; the first lookups after a
  # cold start (JIT in camara and ECS, first Gx/Sd CCR) overrun it and /authorize fails
  # closed. Warm the REAL path from the host (demo-bank /nv/start, then /authorize through
  # edge-sim) until two codes in a row; the codes are thrown away. Scenario A waits until
  # this binding is stale (5 s), so it still makes its own CCR to the PCRF simulator.
  local codes=() ok=0 url loc i
  for i in $(seq 1 8); do
    url=$(curl -s -X POST http://127.0.0.1:8092/nv/start | sed -nE 's/.*"authorizeUrl":"([^"]+)".*/\1/p')
    loc=$(curl -s -o /dev/null -w '%{redirect_url}' "${url/10.0.2.2/127.0.0.1}")
    case "$loc" in
      *code=*)  codes+=(code); ok=$((ok + 1)) ;;
      *error=*) codes+=("$(sed -nE 's/.*error=([^&]+).*/\1/p' <<< "$loc")"); ok=0 ;;
      *)        codes+=(none); ok=0 ;;
    esac
    (( ok >= 2 )) && break
  done
  (( ok >= 2 )) || { echo "warm-up never got two codes in a row: ${codes[*]}"; exit 2; }
  echo "warm-up /authorize answers: ${codes[*]}" > "$LOGS/warmup.log"
  WARMUP_END=$(date +%T)
  WARM_TS=$(date +%s)
  phase "[$core] warmed up through /authorize: ${codes[*]}"
}

# ---------------------------------------------------------------- app driver
rc=0
run() { # profile, id, title, phone, expected-prefix
  local core=$1 id=$2 got=""
  adb logcat -c
  echo "[$(date +%T.%3N)] ---- $id: $3" >> "$LOGS/scenarios.log"
  adb shell am start -S -W -n et.restlink.demo/.MainActivity --es phone "$4" --ez autologin true > /dev/null
  for _ in $(seq 1 120); do
    kill -0 "$PID_EMU" 2>/dev/null || { got="<emulator crashed, see emulator.log>"; break; }
    got=$(adb logcat -d -s SilentAuthDemo:I 2>/dev/null | sed -n 's/.*RESULT //p' | tail -1 | tr -d '\r')
    [[ -n "$got" ]] && break
    sleep 0.25
  done
  # Synchronous on purpose: a screenshot taken while the next "am start -S" tears the app
  # down crashed the emulator's GPU layer (gfxstream "null ctx").
  adb exec-out screencap -p > "$P_OUT/scenario-$id.png"
  if [[ -n "$got" && "$got" == "$5"* ]]; then RESULT[$core.$id]="PASS  $got"
  else RESULT[$core.$id]="FAIL  ${got:-<no result in 30 s>}  (expected: $5…)"; rc=1; fi
  echo "[$(date +%T.%3N)] $id ${RESULT[$core.$id]}" >> "$LOGS/scenarios.log"
  phase "[$core] $id ${RESULT[$core.$id]%%  *}"
  kill -0 "$PID_EMU" 2>/dev/null || { phase "emulator crashed; stopping (see $OUT/emulator.log)"; exit 1; }
}

scenarios() { # profile
  local core=$1
  # The console takes a bare file name and writes it under the AVD's console_out/.
  local cap="emulator-$core.pcap"
  rm -f "$HOME/.android/avd/sa-demo.avd/console_out/$cap"
  adb emu network capture start "$cap" | grep -q '^OK' || { echo "emulator capture did not start"; exit 2; }
  wifi_and_cellular
  # The warm-up's binding must be stale (sd.stale-after-ms=5000) before A.
  while (( $(date +%s) < WARM_TS + 6 )); do sleep 0.25; done
  if [[ "$core" == gx ]]; then
    run "$core" A "SIM number, Wi-Fi + mobile data on" +251911111111 "Logged in as +251911111111"
  else
    run "$core" A "SIM number, Wi-Fi + mobile data on (full core: SIM-change has no live source)" +251911111111 \
        "Not verified: the network could not confirm +251911111111"
  fi
  local a_ts=$(date +%s)
  run "$core" B "another number" +251922222222 "Not verified"
  adb shell svc data disable
  until_true 80 wifi_only_default || { echo "Wi-Fi never became the only default network" >> "$LOGS/scenarios.log"; }
  run "$core" C "mobile data off, Wi-Fi on" +251911111111 "No mobile data network"
  adb shell svc data enable
  wifi_and_cellular
  # The ECS caches a Gx/Sd binding for 5 s here (stale-after-ms); D must not see A's.
  while (( $(date +%s) < a_ts + 6 )); do sleep 0.25; done
  kill "$PID_pcrf"; wait "$PID_pcrf" 2>/dev/null
  run "$core" D "PCRF simulator down (Gx/Sd)" +251911111111 "The operator refused"
  adb emu network capture stop > /dev/null
  mv "$HOME/.android/avd/sa-demo.avd/console_out/$cap" "$P_OUT/emulator.pcap" \
    || { echo "emulator capture missing" >> "$LOGS/scenarios.log"; rc=1; }
}

# ---------------------------------------------------------------- evidence
TS=(-d "sctp.port==3870,diameter")
port_name() { # map a port to the process behind it
  awk 'BEGIN{n["8084"]="edge-sim";n["8085"]="camara";n["8092"]="demo-bank";n["8095"]="ecs";
             n["3868"]="HSS-sim(S6a)";n["3870"]="PCRF-sim(Gx/Sd)"} {for(i=1;i<=NF;i++) if($i in n) $i=n[$i]; print}'
}
iso_time() { sed -E 's/^[0-9-]+T([0-9:]+\.[0-9]{3})[0-9]*[+-][0-9]+/\1/'; }
summary() { # profile
  local core=$1 s
  echo "Android demo E2E, CORE=$core — $(date -Is)   (warm-up ended $WARMUP_END; scenario times in logs/scenarios.log)"
  for s in A B C D pcap; do echo "  $s  ${RESULT[$core.$s]:-not run}"; done
  echo
  echo "== host-lo.pcap: HTTP, time-ordered (query strings cut: they carry the one-time code/state)"
  tshark -r "$P_OUT/host-lo.pcap" "${TS[@]}" -Y 'http.request or http.response' -T fields -E separator=' ' \
    -e frame.time -e tcp.srcport -e tcp.dstport -e http.request.method -e http.request.uri -e http.response.code 2>/dev/null \
    | iso_time | sed -E 's/\?[^ ]*//g' \
    | awk '{ if ($4 ~ /^(GET|POST)$/) printf "  %s  %-5s -> %-5s %s %s\n", $1, $2, $3, $4, $5;
             else printf "  %s  %-5s <- %-5s %s %s\n", $1, $3, $2, $4, $5 }' | port_name
  echo
  echo "== host-lo.pcap: Diameter (272 = Gx/Sd CCR/CCA, 316/318 = S6a ULR/AIR, 257/280 = CER/DWR)"
  tshark -r "$P_OUT/host-lo.pcap" "${TS[@]}" -Y 'diameter and not diameter.cmd.code==280' -T fields -E separator=' ' \
    -e frame.time -e sctp.srcport -e sctp.dstport -e diameter.cmd.code -e diameter.flags.request \
    -e diameter.Result-Code 2>/dev/null | iso_time \
    | awk '{ printf "  %s  %s cmd=%s %s %s\n", $1, ($5=="True"||$5=="1") ? $2" -> "$3 : $3" <- "$2,
             $4, ($5=="True"||$5=="1") ? "request" : "answer", ($6=="") ? "" : "Result-Code="$6 }' | port_name
  echo "  (+ $(tshark -r "$P_OUT/host-lo.pcap" "${TS[@]}" -Y 'diameter.cmd.code==280' 2>/dev/null | wc -l) DWR/DWA watchdog frames)"
  echo
  echo "== host-lo.pcap: MAP (SCTP/M3UA/SCCP/TCAP)"
  tshark -r "$P_OUT/host-lo.pcap" -Y gsm_map 2>/dev/null | sed -E 's/^ *[0-9]+ +[0-9.]+ +/  /'
  echo
  echo "== host-lo.pcap: protocol hierarchy"
  tshark -r "$P_OUT/host-lo.pcap" "${TS[@]}" -q -z io,phs 2>/dev/null | sed -n '/^frame/,/^=/p' | sed '$d; s/^/  /'
  echo
  echo "== emulator.pcap: the emulator's CELLULAR LAN only (eth0, 10.0.2.15); Wi-Fi (wlan0) is not on it."
  echo "   Every /authorize must be here; the bank calls (/nv/*) must not; scenario C must add nothing."
  tshark -r "$P_OUT/emulator.pcap" -Y 'http.request' -T fields -E separator=' ' \
    -e frame.time -e ip.src -e ip.dst -e tcp.dstport -e http.request.method -e http.request.uri 2>/dev/null \
    | iso_time | sed -E 's/\?[^ ]*//' | awk '{printf "  %s  %s -> %s:%s  %s %s\n", $1, $2, $3, $4, $5, $6}'
  echo
  echo "== servers"
  sed 's/^/  /' "$LOGS/edge-sim.log" "$LOGS/demo-bank.log"
  grep -hE '/authorize (issued|refused)|ready —|/verify reqId|verify end' "$LOGS/camara.log" | sed 's/^/  /'
}

# ---------------------------------------------------------------- run
for core in "${PROFILES[@]}"; do
  P_OUT="$OUT/$core"
  LOGS="$P_OUT/logs"
  mkdir -p "$LOGS"
  tcpdump -i lo -s 0 -U -w "$P_OUT/host-lo.pcap" \
    'sctp or tcp port 8084 or tcp port 8085 or tcp port 8092 or tcp port 8095' > "$LOGS/tcpdump.log" 2>&1 &
  TCPDUMP=$!
  stack_up "$core"
  emulator_ready
  scenarios "$core"
  stop_pids
  kill "$TCPDUMP" 2>/dev/null; wait "$TCPDUMP" 2>/dev/null; TCPDUMP=""
  # Evidence check on the guest capture (cellular LAN only): A, B, D each sent /authorize
  # over cellular, C sent nothing, and no bank call (/nv/*) used cellular, i.e. Wi-Fi was
  # the default route throughout.
  n_auth=$(tshark -r "$P_OUT/emulator.pcap" -Y 'http.request and http.request.uri contains "/authorize"' 2>/dev/null | wc -l)
  n_bank=$(tshark -r "$P_OUT/emulator.pcap" -Y 'http.request and http.request.uri contains "/nv/"' 2>/dev/null | wc -l)
  if [[ "$n_auth" == 3 && "$n_bank" == 0 ]]; then pcap_check="PASS"; else pcap_check="FAIL"; rc=1; fi
  RESULT[$core.pcap]="$pcap_check  emulator.pcap: /authorize over cellular=$n_auth (want 3), bank calls over cellular=$n_bank (want 0)"
  phase "[$core] ${RESULT[$core.pcap]}"
  summary "$core" > "$P_OUT/summary.txt"
  phase "[$core] done"
done

for core in "${PROFILES[@]}"; do sed -n 1,6p "$OUT/$core/summary.txt"; echo; done
echo "timing:"; cat "$OUT/timing.txt"
exit $rc
