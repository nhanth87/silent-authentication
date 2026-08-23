# Silent Auth SAS — E2E Test Flow

Date: 2026-08-23 · Updated: 2026-08-23 (CAMARA-aligned endpoints + PCRF Sd scenario)
Scope: web → `POST /verify` → SAS → `sas-diameter-testapp`

> CAMARA alignment: primary endpoints are now under `/number-verification/v2`
> (`POST …/verify`, `GET …/device-phone-number`). Legacy `/verify` and
> `/retrieve-phone-number` still work as deprecated lab aliases. Assurance detail
> (score/factors) is OPT-IN via header `X-Sas-Assurance-Detail: true`.
> Spec snapshot + gap analysis: `docs/research/camara/`.

```
curl/browser          SAS (Quarkus :8085)         sas-diameter-testapp (HSS/AAA giả lập)
     │                        │                        ├── instance 1: SCTP :3868 = S6a (ULR/AIR/IDR)
     │  POST /verify          │   Diameter (SCTP)      ├── instance 2: SCTP :3869 = SWx (MAR/SAR/PPR)
     ├───────────────────────►│───────────────────────►├── instance 3: SCTP :3870 = Gx  (CCR-I binding)
     │  {verified, assurance?}│◄── ULA/AIA/SAA/MAA/CCA─┤
     │◄───────────────────────┤                        │  Control UI: /api/messages,
     │                        │                        │  /api/subscriber, /api/binding
```

## 0. Yêu cầu môi trường

- JDK zulu-25: `$HOME/.local/share/mise/installs/java/zulu-25`
- Build luôn dùng: `JAVA_HOME=$HOME/.local/share/mise/installs/java/zulu-25 /usr/bin/mvn`
  (PATH `mvn` là mise shim zulu-8 — KHÔNG dùng trực tiếp)
- Chạy jar: `$HOME/.local/share/mise/installs/java/zulu-25/bin/java -jar …`

## 1. Build

```bash
cd sas-diameter-testapp && mvn -q package
cd ../sas               && mvn -q package -DskipTests
```

## 2. Chạy 2 instance HSS simulator

```bash
# Instance 1: S6a HSS (Diameter SCTP :3868) + control UI :8086
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &

# Instance 2: SWx AAA (SCTP :3869) + control UI :18086
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3869 --web-port 18086 &
```

> Lab HSS chỉ nhận **1 inbound SCTP association mỗi listen port** → SWx buộc phải
> tách port riêng; SAS trỏ qua `-Dsas.transport.diameter.swx.peer-port=3869`.

## 3. Chạy SAS với corsac transports

```bash
cd sas && java \
  -Dsas.transport.s6a=corsac \
  -Dsas.transport.swx=corsac \
  -Dsas.entitlement.hmac-secret=e2e-lab-secret \
  -Dsas.transport.diameter.swx.peer-port=3869 \
  -Dsas.transport.resolver=sd \
  -Dsas.transport.sd.peer-port=3870 \
  -jar target/quarkus-app/quarkus-run.jar &
```

Đợi log `Peer is up for Association [name=s6a-sas…]`, `[name=swx-sas…]` (~15–20 s).
`sas.transport.resolver=sd` bật PCRF Gx binding probe (:3870); bỏ dòng này nếu muốn
resolver memory pilot. Các lựa chọn resolver: `memory | cgnat | radius | sd`.

## 4. Scenarios

### ① S6a LTE happy path

Resolver seed sẵn `10.20.30.40 → +251911111111` (IMSI `655010000000001`) — với
`resolver=sd` binding này nằm trong Gx BindingRegistry của instance :3870
(`POST /api/binding` để đổi).

CAMARA primary path:

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: t1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
# → {"devicePhoneNumberVerified":true}   (CCR→CCA, ULR→ULA 2001, AIR→AIA vectors=1)
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
# → {"devicePhoneNumberVerified":true,"reqId":"…","decision":"APPROVE",
#     "assurance":{"score":100,"level":"HIGH","threshold":80,"riskClass":"TRANSFER",
#       "factors":{…}}}          — threshold theo risk class; CDR lưu full flow
```

Discovery (trả số bound với token):

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

### ③ Fail-closed — rút auth vectors

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":true,"authVectorsAvailable":0}'
# verify lại → AIA rỗng → false
```

Reset về mặc định: `curl -X POST http://127.0.0.1:8086/api/reset`

### ④ SWx Wi-Fi path (token ký HMAC)

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
token) rồi bank mới gọi `/verify` so sánh. Chạy SAS với validation bật:

```bash
java -Dsas.security.token-validation-enabled=true \
     -Dsas.security.hmac-secret=k1 -Dsas.oauth.secret=k1 \
     -jar target/quarkus-app/quarkus-run.jar &
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
curl -s http://127.0.0.1:8086/api/messages    # S6a messages
curl -s http://127.0.0.1:18086/api/messages   # SWx messages
```

Hoặc mở browser `http://127.0.0.1:8086/` — bảng tin tự refresh 2 s: thời điểm,
command, session-id, result-code, AVP chính (`user=… rat=EUTRAN`, `vectors=N`…).

## 6. Ma trận kỳ vọng

| Scenario | Signalling | Kết quả |
|---|---|---|
| LTE happy (resolver=sd) | CCA 2001 + ULA 2001 + AIA vectors≥1 | `true` |
| Detached | ULA **5421** | `false` |
| Zero vectors | AIA rỗng | `false` |
| Gx unknown IP | CCA 5030 | `false` (NO_BINDING) |
| SWx + token hợp lệ | MAA items≥1 + SAA 2001 | `true` |
| Token replay | — (chặn trước Diameter) | `401` |
| amr sai/thiếu | — | `403` |
| Body thiếu phoneNumber/hashed | — | `400 INVALID_ARGUMENT` |
| Assurance detail không opt-in | — | response thuần boolean (CAMARA-pure) |

## 7. Kiểm thử khác trong tree

```bash
cd sas && mvn test                       # 212 unit/scenario tests (JUnit 5, không cần mạng)
python3 harness/run_hardness.py          # 24/24 contract gates
```

## 8. Lỗi thường gặp

| Triệu chứng | Nguyên nhân | Fix |
|---|---|---|
| `UnsupportedClassVersionError` khi chạy jar | `java` mặc định là zulu-8 (shim) | dùng đường dẫn zulu-25 đầy đủ |
| SAS báo fallback in-memory lúc start | corsac start exception | xem WARN trong log (log4j2 root=INFO đã bật) |
| Verify luôn `false`, HSS không nhận gì | SCTP chưa "Peer is up" | đợi thêm; kiểm tra 2 association |
| SWx timeout dù HSS thấy MAR/MAA | 2 link cùng origin-host vào 1 port | tách SWx sang port riêng (`swx.peer-port`) |
