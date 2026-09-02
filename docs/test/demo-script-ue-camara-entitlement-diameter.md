# Demo Script — UE SDK → CAMARA `/verify` → Entitlement (TS.43) → S6a (cellular) + SWx (operator leg)

> Kịch bản demo đầu cuối cho Restlink Silent Auth (Ethiopia): từ **UE SDK** thu
> session-tuple, qua northbound **CAMARA Number Verification**, qua track
> **TS.43 entitlement** (Wi-Fi), xuống tận lớp **Diameter** signalling bằng
> `sas-diameter-testapp`. Hai track **khác nhau**: **S6a + Gx** = cellular
> (4G/5G, freshness = **Sh UDR/SNR**); **SWx** = chân **operator** của TS.43 Wi-Fi.
>
> Bổ trợ: `docs/design/silent-auth-standard-flow.md` (chuẩn hóa flow) và
> `docs/test/testflow.md` (ma trận E2E đầy đủ + CIBA money-loop).

- Ngày: 2026-09-01
- Giả lập: `sas-diameter-testapp` (HSS + 3GPP AAA + PCRF·Gx giả lập, SCTP).

---

## Tổng thể

```
UE SDK (device)                    SAS (Quarkus :8085)              sas-diameter-testapp (operator simulator)
  │  POST /session-tuple             │  RESOLVING→VERIFYING→SCORING    ├─ inst1 S6a :3868 (ULR/ULA) — cellular
  │  X-Sas-Access-Tech: LTE          │                                 ├─ inst2 SWx :3869 (MAR/SAR) — operator AAA↔HSS (TS.43 Wi-Fi)
  │─────────────────────────────────►│──── Diameter (SCTP) ───────────►├─ inst3 Gx  :3870 (CCR-I binding) — resolver
  │  POST /number-verification/v2/verify                              │  UI: /api/messages,
  │  {phoneNumber}                   │◄── ULA · MAA/SAA · CCA ────────┤      /api/subscriber,
  │◄──────── {devicePhoneNumberVerified}                              │      /api/binding
```

Mục tiêu demo:

1. UE SDK khai báo bearer cellular (`accessTech`) trên `/session-tuple` → SAS
   từ chối tuple Wi-Fi (`400 ACCESS_TECH_NOT_CELLULAR`).
2. CAMARA `/verify` + `/device-phone-number` trên cellular → Diameter **S6a** +
   Resolver **Gx**.
3. TS.43 entitlement trên Wi-Fi → `/entitlement/issue` → `/verify` với
   `operatortoken:` → (lab) `swxverifier` RA mở **SWx** thay chân operator;
   production verify qua **operator REST CAMARA NV / SIM Swap**.
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

# 2) root reactor sas-api + sas-entitlement + sas-host
#    → sas-host/target/quarkus-app/quarkus-run.jar (fast-jar)
/usr/bin/mvn -B clean package -DskipTests
```

## 2. Chạy testapp + SAS (corsac transports)

```bash
# Instance 1: S6a HSS (SCTP :3868) + control UI :8086
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &

# Instance 2: SWx = operator 3GPP AAA↔HSS (SCTP :3869) + control UI :18086 — TS.43 Wi-Fi leg
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3869 --web-port 18086 &

# Instance 3: PCRF Gx (SCTP :3870) + control UI :28086  (dùng khi resolver=sd)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3870 --web-port 28086 &
```

> Lab HSS chỉ nhận **1 inbound SCTP association mỗi listen port** → S6a/SWx/Gx
> phải tách port riêng; SAS trỏ qua `-Dsas.transport.diameter.swx.peer-port=3869`
> và `-Dsas.transport.sd.peer-port=3870`.

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

Đợi log `Peer is up for Association [name=s6a-sas…]`, `[name=swx-sas…]` (~15–20 s).
`resolver=sd` bật PCRF Gx binding probe (:3870); bỏ dòng này nếu muốn resolver
memory. Lựa chọn resolver: `memory | cgnat | radius | sd`.

## 3. Kịch bản A — UE SDK cellular + CAMARA verify (S6a + Gx)

Resoler `sd` seed sẵn binding `10.20.30.40:55555 → +251911111111` (IMSI
`655010000000001`) trong Gx BindingRegistry của instance :3870 (`POST /api/binding`
để đổi). UE SDK trên thiết bị khai báo bearer + tuple qua `/session-tuple`.

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

> `accessTech` do thiết bị khai báo là **advisory**: được dùng để *loại trừ* tuple
> xấu và tương quan CDR, không bao giờ để nâng assurance. Assurance tới từ cái
> mà chính mạng operator attest (IP:port:ts qua PGW/Gx/CGNAT).
>
> Với `resolver=memory`, chính `/session-tuple` seed `InMemoryResolverBackend`
> (không cần instance Gx); binding lúc đó do UE SDK trực tiếp tạo.

### 3.2 CAMARA `/verify` (happy path)

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: a1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
# → {"devicePhoneNumberVerified":true}
#   Diameter: Gx CCR→CCA 2001 (resolver), S6a ULR→ULA 2001, Sh UDR binding fresh
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
# → {"devicePhoneNumberVerified":true,"reqId":"…","decision":"APPROVE",
#     "assurance":{...}}   — threshold theo risk class, CDR lưu full flow
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

## 5. Kịch bản C — TS.43 entitlement trên Wi-Fi (SWx = chân operator, lab)

Đây là track **không cần cellular**: thiết bị xác thực EAP-AKA với 3GPP AAA,
nhận operator token tạm, backend đổi token rồi SAS verify. **Lab**: `swxverifier`
RA mở MAR/SAR tới SWx :3869 đóng thế chân operator. **Production**: SAS verify
qua **operator REST (CAMARA NV / SIM Swap)** + freshness **Sh UDR/SNR**, không mở SWx.

### 5.1 Cấp token tạm (thay cho EAP-AKA thật, trong lab gọi trực tiếp)

```bash
TOKEN=$(curl -s -X POST http://localhost:8085/entitlement/issue \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
echo "$TOKEN"
# → {"token":"...","expiresInSeconds":300}
```

> `/entitlement/issue` trên thực tế do **3GPP AAA** gọi sau khi EAP-AKA thành công
> qua SWm; trong lab ta gọi trực tiếp. Binding của token mang **cả MSISDN lẫn
> IMSI** — IMSI được luồn vào `VerifyRequestEvent` để `CorsacSwxVerifierBackend`
> (lab, đóng thế chân operator) đối soát identity/SIM-swap (nếu mất IMSI sẽ hạ
> cấp thầm lặng về anchor chỉ-MSDN). Production: `CorsacSwxVerifierBackend` được
> thay bằng operator REST CAMARA NV / SIM Swap + Sh UDR/SNR.

### 5.2 `/verify` với operator token (AccessTech = WIFI)

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: c1' -d '{}'
# → {"devicePhoneNumberVerified":true}
#   Lab: swxverifier RA mở SWx MAR→MAA items=1, SAR→SAA trên :3869 (chân operator)
```

Biến thể header `X-Sas-Operator-Token` (khi `sas.entitlement.ciba-enabled=true`):

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "X-Sas-Operator-Token: $TOKEN" \
  -H 'x-correlator: c2' -d '{}'
```

### 5.3 Token single-use (anti-replay)

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: c3' -d '{}'
# → 401 (consumed-jti) — token một lần dùng, không refresh
```

### 5.4 Đối soát định danh (exchange)

```bash
curl -s -X POST http://localhost:8085/entitlement/exchange \
  -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\"}"
# → {"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA","valid":false}
#   valid=false vì token đã bị consume ở 5.3
```

## 6. Kịch bản D (tùy chọn) — CIBA money-loop

SAS là operator Auth Server: cấp token user-bound (số điện thoại gắn vào token),
bank gọi `/verify` so sánh. Chạy SAS với validation bật:

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

# 2) Đổi token (CIBA grant; một auth_req_id = MỘT token)
AT=$(curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")

# 3) /verify so sánh claimed vs số BOUND trong token
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H "Authorization: Bearer $AT" -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'   # match → true + assurance
#    phoneNumber khác bound → false ; dùng lại token → 401 single-use
```

Wi-Fi track cho CIBA: thay `X-Sas-Src-Ip/Port` bằng `login_hint=operatortoken:<tk>`.

## 7. Quan sát Diameter signalling

```bash
curl -s http://127.0.0.1:8086/api/messages    # S6a  (ULR/ULA; freshness = Sh UDR)
curl -s http://127.0.0.1:18086/api/messages   # SWx  (MAR/SAR) — operator AAA↔HSS (TS.43)
curl -s http://127.0.0.1:28086/api/messages   # Gx   (CCR)
curl -s http://127.0.0.1:28086/api/binding    # Gx binding registry
```

Hoặc mở browser `http://127.0.0.1:8086/` — bảng tin tự refresh 2 s: thời điểm,
command, session-id, result-code, AVP chính (`user=… rat=EUTRAN`, `vectors=N`…).

---

## 8. Ma trận kỳ vọng

| Kịch bản | Signalling | Kết quả |
|----------|-----------|---------|
| A — LTE happy (resolver=sd) | Gx CCA 2001 + ULA 2001 | `true` |
| A — discovery | Gx CCA 2001 + ULA 2001 | `{"devicePhoneNumber":"+2519…"}` |
| A — tuple `accessTech: WIFI` | — (chặn trước Diameter) | `400 ACCESS_TECH_NOT_CELLULAR` |
| B — Detached | ULA **5421** | `false` |
| B — Barred | ULA 2001 + Subscriber-Status ODB | `false` |
| B — Gx unknown IP | CCA 5030 | `false` (NO_BINDING) |
| C — TS.43 token hợp lệ (lab: SWx MAR/SAR) | MAA items≥1 + SAA 2001 | `true` (production: CAMARA NV REST) |
| C — Token replay | — (chặn trước Diameter) | `401` (consumed-jti) |
| A/B — amr sai/thiếu | — | `403` |
| A — body thiếu `phoneNumber`/`hashedPhoneNumber` | — | `400 INVALID_ARGUMENT` |
| A — assurance không opt-in | — | response thuần boolean (CAMARA-pure) |

---

## 9. Lỗi thường gặp

| Triệu chứng | Nguyên nhân | Fix |
|-------------|-------------|-----|
| `UnsupportedClassVersionError` khi chạy jar | `java` mặc định là zulu-8 (shim) | dùng đường dẫn zulu-25 đầy đủ |
| SAS báo fallback in-memory lúc start | corsac start exception | xem WARN trong log (log4j2 root=INFO đã bật) |
| Verify luôn `false`, HSS không nhận gì | SCTP chưa "Peer is up" | đợi thêm; kiểm tra 3 association (S6a/SWx/Gx) |
| SWx timeout dù HSS thấy MAR/MAA | 2 link cùng origin-host vào 1 port | tách SWx sang port riêng (`swx.peer-port=3869`) |
| Gx resolver `false` dù có binding | binding nằm sai instance | seed binding ở instance **:3870** (`POST :28086/api/binding`) |
| Operator-token verify sai identity | IMSI bị null (hạ cấp anchor) | xác nhận `/entitlement/issue` có `imsi`; bản vá đã luồn `claimedImsi` vào event |

---

## 10. Kiểm thử tự động liên quan

```bash
/usr/bin/mvn -B clean test                          # 363 tests trên các module (JUnit 5)
/usr/bin/mvn -B -pl sas-api test -Dtest=VerifyResourceTest   # regress nhánh operator-token (threads claimedImsi)
python3 harness/run_hardness.py          # gates H1–H24 (H22 bearer parity, H24 slee boundary)
python3 harness/preflight_prod.py        # verdict profile prod cho MÔI TRƯỜNG này
```