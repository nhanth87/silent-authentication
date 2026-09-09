# CAMARA SimSwap — Flow Analysis cho SAS (Restlink)

Date: 2026-08-24 · Spec snapshots cùng thư mục: `sim-swap-r3.3.yaml` (release r3.3),
`sim-swap-main.yaml` (wip), `CHANGELOG.md`, `README.md`.

## A. Contract (r3.3, base `{apiRoot}/sim-swap/v2`)

| Endpoint | Scope | Request | Response 200 |
|---|---|---|---|
| `POST /retrieve-date` | `sim-swap:retrieve-date` | `{"phoneNumber":"+E164"}` | `{"latestSimChange":"2024-09-18T07:37:53Z"}` (+ `monitoredPeriod` tùy chọn, ngày) |
| `POST /check` | `sim-swap:check` | `{"phoneNumber":"+E164","maxAge":240}` (`maxAge` **giờ**, tùy chọn, 1..2400, default 240) | `{"swapped":true\|false}` |

> Tên field theo đúng YAML r3.3: `latestSimChange` (KHÔNG phải `latestSimChangedAt`)
> và `maxAge` tính bằng **giờ** (KHÔNG phải `maxAgeHours`). Bản nháp cũ của bảng này
> ghi sai — đã sửa 2026-09-09 khi implement.

- Lỗi theo commonalities: 400 `INVALID_ARGUMENT`/`OUT_OF_RANGE`, 401 `UNAUTHENTICATED`,
  403 `PERMISSION_DENIED`, **404 `NOT_FOUND`/`IDENTIFIER_NOT_FOUND`** (số không thuộc
  operator — khác NV không có 404), **422 `MISSING_IDENTIFIER`/`UNNECESSARY_IDENTIFIER`/
  `SERVICE_NOT_APPLICABLE`**, 429 `QUOTA_EXCEEDED`; body `{status,code,message}` như NV.
- `phoneNumber` là tham số request (không phải hashed); token phải user-bound trừ khi
  operator cho phép 2-legged trusted caller. Token đã bind số mà body vẫn gửi
  `phoneNumber` → `422 UNNECESSARY_IDENTIFIER` (server không so sánh được hai nguồn).

## B. Binding model

Giống hệt NumberVerification: token do operator Auth Server phát (ICM/CIBA,
`login_hint=operatortoken:<tk>` cho track Wi-Fi), số trong request so với số bound
trong token. → **tái dùng nguyên bộ `/bc-authorize` + `/token` của sas-api**, chỉ thêm
scope `sim-swap:*` vào whitelist của AuthorizationRequestService.

## C. Mapping sang SAS hiện có

Bằng chứng SIM-swap ta ĐÃ có sẵn trong verifier evidence:
- `InMemory{Map,S6a,Swx}VerifierBackend`: field `lastImsiChangeEpochMs` (seed demo
  "10 ngày trước").
- jSS7 SAI / corsac AIR-AIA: vector freshness = proxy notSimSwapped.

→ `check` = so `(now - lastImsiChange) <= maxAgeHours`; `retrieve-date` = trả
timestamp đó (chuẩn hóa ISO-8601). Feasibility: **cao**, không cần signalling mới.

## D. Kế hoạch implement tối thiểu (module sas-api)

1. `simswap/SimSwapResource.java`: `POST /sim-swap/v2/check` + `/retrieve-date`,
   ErrorInfo chung CamaraError; 404 khi backend báo unknown-subscriber.
2. Port `SimSwapQueryPort { Optional<Instant> lastSimChange(msisdn) }` trong sas-api;
   adapter đặt ở sas-host đọc từ FSM evidence/backends (thêm getter vào
   VerificationEvidence hoặc query trực tiếp backend map/s6a).
3. Whitelist scope `sim-swap:check`, `sim-swap:retrieve-date` trong
   AuthorizationRequestService + TokenValidator scope family.
4. CDR: ghi thêm phase=SIMSWAP (recordFlow đã đủ cột).
5. Testapp: thêm API set `lastImsiChange` cho subscriber để test 2 nhánh.

## E. Đã implement (2026-09-09) — trạng thái thật

Đã làm (D1–D3): `sas-api/src/main/java/et/restlink/sas/simswap/`
(`SimSwapResource`, `SimSwapQueryPort`, `SimSwapCheckRequest`, `SimSwapDateRequest`,
`CheckSimSwapInfo`, `SimSwapInfo`) + adapter sas-host
`et.restlink.sas.simswap.HostSimSwapEvidence` → `SasBootstrap.lastSimChange(msisdn)`
(đọc `lastImsiChangeEpochMs` theo thứ tự MAP → S6a → SWx qua seam bootstrap, không
gọi thẳng RA backend — gate H24). Scope: `TokenValidator.SCOPE_SIM_SWAP_*`, whitelist
CIBA đã nhận cả hai. Test: `SimSwapResourceTest` (29 → 34 sau CDR) + `HostSimSwapEvidenceTest` (6)
+ 2 case whitelist; tổng root `mvn test` lúc đó = 400, hiện tại sau OTP + CDR = 463,
gates 34/34.

Quyết định khác với bản nháp D:

- `maxAge` ngoài khoảng 1..2400 → **400 `OUT_OF_RANGE`**, không âm thầm clamp
  (spec có mã lỗi này; clamp sẽ che lỗi tích hợp phía bank).
- **Fail-closed cứng**: không có evidence ⇒ `404 IDENTIFIER_NOT_FOUND` — không bao
  giờ suy ra `swapped:false`. Hệ quả thực tế: khi chạy transport thật
  (`map=jss7` và/hoặc `s6a`/`swx=corsac`) mà chưa có Sh UDR/SNR, API trả 404; chỉ
  MAP memory (seed 10 ngày) mới có evidence trong lab.
- Không có rule `amr` (spec SimSwap không yêu cầu bằng chứng mobile-network auth);
  các gate token còn lại giống `/verify`: single-use jti, một `x-correlator` cho mỗi
  token key, scope theo endpoint, tenant + quota trước khi đọc evidence.
- Privacy: response chỉ có boolean/timestamp; log mask MSISDN (`+251****11`).

Đã làm (D4): `/sim-swap` ghi CDR qua `ApiCdrRecorder`/`ApiAudit` với phase/operation
`SIMSWAP`; hàng CDR được ghi CSV bền qua logger `SAS_CDR`, flush vào DB và hiện trên
admin sau restart. Live-smoke đã kiểm tra masked MSISDN, không lộ số thuê bao thô
(`docs/test/testflow.md` §4 ⑧ / Step 25).

Chưa làm (D5): testapp API set `lastImsiChange` (muốn demo nhánh `swapped:true` trên transport
thật thì cần Sh UDR thật, không phải seed).

Test steps đã verify trên dist: `docs/test/testflow.md` §4 ⑥ (VN), §0c Step 16–19 +
Step C7 (EN).
