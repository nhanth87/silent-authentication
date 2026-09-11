# Silent Auth SAS — E2E Test Flow

Date: 2026-08-23 · Updated: 2026-09-11 (CAMARA `/camara` aliases, strict request bodies,
`x-correlator` validation/echo; GSMA mock ↔ local compare §0d; CAMARA ICM OAuth Phase 3 manual runbook §0e; dist demo path §0b verified end-to-end;
bẫy `quarkus.config.locations` thắng `-D` sysprops; build profile flag, 3 testapp
instances incl. Gx, SIM-swap fail-closed on the corsac S6a leg; TS.43 = operator REST CAMARA NV + Sh UDR; SWx leg = operator AAA↔HSS;
CDR bền cho SimSwap + OTP: `dist/logs/sas.cdr`, DB history và admin merge)
Scope: web → `POST /verify` → SAS → `sas-diameter-testapp`

> CAMARA alignment: primary endpoints are under `/number-verification/v2` and the
> `/camara` alias root (`POST /camara/number-verification/v2/verify`,
> `GET /camara/number-verification/v2/device-phone-number`). Legacy `/verify` and
> `/retrieve-phone-number` still work as deprecated lab aliases. Unknown CAMARA request
> properties are rejected as `400 INVALID_ARGUMENT`; a present `x-correlator` must match
> `^[a-zA-Z0-9-_:;./<>{}]{0,256}$` and is echoed. Assurance detail
> (score/factors) is OPT-IN via header `X-Sas-Assurance-Detail: true`.
> Spec snapshot + gap analysis: `docs/research/camara/`.

> **Hai track khác nhau (đừng nhập nhằng):** **S6a + Gx** là track *cellular*
> (4G/5G): SAS mở ULR/ULA tới HSS, resolver Gx tra binding; SIM-swap freshness =
> **Sh UDR/SNR** read-only. **SWx** là chân **operator** của track *TS.43 Wi-Fi*
> (3GPP AAA ↔ HSS, EAP-AKA) — SAS **không tự mở SWx trong production**; nó verify
> qua **operator REST (CAMARA NV / SIM Swap)**. Lab `swxverifier` RA + instance SWx
> :3869 chỉ đóng thế chân operator để chạy loop local.

```
curl/browser          SAS (Quarkus :8085)         sas-diameter-testapp (operator simulator)
     │                        │                        ├── instance 1: SCTP :3868 = S6a (ULR/ULA) — cellular
     │  POST /verify          │   Diameter (SCTP)      ├── instance 2: SCTP :3869 = SWx (MAR/MAA, SAR/SAA) — operator AAA↔HSS (TS.43 Wi-Fi)
     ├───────────────────────►│───────────────────────►├── instance 3: SCTP :3870 = Gx  (CCR-I binding) — resolver
     │  {verified, assurance?}│◄── ULA · MAA/SAA · CCA─┤
     │◄───────────────────────┤                        │  Control UI: /api/messages,
     │                        │                        │  /api/subscriber, /api/binding
```

## 0. Yêu cầu môi trường

- JDK zulu-25: `$HOME/.local/share/mise/installs/java/zulu-25`
- Build luôn dùng `/usr/bin/mvn` (PATH `mvn` là mise shim zulu-8 — KHÔNG dùng trực tiếp)
- Thiết lập một lần rồi `java`/`javac` đều là zulu-25:
  ```bash
  export JAVA_HOME="$HOME/.local/share/mise/installs/java/zulu-25"
  export PATH="$JAVA_HOME/bin:$PATH"
  /usr/bin/mvn -version   # phải in "Java version: 25.0.3"
  ```
- Không chạy **hai `mvn` cùng lúc** (race `~/.m2` gây lỗi compile giả) — build **tuần tự**.

## 0b. Demo manual từng bước (dist build sẵn — verified 2026-09-03 01:19)

Artifact đã build sẵn: `dist/` (SAS fast-jar) + `sas-diameter-testapp/target/sas-diameter-testapp.jar`.
`dist/configs/application.properties` **đã cấu hình sẵn** cho demo Diameter
(`s6a=swx=corsac`, `resolver=sd` :3870, `swx.peer-port=3869`, entitlement HMAC secret).
Chạy TUẦN TỰ từng bước, mỗi bước 1 lệnh + 1 kết quả kiểm tra.

> ⚠️ **Bẫy config (đã kiểm chứng):** `dist/run.sh` nạp `configs/application.properties`
> qua `quarkus.config.locations` — nguồn này **thắng cả `-D` system properties**.
> Đổi transport phải sửa **file config**, `SAS_JAVA_OPTS=-D…` KHÔNG có tác dụng.
> (Chạy `java -jar dist/quarkus-run.jar` trực tiếp thì `-D` hoạt động bình thường.)

**Bước 0 — vào worktree, kiểm tra JDK 25:**

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/silent-authentication/main
J=$HOME/.local/share/mise/installs/java/zulu-25/bin/java
$J -version
```
→ kỳ vọng: `openjdk version "25…"` (zulu).

**Bước 1 — simulator S6a (HSS, SCTP :3868, UI :8086):**

```bash
$J -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &
```
→ kỳ vọng log: `HSS Diameter listening on 127.0.0.1:3868` + `Control UI listening on http://127.0.0.1:8086/`

**Bước 2 — simulator SWx (3GPP AAA↔HSS, :3869, UI :18086):**

```bash
$J -jar sas-diameter-testapp/target/sas-diameter-testapp.jar --diameter-port 3869 --web-port 18086 &
```
→ kỳ vọng log: `listening on 127.0.0.1:3869` + UI `:18086`

**Bước 3 — simulator Gx (PCRF binding, :3870, UI :28086):**

```bash
$J -jar sas-diameter-testapp/target/sas-diameter-testapp.jar --diameter-port 3870 --web-port 28086 &
```
→ kỳ vọng log: `listening on 127.0.0.1:3870` + UI `:28086`

**Bước 4 — SAS từ dist** (config đã sẵn corsac + sd; tee log ra file để Bước 5 kiểm tra):

```bash
dist/run.sh 2>&1 | tee /tmp/sas-demo.log &
```
→ kỳ vọng trong log: RA wired, `S6a Diameter transport started`,
`PCRF Sd/Gx resolver dialing 127.0.0.1:3870`, `Listening on: http://0.0.0.0:8085`.

**Bước 5 — chờ đủ 3 Diameter peer:**

```bash
sleep 15; grep -c 'Peer is up' /tmp/sas-demo.log; grep 'Peer is up' /tmp/sas-demo.log | sed -E 's/.*name=([a-z0-9-]+).*/\1/'
```
→ kỳ vọng: count `3` và 3 tên `s6a-sas`, `swx-sas`, `sd-sas`. Nếu <3: lặp lại lệnh sau
mỗi 15 s — host multi-home có thể tới ~3 phút (sim chỉ nhận nguồn loopback, log sim
hiện `Received connect request from non provisioned … address` rồi retry). Chưa đủ 3
thì KHÔNG chạy tiếp.

**Bước 6 — ① verify LTE (CAMARA `/verify`):**

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: d1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `{"devicePhoneNumberVerified":false}` — **fail-closed ĐÚNG**: lab thiếu Sh UDR
nên chiều SIM-swap bị veto (`SIM_SWAP_SUSPECT`). Kể chuyện demo: CCR→CCA + ULR→ULA đều 2001,
SAS vẫn từ chối vì thiếu bằng chứng freshness — đó là thiết kế fail-closed.
(Muốn bước này ra `true`: đổi `sas.transport.s6a=memory` trong `dist/configs/application.properties`
rồi restart Bước 4.)

**Bước 7 — ①b assurance detail (opt-in):**

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: d2' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -H 'X-Sas-Assurance-Detail: true' -H 'X-Sas-Risk-Class: TRANSFER' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `"decision":"FALLBACK"`, `"score":70`, `"threshold":80`, `"fallbackReason":"SIM_SWAP_SUSPECT"`,
factors: `ipBindingFresh 1.0 · reachable 1.0 · notSimSwapped 0.0 · locationPlausible 1.0`.

**Bước 8 — discovery (trả số bound với IP):**

```bash
curl -s http://localhost:8085/number-verification/v2/device-phone-number \
  -H 'Authorization: Bearer demo' -H 'x-correlator: d3' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555'
```
→ kỳ vọng: `{"devicePhoneNumber":"+251911111111"}`

**Bước 9 — ② fail-closed: detach thuê bao:**

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":false}'
```
rồi chạy lại curl Bước 6 (đổi `x-correlator: d4`)
→ kỳ vọng verify: `{"devicePhoneNumberVerified":false}` (ULA 5421 — xem UI `:8086`).

**Bước 10 — ③ fail-closed: barring:**

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":true,"barred":true}'
```
rồi chạy lại curl Bước 6 (đổi `x-correlator: d5`)
→ kỳ vọng verify: `false` (ULA 2001 + Subscriber-Status ODB).

**Bước 11 — reset HSS về mặc định:**

```bash
curl -s -X POST http://127.0.0.1:8086/api/reset
```
→ kỳ vọng: `{"reset":true}`

**Bước 12 — ④ TS.43 Wi-Fi: cấp operator token:**

```bash
curl -s -X POST http://localhost:8085/entitlement/issue \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}'
```
→ kỳ vọng: `{"token":"eyJ…","expiresInSeconds":300}` — copy token vào biến:
`TOKEN="<dán token>"`

**Bước 13 — ④ verify bằng operator token (không cần cellular):**

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: d6' -d '{}'
```
→ kỳ vọng: `{"devicePhoneNumberVerified":true}` (SWx MAR→MAA + SAR→SAA trên :3869 — UI `:18086`).

**Bước 14 — ⑤ anti-replay: dùng lại token:**

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: d7' -d '{}'
```
→ kỳ vọng: `401` (token một lần dùng).

**Bước 15 — quan sát signalling (tùy chọn, mở browser):**
`http://127.0.0.1:8086/` (S6a) · `http://127.0.0.1:18086/` (SWx) · `http://127.0.0.1:28086/` (Gx)

---

**Money-loop CIBA (tùy chọn — chạy SAU khi đã dừng SAS ở Bước 4; simulator giữ nguyên):**

**Bước C1 — dừng SAS** (Ctrl-C terminal Bước 4, hoặc):

```bash
pkill -f quarkus-run.jar
```

**Bước C2 — SAS với token validation (chạy TRỰC TIẾP, không qua run.sh):**

```bash
$J -Dsas.security.token-validation-enabled=true -Dsas.security.hmac-secret=k1 \
   -Dsas.oauth.secret=k1 --add-modules jdk.sctp -jar dist/quarkus-run.jar 2>&1 | tee /tmp/sas-ciba.log &
sleep 8; grep 'Listening on' /tmp/sas-ciba.log
```
→ kỳ vọng: `Listening on: http://0.0.0.0:8085`

**Bước C3 — bank xin auth_req_id (cellular anchor IP:port):**

```bash
curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=number-verification:verify'
```
→ kỳ vọng: `{"auth_req_id":"…","expires_in":120}` — đặt `AUTH="<auth_req_id>"`

**Bước C4 — đổi token (CIBA grant):**

```bash
curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH"
```
→ kỳ vọng: `{"access_token":"eyJ…","expires_in":300,…}` — đặt `AT="<access_token>"`

> Nếu Bước C3 gửi `client_id=...`, Bước C4 phải gửi cùng `client_id`; `auth_req_id`
> được bind vào client. Với `private_key_jwt`, mỗi endpoint cần một client assertion
> riêng vì `jti` chỉ dùng một lần.

**Bước C5 — verify số khớp (token bound +251911111111):**

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $AT" \
  -H 'X-Sas-Amr: mobile' -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `true`, `"decision":"APPROVE"`, `"score":100`

**Bước C6 — dùng lại token (single-use):** chạy lại lệnh Bước C5
→ kỳ vọng: `401`

**Bước C7 — dọn dẹp:**

```bash
for p in $(pgrep -x java); do kill $p; done
pgrep -x java || echo "all JVMs stopped"
```

## 0c. Manual demo runbook — English version (verified 2026-09-03)

Same run as §0b, one command per step. Pre-built artifacts: `dist/` (SAS fast-jar) +
`sas-diameter-testapp/target/sas-diameter-testapp.jar`. `dist/configs/application.properties`
is **already configured** for the Diameter demo (`s6a=swx=corsac`, `resolver=sd` :3870,
`swx.peer-port=3869`, entitlement HMAC secret) plus the two CAMARA add-ons:
SimSwap evidence (in-memory MAP seed) and the OTP SMS lab sender
(`sas.otp.enabled=true`, `sas.otp.sms-delivery=log` — logs the SMS, sends nothing).

> ⚠️ **Config gotcha (verified):** `dist/run.sh` loads `configs/application.properties`
> via `quarkus.config.locations`, and that source **outranks `-D` system properties**.
> Change transports in the **config file** — `SAS_JAVA_OPTS=-D…` has NO effect through
> run.sh (running `java -jar dist/quarkus-run.jar` directly, `-D` works normally).

**Step 0 — enter the worktree, check JDK 25:**

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/silent-authentication/main
J=$HOME/.local/share/mise/installs/java/zulu-25/bin/java
$J -version
```
→ expect: `openjdk version "25…"` (zulu).

**Step 1 — S6a simulator (HSS, SCTP :3868, UI :8086):**

```bash
$J -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &
```
→ expect: `HSS Diameter listening on 127.0.0.1:3868` + `Control UI listening on http://127.0.0.1:8086/`

**Step 2 — SWx simulator (3GPP AAA↔HSS, :3869, UI :18086):**

```bash
$J -jar sas-diameter-testapp/target/sas-diameter-testapp.jar --diameter-port 3869 --web-port 18086 &
```
→ expect: `listening on 127.0.0.1:3869` + UI `:18086`

**Step 3 — Gx simulator (PCRF binding, :3870, UI :28086):**

```bash
$J -jar sas-diameter-testapp/target/sas-diameter-testapp.jar --diameter-port 3870 --web-port 28086 &
```
→ expect: `listening on 127.0.0.1:3870` + UI `:28086`

**Step 4 — SAS from dist** (config already set to corsac + sd; tee the log for Step 5):

```bash
dist/run.sh 2>&1 | tee /tmp/sas-demo.log &
```
→ expect: RAs wired, `S6a Diameter transport started`,
`PCRF Sd/Gx resolver dialing 127.0.0.1:3870`, `Listening on: http://0.0.0.0:8085`.

**Step 5 — wait for all 3 Diameter peers:**

```bash
sleep 15; grep -c 'Peer is up' /tmp/sas-demo.log; grep 'Peer is up' /tmp/sas-demo.log | sed -E 's/.*name=([a-z0-9-]+).*/\1/'
```
→ expect: count `3` and names `s6a-sas`, `swx-sas`, `sd-sas`. If <3: repeat every 15 s —
on a multi-homed host it can take up to ~3 minutes (sims accept loopback sources only;
sim logs show `Received connect request from non provisioned … address` until a retry
picks 127.0.0.1). Do NOT continue until all 3 peers are up.

**Step 6 — ① LTE verify (CAMARA `/verify`):**

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: d1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `{"devicePhoneNumberVerified":false}` — **correct fail-closed**: the lab HSS has
no Sh UDR handler, so the SIM-swap dimension is vetoed (`SIM_SWAP_SUSPECT`). Demo story:
CCR→CCA and ULR→ULA both returned 2001, yet SAS refuses because freshness evidence is
missing — that is fail-closed working as designed.
(For a `true` happy path instead: set `sas.transport.s6a=memory` in
`dist/configs/application.properties`, restart Step 4.)

**Step 7 — ①b assurance detail (opt-in):**

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: d2' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -H 'X-Sas-Assurance-Detail: true' -H 'X-Sas-Risk-Class: TRANSFER' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `"decision":"FALLBACK"`, `"score":70`, `"threshold":80`, `"fallbackReason":"SIM_SWAP_SUSPECT"`,
factors: `ipBindingFresh 1.0 · reachable 1.0 · notSimSwapped 0.0 · locationPlausible 1.0`.

**Step 8 — discovery (number bound to the bearer IP):**

```bash
curl -s http://localhost:8085/number-verification/v2/device-phone-number \
  -H 'Authorization: Bearer demo' -H 'x-correlator: d3' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555'
```
→ expect: `{"devicePhoneNumber":"+251911111111"}`

**Step 9 — ② fail-closed: detach the subscriber:**

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":false}'
```
then re-run the Step 6 curl (change to `x-correlator: d4`)
→ expect: `{"devicePhoneNumberVerified":false}` (ULA 5421 — see UI `:8086`).

**Step 10 — ③ fail-closed: barring:**

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":true,"barred":true}'
```
then re-run the Step 6 curl (change to `x-correlator: d5`)
→ expect: `false` (ULA 2001 + Subscriber-Status ODB).

**Step 11 — reset HSS to defaults:**

```bash
curl -s -X POST http://127.0.0.1:8086/api/reset
```
→ expect: `{"reset":true}`

**Step 12 — ④ TS.43 Wi-Fi: issue operator token:**

```bash
curl -s -X POST http://localhost:8085/entitlement/issue \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}'
```
→ expect: `{"token":"eyJ…","expiresInSeconds":300}` — copy into a variable: `TOKEN="<paste token>"`

**Step 13 — ④ verify with the operator token (no cellular needed):**

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: d6' -d '{}'
```
→ expect: `{"devicePhoneNumberVerified":true}` (SWx MAR→MAA + SAR→SAA on :3869 — UI `:18086`).

**Step 14 — ⑤ anti-replay: reuse the token:**

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: d7' -d '{}'
```
→ expect: `401` (single-use token).

**Step 15 — signalling observation (optional, open a browser):**
`http://127.0.0.1:8086/` (S6a) · `http://127.0.0.1:18086/` (SWx) · `http://127.0.0.1:28086/` (Gx)

**Step 16 — ⑥ CAMARA SimSwap v2.1.0 `/sim-swap/v2/check` (2-legged, on the Step 4 SAS):**

```bash
curl -s -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw1' -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `{"swapped":false}` — the pilot seed's last SIM change is 10 days old,
i.e. outside the default `maxAge=240` h window. Evidence is the same read-only
binding age the Verifier scores as `notSimSwapped` (MAP `lastUpdateLocation` →
Sh UDR → SWx); no Diameter exchange is needed for this call.

**Step 17 — same subscriber, wider window (`maxAge` is hours, spec range 1..2400):**

```bash
curl -s -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw2' -d '{"phoneNumber":"+251911111111","maxAge":2400}'
```
→ expect: `{"swapped":true}` (10 days ≤ 100 days).

**Step 18 — `/sim-swap/v2/retrieve-date`:**

```bash
curl -s -X POST http://localhost:8085/sim-swap/v2/retrieve-date \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw3' -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `{"latestSimChange":"<RFC 3339 instant, ~10 days ago>"}` (no
`monitoredPeriod` while the SAS has no configured monitoring window).

**Step 19 — fail-closed + CAMARA error codes (no evidence is never "not swapped"):**

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw4' -d '{"phoneNumber":"+251999999999"}'
```
→ expect: `404` `{"status":404,"code":"IDENTIFIER_NOT_FOUND",…}`

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw5' -d '{}'
```
→ expect: `422` `MISSING_IDENTIFIER` (2-legged call with no `phoneNumber`)

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw6' -d '{"phoneNumber":"+251911111111","maxAge":0}'
```
→ expect: `400` `OUT_OF_RANGE` (spec range is 1..2400 — never silently clamped)

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'x-correlator: sw7' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `401` `UNAUTHENTICATED` (no `Authorization` header)

**Step 20 — ⑦ CAMARA OneTimePasswordSMS v1.1.1 `/send-code` (the FALLBACK branch):**

```bash
curl -s -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp1' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
```
→ expect: `200` with body `{"authenticationId":"<uuid>"}` (a UUID is exactly the
36 chars the spec schema allows). The OTP itself is **never**
in the response — `message` is a template that must contain `{{code}}` (≤160 chars).
The SAS only orchestrates: in production the SMS goes out over the operator's own
SMSC/SGd route (Restlink does not wholesale SMS).

**Step 21 — read the OTP from the lab sender (log-only, nothing is sent):**

```bash
grep 'LAB-SMS' /tmp/sas-demo.log | tail -1
```
→ expect: `WARN LabLogSmsDelivery - [SAS][LAB-SMS — NOT SENT] to=+251****11 chars=28
text="921540 is your Restlink code"` — set `OTP="921540"`, `AID="<authenticationId>"`.
This cleartext OTP in the log is exactly why the prod profile ships the surface
**off** and preflight `PRO-29` refuses a lab sender.

**Step 22 — `/validate-code` wrong then right:**

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp2' -d "{\"authenticationId\":\"$AID\",\"code\":\"000000\"}"
```
→ expect: `400` `ONE_TIME_PASSWORD_SMS.INVALID_OTP`

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp3' -d "{\"authenticationId\":\"$AID\",\"code\":\"$OTP\"}"
```
→ expect: `204` (no body). Re-run it → `404 NOT_FOUND` (one OTP validates once).

**Step 23 — attempt burn: 3 wrong codes kill the attempt:**

```bash
for i in 1 2 3; do curl -s -o /dev/null -w '%{http_code} ' -X POST \
  http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H "x-correlator: burn$i" -d "{\"authenticationId\":\"$AID2\",\"code\":\"000000\"}"; done; echo
```
→ expect: `400 400 400` — the third is `ONE_TIME_PASSWORD_SMS.VERIFICATION_FAILED`,
and the **correct** code afterwards also answers `VERIFICATION_FAILED`.

**Step 24 — OTP fail-closed matrix:**

```bash
# rate limit: a 4th send to the SAME number inside sas.otp.rate-window-seconds
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' -H 'x-correlator: otp4' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
```
→ expect: `403` `ONE_TIME_PASSWORD_SMS.MAX_OTP_CODES_EXCEEDED` (another MSISDN is
unaffected; a restart clears the in-memory window)

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' -H 'x-correlator: otp5' \
  -d '{"phoneNumber":"+251933333333","message":"no code label here"}'
```
→ expect: `400` `INVALID_ARGUMENT` (template must carry `{{code}}`; >160 chars likewise).
No `Authorization` → `401`; unknown `authenticationId` → `404 NOT_FOUND`;
`sas.otp.enabled=false` (or the key missing from `dist/configs/application.properties`)
→ `404 NOT_FOUND` on both endpoints; no delivery route → `500 INTERNAL_ERROR` with
**no** `authenticationId` issued.

**Step 25 — ⑧ CDR/audit for the SimSwap + OTP calls:**

Each ⑥/⑦ request writes one API CDR row, keyed by `x-correlator`, to both the
durable `SAS_CDR` CSV file and the DB-backed admin ledger. With `dist/run.sh`,
the CSV is `dist/logs/sas.cdr`.

```bash
grep -E 'swcdr1|otpcdr1|otpcdr2' dist/logs/sas.cdr
```
→ expect:
```text
…,swcdr1,+251****11,SIMSWAP,COMPLETED,action=check maxAgeHours=240 swapped=false http=200,http,http,lab
…,otpcdr1,+251****11,OTP,COMPLETED,action=send-code messageChars=35 delivery=log result=SENT authenticationId=<uuid> ttlSeconds=300 http=200,http,http,lab
…,otpcdr2,+251****11,OTP,FAILED,action=validate-code authenticationId=<uuid> attempts=1 maxAttempts=3 result=INVALID http=400 code=ONE_TIME_PASSWORD_SMS.INVALID_OTP,http,http,lab
```

The same rows are visible at `http://localhost:8085/admin/cdr` (lab seed
`admin/admin`). The dashboard merges newly queued rows with persisted DB rows, so
history survives a restart.

Privacy invariants:

- MSISDN is masked (`+251****11`) before the CDR port sees it; raw MSISDN/IMSI are absent.
- OTP plaintext and SMS message text are absent; only `messageChars` and
  `authenticationId` are recorded.
- 2xx maps to `COMPLETED`; 4xx/5xx maps to `FAILED`, with `http=` and CAMARA `code=`.
- A blank `x-correlator` is replaced by a generated UUID, but repeated smoke runs
  should use fresh correlators because `sas_cdr_session.correlation_id` is unique.

---

**CIBA money-loop (optional — run AFTER stopping the Step 4 SAS; keep the simulators):**

**Step C1 — stop SAS** (Ctrl-C the Step 4 terminal, or):

```bash
pkill -f quarkus-run.jar
```

**Step C2 — SAS with token validation (run DIRECTLY, not via run.sh):**

```bash
$J -Dsas.security.token-validation-enabled=true -Dsas.security.hmac-secret=k1 \
   -Dsas.oauth.secret=k1 --add-modules jdk.sctp -jar dist/quarkus-run.jar 2>&1 | tee /tmp/sas-ciba.log &
sleep 8; grep 'Listening on' /tmp/sas-ciba.log
```
→ expect: `Listening on: http://0.0.0.0:8085`

**Step C3 — bank requests auth_req_id (cellular anchor IP:port):**

```bash
curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=number-verification:verify'
```
→ expect: `{"auth_req_id":"…","expires_in":120}` — set `AUTH="<auth_req_id>"`

**Step C4 — exchange for a token (CIBA grant):**

```bash
curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH"
```
→ expect: `{"access_token":"eyJ…","expires_in":300,…}` — set `AT="<access_token>"`

> If Step C3 sends `client_id=...`, Step C4 must send the same `client_id`; the
> `auth_req_id` is bound to that client. With `private_key_jwt`, each endpoint needs a
> separate client assertion because a `jti` is one-time use.

**Step C5 — verify the matching number (token is bound to +251911111111):**

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $AT" \
  -H 'X-Sas-Amr: mobile' -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `true`, `"decision":"APPROVE"`, `"score":100`

**Step C6 — reuse the token (single-use):** re-run the Step C5 command
→ expect: `401`

**Step C7 — SIM Swap on the 3-legged path (identity comes from the token, body empty):**

```bash
AUTH=$(curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=sim-swap:check' | python3 -c 'import sys,json;print(json.load(sys.stdin)["auth_req_id"])')
AT=$(curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $AT" \
  -H 'x-correlator: sw8' -d '{}'
```
→ expect: `{"swapped":false}` + `200`. Re-run the last curl → `401` (single-use).
With a bound token, sending `{"phoneNumber":"+251911111111"}` → `422
UNNECESSARY_IDENTIFIER`; a `number-verification:verify` token → `403
PERMISSION_DENIED` (wrong scope family); `scope=sim-swap:retrieve-date` works on
`/sim-swap/v2/retrieve-date` with an empty body.

**Step C8 — OTP SMS on the 3-legged path (one scope covers both operations):**

```bash
AUTH=$(curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=one-time-password-sms:send-validate' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["auth_req_id"])')
AT=$(curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $AT" -H 'x-correlator: otp6' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
```
→ expect: `200` + `authenticationId` when `phoneNumber` **equals** the token binding.
A different number → `403 PERMISSION_DENIED` ("phoneNumber does not match the
access-token binding") and no OTP is even composed; a `sim-swap:check` token →
`403 PERMISSION_DENIED` (wrong scope family); re-using the token → `401` (single-use,
so send-code and validate-code each need their own token).

**Step C9 — cleanup:**

```bash
for p in $(pgrep -x java); do kill $p; done
pgrep -x java || echo "all JVMs stopped"
```

### Expected results at a glance (English)

| # | Step | Signalling observed | Expected result |
|---|------|--------------------|-----------------|
| 6 | ① LTE verify (corsac S6a) | CCA 2001 + ULA 2001, no Sh UDR | `false` + `SIM_SWAP_SUSPECT` (correct fail-closed) |
| 7 | ①b assurance detail | same | `FALLBACK`, score 70 < threshold 80 |
| 8 | discovery | CCA 2001 | `{"devicePhoneNumber":"+251911111111"}` |
| 9 | ② subscriber detached | ULA **5421** | `false` |
| 10 | ③ subscriber barred | ULA 2001 + ODB status | `false` |
| 12–13 | ④ TS.43 token verify (SWx lab leg) | MAA items≥1 + SAA 2001 | `true` |
| 14 | ⑤ token replay | — (blocked before Diameter) | `401` |
| 16 | ⑥ SIM Swap `/check` (default `maxAge=240`) | — (read-only evidence) | `{"swapped":false}` |
| 17 | ⑥ SIM Swap `/check` `maxAge=2400` | — | `{"swapped":true}` |
| 18 | ⑥ SIM Swap `/retrieve-date` | — | `{"latestSimChange":"…Z"}` |
| 19 | ⑥ unknown · no `phoneNumber` · `maxAge=0` · no token | — | `404` · `422` · `400` · `401` |
| C3–C5 | CIBA money-loop (match) | resolver BOUND | `true`, `APPROVE`, score 100 |
| C5 | CIBA wrong claimed number | — | `false` |
| C6 | CIBA token reuse | — | `401` |
| C7 | SIM Swap 3-legged (empty body) | resolver BOUND | `{"swapped":false}`; reuse → `401`; `phoneNumber` in body → `422` |
| 20 | ⑦ OTP `/send-code` | — (no signalling; lab sender logs) | `200 {"authenticationId":"<uuid>"}` |
| 21 | ⑦ read the OTP from the lab log | — | `[SAS][LAB-SMS — NOT SENT] … text="<6 digits> …"` |
| 22 | ⑦ `/validate-code` wrong · right · replay | — | `400 INVALID_OTP` · `204` · `404` |
| 23 | ⑦ 3 wrong codes burn the attempt | — | `400 400 400`, third = `VERIFICATION_FAILED` |
| 24 | ⑦ 4th send to one number · bad template · no token | — | `403 MAX_OTP_CODES_EXCEEDED` · `400` · `401` |
| C8 | OTP 3-legged (bound number) | resolver BOUND | `200`; foreign number → `403`; wrong scope → `403`; reuse → `401` |
| ICM | `/token` CIBA with `scope` · replayed `auth_req_id` · wrong/expired/replayed client assertion | — (manual §0e) | `400 invalid_request` · `400 invalid_grant` · `401 invalid_client` |
| ICM | `auth_req_id` expiry · pending binding issued to another client | — (unit-tested) | `400 expired_token` · `400 invalid_grant` |
| ICM | `client_credentials` against NV `/verify` | — (manual §0e) | 2-legged `token_use=client`; `/verify` → `403 NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` |
| ICM | JWT bearer `tel:` · missing purpose · bad subject · form `scope` | — (manual §0e) | user-bound token · `400 invalid_scope` · `400 invalid_grant` · `400 invalid_request` |
| ICM | JWT bearer `operatortoken:` | — (unit-tested; manual run needs the §0b entitlement/SWx lab stack) | user-bound token |
| — | LTE happy path on memory transport (optional) | pilot backends | `true`, score 100 |

## 0d. GSMA Open Gateway smoke / mock ↔ local SAS (verified 2026-09-10)

Lab helpers:

- `scripts/gsma_mock_server.py` — mock Open Gateway on `127.0.0.1:18099`.
- `scripts/gsma_camara_smoke.py` — CAMARA smoke/compare for `local`, `gsma`, or `compare`.
- `scripts/gsma.env.template` — copy outside the repo and fill live sandbox values.

The smoke script adds `X-Sas-*` headers only for `--target local`; it never sends them to
GSMA. Reports mask phone numbers and tokens. Do not commit a filled env file or live report.

### Mock ↔ local compare (no live sandbox required)

```bash
MOCK_PORT=18099 python3 scripts/gsma_mock_server.py > /tmp/gsma_mock.log 2>&1 &
MOCK_PID=$!
./dist/run.sh > /tmp/sas.log 2>&1 &
SAS_PID=$!

curl -fsS http://127.0.0.1:18099/health
curl -fsS http://127.0.0.1:8085/camara/health

python3 scripts/gsma_camara_smoke.py --target compare \
  --gsma-api-root http://127.0.0.1:18099/camara \
  --token-url http://127.0.0.1:18099/oauth/token \
  --client-id mock-client --client-secret mock-secret \
  --gsma-test-number +251911111111 \
  --local-base http://127.0.0.1:8085 \
  --local-test-number +251911111111 \
  --tests simswap,cdr --timeout 20 \
  --report /tmp/gsma_local_compare.json --require-match

pkill -P "$SAS_PID" 2>/dev/null || true
kill "$SAS_PID" "$MOCK_PID" 2>/dev/null || true
```

Expected:

```text
[simswap-check] MATCH
[simswap-retrieve-date] MATCH
[cdr-privacy] OK
COMPARE_SUMMARY MATCH=2 OK=1
EXIT=0
```

`cdr-privacy` is local-only: it confirms the CDR/report masks the MSISDN and contains no
OTP plaintext.

### Local SAS only

```bash
python3 scripts/gsma_camara_smoke.py --target local \
  --local-base http://127.0.0.1:8085 \
  --tests nv,simswap,otp,cdr \
  --report /tmp/sas_local_smoke.json
```

Expected exit `0` for the lab dist. NV may return `devicePhoneNumberVerified:false` when
no binding evidence exists; OTP validation reads the lab log-only code.

### Live GSMA sandbox (blocked until browser values are supplied)

Cloudflare blocks curl/headless automation. Copy the template outside the repo and fill it
from a real browser session:

```bash
cp scripts/gsma.env.template /tmp/gsma.env
```

Required values: `GSMA_API_ROOT`, `GSMA_TOKEN_URL`, `GSMA_CLIENT_ID`,
`GSMA_CLIENT_SECRET` or preissued tokens, `GSMA_TEST_NUMBER`, and enabled scopes. NV
usually requires a user-bound 3-legged token (`GSMA_NV_TOKEN`, `GSMA_DISCOVERY_TOKEN`);
with a bound token the request body should be `{}`.

```bash
python3 scripts/gsma_camara_smoke.py --target gsma --env-file /tmp/gsma.env \
  --tests token,simswap,nv --timeout 20 --report /tmp/gsma_live.json
```

Do not run live OTP unless the sandbox explicitly confirms it will not send a real SMS;
the script refuses GSMA OTP without `--allow-send`.

## 0e. CAMARA ICM OAuth Phase 3 — manual runbook (verified 2026-09-11)

Chạy độc lập với §0b–§0d. Không cần Diameter simulator vì SAS dùng memory/pilot
transport. Phải chạy `java -jar dist/quarkus-run.jar` trực tiếp để `-D` system
properties thắng config; **không dùng `dist/run.sh`** cho phần này. Helper lab
`scripts/sas_oauth_jwt_tool.py` tạo RSA key, `clients.json`, và ký assertion RS256.

**P0 — dọn process cũ, đặt biến môi trường:**

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/silent-authentication/main
pkill -f "$PWD/dist/quarkus-run.jar" || true
export JAVA_HOME="$HOME/.local/share/mise/installs/java/zulu-25"
export PATH="$JAVA_HOME/bin:$PATH"
J="$JAVA_HOME/bin/java"
BASE=http://localhost:8085
CID=bank-backend
TYPE='urn:ietf:params:oauth:client-assertion-type:jwt-bearer'
decode() {
  python3 -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; p+="="*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p)))' "$1"
}
"$J" -version
```
→ kỳ vọng: `openjdk version "25…"` (zulu).

**P1 — regression tự động:**

```bash
/usr/bin/mvn -o -B test -Dquarkus.profile=lab
python3 harness/run_hardness.py
python3 harness/run_hardness.py --mutations
python3 harness/preflight_prod.py --selftest
```
→ kỳ vọng: `528` tests pass · `34/34 gates` · `10` mutations caught · `23/23` preflight
scenarios detected.

**P2 — build/package dist:**

```bash
/usr/bin/mvn -o -B clean package -DskipTests -Dquarkus.profile=lab
./scripts/package-dist.sh
```
→ kỳ vọng: có `dist/quarkus-run.jar`.

**P3 — tạo lab client key + registry:**

```bash
rm -rf /tmp/sas-oauth-test
python3 scripts/sas_oauth_jwt_tool.py keygen --client-id "$CID" --out /tmp/sas-oauth-test
KEY=/tmp/sas-oauth-test/client.key
```
→ kỳ vọng: in `private_key=...client.key`, `clients_json=...clients.json`, `kid=...`.

**P4 — start SAS với `private_key_jwt` bắt buộc:**

```bash
export SAS_OAUTH_CLIENTS_JSON="$(cat /tmp/sas-oauth-test/clients.json)"
nohup "$J" \
  -Dsas.security.token-validation-enabled=true \
  -Dsas.security.hmac-secret=k1 \
  -Dsas.oauth.secret=k1 \
  -Dsas.oauth.public-base-url="$BASE" \
  -Dsas.oauth.require-client-auth=true \
  -Dsas.oauth.clients-json="$SAS_OAUTH_CLIENTS_JSON" \
  --add-modules jdk.sctp \
  -jar dist/quarkus-run.jar >/tmp/sas-oauth.log 2>&1 &
echo $! >/tmp/sas-oauth.pid
for i in {1..60}; do curl -fs "$BASE/camara/health" >/dev/null && break; sleep 1; done
curl -s "$BASE/camara/health"
```
→ kỳ vọng: `{"status":"UP"}`.

**P5 — discovery metadata:**

```bash
curl -s "$BASE/.well-known/oauth-authorization-server" | python3 -m json.tool
```
→ kỳ vọng: `token_endpoint_auth_methods_supported` chứa `private_key_jwt`;
`grant_types_supported` chứa `urn:openid:params:grant-type:ciba`, `client_credentials`,
`urn:ietf:params:oauth:grant-type:jwt-bearer`.

**P6 — CIBA + `private_key_jwt` happy path:**

```bash
BCA=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/bc-authorize")
AUTH=$(curl -s -X POST "$BASE/bc-authorize" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$BCA" \
  -d 'scope=number-verification:verify' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["auth_req_id"])')
echo "$AUTH"
```
→ kỳ vọng: in một `auth_req_id` không rỗng.

```bash
TCA=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/token")
AT=$(curl -s -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$TCA" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
decode "$AT"
```
→ kỳ vọng: payload có `token_use: user` và `phone_number: +251911111111`.

```bash
curl -s -X POST "$BASE/number-verification/v2/verify" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $AT" \
  -H 'X-Sas-Amr: mobile' -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `"devicePhoneNumberVerified":true`, `"decision":"APPROVE"`, `"score":100`.

**P7 — CIBA access token single-use:** chạy lại đúng lệnh verify ở P6
→ kỳ vọng: `401`, `UNAUTHENTICATED`, `token already used (single-use)`.

**P8 — `auth_req_id` replay:**

```bash
TCA2=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/token")
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$TCA2"
```
→ kỳ vọng: `400`, `invalid_grant`, `auth_req_id is invalid or was already exchanged`.

**P9 — `client_credentials` 2-legged:**

```bash
CCA=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/token")
TOK_C=$(curl -s -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=number-verification:verify' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$CCA" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
decode "$TOK_C"
```
→ kỳ vọng: `token_use: client`, `client_id: bank-backend`, không có `phone_number`.

```bash
curl -s -w '\n%{http_code}\n' -X POST "$BASE/number-verification/v2/verify" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK_C" \
  -H 'X-Sas-Amr: mobile' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `403`, `NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK`,
message nói token không có user phone-number binding.

**P10 — JWT bearer, subject `tel:`:**

```bash
JBA=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" \
  --aud "$BASE/token" --sub 'tel:+251911111111' \
  --scope 'dpv:FraudPreventionAndDetection number-verification:verify')
TOK_J=$(curl -s -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$JBA" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
decode "$TOK_J"
```
→ kỳ vọng: `token_use: user`, `phone_number: +251911111111`.

```bash
curl -s -X POST "$BASE/number-verification/v2/verify" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK_J" \
  -H 'X-Sas-Amr: mobile' -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `true`, `APPROVE`, `score: 100`.

**P11 — JWT bearer negatives:**

```bash
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$JBA" \
  --data-urlencode 'scope=number-verification:verify'
```
→ kỳ vọng: `400`, `invalid_request`, scope không được gửi ở JWT bearer token request.

```bash
JBA_NO_PURPOSE=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" \
  --aud "$BASE/token" --sub 'tel:+251911111111' --scope 'number-verification:verify')
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$JBA_NO_PURPOSE"
```
→ kỳ vọng: `400`, `invalid_scope`, `a dpv purpose scope is required`.

```bash
JBA_BAD_SUB=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" \
  --aud "$BASE/token" --sub 'user@example.com' \
  --scope 'dpv:FraudPreventionAndDetection number-verification:verify')
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$JBA_BAD_SUB"
```
→ kỳ vọng: `400`, `invalid_grant`, `sub must be tel:<E.164> or operatortoken:<token>`.
Path `operatortoken:` cần entitlement/SWx lab stack ở §0b nên ở runbook này chỉ
unit-test; JWT bearer `tel:` là manual step.

**P12 — client-authentication negatives:**

```bash
curl -s -w '\n%{http_code}\n' -X POST "$BASE/bc-authorize" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d "client_id=$CID" -d 'scope=number-verification:verify'
```
→ kỳ vọng: `401`, `invalid_client`, `client authentication is required`.

```bash
curl -s -w '\n%{http_code}\n' -X POST "$BASE/bc-authorize" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$BCA" \
  -d 'scope=number-verification:verify'
```
→ kỳ vọng: `401`, `invalid_client`, `JWT assertion jti has already been used`.

```bash
BAD_CLIENT=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id wrong-client --aud "$BASE/token")
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=number-verification:verify' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$BAD_CLIENT"
```
→ kỳ vọng: `401`, `invalid_client`, `unknown client: wrong-client`.

```bash
EXPIRED=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/token" --ttl -5)
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=number-verification:verify' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$EXPIRED"
```
→ kỳ vọng: `401`, `invalid_client`, `JWT assertion has expired`.

```bash
WRONG_AUD=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/bc-authorize")
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=number-verification:verify' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$WRONG_AUD"
```
→ kỳ vọng: `401`, `invalid_client`, `JWT assertion audience is invalid`.

**P13 — cleanup:**

```bash
kill "$(cat /tmp/sas-oauth.pid 2>/dev/null)" 2>/dev/null || true
pkill -f "$PWD/dist/quarkus-run.jar" || true
pgrep -f "$PWD/dist/quarkus-run.jar" || echo 'SAS stopped'
```

## 1. Build

```bash
(cd sas-diameter-testapp && /usr/bin/mvn -B clean package)      # testapp jar ~26 MB
/usr/bin/mvn -B clean package -DskipTests -Dquarkus.profile=lab  # root: sas-api + sas-entitlement + sas-host → quarkus-app
```

> **BẮT BUỘC `-Dquarkus.profile=lab`** cho build sas-host: một số property cố định
> từ build-time (vd `quarkus.datasource.db-kind`) được bake theo profile. Build
> không có cờ này tạo fast-jar **im lặng không boot** (dừng ngay sau
> `MicroSleeContainer started`, log chỉ còn các dòng `Failed to load config value…`).
> `scripts/package-dist.sh` luôn build với cờ này (prod artifact: `-Dquarkus.profile=prod`).

## 2. Chạy 3 instance operator simulator (S6a + SWx + Gx)

```bash
# Instance 1: S6a HSS (Diameter SCTP :3868) + control UI :8086 — cellular (ULR/ULA)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &

# Instance 2: SWx = operator 3GPP AAA ↔ HSS (SCTP :3869) + control UI :18086 — TS.43 Wi-Fi leg
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3869 --web-port 18086 &

# Instance 3: PCRF Gx (SCTP :3870) + control UI :28086 — resolver binding (bắt buộc
# khi chạy SAS với -Dsas.transport.resolver=sd ở §3; thiếu nó scenario ① trả NO_BINDING)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3870 --web-port 28086 &
```

> Lab HSS chỉ nhận **1 inbound SCTP association mỗi listen port** → SWx buộc phải
> tách port riêng; SAS trỏ qua `-Dsas.transport.diameter.swx.peer-port=3869`.
> (Lab chỉ: `swxverifier` RA đóng thế chân operator. Production: SAS verify TS.43
> qua operator REST CAMARA NV / SIM Swap + Sh UDR/SNR, không mở SWx.)

## 3. Chạy SAS với corsac transports

```bash
cd sas-host && java \
  -Dsas.transport.s6a=corsac \
  -Dsas.transport.swx=corsac \
  -Dsas.entitlement.hmac-secret=e2e-lab-secret \
  -Dsas.transport.diameter.swx.peer-port=3869 \
  -Dsas.transport.resolver=sd \
  -Dsas.transport.sd.peer-port=3870 \
  -jar target/quarkus-app/quarkus-run.jar &
```

Đợi log `Peer is up for Association [name=s6a-sas…]`, `[name=swx-sas…]` — thường
~15–20 s, nhưng trên host multi-home có thể tới ~3 phút: simulator chỉ nhận nguồn
loopback và từ chối các lần bắt tay từ IP LAN/IPv6 (log sim: `Received connect request
from non provisioned … address. Closing Channel`) cho khi client retry chọn 127.0.0.1.
`sas.transport.resolver=sd` bật PCRF Gx binding probe (:3870 — cần instance 3 ở §2);
bỏ dòng này nếu muốn resolver memory pilot. Các lựa chọn resolver: `memory | cgnat | radius | sd`.

## 4. Scenarios

### ① S6a LTE happy path

Resolver seed sẵn `10.20.30.40 → +251911111111` (IMSI `655010000000001`) — với
`resolver=sd` binding này nằm trong Gx BindingRegistry của instance :3870
(`POST http://127.0.0.1:28086/api/binding`). Registry key theo **IP, upsert thay
thế** (một binding/IP): POST lại cùng IP với MSISDN khác sẽ **thay** seed — verify
sau đó resolve sang số mới và thường `false` (lab verifier không biết thuê bao đó).
`AMBIGUOUS_BINDING` trên sd chỉ xảy ra nếu CCA mang ≥2 E.164 phân biệt (lab testapp
không tạo được trường hợp này; gặp ở CGNAT/RADIUS backend khi trùng IP:port:ts).

> ⚠️ **Kỳ vọng thực tế với `s6a=corsac`**: `CorsacS6aVerifierBackend` fail-close chiều
> SIM-swap (lab testapp **không** có Sh UDR handler → freshness không có nguồn) ⇒
> scenario ①/①b trả `false` / `SIM_SWAP_SUSPECT` dù CCR→CCA và ULR→ULA đều 2001 —
> đó là fail-closed hoạt động đúng (không phải bug). Muốn ①/①b `true`: chạy SAS
> không kèm `-Dsas.transport.s6a=corsac` (memory transport pilot).

CAMARA primary path:

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: t1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
# s6a=corsac → {"devicePhoneNumberVerified":false}   (CAMARA-pure boolean)
#              (CCR→CCA 2001 + ULR→ULA 2001, nhưng Sh UDR binding freshness thiếu → veto)
# memory transport (không -Dsas.transport.s6a) → {"devicePhoneNumberVerified":true}
```

Opt-in assurance detail (score/factors cho bank tự đánh giá rủi ro) + risk class:

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: t1b' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -H 'X-Sas-Assurance-Detail: true' -H 'X-Sas-Risk-Class: TRANSFER' \
  -d '{"phoneNumber":"+251911111111"}'
# s6a=corsac → {"devicePhoneNumberVerified":false,"reqId":"…","decision":"FALLBACK",
#     "assurance":{"score":70,"level":"FALLBACK","threshold":80,"riskClass":"TRANSFER",
#       "factors":{"ipBindingFresh":{"value":1.0,…},"reachable":{"value":1.0,…},
#         "notSimSwapped":{"value":0.0,…},"locationPlausible":{"value":1.0,…}}},
#     "fallbackReason":"SIM_SWAP_SUSPECT"}   — CDR vẫn lưu full flow (dùng reqId tra)
# memory transport → decision APPROVE, score 100 — threshold theo risk class
```

Discovery (trả số bound với token; resolver-only, không cần Verifier):

```bash
curl -s http://localhost:8085/number-verification/v2/device-phone-number \
  -H 'Authorization: Bearer demo' -H 'x-correlator: t1c' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555'
# → {"devicePhoneNumber":"+251911111111"}
```

### ② Fail-closed — detach thuê bao

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":false}'
# verify lại như ① → ULA trả 5421 → {"devicePhoneNumberVerified":false}
```

### ③ Fail-closed — thuê bao bị barring (ULR/ULA)

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":true,"barred":true}'
# verify lại → ULA 2001 + Subscriber-Status=ODB → {"devicePhoneNumberVerified":false}
```

Reset về mặc định: `curl -X POST http://127.0.0.1:8086/api/reset`

### ④ TS.43 Wi-Fi path (token ký HMAC) — SWx là chân operator (lab)

> Lab: `swxverifier` RA mở MAR/SAR tới SWx :3869 để đóng thế chân 3GPP AAA↔HSS.
> Production: SAS **không** mở SWx — verify qua **operator REST (CAMARA NV / SIM
> Swap)** và freshness qua **Sh UDR/SNR** read-only.

```bash
TOKEN=$(curl -s -X POST http://localhost:8085/entitlement/issue \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")

curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: t4' -d '{}'
# → true   (MAR→MAA items=1, SAR→SAA trên :3869)
```

### ⑤ Token single-use

Dùng lại `$TOKEN` của bước ④ → `401` (consumed-jti).

### ⑥ SIM Swap — CAMARA SimSwap v2.1.0 (`/sim-swap/v2`)

Cùng evidence read-only mà Verifier chấm `notSimSwapped` (MAP `lastUpdateLocation`
→ Sh UDR → SWx), phơi ra thành API CAMARA riêng cho bank. Không AIR/AIA (không đốt
SQN của AuC), không IDR/IDA, không ATI liên mạng (FS.11). **Fail-closed:** không có
evidence ⇒ `404 IDENTIFIER_NOT_FOUND`, không bao giờ trả `swapped:false`.

```bash
# 2-legged (lab: token validation tắt) — số trong body
curl -s -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw1' -d '{"phoneNumber":"+251911111111"}'
# → {"swapped":false}      (seed đổi SIM 10 ngày trước, ngoài cửa sổ mặc định maxAge=240h)

curl -s -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw2' -d '{"phoneNumber":"+251911111111","maxAge":2400}'
# → {"swapped":true}       (maxAge tính theo GIỜ, spec 1..2400)

curl -s -X POST http://localhost:8085/sim-swap/v2/retrieve-date \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw3' -d '{"phoneNumber":"+251911111111"}'
# → {"latestSimChange":"2026-08-30T…Z"}   (RFC 3339; không có monitoredPeriod)

# Error codes theo CAMARA commonalities
#   số lạ / không có evidence → 404 IDENTIFIER_NOT_FOUND
#   thiếu phoneNumber (2-legged) → 422 MISSING_IDENTIFIER
#   maxAge=0 hoặc 2401          → 400 OUT_OF_RANGE (không tự clamp)
#   không có Authorization      → 401 UNAUTHENTICATED
```

3-legged (SAS chạy validation như §4b): identity lấy từ binding của access token,
body để `{}`; scope `sim-swap:check` / `sim-swap:retrieve-date` (family `sim-swap`
cấp cả hai). Gửi kèm `phoneNumber` khi token đã bound → `422 UNNECESSARY_IDENTIFIER`;
token khác family → `403 PERMISSION_DENIED`; dùng lại token → `401`.
Chi tiết từng lệnh: §0c Step C7 (EN).

### ⑦ OTP SMS — CAMARA OneTimePasswordSMS v1.1.1 (`/one-time-password-sms/v1`)

**Nhánh FALLBACK**: khi `/verify` trả FALLBACK thì bank rơi về OTP qua SMS. SAS chỉ
**orchestrate** (sinh OTP, giữ state, validate) — SMS thật do SMSC operator gửi
(Restlink không bán SMS wholesale). Lab: `sas.otp.sms-delivery=log` → tin nhắn
(**kèm OTP**) được ghi ra log, **không gửi đi đâu cả**. Prod: surface này TẮT
(`sas.otp.enabled=false`), preflight `PRO-29` từ chối sender lab.

```bash
# 1) send-code — message là TEMPLATE, bắt buộc chứa {{code}}, ≤160 ký tự
curl -s -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp1' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
# → 200 {"authenticationId":"<uuid 36 ký tự>"}   (OTP KHÔNG bao giờ trả về body)

# 2) đọc OTP từ log lab (chỉ lab — đây là lý do prod không được dùng sender log)
grep 'LAB-SMS' /tmp/sas-otp.log | tail -1
# → WARN LabLogSmsDelivery - [SAS][LAB-SMS — NOT SENT] to=+251****11 chars=28 text="921540 is your Restlink code"

# 3) validate-code sai → 400 ONE_TIME_PASSWORD_SMS.INVALID_OTP
#    sai tới lần thứ 3 (sas.otp.max-attempts) → 400 …VERIFICATION_FAILED (đốt attempt,
#    sau đó nhập ĐÚNG code vẫn VERIFICATION_FAILED)
#    đúng code → 204 (không body); dùng lại authenticationId → 404 NOT_FOUND
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp2' -d '{"authenticationId":"<uuid>","code":"921540"}'
# → 204
```

Các nhánh fail-closed đã verify: thiếu `{{code}}` / message >160 → `400
INVALID_ARGUMENT`; gửi quá `sas.otp.max-codes-per-number` (3) cho MỘT số trong
`rate-window` → `403 …MAX_OTP_CODES_EXCEEDED` (số khác không bị); operator từ chối
(`NOT_ALLOWED`/`BLOCKED` từ delivery seam) → `403 …PHONE_NUMBER_*`; không có
delivery route → `500 INTERNAL_ERROR` và **không** cấp `authenticationId`;
`sas.otp.enabled=false` (hoặc thiếu key) → `404 NOT_FOUND` cho cả 2 endpoint.

3-legged: scope duy nhất `one-time-password-sms:send-validate` cho cả hai endpoint;
`phoneNumber` phải **trùng** binding của token, khác → `403 PERMISSION_DENIED`
(chưa kịp sinh OTP). Lệnh đầy đủ: §0c Step C8 (EN).

### ⑧ CDR/audit cho SimSwap + OTP

Mỗi request ⑥/⑦ ghi **một** dòng CDR API theo `x-correlator` vào file CSV bền
`dist/logs/sas.cdr` (log4j logger `SAS_CDR`) và vào ledger DB hiển thị ở
`/admin/cdr`. `operation` là `SIMSWAP` hoặc `OTP`; 2xx → `COMPLETED`, 4xx/5xx →
`FAILED` kèm `http=` và `code=` trong `detail`.

```bash
grep -E 'sw1|otp1|otp2' dist/logs/sas.cdr
```

Dashboard merge hàng vừa enqueue với hàng đã persist, nên restart vẫn thấy lịch sử.
Quy tắc privacy đã smoke: MSISDN mask trước khi qua CDR port; **không** ghi OTP
plaintext, nội dung SMS, raw MSISDN/IMSI. OTP chỉ ghi `authenticationId`,
`messageChars`, delivery/result; SimSwap ghi `action`, `maxAgeHours`, `swapped`,
`evidence=ABSENT` khi fail-closed.

## 4b. Money-loop — operator Auth Server (CAMARA CIBA)

Đây là **lớp sản phẩm kiếm tiền**: SAS cấp token user-bound (bind số điện thoại vào
token) rồi bank mới gọi `/verify` so sánh. Chạy SAS với validation bật (thêm các cờ
transport corsac như §3 nếu muốn cùng chạy Diameter; CIBA memory transport hoạt động
độc lập):

```bash
java -Dsas.security.token-validation-enabled=true \
     -Dsas.security.hmac-secret=k1 -Dsas.oauth.secret=k1 \
     -jar sas-host/target/quarkus-app/quarkus-run.jar &
```

```bash
# 1) Bank xin auth_req_id (cellular anchor: resolver tra IP:port -> MSISDN)
curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=number-verification:verify'
# → {"auth_req_id":"…","expires_in":120}

# 2) Đổi token (CIBA grant; một auth_req_id = MỘT token)
curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH"
# → {"access_token":"<JWS HS256, phone_number=+E164>","expires_in":300,…}

# 3) /verify so sánh claimed vs số BOUND trong token
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H "Authorization: Bearer $AT" -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'   # match → true + assurance
#    phoneNumber khác bound → false ; dùng lại token → 401 single-use
```

Wi-Fi track: thay X-Sas-Src-Ip/Port bằng `login_hint=operatortoken:<tk>`
(TS.43 entitlement token — track riêng ngoài CAMARA surface).

## 5. Quan sát signalling

```bash
curl -s http://127.0.0.1:8086/api/messages     # S6a messages
curl -s http://127.0.0.1:18086/api/messages    # SWx (operator AAA↔HSS) messages — TS.43 leg
curl -s http://127.0.0.1:28086/api/messages    # Gx (PCRF) messages — resolver binding
curl -s http://127.0.0.1:28086/api/binding     # Gx binding registry (10.20.30.40 → +251911111111)
```

Hoặc mở browser `http://127.0.0.1:8086/` — bảng tin tự refresh 2 s: thời điểm,
command, session-id, result-code, AVP chính (`user=… rat=EUTRAN`, `vectors=N`…).

## 6. Ma trận kỳ vọng

| Scenario | Signalling | Kết quả |
|---|---|---|
| LTE happy (resolver=sd, s6a=corsac) | CCA 2001 + ULA 2001, Sh UDR thiếu | `false` + `SIM_SWAP_SUSPECT` (fail-closed đúng) |
| LTE happy (memory transport) | pilot resolver/verifier | `true` |
| Detached | ULA **5421** | `false` |
| Barred | ULA 2001 + Subscriber-Status ODB | `false` |
| Gx unknown IP | CCA 5030 | `false` (NO_BINDING) |
| TS.43 token hợp lệ (lab: SWx MAR/SAR) | MAA items≥1 + SAA 2001 (lab) | `true` (production: CAMARA NV REST) |
| Token replay | — (chặn trước Diameter) | `401` |
| amr sai/thiếu | — | `403` |
| Body thiếu phoneNumber/hashed | — | `400 INVALID_ARGUMENT` |
| Assurance detail không opt-in | — | response thuần boolean (CAMARA-pure) |
| SIM Swap `/check` (seed 10 ngày, `maxAge` mặc định 240h) | — (đọc evidence, không Diameter) | `{"swapped":false}` |
| SIM Swap `/check` `maxAge=2400` | — | `{"swapped":true}` |
| SIM Swap `/retrieve-date` | — | `{"latestSimChange":"…Z"}` |
| SIM Swap số lạ / thiếu evidence | — | `404 IDENTIFIER_NOT_FOUND` (fail-closed) |
| SIM Swap 3-legged + `phoneNumber` trong body | — | `422 UNNECESSARY_IDENTIFIER` |
| OTP SMS `/send-code` (lab sender `log`) | — (không signalling) | `200 {"authenticationId":…}`, OTP chỉ nằm trong log |
| OTP SMS `/validate-code` đúng code | — | `204` (dùng lại → `404`) |
| OTP SMS sai code 3 lần | — | `INVALID_OTP`, `INVALID_OTP`, `VERIFICATION_FAILED` |
| OTP SMS quá 3 code/số/giờ | — | `403 MAX_OTP_CODES_EXCEEDED` |
| OTP SMS 3-legged sai số bound | — | `403 PERMISSION_DENIED` (không sinh OTP) |
| `/token` CIBA có `scope` · replay `auth_req_id` · client assertion sai/hết hạn/dùng lại | — (manual §0e) | `400 invalid_request` · `400 invalid_grant` · `401 invalid_client` |
| `auth_req_id` hết hạn · pending binding cấp cho client khác | — (unit-tested) | `400 expired_token` · `400 invalid_grant` |
| `client_credentials` gọi NV `/verify` | — (manual §0e) | token 2-legged `token_use=client`; `/verify` → `403 NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` |
| JWT bearer `tel:` · thiếu purpose · `sub` lạ · `scope` ở form | — (manual §0e) | token user-bound · `400 invalid_scope` · `400 invalid_grant` · `400 invalid_request` |
| JWT bearer `operatortoken:` | — (unit-tested; muốn chạy manual cần entitlement/SWx lab stack ở §0b) | token user-bound |

## 7. Kiểm thử khác trong tree

```bash
mvn -o -B test -Dquarkus.profile=lab                  # từ repo root: 528 tests trên 3 module (JUnit 5, không cần mạng)
python3 harness/run_hardness.py          # 34/34 gates (H1–H24)
python3 harness/preflight_prod.py        # verdict for THIS env (exit = số check fail)
python3 harness/preflight_prod.py --selftest   # 23/23 kịch bản cấu hình sai bị bắt
```

## 8. Lỗi thường gặp

| Triệu chứng | Nguyên nhân | Fix |
|---|---|---|
| `UnsupportedClassVersionError` khi chạy jar | `java` mặc định là zulu-8 (shim) | dùng đường dẫn zulu-25 đầy đủ |
| SAS không boot, im lặng sau `MicroSleeContainer started`, log toàn `Failed to load config value…` | build thiếu `-Dquarkus.profile=lab` (property build-time bake theo profile) | build lại: `mvn -o -B clean package -DskipTests -Dquarkus.profile=lab` |
| Chạy `dist/run.sh` với `SAS_JAVA_OPTS=-Dsas.transport…` mà transport vẫn memory | `quarkus.config.locations` của run.sh thắng `-D` sysprops | sửa `dist/configs/application.properties` thay vì truyền `-D` (hoặc chạy `java -jar dist/quarkus-run.jar` trực tiếp) |
| `Peer is up` rất chậm (tới ~3 phút), sim log `Received connect request from non provisioned … address` | host multi-home: SCTP client thử nguồn LAN/IPv6, sim chỉ nhận loopback | đợi retry chọn 127.0.0.1; không cần can thiệp |
| Verify luôn `false`, HSS không nhận gì | SCTP chưa "Peer is up" | đợi thêm; kiểm tra 3 association |
| Verify `false` + `SIM_SWAP_SUSPECT` dù CCA/ULA đều 2001 | `s6a=corsac` fail-closed chiều SIM-swap (lab không có Sh UDR handler) | đúng thiết kế; muốn happy path `true` dùng memory transport (bỏ `-Dsas.transport.s6a=corsac`) |
| Discovery `403 USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` | binding resolve sang MSISDN mà verifier lab không biết (vd sau khi POST `/api/binding` thay seed) | `POST http://127.0.0.1:28086/api/reset` để re-seed |
| Scenario ① `NO_BINDING` | thiếu Gx instance :3870 (resolver=sd) | chạy instance 3 ở §2 |
| SWx timeout dù HSS thấy MAR/MAA | 2 link cùng origin-host vào 1 port | tách SWx sang port riêng (`swx.peer-port`) |
| `/sim-swap/v2/*` trả `404 IDENTIFIER_NOT_FOUND` cho số có trong seed | không còn nguồn binding-age nào được wire: `sas.transport.map=jss7` **và** `s6a`/`swx`=corsac (Sh UDR/SNR thật chưa có — open item) | đúng thiết kế fail-closed; muốn demo `swapped` thật thì để MAP transport = memory |
| `/sim-swap/v2/check` trả `403 PERMISSION_DENIED` dù token hợp lệ | token thiếu scope family `sim-swap` (vd chỉ có `number-verification:verify`) | xin lại token với `scope=sim-swap:check` (hoặc `sim-swap:retrieve-date`) ở `/bc-authorize` |
| `/one-time-password-sms/v1/*` trả `404 NOT_FOUND` cho mọi request | `sas.otp.enabled` thiếu/`false` trong config đang nạp (dist: `dist/configs/application.properties`) | thêm block `sas.otp.*` (enabled=true, sms-delivery=log) rồi restart — prod cố tình TẮT (`PRO-29`) |
| Không thấy OTP để validate | sender lab ghi OTP vào **log**, không trả về body | `grep 'LAB-SMS' <log>` — file log do lệnh chạy SAS quyết định (`/tmp/sas-demo.log`, `dist/logs/…`) |
| `403 MAX_OTP_CODES_EXCEEDED` dù mới gửi 1 OTP | cửa sổ rate limit in-memory còn giữ lượt gửi trước đó | restart SAS (window reset) hoặc đổi số khác |
| `500 INTERNAL_ERROR` khi send-code | `sas.otp.sms-delivery` không phải `log` mà cũng chưa có adapter operator thật | lab: đặt `log`; prod: giữ surface OFF tới khi có adapter SMSC/SGd (TS 29.338) |
| Không thấy `dist/logs/sas.cdr` | chạy jar trực tiếp mà không set `-Dsas.log.dir`; appender fallback về `target/logs/sas.cdr` | dùng `dist/run.sh`, hoặc kiểm tra `target/logs/sas.cdr` |
| Không thấy dòng CDR trên console | logger `SAS_CDR` được route `additivity=false` vào file CSV, không phải console | đọc `dist/logs/sas.cdr` hoặc `/admin/cdr` |
| `/admin/cdr` thiếu lịch sử cũ | `sas.cdr.db.enabled=false`, DB lab bị xóa, hoặc process chưa persist xong | bật DB CDR, giữ `dist/data/`, chờ flusher ghi; file CSV vẫn là bản durable |
| Log `[cdr-db] persist failed ... Unique index` | dùng lại cùng `x-correlator` trong DB CDR (`correlation_id` UNIQUE) | đổi `x-correlator` mới cho mỗi lần gọi, hoặc reset lab DB khi smoke lại |
