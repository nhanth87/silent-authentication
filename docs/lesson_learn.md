# Lesson Learned — Silent Auth SAS Flow, EAP-AKA, UE SDK, CAMARA

Date: 2026-08-21 · Scope: `sas/` (Restlink SAS, micro-jainslee) · Source: code review + design docs

---

> **Cập nhật 2026-10-01 — kiến trúc tách theo tầng** (`docs/design/sas-layered-architecture.md`).
> Q1–Q4 dưới đây đã viết lại theo hai binary hiện tại; bản cũ (SAS tự verify SWx, CAMARA
> tự giải mã operator token) không còn đúng.

## Q1. Flow xác thực qua SAS (end-to-end)

Hai binary, tách theo **tầng**: **sas-camara** (CAMARA API gateway: OAuth, CAMARA API,
chấm điểm) không chạm core; **sas-ecs** (entitlement server) là nơi duy nhất nói MAP/Diameter.
Gateway hỏi mọi thông tin thuê bao qua **subscriber exposure API** (REST, kiểu 3GPP
TS 29.522/29.122).

**Path A — IP-match (cellular data, happy path):**

```
User mở app
  → Bank App (4G/5G) → Bank Backend: login
  → Bank Backend → sas-camara: Authorization Code + PKCE (NV) hoặc POST /verify
  → sas-camara VerifySbb (FSM, qua exposure-ra = HTTP client):
       RESOLVING: POST /ue-id/retrieve  {IP, port, ts}        → sas-ecs: Gx/RADIUS/CGNAT → GPSI
       VERIFYING: POST /monitoring-event/…  ∥  POST /sim-change/retrieve
                   → sas-ecs: SendIMSI + MAP PSI (hoặc S6a + Sh) · nguồn SIM-change
       SCORING:   assurance = w1·ipFresh + w2·reachable + w3·notSimSwapped + w4·locationPlausible
                  (locationPlausible = PLMN nhà + tuổi vị trí ≤ ngưỡng — quyết ở gateway)
  → sas-camara → Bank Backend: {devicePhoneNumberVerified: true/false}
```

Code: `VerifyResource` → `SasBootstrap.submit` → `VerifySbb.drive` → `ExposureAdapters` →
`VerificationFsm.decide`. Phía ECS: `SubscriberExposureResource` → `EcsBootstrap` →
`SubscriberSbb` → RA của `sas-operator-adapter`.

**Path B — TS.43 / EAP-AKA (Wi-Fi):** UE chạy EAP-AKA với **sas-ecs** (`/ts43`, vector qua
MAP SAI hoặc SWx MAR, số xác nhận qua SendIMSI) → nhận **operator token** (JWE mã hóa cho
chính ECS) → app → bank → sas-camara (`login_hint=operatortoken:` ở CIBA, hoặc `sub=` ở
JWT-bearer) → sas-camara chuyển nguyên token sang `POST /operator-token/redeem` của ECS →
ECS giải mã, kiểm tra, tiêu **đúng một lần** (ledger `ecs_operator_token_jti`) → trả
*subscriber verification result*. Wi-Fi không có token đã redeem → `FALLBACK WIFI_NOT_READY`.

---

## Q2. EAP-AKA nằm ở đâu

Trong **sas-ecs**, endpoint `POST /ts43` (quyết định D6, Shape S): Restlink là EAP server
theo thỏa thuận truy cập AuC với operator. Code: `sas-entitlement` (`eap/`, `EapAkaServer`)
+ `EntitlementSbb` (sas-ecs-host) + RA `auth-vector` (MAP SAI hoặc SWx MAR) và
`subscriber-binding` (SendIMSI) trong `sas-operator-adapter`. **sas-camara không biết
TS.43** (gate H25 cấm). Lưu ý P1: codec EAP-AKA chưa đúng RFC 4187 — lab card dùng cùng
codec nên lab thành công không chứng minh được thiết bị thật.

---

## Q3. Có cần SDK để UE xác thực không

**Path A:** SDK mỏng thu tuple `IP + source port + timestamp` gửi lên **sas-ecs**
`/session-tuple` (từ M3 nằm ở ECS — cạnh resolver). SDK khai báo bearer (`accessTech`);
tuple Wi-Fi bị từ chối `400 ACCESS_TECH_NOT_CELLULAR`.

**Path B:** client TS.43/EAP-AKA phía UE (thường là OS/stack hoặc SDK entitlement) để lấy
operator token. Logic xác thực lõi nằm ở server, không ở SDK.

---

## Q4. CAMARA API được gọi ở đâu

CAMARA (NV v2.1.0, SIM Swap v2.1.0, OTP SMS v1.1.1, ICM OAuth) là **northbound của
sas-camara**, do Bank Backend gọi server-to-server: `sas-api` (`VerifyResource`,
`CamaraNumberVerificationResource`, `SimSwapResource`, `oauth/*`). Không có endpoint CAMARA
nào trên sas-ecs. **Privacy:** response CAMARA chỉ có boolean/ngày; IMSI không bao giờ tới
gateway; MSISDN không bao giờ tới app; nguồn evidence nội bộ (`evidenceSource`) chỉ vào CDR.

---

## Tóm tắt

| Câu hỏi | Trả lời |
|---|---|
| Flow | Bank → sas-camara (CAMARA/OAuth, chấm điểm) → exposure API → sas-ecs → MAP/Diameter → core |
| EAP-AKA ở đâu | sas-ecs `/ts43` (EAP server), vector qua MAP SAI / SWx MAR |
| Cần SDK UE không | Không cho logic lõi; SDK mỏng cho tuple (path A) / client TS.43 (path B) |
| CAMARA gọi ở đâu | sas-camara (`sas-api`); sas-ecs không có CAMARA |

## Sync 2026-10-01 — tách hai binary theo tầng (M0–M6)

- **Không release SBB entity trên luồng đang hoàn tất future.** `future.whenComplete(release)`
  chạy trên chính luồng event của container (bên trong `onEvent`) → dispatcher kẹt, mọi event
  sau treo tới hết budget. Release bằng `whenCompleteAsync`. Unit test gọi `onEvent` trực tiếp
  nên **không** bắt được — chỉ binary đóng gói mới lộ (exposure-smoke).
- **Đừng để dữ liệu lab "bù" cho transport thật.** SIM-change từng trả ngày từ backend S6a
  in-memory trong khi MAP chạy thật → evidence bịa. Nguồn evidence phải đi theo đúng transport
  đang dùng; live mà chưa có nguồn → `NO_EVIDENCE`.
- **Map lỗi một chỗ.** `ExposureAdapters` là nơi duy nhất đổi câu trả lời exposure thành
  `ResolverResult`/`VerificationEvidence`; FSM giữ nguyên.
- **Một normalizer số điện thoại** (`PhoneNumberNormalizer`, sas-common): có 5 bản copy khác
  nhau (giữ/bỏ `+`, xóa/không xóa dấu cách). Số claim sai định dạng phải bị **từ chối**, không
  được biến thành "không có claim" (sẽ chuyển sang discovery).
- **Kiểm tra SCTP theo inode, đọc cột theo header.** `/proc/net/sctp/assocs` có cột INODE khác
  `eps`; đoán số cột → check âm tính giả. Luôn có **đối chứng dương** (ECS phải bị phát hiện
  có SCTP) trước khi tin kết quả "camara không có SCTP".
- **`pkill -f <pattern>` có thể giết chính shell** nếu pattern xuất hiện trong dòng lệnh của
  shell (exit 144). Dừng tiến trình theo PID / `/proc/<pid>/cmdline`.
- **`wait` không đối số trong `trap cleanup` sẽ chờ cả tcpdump** → script treo. Chờ PID cụ thể.
- **`git add -A <thư mục>` có thể cuốn file không được phép** (đã cuốn `harness/oracle/`).
  Stage theo file/thư mục cụ thể và để thư mục local trong `.gitignore`.
- **Dùng `x-correlator` mới cho mỗi request** trong script: CDR có unique `correlation_id`;
  dùng lại → `persist failed … Could not commit transaction`.
- **Contract test hai chiều:** DTO serialize phải khớp OpenAPI (networknt, format bật) và
  validator Java phải đồng ý với schema trên cùng tập body hợp lệ/không hợp lệ (test A); gate
  H28 giữ path/scope/cause/method đồng bộ giữa OpenAPI, resource ECS, client gateway.

## Sync 2026-08-23 — từ gmlc-microjainslee (Monitor Hub / KPI / fast-jar deploy)

- **Deploy Quarkus fast-jar = rsync `quarkus/` + `lib/` CÙNG LÚC với app jar — không bao giờ chỉ jar.** Quarkus load class app từ `quarkus/transformed-bytecode.jar` + `generated-bytecode.jar`; `quarkus/` stale → code cũ vẫn chạy (dù jar mới, mtime mới) hoặc augment H2-era đè URL PG → crash-loop `Driver does not support jdbc:postgresql`. Prove boot bằng **log line**, không phải mtime. (Sự cố GMLC 2026-08-23.)
- **ServiceLoader không thấy `META-INF/services` trong ROOT app jar** lúc boot (fast-jar layering) — chỉ thấy pack trong `lib/main`. SPI tự viết (vd `RaAdminDashboardContributor`) phải đăng ký tường minh: merge nhiều ClassLoader, dedupe theo tên. Tham khảo: gmlc `AdminHttpHandler.buildHub()`.
- **Monitor Hub routing law**: route đủ các path hub (`isMonitorHubPath`: `/telemetry/*`, `/api/telemetry/*`, `/api/admin/dashboards`, `/admin/ra/**`, `/api/ra/**`, `/api/autonomous/*`); **`/metrics` KHÔNG phải hub path** — serve `port.scrape()` trong app; anonymous chỉ được static extension trơ; Overview poll `/admin/monitor-feed` mỗi 1s.
- **Branding hub**: truyền tên sản phẩm qua ctor mới `MonitorHandler(…, appName)` (token `@@APP_NAME@@`) — nếu không, shell hiện chữ "Restlink USSDGW" cho mọi product.
- **KPI pattern cho SAS** (tham khảo gmlc `GmlcKpi`): LongAdder map + mirror Micrometer (`*_kpi_*` trên `/metrics`) + tab hub riêng. Áp dụng trực tiếp: counter per-op MAP SAI/PSI/ATI và S6a ULR/PUR (+ Sh UDR) — request/response success/fail, verify pass/fail, SIM-swap freshness age — đúng hình dạng bảng "operation | requests | success | failed".
- Readiness probe: endpoint status có auth-gate — **401 anonymous = HTTP up thôi**; ready = 200 CÓ admin key header.
