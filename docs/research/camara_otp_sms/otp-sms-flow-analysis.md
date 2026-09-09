# CAMARA OTP SMS — Flow Analysis cho SAS (Restlink)

Date: 2026-09-09 · Spec snapshot cùng thư mục: `one-time-password-sms-r3.2.yaml`
(release r3.2, API v1.1.1, commonalities 0.6). Nguồn: `camaraproject/OTPValidation`.

Đây là **nhánh FALLBACK** của silent auth: khi SAS không chứng minh được
bearer/identity (Wi-Fi-only không có TS.43, binding cũ, assurance dưới ngưỡng) thì
bank rơi về OTP qua SMS. Nó KHÔNG thay thế `/verify`, và không làm yếu đi lý do
tồn tại của sản phẩm (OTP chỉ còn là fallback đã được bảo vệ bằng Home Routing +
signalling FW — Strategy B).

## A. Contract (r3.2, base `{apiRoot}/one-time-password-sms/v1`)

| Endpoint | Scope | Request | Response |
|---|---|---|---|
| `POST /send-code` | `one-time-password-sms:send-validate` | `{"phoneNumber":"+E164","message":"…{{code}}…"}` (`message` **bắt buộc**, phải chứa `{{code}}`, ≤160 ký tự) | `200 {"authenticationId":"<uuid ≤36>"}` |
| `POST /validate-code` | `one-time-password-sms:send-validate` | `{"authenticationId":"…","code":"…"}` (`code` ≤10 ký tự) | `204` (không body) khi đúng |

Cả hai dùng **một** scope duy nhất (`send-validate`) — khác SimSwap/NV là mỗi
endpoint một scope. `x-correlator` echo lại như các API CAMARA khác.

Mã lỗi đặc thù (ngoài `INVALID_ARGUMENT`/`UNAUTHENTICATED`/`PERMISSION_DENIED`/
`NOT_FOUND`/`QUOTA_EXCEEDED`/`TOO_MANY_REQUESTS`):

| HTTP | code | Nghĩa |
|---|---|---|
| 400 | `ONE_TIME_PASSWORD_SMS.INVALID_OTP` | code sai cho `authenticationId` đó |
| 400 | `ONE_TIME_PASSWORD_SMS.VERIFICATION_EXPIRED` | `authenticationId` hết hạn |
| 400 | `ONE_TIME_PASSWORD_SMS.VERIFICATION_FAILED` | vượt số lần thử tối đa → đốt luôn attempt |
| 403 | `ONE_TIME_PASSWORD_SMS.MAX_OTP_CODES_EXCEEDED` | quá nhiều OTP cho một MSISDN (rate limit) |
| 403 | `ONE_TIME_PASSWORD_SMS.PHONE_NUMBER_NOT_ALLOWED` | operator từ chối vì lý do nghiệp vụ (fraud, không hỗ trợ SMS) |
| 403 | `ONE_TIME_PASSWORD_SMS.PHONE_NUMBER_BLOCKED` | số bị chặn nhận SMS |

## B. Vị trí sản phẩm (ràng buộc, không phải lựa chọn kỹ thuật)

- Restlink **không bán SMS wholesale**: SMS do **SMSC của Ethio Telecom** gửi, doanh
  thu SMS vẫn ở operator (AGENTS §2, proposal ch.7 §7.6.2).
- Vì vậy SAS chỉ được là **lớp orchestration + policy + validation**: sinh OTP, giữ
  state (`authenticationId`, expiry, số lần thử, rate limit theo MSISDN), ghép
  `message` theo template của bank, và **giao việc gửi** cho một port phía operator.
- OTP phát ra phải đi qua kênh đã bảo vệ (Home Routing + SS7/Diameter/5G FW,
  SG.22/FF.09) — SAS không mở đường SMS riêng.
- `PHONE_NUMBER_NOT_ALLOWED` / `PHONE_NUMBER_BLOCKED` chính là chỗ treo policy của
  operator (fraud list, barring) — trong SAS nó map từ evidence sẵn có
  (subscriber barred/purged ở Verifier) chứ không tự bịa.

## C. Mapping sang SAS hiện có

| Thành phần OTP SMS | Cái ta đã có |
|---|---|
| `authenticationId`, expiry, attempts, per-MSISDN rate limit | `ReplayGuard` (single-use, window) + `TenantRegistry` (quota theo tenant) — cần thêm store riêng cho OTP attempt |
| Xác thực/scope cho API consumer | `TokenValidator` + `AuthorizationRequestService` (thêm scope `one-time-password-sms:send-validate` vào whitelist) |
| `phoneNumber` có được phép nhận SMS không | Verifier evidence: `reachable`, barred/purged (`FallbackReason`), `notSimSwapped` |
| Gửi SMS thật | **CHƯA CÓ** — cần `SmsDeliveryPort`; lab chỉ log, prod phải là adapter SMSC/SGd (TS 29.338) của operator |
| Khi nào thì bank bị đẩy sang OTP | `VerifyResult.decision == FALLBACK` + `fallbackReason` (đã có trong assurance snapshot) |

## D. Phương án tối thiểu (nếu owner duyệt làm trong SAS)

1. `sas-api/et.restlink.sas.otpsms`:
   - `OtpSmsResource @Path("/one-time-password-sms/v1")`: `POST /send-code`,
     `POST /validate-code` (204), ErrorInfo + `x-correlator` như SimSwap.
   - `OtpPolicy` (code length/charset, TTL, max attempts, max OTP per MSISDN per
     window) — cấu hình `sas.otp.*`, mặc định fail-closed.
   - `OtpAttemptStore` (in-memory lab / PostgreSQL prod, giống pattern CDR store):
     lưu **hash** của code, không lưu plaintext.
   - Port `SmsDeliveryPort { DeliveryResult deliver(msisdn, text) }`; lab impl chỉ
     LOG (không gửi), prod impl bắt buộc là adapter operator — preflight prod phải
     từ chối lab impl (thêm check `PRO-xx`).
2. Whitelist scope trong `AuthorizationRequestService`; `TokenValidator` thêm
   `SCOPE_ONE_TIME_PASSWORD_SMS_SEND_VALIDATE`.
3. Policy hook: `/verify` trả FALLBACK ⇒ bank gọi `/send-code`. Có thể thêm
   `X-Sas-Fallback-Reason` (đã có trong assurance detail) để bank chọn kênh
   (OTP SMS / TOTP / passkey) — SAS **không** tự gửi SMS khi chưa được yêu cầu.
4. Test: happy path (send → validate 204), sai code (400 INVALID_OTP), quá attempts
   (VERIFICATION_FAILED + đốt attempt), hết hạn (VERIFICATION_EXPIRED), rate limit
   (MAX_OTP_CODES_EXCEEDED), số barred (PHONE_NUMBER_BLOCKED), thiếu scope (403),
   message thiếu `{{code}}` / >160 ký tự (400 INVALID_ARGUMENT), 404
   `authenticationId` lạ, tenant quota (429).

## E. Trạng thái

**Đã implement theo phương án A — orchestration đầy đủ, sender lab log-only**
(2026-09-09, owner duyệt):

- `sas-api/src/main/java/et/restlink/sas/otpsms/`: `OtpSmsResource`
  (`/one-time-password-sms/v1/send-code` + `/validate-code`), `OtpConfig`
  (`sas.otp.*`), `OtpAttemptStore` (attempt state + per-MSISDN window),
  `SmsDeliveryPort` (seam giao việc gửi SMS), DTO `SendCodeRequest` /
  `SendCodeResponse` / `ValidateCodeRequest`.
- `sas-host/src/main/java/et/restlink/sas/otpsms/LabLogSmsDelivery.java`: sender lab
  — ghi tin nhắn (kèm OTP) ra log, **không gửi**; mọi giá trị
  `sas.otp.sms-delivery` khác `log` → `UNAVAILABLE` (fail-closed).
- Scope `one-time-password-sms:send-validate` (một scope cho cả 2 endpoint) trong
  `TokenValidator` + whitelist CIBA.
- Config: lab `sas.otp.enabled=true` + `sms-delivery=log`; **prod
  `sas.otp.enabled=false`** và cả hai key nằm trong `CRITICAL_KEYS` của preflight.
- Gate mới **`PRO-29`** ("OTP SMS fallback is off or on a real operator route") +
  mutation scenario `lab OTP SMS sender` trong gate **H18** → selftest 23/23.
- Test: `OtpSmsResourceTest` (26 → 32 sau CDR) + `OtpAttemptStoreTest` (8) + `LabLogSmsDeliveryTest` (4);
  root `mvn test` lúc đó = 438, hiện tại sau CDR = 463, gates 34/34. Steps đã verify trên dist:
  `docs/test/testflow.md` §4 ⑦ (VN), §0c Step 20–24 + C8 (EN), CDR: §4 ⑧ / Step 25.

Quyết định đáng chú ý:

- **Không có `authenticationId` khi chưa giao được SMS**: delivery
  `UNAVAILABLE`/exception → `500 INTERNAL_ERROR` (mã lỗi lấy từ CAMARA_common vì
  YAML của API không liệt kê 500) — bank không bao giờ tưởng OTP đã gửi.
- **Chỉ lưu hash** `sha256(authenticationId|code)`, code đúng ⇒ xoá attempt (một OTP
  validate đúng một lần); sai đủ `sas.otp.max-attempts` ⇒ đốt attempt, sau đó nhập
  đúng vẫn `VERIFICATION_FAILED`.
- **3-legged privacy gate**: `phoneNumber` khác binding của token → `403` trước cả
  khi sinh OTP.
- Đã ghi CDR: `/one-time-password-sms/v1` tạo hàng CDR phase/operation `OTP` qua
  `ApiCdrRecorder`/`ApiAudit`; CSV bền `SAS_CDR`, DB và admin giữ lịch sử sau restart.
  CDR không ghi OTP plaintext, `message` gốc hay MSISDN thô.
- Còn thiếu (open): adapter SMSC/SGd thật (TS 29.338) + attempt store bền vững
  (PostgreSQL cạnh CDR) — hai thứ này là điều kiện để bật surface ở prod.
