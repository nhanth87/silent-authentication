# CAMARA SimSwap — Flow Analysis cho SAS (Restlink)

Date: 2026-08-24 · Spec snapshots cùng thư mục: `sim-swap-r3.3.yaml` (release r3.3),
`sim-swap-main.yaml` (wip), `CHANGELOG.md`, `README.md`.

## A. Contract (r3.3, base `{apiRoot}/sim-swap/v2`)

| Endpoint | Scope | Request | Response 200 |
|---|---|---|---|
| `POST /retrieve-date` | `sim-swap:retrieve-date` | `{"phoneNumber":"+E164"}` | `{"latestSimChangedAt":"2024-08-01T12:00:00Z"}` |
| `POST /check` | `sim-swap:check` | `{"phoneNumber":"+E164","maxAgeHours":240}` (maxAge tùy chọn) | `{"swapped":true\|false}` |

- Lỗi theo commonalities: 400 `INVALID_ARGUMENT`, 401 `UNAUTHENTICATED`,
  403 `PERMISSION_DENIED`, **404 `NOT_FOUND`/`IDENTIFIER_NOT_FOUND`** (số không thuộc
  operator — khác NV không có 404), body `{status,code,message}` như NV.
- `phoneNumber` là tham số request (không phải hashed); token phải user-bound trừ khi
  operator cho phép 2-legged trusted caller.

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
