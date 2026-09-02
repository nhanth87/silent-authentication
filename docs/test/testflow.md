# Silent Auth SAS — E2E Test Flow

Date: 2026-08-23 · Updated: 2026-09-01 (TS.43 = operator REST CAMARA NV + Sh UDR; SWx leg = operator AAA↔HSS)
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

## 1. Build

```bash
(cd sas-diameter-testapp && /usr/bin/mvn -B clean package)      # testapp jar ~26 MB
/usr/bin/mvn -B clean package -DskipTests                        # root: sas-api + sas-entitlement + sas-host → quarkus-app
```

## 2. Chạy 2 instance operator simulator (S6a + SWx)

```bash
# Instance 1: S6a HSS (Diameter SCTP :3868) + control UI :8086 — cellular (ULR/ULA)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &

# Instance 2: SWx = operator 3GPP AAA ↔ HSS (SCTP :3869) + control UI :18086 — TS.43 Wi-Fi leg
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3869 --web-port 18086 &
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
# → {"devicePhoneNumberVerified":true}   (CCR→CCA, ULR→ULA 2001, Sh UDR binding fresh)
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
token) rồi bank mới gọi `/verify` so sánh. Chạy SAS với validation bật:

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
curl -s http://127.0.0.1:8086/api/messages    # S6a messages
curl -s http://127.0.0.1:18086/api/messages   # SWx (operator AAA↔HSS) messages — TS.43 leg
```

Hoặc mở browser `http://127.0.0.1:8086/` — bảng tin tự refresh 2 s: thời điểm,
command, session-id, result-code, AVP chính (`user=… rat=EUTRAN`, `vectors=N`…).

## 6. Ma trận kỳ vọng

| Scenario | Signalling | Kết quả |
|---|---|---|
| LTE happy (resolver=sd) | CCA 2001 + ULA 2001 | `true` |
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
| SAS báo fallback in-memory lúc start | corsac start exception | xem WARN trong log (log4j2 root=INFO đã bật) |
| Verify luôn `false`, HSS không nhận gì | SCTP chưa "Peer is up" | đợi thêm; kiểm tra 2 association |
| SWx timeout dù HSS thấy MAR/MAA | 2 link cùng origin-host vào 1 port | tách SWx sang port riêng (`swx.peer-port`) |
