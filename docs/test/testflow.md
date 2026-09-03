# Silent Auth SAS — E2E Test Flow

Date: 2026-08-23 · Updated: 2026-09-03 (dist demo path §0b verified end-to-end;
bẫy `quarkus.config.locations` thắng `-D` sysprops; build profile flag, 3 testapp
instances incl. Gx, SIM-swap fail-closed on the corsac S6a leg; TS.43 = operator REST CAMARA NV + Sh UDR; SWx leg = operator AAA↔HSS)
Scope: web → `POST /verify` → SAS → `sas-diameter-testapp`

> CAMARA alignment: primary endpoints are now under `/number-verification/v2`
> (`POST …/verify`, `GET …/device-phone-number`). Legacy `/verify` and
> `/retrieve-phone-number` still work as deprecated lab aliases. Assurance detail
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
`swx.peer-port=3869`, entitlement HMAC secret).

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

**Step C7 — cleanup:**

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
| C3–C5 | CIBA money-loop (match) | resolver BOUND | `true`, `APPROVE`, score 100 |
| C5 | CIBA wrong claimed number | — | `false` |
| C6 | CIBA token reuse | — | `401` |
| — | LTE happy path on memory transport (optional) | pilot backends | `true`, score 100 |

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

## 7. Kiểm thử khác trong tree

```bash
/usr/bin/mvn -B clean test                           # từ repo root: 363 tests trên 3 module (JUnit 5, không cần mạng)
python3 harness/run_hardness.py          # 34/34 gates (H1–H24)
python3 harness/preflight_prod.py        # verdict for THIS env (exit = số check fail)
python3 harness/preflight_prod.py --selftest   # 22/22 kịch bản cấu hình sai bị bắt
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
