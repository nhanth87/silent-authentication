# Silent Auth SAS — E2E Test Flow

Date: 2026-08-23 · Scope: web → `POST /verify` → SAS → `sas-diameter-testapp`

```
curl/browser          SAS (Quarkus :8085)         sas-diameter-testapp (HSS/AAA giả lập)
     │                        │                        ├── instance 1: SCTP :3868 = S6a (ULR/AIR/IDR)
     │  POST /verify          │   Diameter (SCTP)      └── instance 2: SCTP :3869 = SWx (MAR/SAR/PPR)
     ├───────────────────────►│───────────────────────►│
     │  {verified: boolean}   │◄─── ULA/AIA/SAA/MAA ───┤
     │◄───────────────────────┤                        │  Control UI: GET /api/messages,
     │                        │                        │  POST /api/subscriber (đổi state thuê bao)
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
  -jar target/quarkus-app/quarkus-run.jar &
```

Đợi log `Peer is up for Association [name=s6a-sas…]` và `[name=swx-sas…]` (~15–20 s).

## 4. Scenarios

### ① S6a LTE happy path

Resolver memory seed sẵn `10.20.30.40:55555 → +251911111111` (IMSI `655010000000001`).

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: t1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
# → {"devicePhoneNumberVerified":true}   (ULR→ULA 2001, AIR→AIA vectors=1)
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
| LTE happy | ULA 2001 + AIA vectors≥1 | `true` |
| Detached | ULA **5421** | `false` |
| Zero vectors | AIA rỗng | `false` |
| SWx + token hợp lệ | MAA items≥1 + SAA 2001 | `true` |
| Token replay | — (chặn trước Diameter) | `401` |
| amr sai/thiếu | — | `403` |

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
