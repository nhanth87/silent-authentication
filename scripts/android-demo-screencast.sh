#!/usr/bin/env bash
# Screencast: a Hyprland workspace with real terminals, a live Wireshark capture and the
# Android emulator — CAMARA Number Verification v2 from the app to the services.
#
#   ./scripts/android-demo-screencast.sh
#   → target/android-demo-screencast/silent-auth-camara-v2-screencast.mp4 (+ .srt, pcaps, logs)
#
# What is filmed is a headless Hyprland output (SADEMO) that only the demo windows use, so
# no other application can appear. wf-recorder films it below the shell's bar; desktop
# notifications are muted while recording; focus and the cursor are given back each time a
# demo window opens. Commands are typed into the terminals through kitty remote control and
# run for real, inside one private user + network namespace (no root): Wireshark captures
# the namespace's loopback live (a private copy of dumpcap may capture there), the services
# and the emulator share it. English subtitles, intro/outro cards and chapters are added
# in post; the screen itself is not edited.
#
# Needs: Hyprland, wf-recorder, kitty, wireshark, ffmpeg (libass), ImageMagick, tshark, jq,
# the dists, sas-diameter-testapp, bank-backend-sdk, the AVD "sa-demo" + snapshot sa-e2e-sw.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SELF="$ROOT/scripts/android-demo-screencast.sh"
OUT="$ROOT/target/android-demo-screencast"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/zulu-25}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME ANDROID_HOME
# kitty control sockets: a unix socket path must stay under 108 bytes
SOCKS="${XDG_RUNTIME_DIR:-/tmp}/sademo"

# ================================================================== the namespace holder
ns_holder() { # runs as root of a fresh user + network namespace
  ip link set lo up
  # A non-loopback address: the emulator's modem needs ::1 to resolve (AI_ADDRCONFIG), and the
  # test harness talks from 10.99.0.1 so Wireshark can leave its probes out of the view.
  ip link add dummy0 type dummy; ip addr add 10.99.0.1/24 dev dummy0
  ip addr add fd00:5a::1/64 dev dummy0 nodad; ip link set dummy0 up
  tcpdump -i lo -s 0 -U -w "$OUT/host-lo.pcap" 'sctp or tcp portrange 8084-8095' > "$OUT/logs/tcpdump.log" 2>&1 &
  echo $! > "$OUT/tcpdump.pid"
  touch "$OUT/ns.ready"
  exec sleep infinity
}

# ================================================================== host side helpers
hypr() { hyprctl eval "$1" > /dev/null; }
save_focus() {
  FOCUS_ADDR=$(hyprctl activewindow -j | jq -r '.address // empty')
  local p; p=$(hyprctl cursorpos); CUR_X=${p%,*}; CUR_Y=${p#*, }
}
restore_focus() {
  [[ -n "${FOCUS_ADDR:-}" ]] && hypr "hl.dispatch(hl.dsp.focus({ window = \"address:$FOCUS_ADDR\" }))"
  hypr "hl.dispatch(hl.dsp.cursor.move({ x = $CUR_X, y = $CUR_Y }))"
}
await_window() { # jq predicate on a client, seconds → address; gives focus back once it is mapped
  local pred=$1 n=$(( ${2:-60} * 10 )) a=""
  for _ in $(seq 1 "$n"); do
    a=$(hyprctl clients -j | jq -r "[.[] | select($pred)][0].address // empty")
    [[ -n "$a" ]] && break
    sleep 0.1
  done
  restore_focus
  echo "$a"
}
step() { # number, title, subtitle — the subtitle stays until the next step or note
  printf '%s\tstep\t%s\t%s\t%s\n' "$(date +%s%3N)" "$1" "$2" "$3" >> "$OUT/steps.tsv"
  echo "[screencast] step $1: $2"
}
note() { printf '%s\tnote\t\t\t%s\n' "$(date +%s%3N)" "$1" >> "$OUT/steps.tsv"; }
type_in() { # terminal, command — typed, then Enter
  local sock="unix:$SOCKS/t$1.sock" text=$2 i
  printf '\x15' | kitty @ --to "$sock" send-text --stdin
  for (( i = 0; i < ${#text}; i += 4 )); do
    printf '%s' "${text:i:4}" | kitty @ --to "$sock" send-text --stdin
    sleep 0.035
  done
  sleep 0.25
  printf '\r' | kitty @ --to "$sock" send-text --stdin
}
wait_for() { # seconds, command... (host side; logs are on the shared filesystem)
  local n=$(( $1 * 4 )); shift
  for _ in $(seq 1 "$n"); do "$@" > /dev/null 2>&1 && return 0; sleep 0.25; done
  echo "[screencast] timeout waiting for: $*"; return 1
}

# ================================================================== the terminals' shells
write_shells() {
  mkdir -p "$OUT/rc" "$OUT/run" "$OUT/device" "$OUT/bin" "$OUT/wsconf/wireshark"
  cp /usr/bin/wireshark /usr/bin/dumpcap "$OUT/bin/"     # dumpcap next to wireshark: a copy may capture in the namespace
  # A relocated wireshark looks for its data next to itself (../share, ../lib): without them it
  # has no Diameter dictionary, and a filter on diameter.* fields is rejected.
  mkdir -p "$OUT/share" "$OUT/lib"
  ln -s /usr/share/wireshark "$OUT/share/wireshark"; ln -s /usr/lib/wireshark "$OUT/lib/wireshark"
  cat > "$OUT/rc/common.sh" <<EOF
export ROOT="$ROOT" OUT="$OUT" JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$ANDROID_HOME"
export PATH="$OUT/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:\$PATH"
export XDG_CONFIG_HOME="$OUT/wsconf" HISTFILE=/dev/null TMPDIR="$OUT/run" HOST_UID=$(id -u) HOST_GID=$(id -g)
export CAMARA_API='camara.path matches "^/(authorize|token|camara/)"'

export SIMS="$ROOT/sas-diameter-testapp/target" BANK_JAR="$ROOT/bank-backend-sdk/target/bank-backend-sdk-0.1.0-SNAPSHOT.jar"
export DEMO="$ROOT/demo-android/server" APK="$ROOT/demo-android/apk/silent-auth-demo-0.1.0-debug.apk"
export ECS_OPTS="-Dsas.transport.resolver=sd -Dsas.transport.sd.peer-port=3870 -Dsas.transport.sd.stale-after-ms=5000"
export CAMARA_OPTS='-Dsas.security.token-validation-enabled=true -Dsas.oauth.authorize.trust-src-header=true -Dsas.oauth.clients-json={"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["authorization_code"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","number-verification:device-phone-number:read","sim-swap:check","sim-swap:retrieve-date"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'
export CAMARA_VIEW='(http.request or http.response or diameter.cmd.code == 257 or diameter.cmd.code == 272 or sctp.chunk_type == 1) and not ip.addr == 10.99.0.1 and not diameter.Result-Code == 5030 and not diameter.Framed-IP-Address[0:3] == 0a:63:63'
cd "$OUT/run"
EOF
  local name prompt
  for t in 1 2 3; do
    case $t in 1) name="pcap · core" ;; 2) name="camara services" ;; 3) name="android test" ;; esac
    cat > "$OUT/rc/t$t.rc" <<EOF
source "$OUT/rc/common.sh"
PS1='\[\e[1;38;5;81m\]$name\[\e[0m\] \[\e[38;5;244m\]❯\[\e[0m\] '
EOF
  done
  # The capture terminal: tcpdump (root of the namespace) captures; Wireshark runs as the desktop
  # user — as root it would not load the masking script — and reads tcpdump's output from a fifo.
  cat >> "$OUT/rc/t1.rc" <<'EOF'
wireshark() {
  unshare --user --map-user="$HOST_UID" --map-group="$HOST_GID" \
    "$OUT/bin/wireshark" -X lua_script:"$OUT/wsconf/camara-masked.lua" "$@"
}
EOF
  # the Android side: helpers the test terminal calls
  cat >> "$OUT/rc/t3.rc" <<'EOF'
adb() { timeout "${ADB_TIMEOUT:-25}" "$ANDROID_HOME/platform-tools/adb" "$@"; }
_wifi_default() {
  local d id; d=$(adb shell dumpsys connectivity 2>/dev/null | tr -d '\r')
  id=$(sed -n 's/.*Active default network: \([0-9][0-9]*\).*/\1/p' <<< "$d" | head -1)
  [[ -n "$id" ]] && grep -q "network{$id}.*ni{WIFI[^}]*CONNECTED" <<< "$d"
}
_mobile_up() { adb shell dumpsys connectivity 2>/dev/null | grep -q 'ni{MOBILE[^}]*CONNECTED'; }
_until() { local n=$1; shift; for _ in $(seq 1 "$n"); do "$@" && return 0; sleep 0.25; done; return 1; }
android_ready() { # boot, Wi-Fi default route + cellular, the demo app
  ADB_TIMEOUT=180 adb wait-for-device
  _until 960 bash -c '[[ "$(adb shell getprop sys.boot_completed | tr -d "\r")" == 1 ]]'
  adb shell settings put global hide_error_dialogs 1
  adb shell svc data enable
  _until 20 _wifi_default || { adb shell svc wifi disable; adb shell svc wifi enable; }
  _until 160 _wifi_default; _until 160 _mobile_up
  ADB_TIMEOUT=90 adb install -r "$APK" > /dev/null && echo "demo app installed: et.restlink.demo"
  adb shell dumpsys connectivity | tr -d '\r' | grep -oE 'ni\{(WIFI|MOBILE)[^}]*\}' | sed 's/^ni{/  /; s/ extra:.*//'
  adb shell am start -S -W -n et.restlink.demo/.MainActivity > /dev/null
  touch "$OUT/run/android.ready"
}
login() { # [--hashed | --device-phone-number] <phone> — one login through the app
  local mode=phone age=240 got="" n
  case "${1:-}" in
    --hashed) mode=hashed; shift ;;
    --device-phone-number) mode=device; shift ;;
    --sim-swap-check) mode=simswap; age=$2; shift 2 ;;
    --sim-swap-date) mode=simswap-date; shift ;;
  esac
  local phone=$1
  # Harness only (from 10.99.0.1, left out of the Wireshark view): the bank's v2 operation for
  # this login, and three lookups that keep the entitlement server's Gx path warm.
  curl -s -o /dev/null --interface 10.99.0.1 -X POST http://10.99.0.1:8092/nv/mode -d "{\"mode\":\"$mode\",\"maxAge\":$age}"
  for i in 1 2 3; do
    curl -s -o /dev/null --interface 10.99.0.1 -X POST http://10.99.0.1:8095/sas-subscriber-exposure/v1/ue-id/retrieve \
      -H 'Content-Type: application/json' -H 'X-Sas-Budget-Ms: 400' -H "X-Request-Id: keepwarm-$i-$RANDOM$RANDOM" \
      -d "{\"afId\":\"sas-camara\",\"ueIpAddr\":{\"ipv4Addr\":\"10.99.99.$i\"},\"portNumber\":40000,\"observedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%S.000Z)\"}"
  done
  n=$(( $(ls "$OUT/device" | wc -l) + 1 ))
  adb emu network capture start "sademo-$n.pcap" > /dev/null
  adb logcat -c
  adb shell am start -S -W -n et.restlink.demo/.MainActivity --es phone "$phone" --ez autologin true > /dev/null
  for _ in $(seq 1 120); do
    got=$(adb logcat -d -s SilentAuthDemo:I 2>/dev/null | sed -n 's/.*RESULT //p' | tail -1 | tr -d '\r')
    [[ -n "$got" ]] && break; sleep 0.25
  done
  sleep 1.5
  adb emu network capture stop > /dev/null
  mv "$HOME/.android/avd/sa-demo.avd/console_out/sademo-$n.pcap" "$OUT/device/$n.pcap" 2>/dev/null
  printf '\e[1;38;5;114m📱 %s\e[0m\n' "${got//|/  ·  }"
  printf '%s\t%s\t%s\n' "$n" "$mode" "$got" >> "$OUT/run/logins.tsv"
}
data() { adb shell svc data "$1"; _until 80 bash -c "$( [[ $1 == disable ]] && echo '!' ) adb shell dumpsys connectivity | grep -q 'ni{MOBILE[^}]*CONNECTED'"; [[ $1 == enable ]] && _until 160 _wifi_default; echo "mobile data: $1d"; }
cellular_capture() { # what the phone's cellular link carried, across all logins
  mergecap -w "$OUT/device.pcap" "$OUT"/device/*.pcap 2>/dev/null
  tshark -r "$OUT/device.pcap" -Y http.request -T fields -e ip.src -e http.request.method -e http.request.uri 2>/dev/null \
    | sed -E 's/\?.*//' | sort | uniq -c
}
EOF
  # the services terminal: the warm-up after a cold start
  cat >> "$OUT/rc/t2.rc" <<'EOF'
warmup() { # two logins from the test client: the first lookup after a cold start overruns 400 ms
  local ok=0 url loc out=()
  for _ in $(seq 1 8); do
    url=$(curl -s -X POST http://127.0.0.1:8092/nv/start | sed -nE 's/.*"authorizeUrl":"([^"]+)".*/\1/p')
    loc=$(curl -s -o /dev/null -w '%{redirect_url}' "${url/10.0.2.2/127.0.0.1}")
    case "$loc" in *code=*) out+=(code); ok=$((ok + 1)) ;; *error=*) out+=(access_denied); ok=0 ;; *) out+=(none); ok=0 ;; esac
    (( ok >= 2 )) && break
  done
  echo "warm-up logins: ${out[*]}"
  touch "$OUT/run/warm"
}
EOF
  # Wireshark profile: service names for the ports, Diameter on the Gx port, the columns
  local W="$OUT/wsconf/wireshark"
  cat > "$W/services" <<'EOF'
edge-proxy      8084/tcp
sas-camara      8085/tcp
bank            8092/tcp
sas-ecs         8095/tcp
pcrf-gx         3870/sctp
EOF
  printf 'decode_as_entry: sctp.port,3870,(none),DIAMETER\n' > "$W/decode_as_entries"
  # Nothing secret on screen: the columns come from a post-dissector that drops query strings
  # and masks tokens, codes, state, nonce and PKCE values (scripts/video/camara-masked.lua).
  # Not in plugins/: run as the desktop user Wireshark would load it from there AND from -X.
  cp "$ROOT/scripts/video/camara-masked.lua" "$OUT/wsconf/camara-masked.lua"
  cat > "$W/preferences" <<'EOF'
gui.update.enabled: FALSE
gui.layout_type: 1
gui.layout_content_1: PLIST
gui.layout_content_2: NONE
gui.layout_content_3: NONE
gui.qt.font_name: FiraCode Nerd Font Mono,9,-1,5,400,0,0,0,0,0,0,0,0,0,0,1
gui.window_title: CAMARA NV v2 · live capture
nameres.transport_name: TRUE
gui.column.format:
	"No.", "%m",
	"Time", "%t",
	"From", "%rS",
	"To", "%rD",
	"Protocol", "%p",
	"Message", "%Cus:camara.msg:0:R",
	"Path", "%Cus:camara.path:0:R",
	"Body (secrets masked)", "%Cus:camara.body:0:R"
EOF
  cat > "$W/recent" <<'EOF'
gui.time_format: ABSOLUTE
gui.time_precision: MSEC
column.width:
	%m,
	46,
	%t,
	96,
	%rS,
	92,
	%rD,
	92,
	%p,
	78,
	%Cus:camara.msg:0:R,
	210,
	%Cus:camara.path:0:R,
	330,
	%Cus:camara.body:0:R,
	600
EOF
  cat > "$W/colorfilters" <<'EOF'
@Android (cellular) → edge proxy@tcp.port == 8084@[65535,57054,47288][0,0,0]
@CAMARA Number Verification v2@http.request.uri contains "/camara/number-verification" or json.member_with_value contains "devicePhoneNumber"@[46774,56283,65535][0,0,0]
@OIDC authorize / token@http.request.uri contains "/authorize" or http.request.uri contains "/token" or http.location@[51914,62451,51914][0,0,0]
@Entitlement server API@tcp.port == 8095@[61166,61166,61166][0,0,0]
@Diameter Gx@diameter@[62451,60395,65535][0,0,0]
@SCTP association@sctp@[65535,65535,51400][0,0,0]
EOF
}

# ================================================================== post production
compose() {
  local P="$OUT/post"; mkdir -p "$P"
  local t0 dur date_str
  t0=$(cat "$OUT/t0"); date_str=$(LC_ALL=C date '+%-d %B %Y')
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/screen.mp4")
  INTRO=5 OUTRO=7
  python3 -I - "$OUT/steps.tsv" "$t0" "$dur" "$INTRO" "$P" <<'PY'
import sys
steps, t0, dur, intro, P = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
rows = []
for line in open(steps, encoding="utf-8"):
    ms, kind, n, title, text = line.rstrip("\n").split("\t")
    rows.append(((int(ms) - t0) / 1000.0, kind, n, title, text))
def ts(t, srt=False):
    t = max(t, 0.0); h, m, s = int(t // 3600), int(t % 3600 // 60), t % 60
    return f"{h:02d}:{m:02d}:{int(s):02d},{int(round((s % 1) * 1000)) % 1000:03d}" if srt else f"{h}:{m:02d}:{s:05.2f}"
ass = ["[Script Info]", "ScriptType: v4.00+", "PlayResX: 1920", "PlayResY: 1080", "WrapStyle: 0",
       "ScaledBorderAndShadow: yes", "", "[V4+ Styles]",
       "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, "
       "Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, "
       "MarginR, MarginV, Encoding",
       "Style: Sub,Inter,31,&H00FFFFFF,&H00FFFFFF,&H28000000,&H28000000,0,0,0,0,100,100,0,0,3,14,0,2,170,170,26,1",
       "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"]
srt, chapters, cur = [], [], ("", "")
for i, (t, kind, n, title, text) in enumerate(rows):
    end = rows[i + 1][0] if i + 1 < len(rows) else dur
    if kind == "step":
        cur = (n, title); chapters.append((t, f"{n} · {title}"))
    label = f"{cur[0]}  ·  {cur[1].upper()}"
    body = text.replace("{", "(").replace("}", ")")
    ass.append(f"Dialogue: 0,{ts(t)},{ts(end - 0.05)},Sub,,0,0,0,,"
               f"{{\\fnInter SemiBold\\fs19\\c&HFFA658&\\fsp2}}{label}{{\\r}}\\N{body}")
    srt.append(f"{len(srt) + 1}\n{ts(t + intro, True)} --> {ts(end + intro - 0.05, True)}\n{label}\n{text}\n")
open(f"{P}/subs.ass", "w", encoding="utf-8").write("\n".join(ass) + "\n")
open(f"{P}/subs.srt", "w", encoding="utf-8").write("\n".join(srt))
meta = [";FFMETADATA1", "title=Silent Authentication — CAMARA Number Verification v2, Android to services (screencast)"]
marks = [(0.0, "Introduction")] + [(t + intro, c) for t, c in chapters] + [(dur + intro, "Results")]
for i, (t, c) in enumerate(marks):
    end = marks[i + 1][0] if i + 1 < len(marks) else dur + intro + 7
    meta += ["[CHAPTER]", "TIMEBASE=1/1000", f"START={int(t * 1000)}", f"END={int(end * 1000)}", f"title={c}"]
open(f"{P}/chapters.txt", "w", encoding="utf-8").write("\n".join(meta) + "\n")
PY
  magick -size 1920x1080 gradient:'#0d1117'-'#161b22' \
    -font Inter-SemiBold -fill '#58a6ff' -pointsize 30 -annotate +160+380 'SCREENCAST · CAMARA NUMBER VERIFICATION v2' \
    -font Inter-Bold -fill white -pointsize 84 -annotate +156+490 'Silent Authentication' \
    -font Inter-Regular -fill '#c9d1d9' -pointsize 40 -annotate +160+560 'From the Android app to the CAMARA services, message by message' \
    -fill '#58a6ff' -draw 'rectangle 160,600 400,604' \
    -font Inter-Regular -fill '#8b949e' -pointsize 28 -annotate +160+660 'Hyprland · kitty · Wireshark live capture · Android emulator' \
    -font Inter-Regular -fill '#8b949e' -pointsize 28 -annotate +160+710 'sas-camara (OIDC + NV v2)  ·  sas-ecs (entitlement)  ·  PCRF over Diameter Gx / SCTP' \
    -font Inter-Regular -fill '#6e7681' -pointsize 24 -annotate +160+940 "Restlink · Digicom-ET  ·  $date_str" "$P/intro.png"
  short() { local o=${1%%|*}; (( ${#o} > 46 )) && o=${o%%:*}; printf '%s' "$o"; }
  local args=() y=410 n mode got label want ok
  while IFS=$'\t' read -r n mode got; do
    case "$n:$mode" in
      1:phone) label="A   · v2 verify(phoneNumber)";       want="Logged in" ;;
      2:hashed) label="A2  · v2 verify(hashedPhoneNumber)"; want="Logged in" ;;
      3:device) label="A3  · v2 device-phone-number";      want="Logged in" ;;
      4:simswap) label="S1  · SIM Swap check, 72 h";       want="Logged in" ;;
      5:simswap) label="S2  · SIM Swap check, 720 h";      want="The operator refused (sim_swapped" ;;
      6:simswap-date) label="S3  · SIM Swap retrieve-date"; want="Logged in" ;;
      7:phone) label="B   · another number";               want="Not verified" ;;
      8:phone) label="C   · mobile data off";              want="No mobile data" ;;
      9:phone) label="D   · PCRF down";                    want="The operator refused" ;;
      *) label="$n $mode"; want="" ;;
    esac
    [[ -n "$want" && "$got" == "$want"* ]] && ok=PASS || ok=FAIL
    [[ "$ok" == PASS ]] && args+=(-fill '#3fb950') || args+=(-fill '#f85149')
    args+=(-font Inter-SemiBold -pointsize 30 -annotate +160+$y "$ok"
           -fill '#e6edf3' -font Inter-Regular -pointsize 30 -annotate +300+$y "$label"
           -fill '#8b949e' -annotate +900+$y "→" -fill '#e6edf3' -annotate +960+$y "$(short "$got")")
    y=$((y + 54))
  done < "$OUT/run/logins.tsv"
  magick -size 1920x1080 gradient:'#0d1117'-'#161b22' \
    -font Inter-SemiBold -fill '#58a6ff' -pointsize 30 -annotate +160+290 'RESULTS' \
    -font Inter-Bold -fill white -pointsize 56 -annotate +156+370 'What the app showed is what the wire carried.' \
    "${args[@]}" \
    -font Inter-Regular -fill '#6e7681' -pointsize 24 -annotate +160+960 'Captures: host-lo.pcap (services) · device.pcap (the phone’s cellular link)  ·  target/android-demo-screencast/' \
    "$P/outro.png"
  local filt
  filt="[0:v]fps=30,format=yuv420p,setpts=PTS-STARTPTS,setsar=1,ass=$P/subs.ass,fade=t=in:st=0:d=0.6[main];"
  filt+="[1:v]format=yuv420p,setsar=1,fade=t=in:st=0:d=0.8,fade=t=out:st=$((INTRO - 1)):d=1[intro];"
  filt+="[2:v]format=yuv420p,setsar=1,fade=t=in:st=0:d=1,fade=t=out:st=$((OUTRO - 1)):d=1[outro];"
  filt+="[intro][main][outro]concat=n=3:v=1:a=0[v]"
  ffmpeg -v error -y -i "$OUT/screen.mp4" -loop 1 -t "$INTRO" -framerate 30 -i "$P/intro.png" \
    -loop 1 -t "$OUTRO" -framerate 30 -i "$P/outro.png" -i "$P/subs.srt" -i "$P/chapters.txt" \
    -filter_complex "$filt" -map '[v]' -map 3:s -map_metadata 4 -map_chapters 4 \
    -c:v libx264 -preset slow -crf 18 -pix_fmt yuv420p -r 30 -movflags +faststart \
    -c:s mov_text -metadata:s:s:0 language=eng -metadata:s:s:0 title=English \
    "$OUT/silent-auth-camara-v2-screencast.mp4" || return 1
  cp "$P/subs.srt" "$OUT/silent-auth-camara-v2-screencast.srt"
}

# ================================================================== the film
main() {
  for c in wf-recorder kitty wireshark ffmpeg magick tshark jq hyprctl nsenter mergecap; do
    command -v "$c" > /dev/null || { echo "missing $c"; exit 2; }
  done
  [[ -d "$HOME/.android/avd/sa-demo.avd/snapshots/sa-e2e-sw" ]] || { echo "no snapshot sa-e2e-sw: run scripts/android-demo-e2e.sh once"; exit 2; }
  ps -eo comm | grep -q '^qemu-system' && { echo "an emulator is running: stop it first"; exit 2; }
  rm -rf "$OUT" "$SOCKS"; mkdir -p "$OUT/logs" "$SOCKS"
  write_shells
  local REC="" NS="" made_output=0 kitties=()
  finish() {
    [[ -n "$REC" ]] && kill -INT "$REC" 2>/dev/null && wait "$REC" 2>/dev/null
    if [[ -n "$NS" ]]; then           # everything that lives in the namespace
      local ino; ino=$(readlink "/proc/$NS/ns/net")
      timeout 10 nsenter -t "$NS" -U -n --preserve-credentials "$ANDROID_HOME/platform-tools/adb" emu kill > /dev/null 2>&1
      for p in /proc/[0-9]*; do [[ "$(readlink "$p/ns/net" 2>/dev/null)" == "$ino" ]] && kill "${p#/proc/}" 2>/dev/null; done
      sleep 2
      for p in /proc/[0-9]*; do [[ "$(readlink "$p/ns/net" 2>/dev/null)" == "$ino" ]] && kill -9 "${p#/proc/}" 2>/dev/null; done
    fi
    for p in "${kitties[@]}"; do kill "$p" 2>/dev/null; done
    sleep 1
    (( made_output )) && hyprctl output remove SADEMO > /dev/null 2>&1
    timeout 5 qs -c noctalia-shell ipc call notifications disableDND > /dev/null 2>&1
    restore_focus 2>/dev/null
  }
  trap finish EXIT
  trap 'exit 130' INT TERM

  # --- the namespace: lo + dummy0, tcpdump for the deliverable pcap
  unshare -rn "$SELF" _ns "$OUT" > "$OUT/logs/ns.log" 2>&1 &
  NS=$!
  wait_for 10 test -f "$OUT/ns.ready" || exit 2

  # --- the stage: a headless output only the demo uses; the shell's bar is above the film
  save_focus
  timeout 5 qs -c noctalia-shell ipc call notifications enableDND > /dev/null 2>&1
  hyprctl output create headless SADEMO > /dev/null; made_output=1
  hypr 'hl.monitor({ output = "SADEMO", mode = "1920x1120@30", position = "20000x0", scale = 1 })'
  rule() { # name, class regex, extra fields
    hypr "hl.window_rule({ name = \"sademo-$1\", match = { class = \"$2\" }, monitor = \"SADEMO\", float = true,
          no_initial_focus = true, $3 })"
  }
  # film coordinates (x, y) → monitor-relative (x, y + 40)
  rule t1 '^sademo-t1$' 'move = "0 602", size = "441 392"'
  rule t2 '^sademo-t2$' 'move = "445 602", size = "441 392"'
  rule t3 '^sademo-t3$' 'move = "890 602", size = "441 392"'
  rule ws '^org.wireshark.Wireshark$' 'move = "0 40", size = "1331 556"'
  rule emu '^Emulator$' 'border_size = 0'
  sleep 0.5

  wf-recorder -o SADEMO -g "20000,40 1920x1080" -r 30 -c libx264 -p preset=veryfast -p crf=16 \
    -f "$OUT/screen.mp4" > "$OUT/logs/wf-recorder.log" 2>&1 &
  REC=$!
  sleep 0.5; date +%s%3N > "$OUT/t0"
  step 1 "The demo workspace" "A Hyprland workspace reserved for the demo. Three terminals open: packet capture and core network, the CAMARA services, the Android tests."
  sleep 2.5

  # --- three terminals pop up, each a shell inside the demo namespace
  for t in 1 2 3; do
    save_focus
    ( exec kitty --class "sademo-t$t" --config NONE -o allow_remote_control=socket-only --listen-on "unix:$SOCKS/t$t.sock" \
        -o font_family="FiraCode Nerd Font Mono" -o font_size=9 -o background=#0d1117 -o foreground=#c9d1d9 \
        -o window_padding_width=8 -o confirm_os_window_close=0 -o enable_audio_bell=no -o cursor_blink_interval=0 \
        nsenter -t "$NS" -U -n --preserve-credentials -- bash --noprofile --rcfile "$OUT/rc/t$t.rc" -i ) > /dev/null 2>&1 &
    kitties+=($!)
    await_window ".class == \"sademo-t$t\"" 20 > /dev/null
    wait_for 10 test -S "$SOCKS/t$t.sock"
    sleep 0.9
  done
  sleep 1.5

  # --- packet capture first
  step 2 "Packet capture" "Packet capture first: tcpdump captures the loopback, where every service talks, saves camara.pcap and feeds Wireshark live. The view keeps the CAMARA flow; tokens, codes and state are masked."
  type_in 1 'mkfifo lo.fifo'
  type_in 1 'tcpdump -i lo -U -w - "tcp portrange 8084-8095 or sctp" 2>/dev/null | tee camara.pcap > lo.fifo &'
  sleep 1.5
  type_in 1 'echo "$CAMARA_VIEW"'
  sleep 2
  save_focus
  type_in 1 'wireshark -k -i lo.fifo -Y "$CAMARA_VIEW" &> wireshark.log &'
  await_window '.class == "org.wireshark.Wireshark"' 40 > /dev/null
  sleep 3

  # --- core network
  step 3 "Core network: PCRF" "The core network: a PCRF simulator that maps the subscriber's IP address to the phone number over Diameter Gx, carried on SCTP."
  type_in 1 'java -jar $SIMS/sas-diameter-testapp.jar --diameter-port 3870 --web-port 28086 --client-host sas.restlink.et &> pcrf.log &'
  wait_for 60 grep -q 'simulator ready' "$OUT/run/pcrf.log"
  type_in 1 'grep -oE "Started Server=SCTP.*|Diameter listening on [0-9.:]+ transport=sctp" pcrf.log'
  sleep 3

  # --- the services
  step 4 "Entitlement server" "The entitlement server (sas-ecs) starts and opens its SCTP association to the PCRF: SCTP INIT and the Diameter capabilities exchange (CER/CEA) appear in Wireshark."
  type_in 2 'SAS_JAVA_OPTS="$ECS_OPTS" $ROOT/dist-ecs/run.sh &> ecs.log &'
  wait_for 150 grep -q 'Peer is up for Association \[name=sd-sas' "$OUT/run/ecs.log"
  type_in 2 'grep -oE "subscriber exposure on.{0,40}|Peer is up for Association .name=sd-sas" ecs.log'
  sleep 4
  step 5 "CAMARA gateway, bank, edge proxy" "The CAMARA gateway (sas-camara: OpenID Connect and Number Verification v2), the bank's backend, and the operator's edge proxy in front of the gateway."
  type_in 2 'SAS_JAVA_OPTS="$CAMARA_OPTS" $ROOT/dist/run.sh &> camara.log &'
  type_in 2 'java -Ddemo.bind=0.0.0.0 -cp $BANK_JAR $DEMO/DemoBank.java 8092 &> bank.log &'
  type_in 2 'java $DEMO/EdgeSim.java 8084 http://127.0.0.1:8085 &> edge.log &'
  wait_for 150 grep -q 'sas-camara ready' "$OUT/run/camara.log"
  wait_for 60 grep -q 'demo-bank\] :8092' "$OUT/run/bank.log"
  wait_for 60 grep -q 'edge-sim\] :8084' "$OUT/run/edge.log"
  type_in 2 'grep -ohE "sas-camara ready|\[demo-bank\] :8092|\[edge-sim\] :8084 .{0,40}" camara.log bank.log edge.log'
  sleep 3
  step 6 "Warm-up" "Warm-up: two logins from the test client. The first lookup after a cold start exceeds the gateway's 400 ms budget and fails closed, so the path is warmed before the phone logs in."
  type_in 2 'warmup'
  wait_for 90 test -f "$OUT/run/warm"
  sleep 3
  type_in 2 'clear; tail -n 0 -F camara.log | grep --line-buffered -oE "/authorize (issued|refused).{0,60}|match=[a-z]+ assurance=[A-Z]+ fallback=[A-Za-z_]+"'

  # --- Android
  step 7 "Android emulator" "The Android emulator boots with the demo bank app. Wi-Fi is the default route; mobile data is on."
  save_focus
  type_in 3 'QT_QPA_PLATFORM=xcb emulator -avd sa-demo -snapshot sa-e2e-sw -no-snapshot-save -gpu swiftshader_indirect &> emulator.log &'
  local main_win tool_win
  main_win=$(await_window '.class == "Emulator" and (.title | startswith("Android Emulator"))' 90)
  sleep 1
  # Emulator windows place themselves; move them onto the film (global coordinates).
  [[ -n "$main_win" ]] && hypr "hl.dispatch(hl.dsp.window.move({ x = 21374, y = 60, window = \"address:$main_win\" }))"
  for tool_win in $(hyprctl clients -j | jq -r '.[] | select(.class == "Emulator" and (.title | startswith("Android Emulator") | not)) | .address'); do
    hypr "hl.dispatch(hl.dsp.window.move({ x = 21791, y = 60, window = \"address:$tool_win\" }))"
  done
  restore_focus
  type_in 3 'android_ready'
  wait_for 300 test -f "$OUT/run/android.ready"
  sleep 3

  login_and_wait() { # count, subtitle note after the result
    type_in 3 "$1"
    wait_for 60 bash -c "[[ \$(wc -l < '$OUT/run/logins.tsv' 2>/dev/null || echo 0) -ge $2 ]]"
    sleep 1.5
    [[ -n "${3:-}" ]] && note "$3"
    sleep 6
  }
  step 8 "Scenario A — v2 verify" "Scenario A. The app logs in. Watch Wireshark: the phone's GET /authorize to the edge proxy, the gateway asking the entitlement server, Gx resolving the IP, the 302 with a code."
  login_and_wait 'login +251911111111' 1 \
    "Then the bank: POST /token, and POST /camara/number-verification/v2/verify → devicePhoneNumberVerified: true. The app shows the same: logged in."
  step 9 "Scenario A2 — hashed number" "Scenario A2. The bank sends only the SHA-256 of the number: v2 verify with hashedPhoneNumber. The answer is the same: true."
  login_and_wait 'login --hashed +251911111111' 2
  step 10 "Scenario A3 — device-phone-number" "Scenario A3. The bank asks the network which number this device has: GET /number-verification/v2/device-phone-number → +251911111111."
  login_and_wait 'login --device-phone-number +251911111111' 3
  step 11 "SIM Swap — check, 72 hours" "CAMARA SIM Swap v2. With the login's token the bank asks: did this SIM change in the last 72 hours? POST /camara/sim-swap/v2/check → swapped: false. Login allowed."
  login_and_wait 'login --sim-swap-check 72 +251911111111' 4
  step 12 "SIM Swap — check, 720 hours" "Same question over 30 days. The lab SIM changed 10 days ago: swapped: true. The bank refuses the silent login and falls back to OTP."
  login_and_wait 'login --sim-swap-check 720 +251911111111' 5
  step 13 "SIM Swap — retrieve-date" "POST /camara/sim-swap/v2/retrieve-date: the network returns latestSimChange, the date of the last SIM change, for the bank's own risk rules."
  login_and_wait 'login --sim-swap-date +251911111111' 6
  step 14 "Scenario B — another number" "Scenario B. Another number on the same SIM: v2 verify answers devicePhoneNumberVerified: false. The bank falls back to OTP."
  login_and_wait 'login +251922222222' 7
  step 15 "Scenario C — mobile data off" "Scenario C. Mobile data off. The SDK refuses to send the request over Wi-Fi: no new /authorize appears in Wireshark at all."
  type_in 3 'data disable'
  sleep 4
  login_and_wait 'login +251911111111' 8
  type_in 3 'data enable'
  sleep 5
  step 16 "Scenario D — PCRF down" "Scenario D. The PCRF is stopped: the entitlement server cannot resolve the IP, the gateway redirects with access_denied and no token is ever issued."
  type_in 1 'kill %3; echo "PCRF simulator stopped"'
  sleep 3
  login_and_wait 'login +251911111111' 9
  step 17 "The phone's cellular link" "The phone's own capture: /authorize is the only call on the cellular link. The bank calls (/nv/start, /nv/complete) went over Wi-Fi."
  type_in 3 'clear; cellular_capture'
  sleep 8
  step 18 "The CAMARA API in Wireshark" "camara.pcap opened in Wireshark, filtered to the CAMARA API: /authorize, /token, Number Verification v2 and SIM Swap v2, every login in order. Tokens, codes and state are masked."
  local frame before ws
  frame=$(tshark -r "$OUT/run/camara.pcap" -Y 'http.response and json.member_with_value contains "devicePhoneNumberVerified:true"' \
          -T fields -e frame.number 2>/dev/null | head -1)
  before=$(hyprctl clients -j | jq -c '[.[] | select(.class == "org.wireshark.Wireshark") | .address]')
  save_focus
  type_in 1 "clear; wireshark -r camara.pcap -Y \"\$CAMARA_API\" -g ${frame:-1} -o gui.layout_content_2:PDETAILS &> wireshark-api.log &"
  ws=$(await_window ".class == \"org.wireshark.Wireshark\" and ((.address | IN($before[])) | not)" 40)
  if [[ -n "$ws" ]]; then
    hypr "hl.dispatch(hl.dsp.window.resize({ x = 1920, y = 910, window = \"address:$ws\" }))"
    hypr "hl.dispatch(hl.dsp.window.move({ x = 20000, y = 40, window = \"address:$ws\" }))"
    restore_focus
  fi
  sleep 6
  note "The selected packet is the Number Verification v2 answer of the first login: 200 OK, devicePhoneNumberVerified: true. Above it: authorize, the code, the token, the verify call."
  sleep 9
  note "Further down: SIM Swap v2 — check answers swapped: false, then true; retrieve-date answers latestSimChange. What each app screen showed is what the wire carried."
  sleep 9
  REC_END=1
  kill -INT "$REC"; wait "$REC" 2>/dev/null; REC=""
  finish; trap - EXIT
  echo "[screencast] composing"
  compose && echo "[screencast] $OUT/silent-auth-camara-v2-screencast.mp4"
}

case "${1:-}" in
  _ns) OUT=$2; ns_holder ;;
  _compose) compose && echo "[screencast] $OUT/silent-auth-camara-v2-screencast.mp4" ;;   # post production only
  *) main ;;
esac
