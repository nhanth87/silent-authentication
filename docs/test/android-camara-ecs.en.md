# Android device → CAMARA gateway → entitlement server: test steps

> Verified 2026-10-23 on branch `nv-2`: Android emulator API 36, `dist/` (sas-camara),
> `dist-ecs/` (sas-ecs), and the HLR, HSS and PCRF simulators.
> - Automated run (§2): 8/8 scenarios pass, plus the capture check in both profiles; about
>   2.5–4 min, depending on host load.
> - Screencast (§4): 9/9 logins as expected, covering Number Verification v2 (verify,
>   hashed verify, device-phone-number) and SIM Swap v2 (check, retrieve-date); about 8 min.

This guide tests CAMARA Number Verification v2 and SIM Swap v2 end to end, from a real
Android app built on `ue-sdk-android` to the two server binaries and the core-network
simulators behind them:

| Component | Binary / file | Role in this test |
|---|---|---|
| Android app "Silent Auth Demo" | `demo/android/apk/silent-auth-demo-0.1.0-debug.apk` | **Login** button: `/nv/start` → SDK `authorize()` over **cellular** → `/nv/complete` |
| Bank backend (lab) | `demo/server/DemoBank.java` (`bank-backend-sdk`) | builds the OIDC request (PKCE, `prompt=none`), redeems the code, then makes **one** CAMARA call with the token: NV v2 `verify` (phone or hashed) or `device-phone-number`, or SIM Swap v2 `check` / `retrieve-date` (§3.1) |
| Edge proxy simulator (lab) | `demo/server/EdgeSim.java` | stamps `X-Sas-Src-Ip/Port = 10.20.30.40:55555`, the post-CGNAT tuple |
| **CAMARA gateway** | `dist/` = sas-camara :8085 | OAuth AS (`/authorize`, `/token`) + `POST /number-verification/v2/verify` |
| **Entitlement server** | `dist-ecs/` = sas-ecs :8095 | subscriber exposure API (`ue-id`, `sim-change`, `monitoring-event`), the only path to the core |
| PCRF simulator | `sas-diameter-testapp` :3870 | Gx/Sd: binds `10.20.30.40` → `+251911111111` |
| HLR simulator | `sas-jss7-testapp` :2906 | MAP `sendIMSI` / `provideSubscriberInfo` / `sendAuthenticationInfo` (profile `full`) |
| HSS simulator | `sas-diameter-testapp` :3868 | S6a peer (profile `full`) |

```
Emulator (host = 10.0.2.2)                    host / test namespace
 app ── POST /nv/start ─────── Wi-Fi ───────►  demo-bank :8092
 SDK ── GET /authorize ─────── CELLULAR ────►  edge-sim :8084 ──► sas-camara :8085 ──ue-id──► sas-ecs :8095 ──Gx/Sd CCR──► PCRF sim
     ◄─ 302 redirect_uri?code&state (caught by the SDK, never loaded)
 app ── POST /nv/complete ──── Wi-Fi ───────►  demo-bank ──/token, /verify──► sas-camara ──sim-change, monitoring──► sas-ecs ──MAP──► HLR sim
     ◄─ {"verified": true|false}
```

The emulator NATs every interface to the host, so it cannot prove **who** the
subscriber is: edge-sim plays the operator edge proxy. It does prove **which network**
each request used. The emulator's capture covers its cellular LAN only (eth0,
`10.0.2.15`). `/authorize` must appear there, and the bank calls must not.

## 1. One-time setup

### 1.1 Android SDK, emulator, AVD

```bash
# command-line tools: https://developer.android.com/studio#command-line-tools-only
export ANDROID_HOME=$HOME/Android/Sdk
unzip commandlinetools-linux-*_latest.zip -d /tmp/clt && mkdir -p $ANDROID_HOME/cmdline-tools \
  && mv /tmp/clt/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
yes | sdkmanager --licenses
sdkmanager "platform-tools" "emulator" "system-images;android-36;google_apis;x86_64"
echo no | avdmanager create avd -n sa-demo -k "system-images;android-36;google_apis;x86_64" -d pixel_7
emulator -accel-check                      # expect: KVM ... is installed and usable
```

The AVD must be named `sa-demo`. On a Wayland desktop, start the emulator with
`QT_QPA_PLATFORM=xcb`, because its Qt has no Wayland plugin.

### 1.2 Server side and simulators

```bash
./scripts/package-dist.sh all                                     # dist/ + dist-ecs/ (docs/BUILD.md §2–3: upstream forks first)
(cd sdk/bank-backend && mvn -o package)
(cd sas-diameter-testapp && mvn -o package)
(cd sas-jss7-testapp && mvn -o package -DskipTests)
```

The APK is in the repository (`demo/android/apk/`). The app's source is not.

## 2. Automated run (recommended)

```bash
./scripts/android-demo-e2e.sh              # CORE=both: profile gx, then full
CORE=gx ./scripts/android-demo-e2e.sh      # PCRF simulator only; HLR/HSS in-memory in the ECS
CORE=full ./scripts/android-demo-e2e.sh    # + HLR (MAP/SCTP) and HSS (S6a) simulators
# SHOW_EMULATOR=1  show the window · FRESH_SNAPSHOT=1  cold boot, re-save the snapshot
# MAX_SECONDS=360  hard limit: the run stops and prints the results it has
```

What the script does:

- It runs in a private user and network namespace, so it needs **no root**. tcpdump
  works and no host port is touched.
- It boots the emulator headless from snapshot `sa-e2e-sw` while the servers start
  (the first run cold boots and saves the snapshot).
- It brings up each profile's simulators, sas-ecs, sas-camara, edge-sim and demo-bank.
- It drives the app through intent extras and reads the verdict from logcat:

```bash
adb shell am start -S -n et.restlink.demo/.MainActivity --es phone +251911111111 --ez autologin true
adb logcat -d -s SilentAuthDemo:I          # SilentAuthDemo: RESULT Logged in as +251911111111|...
```

Expected console output (2026-10-23):

```
   1s  emulator starting (snapshot boot)
  24s  [gx] servers up, Diameter peers up
  25s  [gx] warmed up through /authorize: access_denied code code
  31s  emulator ready: cellular up, app installed
  40s  [gx] A PASS
  47s  [gx] B PASS
  …    [gx] C PASS
  …    [gx] D PASS
  …    [gx] PASS  emulator.pcap: /authorize over cellular=3 (want 3), bank calls over cellular=0 (want 0)
  …    [full] A PASS … D PASS, PASS emulator.pcap …
```

Exit code 0 means every scenario and both capture checks passed. Output goes to
`target/android-demo-e2e/`: `timing.txt`, and per profile (`gx/`, `full/`) the files
`summary.txt`, `host-lo.pcap`, `emulator.pcap`, `scenario-A..D.png` and `logs/`.

### 2.1 Expected results

| | Scenario | Profile `gx` | Profile `full` | sas-camara log (`/verify`) |
|---|---|---|---|---|
| A | SIM number `+251911111111`, Wi-Fi and data on | **Logged in … (via the mobile network)** | Not verified | gx: `match=true assurance=HIGH`; full: `fallback=SIM_SWAP_SUSPECT` |
| B | another number `+251922222222` | Not verified | Not verified | `fallback=MSISDN_MISMATCH` |
| C | mobile data off, Wi-Fi on | No mobile data network → OTP | same | nothing: no `/authorize` was sent |
| D | PCRF simulator killed | The operator refused (`access_denied`) | same | `/authorize refused … (NO_BINDING)` |

**Why A differs in `full`.** `/verify` also checks how recently the SIM changed. Only the
in-memory HLR/HSS backends can date a SIM change (`EcsBootstrap.labSimChange`). With live
MAP/S6a there is no such source yet, so the ECS answers
`404 NO_EVIDENCE "no source could say when the SIM last changed"`. The verifier then
fails closed with `SIM_SWAP_SUSPECT`. That is the correct answer until a live SIM-change
source exists.

### 2.2 Reading the captures

```bash
D=target/android-demo-e2e/gx
cat $D/summary.txt                                        # everything below, already decoded
# Device side: which network carried what (cellular LAN only)
tshark -r $D/emulator.pcap -Y http.request -T fields -e ip.src -e http.request.method -e http.request.uri | cut -c1-60
# Gateway → entitlement server
tshark -r $D/host-lo.pcap -Y 'http.request and tcp.dstport==8095' -T fields -e http.request.uri
# Entitlement server → PCRF simulator (Gx/Sd on :3870 is not a well-known Diameter port)
tshark -r $D/host-lo.pcap -d sctp.port==3870,diameter -Y 'diameter.cmd.code==272' \
  -T fields -e diameter.flags.request -e diameter.Result-Code
# Entitlement server → HLR simulator (profile full)
tshark -r target/android-demo-e2e/full/host-lo.pcap -Y gsm_map
```

Expected:

- **`emulator.pcap`:** exactly three `GET /authorize` from `10.0.2.15` (A, B, D) and no
  `/nv/start` or `/nv/complete`. C adds nothing.
- **`host-lo.pcap`, gateway → entitlement server:** for each `/authorize`, one
  `POST /sas-subscriber-exposure/v1/ue-id/retrieve`. A 200 answer reads
  `{"gpsi":"msisdn-251911111111",…,"evidenceSource":"GX"}`. For each `/verify`, the
  gateway calls `ue-id/retrieve`, `sim-change/retrieve` and `monitoring-event/…/subscriptions`.
- **Gx/Sd:**
  - For A, B and D, a CCR/CCA (cmd 272) to the PCRF simulator with `Result-Code=2001`.
  - Tuples that aren't bound get `5030`.
  - When the simulator is killed, it sends DPR (cmd 282) before scenario D.
- **MAP (`full`):** for each `/verify`, `sendIMSI 251911111111`, `provideSubscriberInfo`
  and `sendAuthenticationInfo`, each one dialog with `returnResultLast`.

The pcaps contain the one-time lab codes. They are already spent and expire within
minutes, but don't publish the pcaps.

## 3. Manual run on the host (step by step)

Use this to watch the flow by hand. It needs host ports 8084, 8085, 8092, 8095, 3870 and
28086 to be free. A host capture of `lo` needs root (`sudo tcpdump`). The emulator
capture does not.

**T1: PCRF simulator** (Gx/Sd, binds `10.20.30.40` → `+251911111111`)

```bash
# its own working directory: the simulator writes stack state into it
mkdir -p target/pcrf && (cd target/pcrf && java -jar ../../sas-diameter-testapp/target/sas-diameter-testapp.jar \
  --diameter-port 3870 --web-port 28086 --client-host sas.restlink.et)
curl -s http://127.0.0.1:28086/api/binding          # → the 10.20.30.40 binding
```

**T2: entitlement server** (sas-ecs; resolver = Gx/Sd probe)

```bash
(cd dist-ecs && SAS_JAVA_OPTS="-Dsas.transport.resolver=sd -Dsas.transport.sd.peer-port=3870 -Dsas.transport.sd.stale-after-ms=5000" ./run.sh)
# wait for: DiameterLinkImpl - Peer is up for Association [name=sd-sas, …]
```

For profile `full`, also start the HLR simulator
(`java --add-modules jdk.sctp -jar sas-jss7-testapp/target/sas-jss7-testapp.jar --listen-port 2906 --peer-port 2907 --http-port 8087`)
and the HSS simulator (`java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar`).
Then add `SAS_TRANSPORT_AUTHVECTOR=map SAS_TRANSPORT_MAP=jss7 SAS_TRANSPORT_S6A=corsac SAS_BINDING_SOURCE_ORDER=map-smi SAS_TRANSPORT_JSS7_CONFIG=$PWD/configs/ss7-ecs.json`
to the sas-ecs command.

**T3: CAMARA gateway** (sas-camara)

```bash
CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["authorization_code"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","number-verification:device-phone-number:read","sim-swap:check","sim-swap:retrieve-date"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'
(cd dist && SAS_JAVA_OPTS="-Dsas.security.token-validation-enabled=true -Dsas.security.accept-hs256=false -Dsas.oauth.authorize.trust-src-header=true -Dsas.oauth.clients-json=$CLIENTS" ./run.sh)
curl -s http://127.0.0.1:8085/camara/health
```

**T4: edge-sim and demo-bank**

```bash
java demo/server/EdgeSim.java 8084 http://127.0.0.1:8085
java -cp sdk/bank-backend/target/bank-backend-sdk-0.1.0-SNAPSHOT.jar demo/server/DemoBank.java 8092
```

**T5: warm-up, then check the server chain without a device**

```bash
for i in 1 2 3; do
  U=$(curl -s -X POST http://127.0.0.1:8092/nv/start | sed -E 's/.*"authorizeUrl":"([^"]+)".*/\1/')
  curl -s -o /dev/null -w '%{redirect_url}\n' "${U/10.0.2.2/127.0.0.1}" | sed -E 's/(code|state)=[^&]+/\1=…/g'
done
# expect: the first one may be ...?error=access_denied (cold start), then https://bank.example/cb?code=…&state=…
```

**T6: emulator and app**

```bash
QT_QPA_PLATFORM=xcb emulator -avd sa-demo -gpu swiftshader_indirect &
adb wait-for-device; adb install -r demo/android/apk/silent-auth-demo-0.1.0-debug.apk
adb shell dumpsys connectivity | grep -E 'Active default network|ni\{(WIFI|MOBILE)'
# expect: both WIFI and MOBILE CONNECTED, and the active default network is the WIFI one
adb emu network capture start manual.pcap   # written to ~/.android/avd/sa-demo.avd/console_out/
```

**T7: scenarios.** Press **Login** in the app, or send the intent from §2:

| Step | Action | Screen | Server evidence |
|---|---|---|---|
| A | number `+251911111111`, Login | `Logged in as +251911111111 … (via the mobile network)` | edge-sim `GET /authorize -> 302`; demo-bank `devicePhoneNumberVerified=true` |
| B | number `+251922222222`, Login | `Not verified: the network could not confirm …` | camara `fallback=MSISDN_MISMATCH` |
| C | `adb shell svc data disable`, wait until Wi-Fi is the default network, Login | `No mobile data network.` | nothing at edge-sim |
| D | `adb shell svc data enable`, stop T1, wait over 5 s, Login | `The operator refused (access_denied).` | camara `/authorize refused … (NO_BINDING)` |

```bash
adb emu network capture stop
tshark -r ~/.android/avd/sa-demo.avd/console_out/manual.pcap -Y http.request -T fields -e ip.src -e http.request.uri | cut -c1-50
```

### 3.1 The other CAMARA operations: NV v2 and SIM Swap v2

The bank makes exactly **one** CAMARA call per login. sas-camara pairs an access token with a
single `x-correlator`, so a second call with the same token is answered
`401 UNAUTHENTICATED` (anti-replay). Select the operation for the next logins, then press
**Login** (or send the intent from §2):

```bash
curl -s -X POST http://127.0.0.1:8092/nv/mode -d '{"mode":"phone"}'                 # default
curl -s -X POST http://127.0.0.1:8092/nv/mode -d '{"mode":"hashed"}'
curl -s -X POST http://127.0.0.1:8092/nv/mode -d '{"mode":"device"}'
curl -s -X POST http://127.0.0.1:8092/nv/mode -d '{"mode":"simswap","maxAge":72}'
curl -s -X POST http://127.0.0.1:8092/nv/mode -d '{"mode":"simswap-date"}'
```

| Mode | Scope requested at `/authorize` | CAMARA call on the wire | Answer (lab, `+251911111111`) | App |
|---|---|---|---|---|
| `phone` | `number-verification:verify`, `…:device-phone-number:read` | `POST /camara/number-verification/v2/verify {"phoneNumber"}` | `{"devicePhoneNumberVerified":true}` | Logged in |
| `hashed` | same | `POST …/v2/verify {"hashedPhoneNumber": SHA-256(E.164)}` | `{"devicePhoneNumberVerified":true}` | Logged in |
| `device` | same | `GET …/v2/device-phone-number` (the bank compares it with the typed number) | `{"devicePhoneNumber":"+251911111111"}` | Logged in |
| `simswap`, `maxAge` 72 | `sim-swap:check` | `POST /camara/sim-swap/v2/check {"maxAge":72}` | `{"swapped":false}` | Logged in |
| `simswap`, `maxAge` 720 | `sim-swap:check` | `POST /camara/sim-swap/v2/check {"maxAge":720}` | `{"swapped":true}` (the lab SIM changed 10 days ago) | The operator refused (`sim_swapped_recently`) |
| `simswap-date` | `sim-swap:retrieve-date` | `POST /camara/sim-swap/v2/retrieve-date` | `{"latestSimChange":"<10 days ago>"}` | Logged in |

The SIM-change date comes from the in-memory HLR of the gx profile (`evidenceSource=LAB_HLR`).
On the full live core there is no SIM-change source yet (§2.1).

## 4. Screencast: Hyprland, terminals, Wireshark, Android emulator

`scripts/android-demo-screencast.sh` films the whole test on a Hyprland workspace: terminals
open, commands are typed and run line by line (packet capture first), the Android emulator
boots with the demo app, every login is followed live in Wireshark, and the clip ends on the
CAMARA API in Wireshark. English subtitles, intro/outro cards and chapters are added in post.

```bash
./scripts/android-demo-screencast.sh
# → target/android-demo-screencast/silent-auth-camara-v2-screencast.mp4            (subtitles burned in)
#   target/android-demo-screencast/silent-auth-camara-v2-screencast.srt
#   target/android-demo-screencast/run/camara.pcap, host-lo.pcap, device.pcap
./scripts/android-demo-screencast.sh _compose    # post production again, from the same recording
```

Needs Hyprland, `wf-recorder`, `kitty`, `wireshark`, `ffmpeg` with libass, ImageMagick,
`tshark`, `jq`, `nsenter`, plus everything in §1. Run `scripts/android-demo-e2e.sh` once
first: it saves the emulator snapshot `sa-e2e-sw`.

**What is filmed.**
- **Only the demo:** a headless Hyprland output (`SADEMO`), created for the run and removed
  afterwards. Only the demo windows go there, and `wf-recorder` films it below the shell's bar,
  so no other application can appear. Desktop notifications are muted while recording.
- **Your desktop stays usable:** focus and the cursor are given back each time a demo window
  opens.
- **No root:** every command runs inside one private user + network namespace.

| Chapter | On screen |
|---|---|
| 1 | Three kitty terminals open: `pcap · core`, `camara services`, `android test` |
| 2 | `mkfifo lo.fifo` · `tcpdump -i lo -U -w - … \| tee camara.pcap > lo.fifo &` · `wireshark -k -i lo.fifo -Y "$CAMARA_VIEW"`, and the Wireshark window opens |
| 3–6 | PCRF simulator, sas-ecs (`SCTP INIT`, Diameter CER/CEA in Wireshark), sas-camara, bank, edge proxy, warm-up |
| 7 | `emulator -avd sa-demo …`, and the emulator window opens with the demo app (`android_ready`) |
| 8–10 | A `verify(phoneNumber)`, A2 `verify(hashedPhoneNumber)`, A3 `device-phone-number` |
| 11–13 | SIM Swap `check` 72 h → `swapped:false`, `check` 720 h → `swapped:true`, `retrieve-date` |
| 14–16 | B another number, C mobile data off, D PCRF down |
| 17 | The phone's cellular capture: `/authorize` only |
| 18 | `wireshark -r camara.pcap -Y "$CAMARA_API" -g <frame>`: the window fills the screen, only the CAMARA API calls are shown, and the NV v2 answer `devicePhoneNumberVerified:true` is selected |

**No secret on screen.** Wireshark's columns come from a demo-only Lua post-dissector,
`scripts/video/camara-masked.lua`:
- **Path:** the request path without its query string;
- **Body:** JSON members and form fields, with `code`, `state`, `nonce`, `code_verifier`,
  `code_challenge`, `access_token`, `id_token`, `refresh_token` and `authorizeUrl`
  shown as `•••`;
- **Message:** the HTTP method or status, the Diameter command and result, or the SCTP setup.

The Info column is not shown. The **pcaps themselves contain the lab tokens and codes**
(plain HTTP): keep them local, never publish them.

## 5. Known behaviours and pitfalls

| Symptom | Cause | What the script does |
|---|---|---|
| The first `/authorize` after the servers start is refused: `access_denied (network binding lookup timed out)` | sas-camara gives the ue-id lookup 400 ms (`ue-id=400ms` in its ready line). The first lookup is cold (JIT + first CCR). | Warms up through the real `/authorize` path until it gets two codes in a row. **Product note:** in production, the first user after a restart falls back to OTP. |
| `full` A is not verified | no live SIM-change source (§2.1) | expects `Not verified` in `full` |
| Bank calls appear in `emulator.pcap`; C fails with `ConnectException` | after a snapshot restore, the emulator's Wi-Fi is enabled but not associated, so cellular is the default route | toggles Wi-Fi until it is the active default network, then checks the capture (`bank calls over cellular=0`) |
| No `CELLULAR` network inside a network namespace | glibc `AI_ADDRCONFIG` ignores loopback, so `::1` does not resolve and the modem simulator never connects | adds `dummy0` with an IPv4 and an IPv6 address |
| The emulator segfaults mid-run (`gfxstream … null ctx`) | host-GPU rendering | `-gpu swiftshader_indirect`, headless |
| D is answered from the cache | the ECS caches a Gx/Sd binding (`sd.stale-after-ms`, default 60 s) | sets it to 5 s and waits until the binding is stale |
| `adb emu network capture start /path/x.pcap` → `KO` | the console takes a bare file name only | writes to `console_out/`, then moves the file |
| Emulator window doesn't open on Wayland | no Qt Wayland plugin | `QT_QPA_PLATFORM=xcb` |
| A second CAMARA call with the same token → `401 UNAUTHENTICATED` | sas-camara pairs a token with one `x-correlator` (anti-replay); `bank-backend-sdk` sends a new one per call | one CAMARA operation per login (§3.1) |
| Wireshark rejects a `diameter.*` display filter (red bar), and Diameter is not decoded | a relocated `wireshark` binary looks for its data in `../share/wireshark` | the screencast links `share/wireshark` and `lib/wireshark` next to its copy |
| The masking Lua script does not load | Wireshark loads no user Lua when running as root | tcpdump captures as root and writes a fifo; Wireshark reads it as the desktop user (`unshare --map-user`) |
| `nsenter: setgroups failed` | a user namespace made by `unshare -r` denies `setgroups` | `nsenter --preserve-credentials` |
| `kitty @ … connect: invalid argument` | a unix socket path must stay under 108 bytes | sockets in `$XDG_RUNTIME_DIR/sademo/` |
