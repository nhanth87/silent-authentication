# Silent Auth SAS — E2E Test Flow (Tiếng Việt)

> 🇬🇧 English version: [`testflow.en.md`](testflow.en.md) — cùng số mục, cùng lệnh.

Ngày: 2026-08-23 · Cập nhật: **2026-10-01** — SAS tách thành **hai binary**:
**sas-camara** (`sas-host`, :8085 — CAMARA NV / SIM Swap / OTP-SMS + OAuth AS) và
**sas-ecs** (`sas-ecs-host`, :8095 — TS.43 entitlement server, EAP-AKA qua MAP/Diameter).
Mục mới: **§0a** (quick start hai binary + Authorization Code NV + operator token),
**§0f** (entitlement ↔ core qua MAP trên sas-ecs, có pcap). Lịch sử cập nhật trước đó:
alias `/camara`, body strict, `x-correlator`; GSMA mock ↔ local §0d; OAuth ICM Phase 3 §0e;
dist demo §0b; 3 testapp gồm Gx; SIM-swap fail-closed trên chân S6a corsac; CDR bền cho
SimSwap + OTP.

Kiến trúc: [`../design/sas-two-binary-architecture.md`](../design/sas-two-binary-architecture.md).

> **Căn chỉnh CAMARA:** endpoint chính nằm dưới `/number-verification/v2` và alias
> `/camara` (`POST /camara/number-verification/v2/verify`,
> `GET /camara/number-verification/v2/device-phone-number`). `/verify` và
> `/retrieve-phone-number` cũ vẫn chạy như alias lab (deprecated). Thuộc tính lạ trong body
> CAMARA bị từ chối `400 INVALID_ARGUMENT`; `x-correlator` (nếu có) phải khớp
> `^[a-zA-Z0-9-_:;./<>{}]{0,256}$` và được echo lại. Assurance detail (score/factors) là
> OPT-IN qua header `X-Sas-Assurance-Detail: true`. Snapshot spec + gap: `docs/research/camara/`.

> **Hai track khác nhau (đừng nhập nhằng):** **S6a + Gx** là track *cellular* (4G/5G):
> sas-camara mở ULR/ULA tới HSS, resolver Gx tra binding; SIM-swap freshness = **Sh
> UDR/SNR** read-only. **TS.43 Wi-Fi** là track *entitlement*: sas-ecs làm EAP-AKA, lấy
> vector bằng **MAP SAI** (hoặc **SWx MAR**) và xác nhận số bằng **MAP SendIMSI**, rồi
> cấp **operator token** (JWE) mà sas-camara chấp nhận đúng một lần. Instance SWx :3869 ở
> §2 chỉ đóng thế chân operator AAA↔HSS cho verifier lab của sas-camara.

```
bank / curl / GSMA client        sas-camara :8085                 operator simulators
     │  OIDC /authorize /token       │  Resolver → Verifier → Policy   ├─ S6a :3868  (ULR/ULA)
     │  CAMARA NV / SIM Swap / OTP   │──── Diameter (SCTP) ───────────►├─ SWx :3869  (MAR/SAR, lab)
     ├──────────────────────────────►│                                 └─ Gx  :3870  (CCR-I binding)
     │                               │  ▲ GET /jwks.json (issuer keys)
UE (lab card) ── /ts43 ─► sas-ecs :8095 ─┘
                            │──── MAP over M3UA/SCTP (own association :2907) ──► HLR sim :2906 (ctrl :8087)
                            │     sendAuthenticationInfo (SSN 6) · sendIMSI (SSN 3)
                            └──► operator token (ES256 JWS trong JWE ECDH-ES/A256GCM) ──► app ──► bank ──► sas-camara
```

## 00. Kiến trúc tách theo tầng (từ M3) — bắt đầu ở đây (verify 2026-10-01 trên dist đóng gói)

Từ bước M3, **CAMARA gateway (sas-camara) không còn chạm core network**. Mọi thông tin về
thuê bao đến từ entitlement server (sas-ecs) qua **subscriber exposure API**
(`/sas-subscriber-exposure/v1`, contract
[`../design/openapi/sas-subscriber-exposure-v1.yaml`](../design/openapi/sas-subscriber-exposure-v1.yaml)).
Hệ quả khi test:

- Mọi công tắc transport (`sas.transport.*`, `SAS_TRANSPORT_*`, peer jSS7/Diameter,
  resolver) được đặt trên **sas-ecs**. Trên sas-camara chúng không còn tồn tại (preflight
  PRO-17 từ chối ở prod).
- Operator token được mã hóa cho và redeem bởi sas-ecs (`/operator-token/redeem`). Gateway
  không giữ khóa hay ledger nào. `/jwks.json` trên sas-ecs đã bỏ (404).
- `/session-tuple` nằm trên sas-ecs.
- `/verify` qua Wi-Fi cần operator token đã redeem; nếu không có → `FALLBACK WIFI_NOT_READY`.
- Trên transport live hiện chưa có nguồn SIM-change. SIM Swap trả 404, NV kết thúc ở
  `SIM_SWAP_SUSPECT` (fail-closed). Với evidence lab trên sas-ecs, cùng các luồng đó approve.

Các mục §0a–§9 bên dưới viết trước M3 ở những chỗ khởi động sas-camara với
`-Dsas.transport.*`; các bước đó được đánh dấu **trước M3**.

### 00.1 Build và đóng gói

```bash
/usr/bin/mvn -B -o clean package -Dquarkus.profile=lab           # 9 module, 791 test
(cd sas-jss7-testapp && /usr/bin/mvn -o package -DskipTests)      # HLR simulator
(cd sas-diameter-testapp && /usr/bin/mvn -o package)              # HSS / AAA / PCRF simulator
./scripts/package-dist.sh all                                     # dist/ (camara) + dist-ecs/ (ecs)
scripts/check-dependency-boundary.sh dist dist-ecs                # cả hai dòng phải OK
```

Đóng gói lại đè lên dist đã có sẽ **giữ nguyên** `configs/*` (thuộc về operator) và ghi bản
mặc định mới bên cạnh dưới dạng `*.new`. Dist cấu hình trước 2026-10-01 vẫn mang cấu hình
gateway trước M3 (`ss7-sas.json`, danh sách issuer operator-token). Với dist lab, hãy bắt đầu
từ cấu hình mặc định hiện tại:

```bash
for d in dist dist-ecs; do mkdir -p $d/configs/backup && mv $d/configs/*.properties* $d/configs/*.json* $d/configs/backup/ 2>/dev/null; done
./scripts/package-dist.sh all                                     # cài cấu hình mới
```

### 00.2 Khởi động bốn tiến trình (bốn terminal)

```bash
# T1 — HLR simulator: MAP qua SCTP :2906 (peer sas-ecs :2907), control :8087
java --add-modules jdk.sctp -jar sas-jss7-testapp/target/sas-jss7-testapp.jar \
     --listen-port 2906 --peer-port 2907 --http-port 8087

# T2 — HSS simulator: Diameter S6a qua SCTP :3868, control :8086
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar

# T3 — sas-ecs (:8095) trên core LIVE: MAP SAI/SendIMSI/PSI + peer S6a
(cd dist-ecs && SAS_TRANSPORT_AUTHVECTOR=map SAS_TRANSPORT_MAP=jss7 SAS_TRANSPORT_S6A=corsac \
   SAS_BINDING_SOURCE_ORDER=map-smi SAS_TRANSPORT_JSS7_CONFIG=$PWD/configs/ss7-ecs.json ./run.sh)

# T4 — sas-camara (:8085), bật kiểm tra OAuth, một client lab
CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["authorization_code","urn:openid:params:grant-type:ciba"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","sim-swap:check"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'
(cd dist && SAS_JAVA_OPTS="-Dsas.security.token-validation-enabled=true -Dsas.security.accept-hs256=false -Dsas.oauth.clients-json=$CLIENTS" ./run.sh)
```

Biến môi trường và `-D` ghi đè được `configs/application.properties` của dist
(`config_ordinal=250`). Trước 2026-10-01, `run.sh` âm thầm bỏ qua chúng.

### 00.3 Các bước — gọi thẳng exposure API trên sas-ecs

```bash
E=http://127.0.0.1:8095/sas-subscriber-exposure/v1
H=(-H 'Content-Type: application/json' -H "X-Request-Id: req-$(date +%s%N)" -H 'X-Sas-Budget-Ms: 2500')

# S1 — UE ID: tuple lab 10.20.30.40:55555 → GPSI
curl -s -X POST $E/ue-id/retrieve "${H[@]}" \
  -d '{"afId":"sas-camara","ueIpAddr":{"ipv4Addr":"10.20.30.40"},"portNumber":55555,"observedAt":"2026-10-01T06:00:00Z"}'
# → 200 {"gpsi":"msisdn-251911111111","bindingObservedAt":"…","evidenceSource":"LAB_RESOLVER"}

# S2 — monitoring qua core MAP live (SendIMSI, rồi PSI)
curl -s -X POST $E/monitoring-event/sas-camara/subscriptions "${H[@]}" \
  -d '{"msisdn":"251911111111","monitoringType":"UE_REACHABILITY","addnMonTypes":["LOCATION_REPORTING"],"maximumNumberOfReports":1,"immediateRep":true}'
# → 200 … "ueReachable":true … "plmnId":{"mcc":"636","mnc":"01"},"locationInfo":{"ageOfLocationInfo":1} … "evidenceSource":"MAP_PSI"
#   số lạ 251900000000 → 404 SUBSCRIBER_NOT_FOUND

# S3 — SIM change trên transport live (chạy lại 2026-10-01)
curl -s -X POST $E/sim-change/retrieve "${H[@]}" -d '{"afId":"sas-camara","msisdn":"251911111111"}'
# → 404 {"title":"NO_EVIDENCE","status":404,"detail":"no source could say when the SIM last changed","cause":"NO_EVIDENCE"}
#   (fail-closed: không bao giờ trả "chưa đổi SIM"; số lạ cũng 404 y hệt —
#   API không để lộ thuê bao có tồn tại hay không)
# evidence lab (00.5):
# → 200 {"msisdn":"251911111111","latestSimChange":"2026-09-21T…Z","eventTime":"…","evidenceSource":"LAB_HLR"}
#   (tuổi binding đã seed, ~10 ngày trước)

# Contract từ chối: trường lạ → 400 INVALID_ARGUMENT; "msisdn":"+2519…" → 400;
# X-Sas-Budget-Ms: 50 cho monitoring → 504 BUDGET_EXHAUSTED (không gửi gì ra core)
```

### 00.4 Các bước — luồng CAMARA qua gateway

```bash
ECS=http://127.0.0.1:8095; CAM=http://127.0.0.1:8085

# S4 — operator token: EAP-AKA thật qua MAP SAI + SendIMSI, thẻ SIM lab
CJ=$(curl -s "$ECS/ts43/challenge?imsi=655010000000001&msisdn=%2B251911111111")
R=$(java -cp sas-entitlement/target/classes et.restlink.sas.entitlement.lab.LabSimCardMain \
      655010000000001 "$(jq -r .challenge <<<"$CJ")")
TOK=$(curl -s -X POST $ECS/ts43/respond -H 'Content-Type: application/json' \
  -d "{\"reqId\":\"$(jq -r .reqId <<<"$CJ")\",\"imsi\":\"655010000000001\",\"msisdn\":\"+251911111111\",\"response\":\"$R\"}" | jq -r .token)
awk -F. '{print NF}' <<<"$TOK"                  # → 5 (JWE)

# S5 — CIBA với login_hint=operatortoken: gateway chuyển nguyên token sang /operator-token/redeem
AUTH=$(curl -s -X POST $CAM/bc-authorize --data-urlencode "login_hint=operatortoken:$TOK" \
  --data-urlencode "scope=dpv:FraudPreventionAndDetection sim-swap:check" \
  --data-urlencode client_id=bank-lab | jq -r .auth_req_id)
AT=$(curl -s -X POST $CAM/token --data-urlencode grant_type=urn:openid:params:grant-type:ciba \
  --data-urlencode "auth_req_id=$AUTH" --data-urlencode client_id=bank-lab | jq -r .access_token)
# gửi lại đúng token đó ở /bc-authorize → 403 (đã tiêu trong ledger của ECS)

# S6 — SIM Swap check bằng token CIBA
curl -s -X POST $CAM/camara/sim-swap/v2/check -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -H "x-correlator: $(cat /proc/sys/kernel/random/uuid)" -d '{"maxAge":240}'
# core live → 404 {"status":404,"code":"IDENTIFIER_NOT_FOUND",
#   "message":"no SIM-change evidence for the requested identifier"}
# evidence lab (00.5) → 200 {"swapped":false}   (chạy lại 2026-10-01, cả gọi 2-legged
#   `Bearer lab` lẫn token CIBA; `maxAge=2400` → 200 {"swapped":true};
#   `/retrieve-date` → 200 {"latestSimChange":"2026-09-21T…Z"})

# S7 — NV Authorization Code + PKCE; xác thực mạng = UE ID retrieval tại ECS
VERIFIER=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=\n')
LOC=$(curl -s -o /dev/null -w '%{redirect_url}' -G $CAM/authorize \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  --data-urlencode response_type=code --data-urlencode client_id=bank-lab \
  --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode "scope=openid dpv:FraudPreventionAndDetection number-verification:verify" \
  --data-urlencode state=s --data-urlencode nonce=n \
  --data-urlencode "code_challenge=$CHALLENGE" --data-urlencode code_challenge_method=S256)
CODE=$(sed -E 's/.*[?&]code=([^&]+).*/\1/' <<<"$LOC")
NVAT=$(curl -s -X POST $CAM/token --data-urlencode grant_type=authorization_code \
  --data-urlencode "code=$CODE" --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode "code_verifier=$VERIFIER" --data-urlencode client_id=bank-lab | jq -r .access_token)
curl -s -X POST $CAM/camara/number-verification/v2/verify -H "Authorization: Bearer $NVAT" \
  -H 'Content-Type: application/json' -H "x-correlator: $(cat /proc/sys/kernel/random/uuid)" \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' -d '{"phoneNumber":"+251911111111"}'
# core live (chạy lại 2026-10-01) → {"devicePhoneNumberVerified":false,"decision":"FALLBACK",
#   "assurance":{"score":70,"level":"FALLBACK","threshold":70,"riskClass":"LOGIN",
#   "factors":{"ipBindingFresh":1.0,"reachable":1.0,"notSimSwapped":0.0,"locationPlausible":1.0}},
#   "fallbackReason":"SIM_SWAP_SUSPECT"}   (log gateway trong dist/logs nói y hệt)
# evidence lab (00.5) → {"devicePhoneNumberVerified":true,"decision":"APPROVE",
#   "assurance":{"score":100,"level":"HIGH",…,"notSimSwapped":1.0,…}};
#   câu trả lời không bao giờ nêu nguồn evidence

# S8 — ranh giới runtime: gateway không có socket SCTP, sas-ecs có
scripts/check-no-sctp.sh "$(pgrep -f '^[^ ]*java .*/dist/quarkus-run\.jar')" sas-camara       # OK … owns no SCTP endpoint
scripts/check-no-sctp.sh "$(pgrep -f '^[^ ]*java .*/dist-ecs/quarkus-run\.jar')" sas-ecs      # FAIL … owns SCTP socket inode(s) — đúng kỳ vọng
```

### 00.5 Biến thể evidence lab, và profile prod trên máy mình

Subscriber exposure API **bật sẵn ở profile lab** (cả hai dist ở trên đều chạy lab).
**Profile prod cố ý tắt nó**: chặng exposure chưa có xác thực client cho tới bước M4, nên ai
chạm tới :8095 cũng tra được IP→MSISDN hoặc tiêu token (preflight PRO-35). Muốn thử profile
prod trên máy local, chỉ bật cho đúng lần chạy đó — không sửa trong file:

```bash
(cd dist-ecs && QUARKUS_PROFILE=prod SAS_EXPOSURE_ENABLED=true ECS_…=… ./run.sh)   # kèm các biến ECS_* của application-prod.properties
```


Khởi động lại T3 **không** có `SAS_TRANSPORT_MAP=jss7`. Khi đó monitoring MAP trả lời từ
backend HLR lab, SIM change lấy từ tuổi binding đã seed, `evidenceSource` là `LAB_HLR`.
EAP-AKA vẫn chạy qua HLR simulator live. S6 lúc này trả `200 {"swapped":false}` và S7 trả `true`.

### 00.6 Chạy tự động

| Script | Chứng minh điều gì | Kết quả đã verify |
|---|---|---|
| `scripts/layered-e2e.sh dist dist-ecs` | 00.2–00.5 đầu-cuối trên dist đóng gói, pha live và lab, có pcap | tất cả OK, rc=0 |
| `scripts/split-smoke.sh` | hai binary từ `target/`: luồng CAMARA, JWKS của ECS đã bỏ, gateway không có SCTP | tất cả OK |
| `scripts/exposure-smoke.sh` | bốn operation exposure đối với HLR simulator, contract từ chối đúng, không có IMSI trong câu trả lời | 14/14 |
| `scripts/ecs-map-smoke.sh` | TS.43 qua MAP + ma trận fail-closed; sas-ecs giữ liên kết MAP, gateway không có | demo OK + 8/8 |

### 00.7 Bảng kỳ vọng

| Luồng | Core live (00.2) | Evidence lab (00.5) |
|---|---|---|
| UE ID 10.20.30.40:55555 | 200 GPSI | 200 GPSI |
| Monitoring 251911111111 | 200 reachable, PLMN 636-01, `MAP_PSI` | 200, `LAB_HLR` |
| SIM change | 404 `NO_EVIDENCE` | 200, 10 ngày trước |
| CIBA với operator token | auth_req_id; gửi lại 403 | như trên |
| SIM Swap check | 404 | 200 `swapped:false` |
| NV Auth Code → /verify | `false` (`SIM_SWAP_SUSPECT`) | `true` |
| /verify Wi-Fi bằng token hợp lệ nhưng không có operator binding | `FALLBACK WIFI_NOT_READY` (score 0, mọi factor 0.0) | như trên |
| /verify Wi-Fi bằng bearer rác (bật validation) | `401 UNAUTHENTICATED` — bị chặn ở cửa auth, chưa tới luật Wi-Fi | như trên |
| Socket SCTP của gateway | không có | không có |

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
- `tcpdump`, `tshark` và `unshare` là đủ cho các script pcap (§0a, §0f); không cần root
  (chúng chạy trong user + network namespace riêng).

## 0a. Hai binary — quick start (verified 2026-10-01)

> **Trước M3 (tách theo track).** Cấu hình operator-token phía gateway (`sas.operator-token.issuers-json`, khóa giải mã, ledger) trong mục này không còn tồn tại — token được redeem tại sas-ecs (§00). Các luồng vẫn chạy được với cấu hình lab hiện tại: `scripts/split-smoke.sh`.

Chạy độc lập với simulator Diameter: cả hai binary ở profile lab dùng transport memory.
Bao phủ: operator token sas-ecs → sas-camara (Wi-Fi `/verify`, CIBA + SIM Swap, replay),
**Authorization Code + PKCE cho NV** (network authentication), ID token, và cách ly
surface giữa hai binary.

**A0 — build + đóng gói cả hai dist:**

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/silent-authentication/main
./scripts/package-dist.sh all          # → dist/ (sas-camara) + dist-ecs/ (sas-ecs)
```
→ kỳ vọng: `Dist ready [camara]: …/dist` và `Dist ready [ecs]: …/dist-ecs`.

> ⚠️ **Nâng cấp một `dist/` cũ:** `package-dist.sh` **không ghi đè** `dist/configs/*`
> đã có (cấu hình của operator) — bản mới nằm cạnh với đuôi `.new`. Key mới không có
> trong file cũ sẽ lấy giá trị mặc định đóng gói trong jar, nhưng key cũ trong file vẫn
> thắng (vd `sas.transport.resolver=sd` của demo Diameter §0b ⇒ `/authorize` trả
> `NO_BINDING` khi không chạy Gx). Chạy §0a trên cấu hình sạch:
> `diff dist/configs/application.properties{,.new}` rồi merge, hoặc đóng gói vào thư mục
> khác: `SAS_SKIP_BUILD=1 SAS_DIST_DIR=/path/dist-clean ./scripts/package-dist.sh camara`.

**A1 — start sas-ecs (:8095) và sas-camara (:8085) với một OAuth client lab:**

```bash
CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["authorization_code","urn:openid:params:grant-type:ciba"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","number-verification:device-phone-number:read","sim-swap:check","sim-swap:retrieve-date"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'
(cd dist-ecs && exec ./run.sh > logs/lab.log 2>&1) &
(cd dist && SAS_JAVA_OPTS="-Dsas.oauth.clients-json=$CLIENTS" exec ./run.sh > logs/lab.log 2>&1) &
for p in 8095 8085; do for i in $(seq 1 60); do curl -fs -o /dev/null http://127.0.0.1:$p/jwks.json && break; sleep 1; done; done
```
→ `SAS_JAVA_OPTS=-D…` có tác dụng ở đây vì `sas.oauth.clients-json` **không** có trong
`configs/application.properties`; key nào có trong file thì file thắng (§8).

**A2 — JWKS của sas-ecs (khóa ký operator token):**

```bash
curl -s http://127.0.0.1:8095/jwks.json | jq -c '.keys[]|{kid,use,alg,has_d:has("d")}'
```
→ kỳ vọng: một khóa `use:"sig"`, `alg:"ES256"`, `has_d:false`.

**A3 — sas-ecs cấp operator token** (lab: `/entitlement/issue` mở, thay cho một EAP-AKA
hoàn tất ở AAA):

```bash
TOK=$(curl -s -X POST http://127.0.0.1:8095/entitlement/issue -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' | jq -r .token)
awk -F. '{print NF}' <<<"$TOK"
```
→ kỳ vọng: `5` — token là **JWE** (ECDH-ES/A256GCM) chứa JWS ES256; số điện thoại không
đọc được khi token đi qua điện thoại/app.

**A4 — JWKS của sas-camara:**

```bash
curl -s http://127.0.0.1:8085/jwks.json | jq -c '.keys[]|{kid,use,alg,has_d:has("d")}'
```
→ kỳ vọng: khóa `use:"sig"` (ES256, ký access/ID token) **và** khóa `use:"enc"`
(ECDH-ES — ECS mã hóa operator token tới khóa này); không khóa nào có `d`.

**A5 — Wi-Fi `/verify` bằng operator token:**

```bash
curl -s -X POST http://127.0.0.1:8085/camara/number-verification/v2/verify \
  -H "Authorization: Bearer operatortoken:$TOK" -H 'X-Sas-Access-Tech: WIFI' \
  -H 'Content-Type: application/json' -H 'x-correlator: demo-wifi-1' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `{"devicePhoneNumberVerified":true}`. sas-camara giải mã JWE, tải khóa ký từ
`http://127.0.0.1:8095/jwks.json` (cache 300 s), verify ES256/aud/exp/typ và ghi `jti`
vào ledger `sas_operator_token_jti`.

**A6 — replay cùng token:** chạy lại lệnh A5 → kỳ vọng `401`
`{"code":"UNAUTHENTICATED","message":"operator token is invalid, expired or already used"}`.

**A7 — CIBA với operator token → access token ES256 → SIM Swap:**

```bash
TOK2=$(curl -s -X POST http://127.0.0.1:8095/entitlement/issue -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' | jq -r .token)
BC=$(curl -s -X POST http://127.0.0.1:8085/bc-authorize --data-urlencode "login_hint=operatortoken:$TOK2" \
  --data-urlencode 'scope=dpv:FraudPreventionAndDetection sim-swap:check' --data-urlencode client_id=bank-lab)
AT=$(curl -s -X POST http://127.0.0.1:8085/token --data-urlencode grant_type=urn:openid:params:grant-type:ciba \
  --data-urlencode auth_req_id=$(jq -r .auth_req_id <<<"$BC") --data-urlencode client_id=bank-lab | jq -r .access_token)
cut -d. -f1 <<<"$AT" | base64 -d 2>/dev/null; echo
curl -s -X POST http://127.0.0.1:8085/camara/sim-swap/v2/check -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -H 'x-correlator: demo-ss-1' -d '{"phoneNumber":"+251911111111","maxAge":240}'
```
→ kỳ vọng: header `{"kid":"…","typ":"at+jwt","alg":"ES256"}` và `{"swapped":false}`.
Gửi lại `/bc-authorize` với cùng `TOK2` → `403 access_denied` (token đã tiêu).

**A8 — NV Authorization Code + PKCE (network authentication):**

```bash
V=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk     # code_verifier (RFC 7636 ví dụ)
C=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM     # = BASE64URL(SHA256(V))
LOC=$(curl -s -o /dev/null -w '%{redirect_url}' -G http://127.0.0.1:8085/authorize \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  --data-urlencode response_type=code --data-urlencode client_id=bank-lab \
  --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode 'scope=openid dpv:FraudPreventionAndDetection number-verification:verify' \
  --data-urlencode state=s1 --data-urlencode nonce=n1 \
  --data-urlencode code_challenge=$C --data-urlencode code_challenge_method=S256)
echo "$LOC"
CODE=$(sed -E 's/.*[?&]code=([^&]+).*/\1/' <<<"$LOC")
TR=$(curl -s -X POST http://127.0.0.1:8085/token --data-urlencode grant_type=authorization_code \
  --data-urlencode code=$CODE --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode code_verifier=$V --data-urlencode client_id=bank-lab)
jq -c 'keys' <<<"$TR"
NV=$(jq -r .access_token <<<"$TR")
curl -s -X POST http://127.0.0.1:8085/camara/number-verification/v2/verify \
  -H "Authorization: Bearer $NV" -H 'Content-Type: application/json' -H 'x-correlator: demo-nv-1' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `https://bank.example/cb?code=…&state=s1`;
`["access_token","expires_in","id_token","scope","token_type"]`;
`{"devicePhoneNumberVerified":true}`. Payload access token: `iss=http://127.0.0.1:8085`,
`aud=http://127.0.0.1:8085/camara`, `amr=["mno"]`, `exp-iat=300`. ID token: `aud=bank-lab`,
`nonce=n1`, `sub` **pairwise** (không phải số điện thoại).

> `X-Sas-Src-Ip/Port` chỉ được tin vì lab bật `sas.oauth.authorize.trust-src-header=true`.
> Prod: SAS lấy địa chỉ nguồn **của kết nối**, trừ khi edge proxy ghi đè header. Thiếu
> port (`X-Sas-Src-Port`) ⇒ `access_denied … NO_BINDING` — đúng quy tắc CGNAT IP+port+ts.

**A9 — các nhánh fail-closed của `/authorize` + `/token`:**

| Lệnh | Kỳ vọng |
|---|---|
| chạy lại `/token` với cùng `CODE` | `400 invalid_grant` "code is invalid, expired or already used" |
| `/authorize` thiếu `code_challenge` | `302 …?error=invalid_request&error_description=PKCE+with+code_challenge_method%3DS256+is+required&state=…` |
| `/authorize` với `redirect_uri=https://evil.example/cb` | `400` JSON `redirect_uri is not registered for this client` — **không** redirect |
| scope `number-verification:device-phone-number:read` → token → `GET /camara/number-verification/v2/device-phone-number` | `200 {"devicePhoneNumber":"+251911111111"}` |

**A10 — cách ly surface giữa hai binary:**

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8085/entitlement/status      # 404
curl -s -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:8085/ts43/challenge?imsi=1' # 404
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8095/token            # 404
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8095/.well-known/openid-configuration  # 404
curl -s http://127.0.0.1:8085/.well-known/openid-configuration | jq -c '{authorization_endpoint,code_challenge_methods_supported,id_token_signing_alg_values_supported}'
```
→ kỳ vọng: bốn `404`, rồi `{"authorization_endpoint":"http://127.0.0.1:8085/authorize","code_challenge_methods_supported":["S256"],"id_token_signing_alg_values_supported":["ES256"]}`.

**A11 — dọn dẹp:**

```bash
pkill -f "$PWD/dist/quarkus-run.jar"; pkill -f "$PWD/dist-ecs/quarkus-run.jar"
sleep 2; pgrep -fa quarkus-run.jar || echo "all SAS JVMs stopped"
```

### 0a.1 Tự động + pcap

```bash
mvn -o -q package -DskipTests -Dquarkus.package.jar.type=fast-jar -Dquarkus.profile=lab
scripts/split-smoke.sh      # A2–A10 tự động, không cần root; pcap: target/split-smoke/split-smoke.pcap
```

Xem pcap:

```bash
P=target/split-smoke/split-smoke.pcap
tshark -r $P -Y http.request -T fields -e tcp.dstport -e http.request.method -e http.request.uri
tshark -r $P -Y 'http.response' -T fields -e tcp.srcport -e http.response.code
```
→ kỳ vọng: sas-camara gọi `GET /jwks.json` tới :8095 **đúng một lần**, ngay sau
`/bc-authorize` đầu tiên (các lần sau lấy từ cache); không có response `101`.

## 0b. Demo manual từng bước — Diameter (S6a/SWx/Gx) + CAMARA (verified 2026-09-03 · chân TS.43 cập nhật cho hai binary 2026-10-01)

> **Trước M3.** Runbook này khởi động sas-camara với `-Dsas.transport.*` (S6a/SWx/Gx); từ M3 các transport đó cấu hình trên **sas-ecs** và đi qua exposure API (§00). Các kịch bản signalling S6a/Gx chưa được chạy lại trên kiến trúc mới.

Artifact đã build sẵn: `dist/` (sas-camara), `dist-ecs/` (sas-ecs — chỉ cần cho Bước 12)
+ `sas-diameter-testapp/target/sas-diameter-testapp.jar`.
`dist/configs/application.properties` **đã cấu hình sẵn** cho demo Diameter
(`s6a=swx=corsac`, `resolver=sd` :3870, `swx.peer-port=3869`, entitlement HMAC secret).
Chạy TUẦN TỰ từng bước, mỗi bước 1 lệnh + 1 kết quả kiểm tra.

> ⚠️ **Bẫy config (đã kiểm chứng):** `dist/run.sh` nạp `configs/application.properties`
> qua `quarkus.config.locations` — với **key có trong file**, file thắng cả `-D` system
> properties. Đổi transport phải sửa **file config**; `SAS_JAVA_OPTS=-D…` chỉ có tác dụng
> với key **không** có trong file (vd `sas.oauth.clients-json` ở §0a). Chạy
> `java -jar dist/quarkus-run.jar` trực tiếp thì `-D` hoạt động bình thường.

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

**Bước 4b — sas-ecs (chỉ cần cho Bước 12 — entitlement server tách riêng từ 2026-10-01):**

```bash
(cd dist-ecs && ./run.sh > logs/lab.log 2>&1 &)
for i in $(seq 1 60); do curl -fs -o /dev/null http://localhost:8095/jwks.json && break; sleep 1; done
curl -s http://localhost:8095/entitlement/status
```
→ kỳ vọng: `{"enabled":true,…}`. sas-camara (Bước 4) tin sas-ecs qua
`sas.operator-token.issuers-json` (lab: `http://127.0.0.1:8095`).

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

**Bước 12 — ④ TS.43 Wi-Fi: sas-ecs cấp operator token:**

```bash
curl -s -X POST http://localhost:8095/entitlement/issue \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}'
```
→ kỳ vọng: `{"token":"eyJ…","expiresInSeconds":300}` — token là JWE 5 phần (số điện
thoại bị mã hóa tới khóa `enc` của sas-camara). Copy token vào biến:
`TOKEN="<dán token>"`

**Bước 13 — ④ verify bằng operator token (không cần cellular):**

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: d6' -d '{}'
```
→ kỳ vọng: `{"devicePhoneNumberVerified":true}` (SWx MAR→MAA + SAR→SAA trên :3869 — UI `:18086`).

> Sau khi tách binary (2026-10-01): đường này đã chạy lại với SWx **memory**
> (`{"devicePhoneNumberVerified":true}`, replay `401`). Với SWx **corsac** tới :3869 thì
> **chưa chạy lại**: operator token mới không mang IMSI nên MAR đi theo MSISDN.

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

**Bước 16 — ⑥ CAMARA SimSwap v2.1.0 `/sim-swap/v2/check` (2-legged, trên SAS của Bước 4):**

```bash
curl -s -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw1' -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `{"swapped":false}` — seed pilot đổi SIM cách đây 10 ngày, tức nằm ngoài cửa sổ
mặc định `maxAge=240` giờ. Evidence là cùng binding age read-only mà Verifier chấm
`notSimSwapped` (MAP `lastUpdateLocation` → Sh UDR → SWx); lệnh này không cần trao đổi
Diameter nào.

**Bước 17 — cùng thuê bao, cửa sổ rộng hơn (`maxAge` tính theo giờ, spec 1..2400):**

```bash
curl -s -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw2' -d '{"phoneNumber":"+251911111111","maxAge":2400}'
```
→ kỳ vọng: `{"swapped":true}` (10 ngày ≤ 100 ngày).

**Bước 18 — `/sim-swap/v2/retrieve-date`:**

```bash
curl -s -X POST http://localhost:8085/sim-swap/v2/retrieve-date \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw3' -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `{"latestSimChange":"<thời điểm RFC 3339, ~10 ngày trước>"}` (không có
`monitoredPeriod` khi SAS chưa cấu hình cửa sổ giám sát).

**Bước 19 — fail-closed + mã lỗi CAMARA (không có evidence không bao giờ là "not swapped"):**

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw4' -d '{"phoneNumber":"+251999999999"}'
```
→ kỳ vọng: `404` `{"status":404,"code":"IDENTIFIER_NOT_FOUND",…}`

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw5' -d '{}'
```
→ kỳ vọng: `422` `MISSING_IDENTIFIER` (gọi 2-legged mà không có `phoneNumber`)

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw6' -d '{"phoneNumber":"+251911111111","maxAge":0}'
```
→ kỳ vọng: `400` `OUT_OF_RANGE` (spec 1..2400 — không bao giờ tự clamp)

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'x-correlator: sw7' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ kỳ vọng: `401` `UNAUTHENTICATED` (không có header `Authorization`)

**Bước 20 — ⑦ CAMARA OneTimePasswordSMS v1.1.1 `/send-code` (nhánh FALLBACK):**

```bash
curl -s -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp1' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
```
→ kỳ vọng: `200` với body `{"authenticationId":"<uuid>"}` (UUID đúng 36 ký tự như schema
spec cho phép). OTP **không bao giờ** nằm trong response — `message` là template bắt buộc
chứa `{{code}}` (≤160 ký tự). SAS chỉ orchestrate: production gửi SMS qua SMSC/SGd của
chính operator (Restlink không bán SMS wholesale).

**Bước 21 — đọc OTP từ sender lab (chỉ ghi log, không gửi gì):**

```bash
grep 'LAB-SMS' /tmp/sas-demo.log | tail -1
```
→ kỳ vọng: `WARN LabLogSmsDelivery - [SAS][LAB-SMS — NOT SENT] to=+251****11 chars=28
text="921540 is your Restlink code"` — đặt `OTP="921540"`, `AID="<authenticationId>"`.
OTP rõ trong log chính là lý do profile prod TẮT surface này và preflight `PRO-29` từ
chối sender lab.

**Bước 22 — `/validate-code` sai rồi đúng:**

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp2' -d "{\"authenticationId\":\"$AID\",\"code\":\"000000\"}"
```
→ kỳ vọng: `400` `ONE_TIME_PASSWORD_SMS.INVALID_OTP`

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp3' -d "{\"authenticationId\":\"$AID\",\"code\":\"$OTP\"}"
```
→ kỳ vọng: `204` (không body). Chạy lại → `404 NOT_FOUND` (một OTP chỉ validate một lần).

**Bước 23 — đốt attempt: 3 code sai giết attempt:**

```bash
for i in 1 2 3; do curl -s -o /dev/null -w '%{http_code} ' -X POST \
  http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H "x-correlator: burn$i" -d "{\"authenticationId\":\"$AID2\",\"code\":\"000000\"}"; done; echo
```
→ kỳ vọng: `400 400 400` — lần thứ ba là `ONE_TIME_PASSWORD_SMS.VERIFICATION_FAILED`, và
sau đó nhập **đúng** code cũng vẫn trả `VERIFICATION_FAILED`.

**Bước 24 — ma trận fail-closed của OTP:**

```bash
# rate limit: lần gửi thứ 4 tới CÙNG một số trong sas.otp.rate-window-seconds
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' -H 'x-correlator: otp4' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
```
→ kỳ vọng: `403` `ONE_TIME_PASSWORD_SMS.MAX_OTP_CODES_EXCEEDED` (số khác không bị ảnh
hưởng; restart xóa cửa sổ in-memory)

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' -H 'x-correlator: otp5' \
  -d '{"phoneNumber":"+251933333333","message":"no code label here"}'
```
→ kỳ vọng: `400` `INVALID_ARGUMENT` (template phải có `{{code}}`; >160 ký tự cũng vậy).
Không có `Authorization` → `401`; `authenticationId` lạ → `404 NOT_FOUND`;
`sas.otp.enabled=false` (hoặc thiếu key trong `dist/configs/application.properties`)
→ `404 NOT_FOUND` ở cả hai endpoint; không có delivery route → `500 INTERNAL_ERROR` và
**không** cấp `authenticationId`.

**Bước 25 — ⑧ CDR/audit cho các lệnh SimSwap + OTP:**

Mỗi request ⑥/⑦ ghi một dòng CDR API theo `x-correlator` vào cả file CSV bền `SAS_CDR`
lẫn ledger admin trên DB. Với `dist/run.sh`, file CSV là `dist/logs/sas.cdr`.

```bash
grep -E 'swcdr1|otpcdr1|otpcdr2' dist/logs/sas.cdr
```
→ kỳ vọng:
```text
…,swcdr1,+251****11,SIMSWAP,COMPLETED,action=check maxAgeHours=240 swapped=false http=200,http,http,lab
…,otpcdr1,+251****11,OTP,COMPLETED,action=send-code messageChars=35 delivery=log result=SENT authenticationId=<uuid> ttlSeconds=300 http=200,http,http,lab
…,otpcdr2,+251****11,OTP,FAILED,action=validate-code authenticationId=<uuid> attempts=1 maxAttempts=3 result=INVALID http=400 code=ONE_TIME_PASSWORD_SMS.INVALID_OTP,http,http,lab
```

Các dòng này cũng hiện ở `http://localhost:8085/admin/cdr` (seed lab `admin/admin`).
Dashboard merge hàng vừa enqueue với hàng đã persist nên restart vẫn thấy lịch sử.

Bất biến privacy:

- MSISDN được mask (`+251****11`) trước khi tới CDR port; không có MSISDN/IMSI thô.
- Không có OTP plaintext và nội dung SMS; chỉ ghi `messageChars` và `authenticationId`.
- 2xx → `COMPLETED`; 4xx/5xx → `FAILED`, kèm `http=` và `code=` của CAMARA.
- `x-correlator` rỗng được thay bằng UUID sinh ra, nhưng các lần smoke lặp lại nên dùng
  correlator mới vì `sas_cdr_session.correlation_id` là unique.

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
→ kỳ vọng: `Listening on: http://0.0.0.0:8085`. Từ 2026-10-01 access token là **ES256
`at+jwt`** (lab `sas.oauth.signing-alg=ES256`, khóa ngẫu nhiên mỗi lần chạy) và được
verify qua `/jwks.json`; `-Dsas.oauth.secret=k1` không còn cần nhưng vô hại.

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

**Bước C7 — SIM Swap trên đường 3-legged (định danh lấy từ token, body rỗng):**

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
→ kỳ vọng: `{"swapped":false}` + `200`. Chạy lại curl cuối → `401` (single-use).
Với token đã bound, gửi `{"phoneNumber":"+251911111111"}` → `422
UNNECESSARY_IDENTIFIER`; token `number-verification:verify` → `403 PERMISSION_DENIED`
(sai scope family); `scope=sim-swap:retrieve-date` dùng được với
`/sim-swap/v2/retrieve-date` và body rỗng.

**Bước C8 — OTP SMS trên đường 3-legged (một scope cho cả hai operation):**

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
→ kỳ vọng: `200` + `authenticationId` khi `phoneNumber` **trùng** binding của token. Số khác
→ `403 PERMISSION_DENIED` ("phoneNumber does not match the access-token binding") và OTP
không hề được sinh; token `sim-swap:check` → `403 PERMISSION_DENIED` (sai scope family);
dùng lại token → `401` (single-use, nên send-code và validate-code mỗi cái cần token riêng).

**Bước C9 — dọn dẹp:**

```bash
for p in $(pgrep -x java); do kill $p; done
pgrep -x java || echo "all JVMs stopped"
```

### Bảng kỳ vọng tóm tắt (§0b)

| # | Bước | Signalling quan sát | Kết quả kỳ vọng |
|---|------|--------------------|-----------------|
| 6 | ① verify LTE (S6a corsac) | CCA 2001 + ULA 2001, không có Sh UDR | `false` + `SIM_SWAP_SUSPECT` (fail-closed đúng) |
| 7 | ①b assurance detail | như trên | `FALLBACK`, score 70 < threshold 80 |
| 8 | discovery | CCA 2001 | `{"devicePhoneNumber":"+251911111111"}` |
| 9 | ② thuê bao detach | ULA **5421** | `false` |
| 10 | ③ thuê bao barring | ULA 2001 + ODB status | `false` |
| 12–13 | ④ verify bằng operator token (sas-ecs cấp, sas-camara đổi) | lab SWx: MAA items≥1 + SAA 2001 | `true` |
| 14 | ⑤ replay token | — (chặn trước Diameter) | `401` |
| 16 | ⑥ SIM Swap `/check` (`maxAge=240` mặc định) | — (evidence read-only) | `{"swapped":false}` |
| 17 | ⑥ SIM Swap `/check` `maxAge=2400` | — | `{"swapped":true}` |
| 18 | ⑥ SIM Swap `/retrieve-date` | — | `{"latestSimChange":"…Z"}` |
| 19 | ⑥ số lạ · thiếu `phoneNumber` · `maxAge=0` · không token | — | `404` · `422` · `400` · `401` |
| C3–C5 | CIBA money-loop (khớp số) | resolver BOUND | `true`, `APPROVE`, score 100 |
| C5 | CIBA sai số claimed | — | `false` |
| C6 | CIBA dùng lại token | — | `401` |
| C7 | SIM Swap 3-legged (body rỗng) | resolver BOUND | `{"swapped":false}`; dùng lại → `401`; `phoneNumber` trong body → `422` |
| 20 | ⑦ OTP `/send-code` | — (không signalling; sender lab ghi log) | `200 {"authenticationId":"<uuid>"}` |
| 21 | ⑦ đọc OTP từ log lab | — | `[SAS][LAB-SMS — NOT SENT] … text="<6 số> …"` |
| 22 | ⑦ `/validate-code` sai · đúng · replay | — | `400 INVALID_OTP` · `204` · `404` |
| 23 | ⑦ 3 code sai đốt attempt | — | `400 400 400`, lần ba = `VERIFICATION_FAILED` |
| 24 | ⑦ gửi lần 4 cho một số · template sai · không token | — | `403 MAX_OTP_CODES_EXCEEDED` · `400` · `401` |
| C8 | OTP 3-legged (số bound) | resolver BOUND | `200`; số khác → `403`; sai scope → `403`; dùng lại → `401` |
| ICM | `/token` CIBA có `scope` · replay `auth_req_id` · client assertion sai/hết hạn/dùng lại | — (manual §0e) | `400 invalid_request` · `400 invalid_grant` · `401 invalid_client` |
| ICM | `auth_req_id` hết hạn · pending binding của client khác | — (unit test) | `400 expired_token` · `400 invalid_grant` |
| ICM | `client_credentials` gọi NV `/verify` | — (manual §0e) | token 2-legged `token_use=client`; `/verify` → `403 NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` |
| ICM | JWT bearer `tel:` · thiếu purpose · `sub` lạ · `scope` ở form | — (manual §0e) | token user-bound · `400 invalid_scope` · `400 invalid_grant` · `400 invalid_request` |
| ICM | JWT bearer `operatortoken:` | — (unit test; CIBA/Wi-Fi với operator token chạy tay ở §0a) | token user-bound |
| — | LTE happy path trên memory transport (tùy chọn) | backend pilot | `true`, score 100 |

## 0d. GSMA Open Gateway smoke / mock ↔ SAS local (verified 2026-09-10)

Công cụ lab:

- `scripts/gsma_mock_server.py` — Open Gateway giả lập trên `127.0.0.1:18099`.
- `scripts/gsma_camara_smoke.py` — smoke/so sánh CAMARA cho `local`, `gsma` hoặc `compare`.
- `scripts/gsma.env.template` — copy ra ngoài repo rồi điền giá trị sandbox thật.

Script chỉ thêm header `X-Sas-*` khi `--target local`; không bao giờ gửi chúng tới GSMA.
Report che số điện thoại và token. Không commit file env đã điền hay report live.

> Từ 2026-10-01 sas-camara có đủ luồng mà sandbox dùng cho **NV** (Authorization Code +
> PKCE, §0a A8) và **SIM Swap** (CIBA, §0a A7), token ES256 kèm `/jwks.json`. Kết nối
> sandbox thật vẫn cần onboarding phía GSMA (endpoint HTTPS công khai, đăng ký client,
> map số test) — xem `docs/design/sas-two-binary-architecture.md` §10.

### So sánh mock ↔ local (không cần sandbox thật)

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

Kỳ vọng:

```text
[simswap-check] MATCH
[simswap-retrieve-date] MATCH
[cdr-privacy] OK
COMPARE_SUMMARY MATCH=2 OK=1
EXIT=0
```

`cdr-privacy` chỉ chạy local: nó xác nhận CDR/report đã che MSISDN và không chứa OTP
plaintext.

### Chỉ SAS local

```bash
python3 scripts/gsma_camara_smoke.py --target local \
  --local-base http://127.0.0.1:8085 \
  --tests nv,simswap,otp,cdr \
  --report /tmp/sas_local_smoke.json
```

Kỳ vọng exit `0` với dist lab. NV có thể trả `devicePhoneNumberVerified:false` khi không có
evidence binding; bước validate OTP đọc code từ log lab.

### Sandbox GSMA thật (chờ giá trị lấy từ trình duyệt)

Cloudflare chặn curl/automation headless. Copy template ra ngoài repo và điền từ một phiên
trình duyệt thật:

```bash
cp scripts/gsma.env.template /tmp/gsma.env
```

Giá trị bắt buộc: `GSMA_API_ROOT`, `GSMA_TOKEN_URL`, `GSMA_CLIENT_ID`,
`GSMA_CLIENT_SECRET` hoặc token cấp sẵn, `GSMA_TEST_NUMBER`, và các scope đã bật. NV
thường cần token 3-legged gắn user (`GSMA_NV_TOKEN`, `GSMA_DISCOVERY_TOKEN`); với token đã
bound thì body request để `{}`.

```bash
python3 scripts/gsma_camara_smoke.py --target gsma --env-file /tmp/gsma.env \
  --tests token,simswap,nv --timeout 20 --report /tmp/gsma_live.json
```

Không chạy OTP live trừ khi sandbox xác nhận rõ sẽ không gửi SMS thật; script từ chối OTP
GSMA nếu thiếu `--allow-send`.

## 0e. CAMARA ICM OAuth Phase 3 — manual runbook (verified 2026-09-11 · chạy lại 2026-10-01 với token ES256)

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
→ kỳ vọng: `791` tests pass · `38/38` gates · H24 `10` + H25 `11` + H28 `8` mutations caught ·
`45/45` kịch bản preflight bị bắt.

**P2 — build/package dist:**

```bash
/usr/bin/mvn -o -B clean package -DskipTests -Dquarkus.profile=lab
./scripts/package-dist.sh
```
→ kỳ vọng: có `dist/quarkus-run.jar` (và `dist-ecs/` nếu đóng gói `all`).

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
→ kỳ vọng: `token_endpoint_auth_methods_supported` = `["private_key_jwt"]` (client auth bắt buộc);
`grant_types_supported` chứa `authorization_code`, `urn:openid:params:grant-type:ciba`,
`client_credentials`, `urn:ietf:params:oauth:grant-type:jwt-bearer`.

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
→ kỳ vọng: payload có `token_use: user`, `phone_number: +251911111111`, `aud` (lab
`http://127.0.0.1:8085/camara`); header token là `{"typ":"at+jwt","alg":"ES256","kid":…}`
— access token ký ES256 từ 2026-10-01, verify qua `/jwks.json`.

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
Path `operatortoken:` cần một operator token của sas-ecs: CIBA/Wi-Fi với operator token
chạy tay ở §0a (A5–A7); JWT bearer `operatortoken:` vẫn là unit test; JWT bearer `tel:` là manual step.

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

## 0f. TS.43 entitlement — EAP-AKA qua MAP trên sas-ecs (verified 2026-10-01, có pcap)

Track **khác** §2–§4: không có Diameter. **sas-ecs** là EAP server (D6 **Shape S**), lấy
vector từ **HLR qua MAP `sendAuthenticationInfo`** (SSN 6) và xác nhận số bằng **MAP
`sendImsi`** (SSN 3 — TS 29.002 đặt `sendImsi` trên SSN OAM, *không* phải SSN HLR), trên
**association SCTP riêng** của nó (`ss7-ecs.json`, local port 2907). Token cấp ra là
operator token (JWE) mà **sas-camara** đổi đúng một lần.

> ⚠️ **Blocker P1 còn mở:** codec EAP-AKA chưa đúng RFC 4187 (xem
> `docs/design/sas-two-binary-architecture.md` §9). Lab card dùng chung codec với server,
> nên track này chứng minh **các chân MAP và đường token**, không chứng minh máy thật
> tương thích.

```
UE (lab card)          sas-ecs :8095                          HLR simulator (jSS7) :2906 / ctrl :8087
  GET /ts43/challenge ─► EntitlementSbb
                          │ MAP SAI (TC-BEGIN, SSN 6) ────────► sendAuthenticationInfo
                          │◄──────────────────── TC-END ─────── UMTS quintet (RAND,XRES,CK,IK,AUTN)
  ◄── EAP-Request/AKA-Challenge
  POST /ts43/respond ──► AT_MAC → AT_RES
                          │ MAP SendIMSI (SSN 3) ─────────────► sendImsi(+251911111111)
                          │◄──────────────────────────────────── IMSI của số
  ◄── operator token (JWE)
bank ── POST /camara/number-verification/v2/verify  (Bearer operatortoken:…, X-Sas-Access-Tech: WIFI) ──► sas-camara :8085
```

### 0f.1 Build + 3 tiến trình

```bash
export JAVA_HOME="$HOME/.local/share/mise/installs/java/zulu-25"; export PATH="$JAVA_HOME/bin:$PATH"
(cd sas-jss7-testapp && /usr/bin/mvn -o -q package -DskipTests)     # simulator (build riêng)
./scripts/package-dist.sh all                                       # dist/ + dist-ecs/

# (1) HLR simulator — SCTP :2906, peer = association của sas-ecs (2907), control :8087.
#     Chạy trong thư mục riêng: jSS7 ghi file state *.xml vào CWD.
mkdir -p /tmp/hlr-sim && (cd /tmp/hlr-sim && java --add-modules jdk.sctp \
  -jar "$OLDPWD/sas-jss7-testapp/target/sas-jss7-testapp.jar" \
  --listen-port 2906 --peer-port 2907 --http-port 8087) &

# (2) sas-ecs — MAP SAI cho vector, MAP SendIMSI cho số
(cd dist-ecs && SAS_TRANSPORT_AUTHVECTOR=map SAS_BINDING_SOURCE_ORDER=map-smi \
   SAS_TRANSPORT_JSS7_CONFIG=$PWD/configs/ss7-ecs.json ./run.sh) &

# (3) sas-camara — đổi operator token (cấu hình sạch, xem ghi chú nâng cấp ở §0a A0)
(cd dist && ./run.sh) &
```

Log sas-ecs phải có (nếu không, xem §8):

```
[map-verifier] jSS7 stack started — HLR GT=251911000000 (PSI+SAI, no ATI)
[ECS] auth-vector transport = MAP SAI (TS 29.002)
[binding] SendIMSI ready on the shared jSS7 stack — HLR GT=251911000000
[ECS] binding source map-smi = MAP SendIMSI (TS 29.002)
=== sas-ecs ready — EntitlementSbb bound to Ts43RequestEvent, token format=operator-jwe ===
```

### 0f.2 Chạy demo (1 lệnh)

```bash
./scripts/ts43-lab-demo.sh http://127.0.0.1:8095 http://127.0.0.1:8087 \
    655010000000001 +251911111111 http://127.0.0.1:8085
```

Kết quả đã verify (2026-10-01):

```
── UE → SAS    GET /ts43/challenge      reqId=ts43-…  challenge=AQEAKRcB…
── HLR sim     …                        {"subscribers":[{"imsi":"655010000000001","msisdn":"+251911111111","attached":true,"vectors":1}],…}
── UE          EAP-Response/AKA-Challenge (AT_MAC + AT_RES)
── UE → SAS    POST /ts43/respond       token=eyJlcGsiOnsia3R5IjoiRUMi…      (JWE)
── bank → CAMARA  POST /camara/number-verification/v2/verify (Wi-Fi path, operator token)
                                        {"devicePhoneNumberVerified":true}
── bank → CAMARA  replay the same operator token (single use)
                                        HTTP 401 (expected 401)
── DEMO OK — EAP-AKA on sas-ecs (MAP SAI + SendIMSI), operator token redeemed once on sas-camara
```

Tham số thứ 5 `-` dừng sau khi có token (khi không chạy sas-camara).

### 0f.3 Xem MAP trên wire — pcap

```bash
scripts/ecs-map-smoke.sh      # tự chạy (1)+(2)+(3) + demo + ma trận 0f.4 trong user netns, không cần root
P=target/ecs-map-smoke/ecs-map-smoke.pcap
tshark -r $P -q -z io,phs                 # sctp → m3ua → sccp → tcap → gsm_map
tshark -r $P -Y gsm_map                   # từng invoke/returnResult
curl -s http://127.0.0.1:8087/messages    # log của simulator (khi chạy tay)
```

Kỳ vọng (demo, đúng 2 dialog theo thứ tự):

```
1 → 2  GSM MAP  invoke sendAuthenticationInfo
2 → 1  GSM MAP  returnResultLast sendAuthenticationInfo
1 → 2  GSM MAP  invoke sendIMSI 251911111111
2 → 1  GSM MAP  returnResultLast sendIMSI
```
Log simulator: `OUT sendAuthenticationInfo | ReturnResultLast | 1 quintuplet(s) imsi=655****01`,
rồi `OUT sendImsi | ReturnResultLast | msisdn=251911111111 imsi=655****01`. Không có ATI,
không có SRI-SM (gate H25 cấm cả hai trên đường entitlement).

### 0f.4 Fail-closed (tự động trong `ecs-map-smoke.sh`, verified 2026-10-01 trên sas-ecs)

```bash
ECS=http://127.0.0.1:8095
# (a) SIM khác trả lời → AT_MAC mismatch; KHÔNG mở dialog SendIMSI
CJ=$(curl -s "$ECS/ts43/challenge?imsi=655010000000001&msisdn=%2B251911111111")
R=$(java -cp sas-entitlement/target/classes et.restlink.sas.entitlement.lab.LabSimCardMain \
     655010000000002 "$(jq -r .challenge <<<"$CJ")")
curl -s -X POST $ECS/ts43/respond -H 'Content-Type: application/json' \
  -d "{\"reqId\":\"$(jq -r .reqId <<<"$CJ")\",\"imsi\":\"655010000000001\",\"msisdn\":\"+251911111111\",\"response\":\"$R\"}"
# → {"code":"EAP_FAILED","message":"AT_MAC mismatch"}

# (b1) POST lại cùng reqId → trả lại đúng câu trả lời đã ghi (idempotent, không dialog mới)
# (b2) reqId không có challenge → {"code":"EAP_FAILED","message":"no outstanding challenge for this reqId"}

# (c) IMSI lạ → không có vector
curl -s "$ECS/ts43/challenge?imsi=655010000009999&msisdn=%2B251911111111"
# → {"code":"ENTITLEMENT_UNAVAILABLE","message":"no authentication vector available"}

# (d) claim số HLR không biết → EAP OK, SỐ bị từ chối, không có token
#     (pcap: invoke sendIMSI 251900000000 → returnError)
# → {"code":"EAP_FAILED","message":"network did not confirm the claimed number"}

# (e) starve SAI → chết ngay ở Challenge
curl -X POST -d '{"vectors":0}' http://127.0.0.1:8087/state    # → no authentication vector available
curl -X POST -d '{"vectors":1}' http://127.0.0.1:8087/state

# (f) detach → HLR trả returnError cho SAI
curl -X POST -d '{"attached":false}' http://127.0.0.1:8087/state # → no authentication vector available
curl -X POST -d '{"attached":true}' http://127.0.0.1:8087/state
```

| Input | Kết quả (sas-ecs) | MAP trên wire |
|---|---|---|
| IMSI + số đúng | token (JWE); CAMARA đổi → `true`, replay → `401` | SAI → result, SendIMSI → result |
| SIM khác trả lời | `AT_MAC mismatch` | chỉ SAI, **không** SendIMSI |
| cùng `reqId` lần nữa | câu trả lời đã ghi | không có dialog mới |
| `reqId` không có challenge | `no outstanding challenge for this reqId` | — |
| IMSI không có trong HLR | `no authentication vector available` | SAI → returnError |
| số HLR không biết | `network did not confirm the claimed number`, không token | SAI → result, SendIMSI → returnError |
| `vectors=0` | `no authentication vector available` | SAI → returnError |
| `attached=false` | `no authentication vector available` | SAI → returnError |

### 0f.5 Test tự động cho track này

```bash
mvn -o test -pl sas-ecs-host,sas-operator-adapter -am \
    -Dtest='EntitlementSessionsTest,LabSimAkaCardTest,SubscriberBindingResourceAdaptorTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
```

`LabSimAkaCardTest` chỉ chứng minh **card lab và server nhất quán với nhau** (cùng một
codec) — không chứng minh tuân thủ RFC 4187 (blocker P1 ở trên).

## 1. Build

> **Trước M3** cho §1–§4: transport giờ thuộc sas-ecs (§00.2). Các bước chỉ liên quan CAMARA (SIM Swap/OTP, CIBA) vẫn áp dụng cho sas-camara.

```bash
(cd sas-diameter-testapp && /usr/bin/mvn -B clean package)      # testapp jar ~26 MB
(cd sas-jss7-testapp && /usr/bin/mvn -B -o package -DskipTests) # HLR simulator (track §0f)
/usr/bin/mvn -B clean package -DskipTests -Dquarkus.profile=lab  # root reactor 7 module →
                                                                 #   sas-host/target/quarkus-app     (sas-camara)
                                                                 #   sas-ecs-host/target/quarkus-app (sas-ecs)
```

> **BẮT BUỘC `-Dquarkus.profile=lab`** cho build hai binary: một số property cố định từ
> build-time (vd `quarkus.datasource.db-kind` của sas-camara) được bake theo profile. Build
> không có cờ này tạo fast-jar **im lặng không boot** (dừng ngay sau
> `MicroSleeContainer started`, log chỉ còn các dòng `Failed to load config value…`).
> `scripts/package-dist.sh` luôn build với cờ này (prod artifact: `-Dquarkus.profile=prod`).

## 2. Chạy 3 instance operator simulator (S6a + SWx + Gx)

```bash
# Instance 1: S6a HSS (Diameter SCTP :3868) + control UI :8086 — cellular (ULR/ULA)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &

# Instance 2: SWx = operator 3GPP AAA ↔ HSS (SCTP :3869) + control UI :18086 — verifier lab
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3869 --web-port 18086 &

# Instance 3: PCRF Gx (SCTP :3870) + control UI :28086 — resolver binding (bắt buộc
# khi chạy sas-camara với -Dsas.transport.resolver=sd ở §3; thiếu nó scenario ① trả NO_BINDING)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3870 --web-port 28086 &
```

> Lab HSS chỉ nhận **1 inbound SCTP association mỗi listen port** → SWx buộc phải tách port
> riêng; sas-camara trỏ qua `-Dsas.transport.diameter.swx.peer-port=3869`. (Lab: RA
> `swxverifier` đóng thế chân operator. Production: sas-camara verify đường Wi-Fi bằng
> **operator token** của entitlement server, freshness qua Sh UDR/SNR, không mở SWx.)

## 3. Chạy sas-camara với corsac transports

```bash
cd sas-host && java \
  -Dsas.transport.s6a=corsac \
  -Dsas.transport.swx=corsac \
  -Dsas.transport.diameter.swx.peer-port=3869 \
  -Dsas.transport.resolver=sd \
  -Dsas.transport.sd.peer-port=3870 \
  -jar target/quarkus-app/quarkus-run.jar &
```

Đợi log `Peer is up for Association [name=s6a-sas…]`, `[name=swx-sas…]` — thường ~15–20 s,
nhưng trên host multi-home có thể tới ~3 phút: simulator chỉ nhận nguồn loopback và từ
chối các lần bắt tay từ IP LAN/IPv6 (log sim: `Received connect request from non
provisioned … address. Closing Channel`) cho tới khi client retry chọn 127.0.0.1.
`sas.transport.resolver=sd` bật PCRF Gx binding probe (:3870 — cần instance 3 ở §2); bỏ
dòng này nếu muốn resolver memory pilot. Lựa chọn resolver: `memory | cgnat | radius | sd`.

Scenario ④ cần thêm **sas-ecs** (entitlement server, :8095):
`(cd sas-ecs-host && java -Dquarkus.profile=lab --add-modules jdk.sctp -jar target/quarkus-app/quarkus-run.jar &)`
— hoặc `dist-ecs/run.sh`. (`-Dsas.entitlement.hmac-secret` cũ không còn cần: token là operator
token JWE.)

## 4. Scenarios

### ① S6a LTE happy path

Resolver seed sẵn `10.20.30.40 → +251911111111` (IMSI `655010000000001`) — với
`resolver=sd` binding này nằm trong Gx BindingRegistry của instance :3870
(`POST http://127.0.0.1:28086/api/binding`). Registry key theo **IP, upsert thay thế**
(một binding/IP): POST lại cùng IP với MSISDN khác sẽ **thay** seed — verify sau đó
resolve sang số mới và thường `false` (lab verifier không biết thuê bao đó).
`AMBIGUOUS_BINDING` trên sd chỉ xảy ra nếu CCA mang ≥2 E.164 phân biệt (lab testapp không
tạo được; gặp ở CGNAT/RADIUS backend khi trùng IP:port:ts).

> ⚠️ **Kỳ vọng thực tế với `s6a=corsac`**: `CorsacS6aVerifierBackend` fail-close chiều
> SIM-swap (lab testapp **không** có Sh UDR handler → freshness không có nguồn) ⇒ scenario
> ①/①b trả `false` / `SIM_SWAP_SUSPECT` dù CCR→CCA và ULR→ULA đều 2001 — đó là fail-closed
> đúng (không phải bug). Muốn ①/①b `true`: chạy sas-camara không kèm
> `-Dsas.transport.s6a=corsac` (memory transport pilot).

CAMARA primary path:

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: t1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
# s6a=corsac → {"devicePhoneNumberVerified":false}   (boolean thuần CAMARA)
#              (CCR→CCA 2001 + ULR→ULA 2001, nhưng thiếu freshness Sh UDR → veto)
# memory transport (không -Dsas.transport.s6a) → {"devicePhoneNumberVerified":true}
```

Assurance detail opt-in (score/factors cho bank tự đánh giá rủi ro) + risk class:

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
#     "fallbackReason":"SIM_SWAP_SUSPECT"}   — CDR vẫn lưu full flow (tra theo reqId)
# memory transport → decision APPROVE, score 100 — threshold theo risk class
```

Discovery (trả số bound; chỉ cần resolver, không cần Verifier):

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

### ④ TS.43 Wi-Fi path — operator token do sas-ecs cấp, sas-camara đổi

> Lab: RA `swxverifier` của sas-camara mở MAR/SAR tới SWx :3869 để đóng thế chân
> 3GPP AAA↔HSS. Production: không mở SWx — định danh đến từ operator token
> (entitlement server đã chạy EAP-AKA), freshness qua **Sh UDR/SNR** read-only.

```bash
TOKEN=$(curl -s -X POST http://localhost:8095/entitlement/issue \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")

curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: t4' -d '{}'
# → true   (lab corsac: MAR→MAA items=1, SAR→SAA trên :3869)
```

> Verify lại 2026-10-01 với SWx **memory** (`true`, replay `401`, xem §0a A5–A6). Với SWx
> **corsac** tới :3869 **chưa chạy lại** sau khi tách binary: token mới không mang IMSI nên
> MAR đi theo MSISDN.

### ⑤ Token single-use

Dùng lại `$TOKEN` của ④ → `401` (ledger `sas_operator_token_jti` của sas-camara; một INSERT
nguyên tử theo `(iss, jti)`, đúng cả khi nhiều instance dùng chung DB).

### ⑥ SIM Swap — CAMARA SimSwap v2.1.0 (`/sim-swap/v2`)

Cùng evidence read-only mà Verifier chấm `notSimSwapped` (MAP `lastUpdateLocation` → Sh
UDR → SWx), phơi ra thành API CAMARA riêng cho bank. Không AIR/AIA (không đốt SQN của AuC),
không IDR/IDA, không ATI liên mạng (FS.11). **Fail-closed:** không có evidence ⇒
`404 IDENTIFIER_NOT_FOUND`, không bao giờ trả `swapped:false`.

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

# Mã lỗi theo CAMARA commonalities
#   số lạ / không có evidence   → 404 IDENTIFIER_NOT_FOUND
#   thiếu phoneNumber (2-legged) → 422 MISSING_IDENTIFIER
#   maxAge=0 hoặc 2401          → 400 OUT_OF_RANGE (không tự clamp)
#   không có Authorization      → 401 UNAUTHENTICATED
```

3-legged (sas-camara chạy validation như §4b): định danh lấy từ binding của access token,
body để `{}`; scope `sim-swap:check` / `sim-swap:retrieve-date` (family `sim-swap` cấp cả
hai). Gửi kèm `phoneNumber` khi token đã bound → `422 UNNECESSARY_IDENTIFIER`; token khác
family → `403 PERMISSION_DENIED`; dùng lại token → `401`. Lệnh chi tiết: §0b Bước C7;
SIM Swap với token CIBA lấy từ operator token: §0a A7.

### ⑦ OTP SMS — CAMARA OneTimePasswordSMS v1.1.1 (`/one-time-password-sms/v1`)

**Nhánh FALLBACK**: khi `/verify` trả FALLBACK thì bank rơi về OTP qua SMS. sas-camara chỉ
**orchestrate** (sinh OTP, giữ state, validate) — SMS thật do SMSC operator gửi (Restlink
không bán SMS wholesale). Lab: `sas.otp.sms-delivery=log` → tin nhắn (**kèm OTP**) được ghi
ra log, **không gửi đi đâu cả**. Prod: surface này TẮT (`sas.otp.enabled=false`), preflight
`PRO-29` từ chối sender lab.

```bash
# 1) send-code — message là TEMPLATE, bắt buộc chứa {{code}}, ≤160 ký tự
curl -s -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp1' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
# → 200 {"authenticationId":"<uuid 36 ký tự>"}   (OTP KHÔNG bao giờ trả về trong body)

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

Các nhánh fail-closed đã verify: thiếu `{{code}}` / message >160 → `400 INVALID_ARGUMENT`;
gửi quá `sas.otp.max-codes-per-number` (3) cho MỘT số trong `rate-window` →
`403 …MAX_OTP_CODES_EXCEEDED` (số khác không bị); operator từ chối (`NOT_ALLOWED`/`BLOCKED`
từ delivery seam) → `403 …PHONE_NUMBER_*`; không có delivery route → `500 INTERNAL_ERROR`
và **không** cấp `authenticationId`; `sas.otp.enabled=false` (hoặc thiếu key) →
`404 NOT_FOUND` cho cả 2 endpoint.

3-legged: một scope `one-time-password-sms:send-validate` cho cả hai endpoint;
`phoneNumber` phải **trùng** binding của token, khác → `403 PERMISSION_DENIED` (chưa kịp
sinh OTP). Lệnh đầy đủ: §0b Bước C8.

### ⑧ CDR/audit cho SimSwap + OTP

Mỗi request ⑥/⑦ ghi **một** dòng CDR API theo `x-correlator` vào file CSV bền
`dist/logs/sas.cdr` (logger log4j `SAS_CDR`) và vào ledger DB hiển thị ở `/admin/cdr`.
`operation` là `SIMSWAP` hoặc `OTP`; 2xx → `COMPLETED`, 4xx/5xx → `FAILED` kèm `http=` và
`code=` trong `detail`.

```bash
grep -E 'sw1|otp1|otp2' dist/logs/sas.cdr
```

Dashboard merge hàng vừa enqueue với hàng đã persist, nên restart vẫn thấy lịch sử. Quy
tắc privacy đã smoke: MSISDN mask trước khi qua CDR port; **không** ghi OTP plaintext, nội
dung SMS, MSISDN/IMSI thô. OTP chỉ ghi `authenticationId`, `messageChars`, delivery/result;
SimSwap ghi `action`, `maxAgeHours`, `swapped`, `evidence=ABSENT` khi fail-closed.

### ⑨ TS.43 entitlement qua MAP trên sas-ecs — không cần Diameter

Scenario riêng của track này, chạy **không** với 3 instance §2. Runbook đầy đủ + pcap: §0f.

```bash
scripts/ecs-map-smoke.sh     # HLR sim + sas-ecs (MAP) + sas-camara + demo + ma trận fail-closed, có pcap
```

Bảng kỳ vọng: §0f.4.

## 4b. Money-loop — operator Auth Server (CAMARA CIBA)

Đây là **lớp sản phẩm kiếm tiền**: sas-camara cấp token user-bound (bind số điện thoại vào
token) rồi bank mới gọi `/verify` so sánh. Chạy sas-camara với validation bật (thêm các cờ
transport corsac như §3 nếu muốn chạy cùng Diameter; CIBA với memory transport hoạt động
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
# → {"access_token":"<JWS ES256 typ=at+jwt, phone_number=+E164, aud>","expires_in":300,…}

# 3) /verify so sánh claimed vs số BOUND trong token
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H "Authorization: Bearer $AT" -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'   # khớp → true + assurance
#    phoneNumber khác số bound → false ; dùng lại token → 401 single-use
```

Access token ký **ES256** (`typ=at+jwt`), verify qua `/jwks.json`; `-Dsas.oauth.secret=k1`
chỉ còn tác dụng với lab HS256 cũ. Track Wi-Fi: thay `X-Sas-Src-Ip/Port` bằng
`login_hint=operatortoken:<tk>` với token do **sas-ecs** cấp (§0a A7).

## 4c. NV Authorization Code + PKCE (network authentication)

Luồng mà CAMARA NV (và GSMA Open Gateway sandbox) dùng: device gọi `/authorize` qua
bearer cellular → sas-camara resolve `IP:port:ts → MSISDN` → cấp `code` một lần (60 s) →
backend bank đổi `code` + `code_verifier` lấy access token ES256 (`amr=["mno"]`) + ID token
pairwise. Lệnh + kỳ vọng: §0a A8–A9.

## 5. Quan sát signalling

```bash
curl -s http://127.0.0.1:8086/api/messages     # S6a messages
curl -s http://127.0.0.1:18086/api/messages    # SWx (operator AAA↔HSS, lab) messages
curl -s http://127.0.0.1:28086/api/messages    # Gx (PCRF) messages — resolver binding
curl -s http://127.0.0.1:28086/api/binding     # Gx binding registry (10.20.30.40 → +251911111111)
curl -s http://127.0.0.1:8087/messages         # HLR simulator (MAP SAI / SendIMSI của sas-ecs)
```

Hoặc mở browser `http://127.0.0.1:8086/` — bảng tin tự refresh 2 s: thời điểm, command,
session-id, result-code, AVP chính (`user=… rat=EUTRAN`, `vectors=N`…).

Pcap (không cần root, chạy trong user netns):

```bash
scripts/split-smoke.sh    && tshark -r target/split-smoke/split-smoke.pcap -Y http.request
scripts/ecs-map-smoke.sh  && tshark -r target/ecs-map-smoke/ecs-map-smoke.pcap -Y gsm_map
```

## 6. Ma trận kỳ vọng

| Scenario | Signalling | Kết quả |
|---|---|---|
| LTE happy (resolver=sd, s6a=corsac) | CCA 2001 + ULA 2001, thiếu Sh UDR | `false` + `SIM_SWAP_SUSPECT` (fail-closed đúng) |
| LTE happy (memory transport) | pilot resolver/verifier | `true` |
| Detached | ULA **5421** | `false` |
| Barred | ULA 2001 + Subscriber-Status ODB | `false` |
| Gx unknown IP | CCA 5030 | `false` (NO_BINDING) |
| Operator token hợp lệ (sas-ecs cấp, sas-camara đổi) | lab: MAA items≥1 + SAA 2001 | `true` |
| Token replay | — (chặn trước Diameter, ledger) | `401` |
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
| `auth_req_id` hết hạn · pending binding của client khác | — (unit test) | `400 expired_token` · `400 invalid_grant` |
| `client_credentials` gọi NV `/verify` | — (manual §0e) | token 2-legged `token_use=client`; `/verify` → `403 NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` |
| JWT bearer `tel:` · thiếu purpose · `sub` lạ · `scope` ở form | — (manual §0e) | token user-bound · `400 invalid_scope` · `400 invalid_grant` · `400 invalid_request` |
| JWT bearer `operatortoken:` | — (unit test) | token user-bound |
| NV Authorization Code + PKCE (§0a A8) | resolver BOUND | `302 code` → access token ES256 `amr=["mno"]` + ID token pairwise → `/verify` `true` |
| code dùng lại · thiếu PKCE · `redirect_uri` lạ (§0a A9) | — | `400 invalid_grant` · `302 error=invalid_request` · `400`, không redirect |
| CIBA `login_hint=operatortoken:` (§0a A7) | GET `/jwks.json` của sas-ecs (cache) | access token ES256 → SIM Swap `{"swapped":false}`; token dùng lại → `403` |
| Cách ly surface (§0a A10) | — | CAMARA không có `/ts43`, `/entitlement`; ECS không có `/token`, discovery → `404` |
| Entitlement qua MAP trên sas-ecs (§0f) | SAI → result, SendIMSI → result | token JWE; CAMARA `true`, replay `401` |
| Entitlement fail-closed (§0f.4) | SAI/SendIMSI → returnError | `no authentication vector available` / `network did not confirm the claimed number` |

## 7. Kiểm thử khác trong tree

```bash
mvn -o -B test -Dquarkus.profile=lab     # từ repo root: 791 test / 9 module, không cần mạng
python3 harness/run_hardness.py          # 38/38 gates (H1–H28)
python3 harness/run_hardness.py --mutations    # H24 10/10 + H25 11/11 + H28 8/8
python3 harness/preflight_prod.py --service all   # verdict cho MÔI TRƯỜNG NÀY (exit = số check fail)
python3 harness/preflight_prod.py --selftest      # 45/45 kịch bản cấu hình sai bị bắt (camara + ecs)

scripts/split-smoke.sh                   # hai binary: operator token, CIBA, NV auth code, cách ly — pcap
scripts/ecs-map-smoke.sh                 # sas-ecs ↔ HLR qua MAP + ma trận fail-closed — pcap
mvn -o test -pl sas-ecs-host,sas-operator-adapter -am \
    -Dtest='EntitlementSessionsTest,LabSimAkaCardTest,SubscriberBindingResourceAdaptorTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
```

`mvn -o test` phải xanh **trước khi** smoke lab. `LabSimAkaCardTest` chỉ chứng minh card lab
và server nhất quán với nhau (cùng codec), **không** chứng minh tuân thủ RFC 4187 — codec
EAP-AKA vẫn là blocker P1 (`docs/design/sas-two-binary-architecture.md` §9).

## 8. Lỗi thường gặp

| Triệu chứng | Nguyên nhân | Fix |
|---|---|---|
| `UnsupportedClassVersionError` khi chạy jar | `java` mặc định là zulu-8 (shim) | dùng đường dẫn zulu-25 đầy đủ |
| Binary không boot, im lặng sau `MicroSleeContainer started`, log toàn `Failed to load config value…` | build thiếu `-Dquarkus.profile=lab` (property build-time bake theo profile) | build lại: `mvn -o -B clean package -DskipTests -Dquarkus.profile=lab` |
| `dist/run.sh` với `SAS_JAVA_OPTS=-Dsas.transport…` mà transport vẫn memory | key đó **có** trong `configs/application.properties`; file nạp qua `quarkus.config.locations` thắng `-D` | sửa file config (key **không** có trong file thì `-D` vẫn có tác dụng, vd `sas.oauth.clients-json`) |
| Sau `package-dist.sh`, dist vẫn chạy cấu hình cũ (vd `/authorize` → `NO_BINDING`, resolver=sd) | `configs/*` đã có **không bị ghi đè**; bản mới nằm ở `*.new` | `diff dist/configs/application.properties{,.new}` rồi merge, hoặc đóng gói vào thư mục khác (`SAS_DIST_DIR=…`) |
| `/authorize` → `error=access_denied … NO_BINDING` | thiếu `X-Sas-Src-Port` (hoặc tuple không có trong resolver) — quy tắc CGNAT IP+port+ts | gửi đủ `X-Sas-Src-Ip` + `X-Sas-Src-Port` (lab: `10.20.30.40:55555`) |
| `/authorize` → `400 unknown client_id` / `/token` → `unknown client` | `sas.oauth.clients-json` không được nạp | truyền qua `SAS_JAVA_OPTS=-Dsas.oauth.clients-json=…` (§0a A1) hoặc env `SAS_OAUTH_CLIENTS_JSON` (prod) |
| `/authorize` với header `X-Sas-Src-*` mà vẫn `access_denied` trên prod | prod không tin header (`trust-src-header=false`), dùng địa chỉ nguồn của kết nối | chỉ bật `SAS_AUTHORIZE_TRUST_SRC_HEADER=true` khi edge proxy ghi đè header |
| Operator token → `401`/`403` ở sas-camara dù vừa cấp | issuer không có trong `issuers-json`; JWKS không tải được (prod chỉ HTTPS); token hết hạn (≤300 s) hoặc đã dùng | xem log `[SAS] operator token rejected: <lý do>` |
| Sau khi restart sas-ecs, token đầu tiên bị từ chối | lab dùng khóa ký tạm → `kid` mới; sas-camara tải lại JWKS khi gặp `kid` lạ nhưng **tối đa 1 lần / 30 s / issuer** | thử lại sau 30 s, hoặc cấu hình `sas.ecs.signing-jwk` cố định |
| `/entitlement/exchange` → `404 legacy exchange is disabled` | prod tắt redeem cục bộ trên ECS (`legacy-exchange-enabled=false`) — sas-camara là nơi đổi duy nhất | đổi token ở sas-camara (`/verify`, `/bc-authorize`, `/token` jwt-bearer) |
| `Peer is up` rất chậm (tới ~3 phút), sim log `Received connect request from non provisioned … address` | host multi-home: SCTP client thử nguồn LAN/IPv6, sim chỉ nhận loopback | đợi retry chọn 127.0.0.1; không cần can thiệp |
| Verify luôn `false`, HSS không nhận gì | SCTP chưa "Peer is up" | đợi thêm; kiểm tra 3 association |
| Verify `false` + `SIM_SWAP_SUSPECT` dù CCA/ULA đều 2001 | `s6a=corsac` fail-closed chiều SIM-swap (lab không có Sh UDR handler) | đúng thiết kế; muốn `true` dùng memory transport |
| Discovery `403 USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` | binding resolve sang MSISDN mà verifier lab không biết (vd sau khi POST `/api/binding` thay seed) | `POST http://127.0.0.1:28086/api/reset` để seed lại |
| Scenario ① `NO_BINDING` | thiếu Gx instance :3870 (resolver=sd) | chạy instance 3 ở §2 |
| SWx timeout dù HSS thấy MAR/MAA | 2 link cùng origin-host vào 1 port | tách SWx sang port riêng (`swx.peer-port`) |
| `/sim-swap/v2/*` trả `404 IDENTIFIER_NOT_FOUND` cho số có trong seed | không còn nguồn binding-age nào được wire: `sas.transport.map=jss7` **và** `s6a`/`swx`=corsac (Sh UDR/SNR thật chưa có — open item) | đúng thiết kế fail-closed; muốn demo `swapped` thật thì để MAP transport = memory |
| `/sim-swap/v2/check` trả `403 PERMISSION_DENIED` dù token hợp lệ | token thiếu scope family `sim-swap` | xin lại token với `scope=sim-swap:check` (hoặc `sim-swap:retrieve-date`) |
| `/one-time-password-sms/v1/*` trả `404 NOT_FOUND` cho mọi request | `sas.otp.enabled` thiếu/`false` trong config đang nạp | thêm block `sas.otp.*` (enabled=true, sms-delivery=log) rồi restart — prod cố tình TẮT (`PRO-29`) |
| Không thấy OTP để validate | sender lab ghi OTP vào **log**, không trả về body | `grep 'LAB-SMS' <log>` |
| `403 MAX_OTP_CODES_EXCEEDED` dù mới gửi 1 OTP | cửa sổ rate limit in-memory còn giữ lượt gửi trước | restart hoặc đổi số khác |
| `500 INTERNAL_ERROR` khi send-code | `sas.otp.sms-delivery` không phải `log` mà cũng chưa có adapter operator thật | lab: đặt `log`; prod: giữ surface OFF tới khi có adapter SMSC/SGd (TS 29.338) |
| Không thấy `dist/logs/sas.cdr` | chạy jar trực tiếp mà không set `-Dsas.log.dir`; appender fallback về `target/logs/sas.cdr` | dùng `dist/run.sh`, hoặc xem `target/logs/sas.cdr` |
| Không thấy dòng CDR trên console | logger `SAS_CDR` route `additivity=false` vào file CSV | đọc `dist/logs/sas.cdr` hoặc `/admin/cdr` |
| `/admin/cdr` thiếu lịch sử cũ | `sas.cdr.db.enabled=false`, DB lab bị xóa, hoặc chưa persist xong | bật DB CDR, giữ `dist/data/`; file CSV vẫn là bản durable |
| sas-ecs không có association tới HLR sim | sim chờ peer port khác (`--peer-port` mặc định 2905 là của sas-camara) | chạy sim với `--peer-port 2907` (port local của `ss7-ecs.json`) |
| `/ts43/challenge` trả `no authentication vector available` mà log có `[auth-vector] SAI sent` | simulator chưa trả, hoặc service MAP chưa `activate()`; IMSI lạ / vectors=0 / detached cũng ra kết quả này | `curl -s :8087/messages`; pcap `ecs-map-smoke.sh` |
| IMSI lạ: `no authentication vector available` chỉ về sau ~2 s dù pcap thấy `returnError` ngay | `returnError` của SAI chưa kết thúc sớm yêu cầu — chờ hết budget SAI 2 s (vẫn fail-closed đúng) | open item nhỏ: map `returnError` thành lỗi ngay |
| `[binding] SendIMSI send failed … No AS found for routing message … si=3` | config SS7 chỉ route SSN 6; `sendImsi` đi trên **SSN 3 (OAM)** | thêm route + service `ssn: 3` (đã có trong `ss7-ecs.json`) |
| `Ss7ConfigException: Cannot load SCTP backend NETTY_KERNEL` | thiếu `org.mobicents.protocols.sctp:sctp-impl` trên runtime classpath | có sẵn qua `sas-operator-adapter`; rebuild dist |
| `Ss7ConfigException: F-Stack sidecar socket missing: data/sctp-fstack.sock` | config còn mặc định DPDK/F-Stack | thêm `"backend": "netty_kernel"` vào block `sctp` |
| `MAPException: Cannot create MAPDialog… is not activated` | fork jSS7 này **không** tự activate service sau `start()` | `MAPServiceMobility/Oam.activate()` sau khi start (đã có) |
| `NullPointerException … mapApplicationContext is null` trong `addSendImsiRequest` | hỏi AC `version3` cho `imsiRetrievalContext`, context này **chỉ có version 2** | dùng `MAPApplicationContextVersion.version2` |
| `/ts43/respond` trả `network did not confirm the claimed number` | SendIMSI là nguồn **theo số**: chỉ xác nhận claim, không tự tìm số | cần `msisdn` trong request; không có nguồn discovery (`sh-udr`, `swx-sar` chưa có) thì đúng là fail-closed |
| Hai stack MAP trong một JVM, chỉ một chân chạy | tranh cùng SCTP local port; stack thua không có association | mỗi binary **một** stack; sas-ecs dùng port riêng 2907 |
| HLR simulator không nhận gì khi chạy từ repo root | jSS7 ghi file state `*.xml` vào CWD | chạy từ thư mục riêng, hoặc dọn `*-sccp_*.xml` (đã gitignore) |
| Log `[cdr-db] persist failed ... Unique index` | dùng lại `x-correlator` trong DB CDR (`correlation_id` UNIQUE) | đổi `x-correlator` mới cho mỗi lần gọi, hoặc reset DB lab |
| Sau khi chạy script, cổng 8085/8095 vẫn bị chiếm | JVM lab còn chạy (vd khởi động tay ở §0a) | `ss -ltnp | grep -E ':(8085|8095) '` rồi kill đúng PID; đừng `pkill -f` theo mẫu có trong chính dòng lệnh của shell |
| Restart sas-ecs rồi mà kết quả vẫn như instance CŨ (bỏ MAP mà vẫn fail live) | `kill` trúng wrapper `run.sh`, java con còn sống, instance mới chết vì `Database may be already in use: …/dist-ecs/data/ecs-db.mv.db` — cổng vẫn thuộc về JVM cũ | kill đúng PID java (`pgrep -f 'dist-ecs.*quarkus-run'`), rồi chứng minh restart bằng dòng log `=== sas-ecs ready …`, đừng tin cổng một mình |
