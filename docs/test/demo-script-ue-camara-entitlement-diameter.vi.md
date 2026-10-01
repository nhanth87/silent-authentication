# Demo Script — UE SDK → CAMARA `/verify` → Entitlement (TS.43) → S6a (cellular) + SWx (lab) (Tiếng Việt)

> **Kiến trúc tách theo tầng (từ M3).** sas-camara không còn chạy transport signalling nào:
> các cấu hình S6a/SWx/Gx và resolver bên dưới giờ thuộc **sas-ecs**, và operator token được
> redeem tại sas-ecs. Các bước đã verify cho binary hiện tại nằm ở
> [`testflow.vi.md` §00](testflow.vi.md); những mục bên dưới khởi động sas-camara với
> `-Dsas.transport.*` là trước M3 và chưa được chạy lại.

> 🇬🇧 English version: [`demo-script-ue-camara-entitlement-diameter.en.md`](demo-script-ue-camara-entitlement-diameter.en.md)

> Kịch bản demo đầu cuối cho Restlink Silent Auth (Ethiopia): từ **UE SDK** thu
> session-tuple, qua northbound **CAMARA Number Verification**, qua track **TS.43
> entitlement** (Wi-Fi), xuống tận lớp **Diameter** bằng `sas-diameter-testapp`.
>
> Từ **2026-10-01** SAS là **hai binary**: **sas-camara** (`sas-host`, :8085 — CAMARA +
> OAuth AS) và **sas-ecs** (`sas-ecs-host`, :8095 — entitlement server). Operator token
> do sas-ecs cấp (JWE) được sas-camara đổi **đúng một lần**. Hai track khác nhau:
> **S6a + Gx** = cellular (freshness = **Sh UDR/SNR**); **TS.43 Wi-Fi** = entitlement
> (sas-ecs: EAP-AKA qua MAP/SWx; lab SWx :3869 chỉ đóng thế chân operator cho verifier
> của sas-camara).
>
> Bổ trợ: `docs/design/sas-two-binary-architecture.md` (kiến trúc hai binary),
> `docs/design/silent-auth-standard-flow.md` (chuẩn hóa flow) và
> `docs/test/testflow.vi.md` (ma trận E2E đầy đủ, NV Authorization Code, MAP + pcap).

- Ngày: 2026-09-01 · cập nhật hai binary: 2026-10-01
- Giả lập: `sas-diameter-testapp` (HSS + 3GPP AAA + PCRF·Gx giả lập, SCTP).

---

## Tổng thể

```
UE SDK (device)                    sas-camara :8085                  sas-diameter-testapp (operator simulator)
  │  POST /session-tuple             │  RESOLVING→VERIFYING→SCORING    ├─ inst1 S6a :3868 (ULR/ULA) — cellular
  │  X-Sas-Access-Tech: LTE          │                                 ├─ inst2 SWx :3869 (MAR/SAR) — lab, chân operator
  │─────────────────────────────────►│──── Diameter (SCTP) ───────────►├─ inst3 Gx  :3870 (CCR-I binding) — resolver
  │  POST /number-verification/v2/verify                              │  UI: /api/messages,
  │  {phoneNumber}                   │◄── ULA · MAA/SAA · CCA ────────┤      /api/subscriber, /api/binding
  │◄──────── {devicePhoneNumberVerified}
  │
  │  (Wi-Fi) EAP-AKA ─► sas-ecs :8095 ── operator token (JWE) ─► app ─► bank ─► sas-camara (đổi 1 lần)
```

Mục tiêu demo:

1. UE SDK khai báo bearer cellular (`accessTech`) trên `/session-tuple` → sas-camara từ
   chối tuple Wi-Fi (`400 ACCESS_TECH_NOT_CELLULAR`).
2. CAMARA `/verify` + `/device-phone-number` trên cellular → Diameter **S6a** + Resolver **Gx**.
3. TS.43 entitlement trên Wi-Fi → **sas-ecs** `/entitlement/issue` → sas-camara `/verify`
   với `operatortoken:` → (lab) RA `swxverifier` mở **SWx** thay chân operator.
4. Quan sát signalling thời gian thực + ma trận fail-closed.

---

## 0. Môi trường

- JDK zulu-25: `$HOME/.local/share/mise/installs/java/zulu-25`.
- Build luôn dùng `/usr/bin/mvn` (PATH `mvn` là mise shim zulu-8 — KHÔNG dùng trực tiếp).
- Thiết lập một lần rồi `java`/`javac` đều là zulu-25:
  ```bash
  export JAVA_HOME="$HOME/.local/share/mise/installs/java/zulu-25"
  export PATH="$JAVA_HOME/bin:$PATH"   # sau dòng này `java`, `javac` = zulu-25
  /usr/bin/mvn -version                # phải in "Java version: 25.0.3"
  ```
- Không chạy **hai `mvn` cùng lúc** (race `~/.m2` gây lỗi compile giả kiểu
  `illegal start of expression` trên source hợp lệ) — build **tuần tự**.

## 1. Build

```bash
# Build TUẦN TỰ (không song song). Chạy từ thư mục gốc worktree.

# 1) testapp HSS/AAA/PCRF giả lập → target/sas-diameter-testapp.jar (~26 MB)
(cd sas-diameter-testapp && /usr/bin/mvn -B clean package)

# 2) root reactor (7 module) → hai fast-jar:
#      sas-host/target/quarkus-app/quarkus-run.jar      (sas-camara)
#      sas-ecs-host/target/quarkus-app/quarkus-run.jar  (sas-ecs)
#    BẮT BUỘC -Dquarkus.profile=lab: thiếu cờ này fast-jar im lặng không boot
#    (dừng sau `MicroSleeContainer started`, log toàn `Failed to load config value…`)
/usr/bin/mvn -B clean package -DskipTests -Dquarkus.profile=lab
```

## 2. Chạy testapp + sas-camara (corsac transports) + sas-ecs

```bash
# Instance 1: S6a HSS (SCTP :3868) + control UI :8086
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &

# Instance 2: SWx = operator 3GPP AAA↔HSS (SCTP :3869) + control UI :18086 — lab
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3869 --web-port 18086 &

# Instance 3: PCRF Gx (SCTP :3870) + control UI :28086  (dùng khi resolver=sd)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3870 --web-port 28086 &
```

> Lab HSS chỉ nhận **1 inbound SCTP association mỗi listen port** → S6a/SWx/Gx phải tách
> port riêng; sas-camara trỏ qua `-Dsas.transport.diameter.swx.peer-port=3869` và
> `-Dsas.transport.sd.peer-port=3870`.

```bash
# sas-camara (:8085)
(cd sas-host && java \
  -Dsas.transport.s6a=corsac \
  -Dsas.transport.swx=corsac \
  -Dsas.transport.diameter.swx.peer-port=3869 \
  -Dsas.transport.resolver=sd \
  -Dsas.transport.sd.peer-port=3870 \
  -jar target/quarkus-app/quarkus-run.jar &)

# sas-ecs (:8095) — entitlement server, cho kịch bản C
(cd sas-ecs-host && java -Dquarkus.profile=lab --add-modules jdk.sctp \
  -jar target/quarkus-app/quarkus-run.jar &)
```

Đợi log `Peer is up for Association [name=s6a-sas…]`, `[name=swx-sas…]` — thường
~15–20 s, nhưng trên host multi-home có thể tới ~3 phút (sim chỉ nhận nguồn loopback, log
sim: `Received connect request from non provisioned … address` cho tới khi retry chọn
127.0.0.1). `resolver=sd` bật PCRF Gx binding probe (:3870); bỏ dòng này nếu muốn resolver
memory. Lựa chọn resolver: `memory | cgnat | radius | sd`.

## 3. Kịch bản A — UE SDK cellular + CAMARA verify (S6a + Gx)

Resolver `sd` seed sẵn binding `10.20.30.40:55555 → +251911111111` (IMSI
`655010000000001`) trong Gx BindingRegistry của instance :3870 (`POST /api/binding` để
đổi). UE SDK trên thiết bị khai báo bearer + tuple qua `/session-tuple`.

> Kịch bản A và B nằm hoàn toàn trong sas-camara; code path của chúng không đổi khi tách
> binary (verify gần nhất trên dist: 2026-09-03).

### 3.1 UE SDK khai báo tuple (advisory, cellular-only)

```bash
# JVM UE SDK: SessionTupleClient.post(...) → body như dưới
curl -s -X POST http://localhost:8085/session-tuple \
  -H 'Content-Type: application/json' \
  -d '{"srcIp":"10.20.30.40","srcPort":55555,"ts":1724200000000,
       "msisdn":"+251911111111","accessTech":"LTE"}'
# → {"registered":true}   (resolver=sd: chỉ ack + forward operator resolver;
#     binding chuẩn nằm ở Gx BindingRegistry, không phải do tuple này seed)
```

Từ chối Wi-Fi (gate H22 — không cho tuple Wi-Fi seed cellular binding):

```bash
curl -s -X POST http://localhost:8085/session-tuple \
  -H 'Content-Type: application/json' \
  -d '{"srcIp":"192.168.1.9","srcPort":0,"ts":1724200000000,"accessTech":"WIFI"}'
# → 400 {"code":"ACCESS_TECH_NOT_CELLULAR", ...}
```

> `accessTech` do thiết bị khai báo là **advisory**: được dùng để *loại trừ* tuple xấu và
> tương quan CDR, không bao giờ để nâng assurance. Assurance tới từ cái mà chính mạng
> operator attest (IP:port:ts qua PGW/Gx/CGNAT).
>
> Với `resolver=memory`, chính `/session-tuple` seed `InMemoryResolverBackend` (không cần
> instance Gx); binding lúc đó do UE SDK trực tiếp tạo.

### 3.2 CAMARA `/verify` (happy path)

> ⚠️ **Kỳ vọng thực tế với `s6a=corsac`**: lab testapp **không** có Sh UDR handler ⇒
> `CorsacS6aVerifierBackend` fail-closed chiều SIM-swap ⇒ `/verify` trả `false` +
> `SIM_SWAP_SUSPECT` dù Gx CCA và ULA đều 2001 — đó là fail-closed hoạt động đúng. Muốn demo
> `true`: chạy sas-camara không kèm `-Dsas.transport.s6a=corsac` (memory pilot).

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: a1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
# s6a=corsac → {"devicePhoneNumberVerified":false}
#   Diameter: Gx CCR→CCA 2001 (resolver), S6a ULR→ULA 2001; thiếu freshness Sh UDR → veto
# memory transport → {"devicePhoneNumberVerified":true}
```

Opt-in assurance detail (mở rộng ngoài CAMARA, không phải mặc định):

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: a2' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -H 'X-Sas-Assurance-Detail: true' -H 'X-Sas-Risk-Class: TRANSFER' \
  -d '{"phoneNumber":"+251911111111"}'
# s6a=corsac → {"devicePhoneNumberVerified":false,"reqId":"…","decision":"FALLBACK",
#     "assurance":{"score":70,"level":"FALLBACK","threshold":80,"riskClass":"TRANSFER",
#       "factors":{"ipBindingFresh":{"value":1.0,…},"reachable":{"value":1.0,…},
#         "notSimSwapped":{"value":0.0,…},"locationPlausible":{"value":1.0,…}}},
#     "fallbackReason":"SIM_SWAP_SUSPECT"}   — CDR lưu full flow
# memory transport → decision APPROVE, score 100 — threshold theo risk class
```

### 3.3 Discovery `/device-phone-number`

```bash
curl -s http://localhost:8085/number-verification/v2/device-phone-number \
  -H 'Authorization: Bearer demo' -H 'x-correlator: a3' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555'
# → {"devicePhoneNumber":"+251911111111"}
```

---

## 4. Kịch bản B — Fail-closed (S6a + Gx)

```bash
# Detach thuê bao → ULA 5421 → false
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":false}'
# verify lại như 3.2 → {"devicePhoneNumberVerified":false}

# Barring thuê bao → ULA Subscriber-Status=ODB → false
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":true,"barred":true}'
# verify lại → false

# IP không có binding trong Gx → CCA 5030 → false (NO_BINDING)
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: b1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.99.99.99' -H 'X-Sas-Src-Port: 55555' \
  -d '{"phoneNumber":"+251911111111"}'
# → {"devicePhoneNumberVerified":false}

# Reset về mặc định
curl -s -X POST http://127.0.0.1:8086/api/reset
```

## 5. Kịch bản C — TS.43 entitlement trên Wi-Fi (sas-ecs cấp, sas-camara đổi)

Track **không cần cellular**: thiết bị xác thực EAP-AKA với entitlement server, nhận
operator token, backend đổi token ở sas-camara. **Lab**: RA `swxverifier` của sas-camara
mở MAR/SAR tới SWx :3869 đóng thế chân operator. **Production**: định danh đến từ operator
token (EAP-AKA đã chạy ở entitlement server), freshness qua **Sh UDR/SNR**, không mở SWx.

> Verify lại 2026-10-01 trên hai binary với SWx **memory** (5.1–5.4 bên dưới). Với SWx
> **corsac** tới :3869 **chưa chạy lại** sau khi tách (token mới không mang IMSI).
> EAP-AKA thật qua MAP (SAI + SendIMSI) trên sas-ecs: `docs/test/testflow.vi.md` §0f.

### 5.1 sas-ecs cấp operator token (thay cho EAP-AKA thật, trong lab gọi trực tiếp)

```bash
TOKEN=$(curl -s -X POST http://localhost:8095/entitlement/issue \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
awk -F. '{print NF}' <<<"$TOKEN"
# → 5  (JWE: ES256 JWS lồng trong ECDH-ES/A256GCM, mã hóa tới khóa enc của sas-camara)
```

> `/entitlement/issue` trên thực tế do **3GPP AAA** gọi sau khi EAP-AKA thành công (hoặc
> chính `/ts43` của sas-ecs); trong lab ta gọi trực tiếp. Token mang **MSISDN** (mã hóa)
> nhưng **không mang IMSI**: sas-camara không cần và không bao giờ biết IMSI. Vì vậy
> verifier SWx lab của sas-camara chạy theo MSISDN.

### 5.2 `/verify` với operator token (AccessTech = WIFI)

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: c1' -d '{}'
# → {"devicePhoneNumberVerified":true}
#   sas-camara: giải mã JWE, tải khóa ký của sas-ecs từ /jwks.json (cache 300 s),
#   verify ES256/aud/exp/typ, ghi jti vào ledger sas_operator_token_jti
```

Biến thể header `X-Sas-Operator-Token` (chỉ khi `sas.operator-token.header-enabled=true`;
prod luôn tắt — PRO-32):

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "X-Sas-Operator-Token: $TOKEN2" \
  -H 'x-correlator: c2' -d '{}'
# → {"devicePhoneNumberVerified":true}   (TOKEN2 = một token mới từ 5.1)
```

### 5.3 Token single-use (anti-replay)

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: c3' -d '{}'
# → 401 {"code":"UNAUTHENTICATED","message":"operator token is invalid, expired or already used"}
```

### 5.4 Đối soát định danh — `/entitlement/exchange` (legacy, chỉ lab)

```bash
curl -s -X POST http://localhost:8095/entitlement/exchange \
  -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\"}"
# lab → 200 {"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA","valid":true}
#        lần 2 → 401 INVALID_TOKEN
```

> ⚠️ Kết quả trên cho thấy **vì sao prod tắt endpoint này**: redeem cục bộ trên sas-ecs là
> một nơi tiêu token **thứ hai**, độc lập với ledger của sas-camara, nên token đã dùng ở
> 5.3 vẫn đổi được ở đây. Prod đặt `sas.entitlement.legacy-exchange-enabled=false`
> (preflight PRO-22) → `404`; sas-camara là nơi đổi duy nhất.

### 5.5 NV Authorization Code + PKCE (luồng NV chuẩn CAMARA)

Device gọi `/authorize` của sas-camara qua bearer cellular → resolver `IP:port:ts →
MSISDN` → `code` một lần → bank đổi `code` + `code_verifier` lấy access token ES256
(`amr=["mno"]`) + ID token pairwise. Lệnh + kỳ vọng: `docs/test/testflow.vi.md` §0a A8–A9.

## 6. Kịch bản D (tùy chọn) — CIBA money-loop

sas-camara là operator Auth Server: cấp token user-bound (số điện thoại gắn vào token),
bank gọi `/verify` so sánh. Chạy với validation bật:

```bash
java -Dsas.security.token-validation-enabled=true \
     -Dsas.security.hmac-secret=k1 -Dsas.oauth.secret=k1 \
     -jar sas-host/target/quarkus-app/quarkus-run.jar &
```

```bash
# 1) Bank xin auth_req_id (cellular anchor: resolver tra IP:port -> MSISDN)
AUTH=$(curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=number-verification:verify' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['auth_req_id'])")

# 2) Đổi token (CIBA grant; một auth_req_id = MỘT token) — access token ES256 typ=at+jwt
AT=$(curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")

# 3) /verify so sánh claimed vs số BOUND trong token
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H "Authorization: Bearer $AT" -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'   # khớp → true + assurance
#    phoneNumber khác số bound → false ; dùng lại token → 401 single-use
```

Track Wi-Fi cho CIBA: thay `X-Sas-Src-Ip/Port` bằng `login_hint=operatortoken:<tk>` với token
do sas-ecs cấp (đã verify 2026-10-01: `testflow.vi.md` §0a A7).

## 7. Quan sát signalling

```bash
curl -s http://127.0.0.1:8086/api/messages    # S6a  (ULR/ULA; freshness = Sh UDR)
curl -s http://127.0.0.1:18086/api/messages   # SWx  (MAR/SAR) — lab, chân operator AAA↔HSS
curl -s http://127.0.0.1:28086/api/messages   # Gx   (CCR)
curl -s http://127.0.0.1:28086/api/binding    # Gx binding registry
```

Hoặc mở browser `http://127.0.0.1:8086/` — bảng tin tự refresh 2 s: thời điểm, command,
session-id, result-code, AVP chính (`user=… rat=EUTRAN`, `vectors=N`…).

Pcap không cần root: `scripts/split-smoke.sh` (HTTP giữa hai binary) và
`scripts/ecs-map-smoke.sh` (SCTP/M3UA/TCAP/MAP của sas-ecs) — cách đọc: `testflow.vi.md` §0a.1, §0f.3.

---

## 8. Ma trận kỳ vọng

| Kịch bản | Signalling | Kết quả |
|----------|-----------|---------|
| A — LTE happy (resolver=sd, s6a=corsac) | Gx CCA 2001 + ULA 2001, thiếu Sh UDR | `false` + `SIM_SWAP_SUSPECT` (fail-closed đúng) |
| A — LTE happy (memory transport) | pilot resolver/verifier | `true` |
| A — discovery | Gx CCA 2001 + ULA 2001 | `{"devicePhoneNumber":"+2519…"}` |
| A — tuple `accessTech: WIFI` | — (chặn trước Diameter) | `400 ACCESS_TECH_NOT_CELLULAR` |
| B — Detached | ULA **5421** | `false` |
| B — Barred | ULA 2001 + Subscriber-Status ODB | `false` |
| B — Gx unknown IP | CCA 5030 | `false` (NO_BINDING) |
| C — operator token hợp lệ (sas-ecs cấp) | lab SWx: MAA items≥1 + SAA 2001 | `true` |
| C — header `X-Sas-Operator-Token` (bật `header-enabled`) | như trên | `true` |
| C — token replay | — (chặn trước Diameter, ledger) | `401` |
| C — `/entitlement/exchange` sau khi đã đổi ở CAMARA | — | lab `200 valid:true` (lý do prod tắt); prod `404` |
| C — NV Authorization Code + PKCE | resolver BOUND | ES256 access token + ID token pairwise → `/verify` `true` |
| A/B — amr sai/thiếu | — | `403` |
| A — body thiếu `phoneNumber`/`hashedPhoneNumber` | — | `400 INVALID_ARGUMENT` |
| A — assurance không opt-in | — | response thuần boolean (CAMARA-pure) |

---

## 9. Lỗi thường gặp

| Triệu chứng | Nguyên nhân | Fix |
|-------------|-------------|-----|
| `UnsupportedClassVersionError` khi chạy jar | `java` mặc định là zulu-8 (shim) | dùng đường dẫn zulu-25 đầy đủ |
| Binary không boot, im lặng sau `MicroSleeContainer started`, log toàn `Failed to load config value…` | build thiếu `-Dquarkus.profile=lab` | build lại với `-Dquarkus.profile=lab` (§1) |
| `Peer is up` rất chậm (tới ~3 phút), sim log `Received connect request from non provisioned …` | host multi-home: SCTP thử nguồn LAN/IPv6, sim chỉ nhận loopback | đợi retry chọn 127.0.0.1 |
| Verify luôn `false`, HSS không nhận gì | SCTP chưa "Peer is up" | đợi thêm; kiểm tra 3 association (S6a/SWx/Gx) |
| Verify `false` + `SIM_SWAP_SUSPECT` dù CCA/ULA đều 2001 | `s6a=corsac` fail-closed chiều SIM-swap (lab không có Sh UDR) | đúng thiết kế; muốn `true` dùng memory transport |
| Discovery `403 USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` | binding resolve sang MSISDN verifier lab không biết | `POST http://127.0.0.1:28086/api/reset` re-seed |
| SWx timeout dù HSS thấy MAR/MAA | 2 link cùng origin-host vào 1 port | tách SWx sang port riêng (`swx.peer-port=3869`) |
| Gx resolver `false` dù có binding | binding nằm sai instance | seed binding ở instance **:3870** (`POST :28086/api/binding`) |
| `/entitlement/issue` → connection refused ở :8085 | entitlement đã chuyển sang **sas-ecs :8095** | chạy sas-ecs (§2) và gọi :8095 |
| Operator token → `401` ở sas-camara | issuer chưa trong `sas.operator-token.issuers-json`, JWKS sas-ecs không tải được, token hết hạn/đã dùng | xem log `[SAS] operator token rejected: <lý do>` |
| Header `X-Sas-Operator-Token` bị bỏ qua | `sas.operator-token.header-enabled=false` (mặc định; prod luôn tắt) | dùng `Authorization: Bearer operatortoken:<tk>` |
| `/entitlement/exchange` → `404` | prod tắt redeem cục bộ trên sas-ecs | đổi token ở sas-camara |

---

## 10. Kiểm thử tự động liên quan

```bash
/usr/bin/mvn -B clean test -Dquarkus.profile=lab   # 649 test / 7 module (JUnit 5)
/usr/bin/mvn -B -o -pl sas-api -am test -Dtest='VerifyResourceTest,OperatorTokenAnchorTest,AuthorizationCodeFlowTest' \
    -Dsurefire.failIfNoSpecifiedTests=false        # nhánh operator token + NV auth code
python3 harness/run_hardness.py          # 37/37 gates H1–H27 (H22 bearer parity, H24 SLEE boundary, H25 binary split)
python3 harness/preflight_prod.py --service all    # verdict profile prod cho MÔI TRƯỜNG này (camara + ecs)
scripts/split-smoke.sh                   # hai binary end-to-end + pcap
scripts/ecs-map-smoke.sh                 # sas-ecs ↔ HLR qua MAP + ma trận fail-closed + pcap
```
