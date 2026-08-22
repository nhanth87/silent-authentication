# Lesson Learned — Silent Auth SAS Flow, EAP-AKA, UE SDK, CAMARA

Date: 2026-08-21 · Scope: `sas/` (Restlink SAS, micro-jainslee) · Source: code review + design docs

---

## Q1. Flow xác thực qua SAS (end-to-end)

Có **2 path**, chọn theo access tech của UE:

**Path A — IP-match (cellular data, happy path):**

```
User mở app
  → Bank App (đang chạy 4G/5G) thu thập {srcIP, srcPort, ts, claimedMSISDN?}
  → Bank App → Bank Backend: POST /login
  → Bank Backend → SAS: POST /verify  (server-to-server, mTLS + OIDC token)
  → SAS (VerifySbb FSM):
       RESOLVING: resolver-ra  IP:port:ts → MSISDN/IMSI   (đọc PGW/CGNAT)
       VERIFYING: map-verifier-ra (jSS7 PSI+SAI) hoặc s6a-verifier-ra (LTE) → HLR/HSS
       SCORING:   assurance = w1·ipFresh + w2·reachable + w3·notSimSwapped + w4·locationPlausible
  → SAS → Bank Backend: {devicePhoneNumberVerified:true/false}
  → Bank Backend → App: Login OK (không OTP)
```

Code: `VerifyResource.verify()` → `bootstrap.submit(event)` → `VerifySbb.drive()` → `fsm.decide()`.

**Path B — EAP-AKA/SWx (Wi-Fi, TS.43):** khi `accessTech=WIFI`, `VerifySbb.verifySwx()`
bỏ qua Resolver, dùng `claimedMsisdn` làm identity anchor, gọi `swx-verifier-ra`.

---

## Q2. EAP-AKA nằm ở đâu

EAP-AKA là **root of trust của path Wi-Fi** — thay thế IP-match khi không có PGW binding.

```
Device(SIM) ──EAP-AKA──► WLAN AN ──SWm(Diameter)──► 3GPP AAA ──SWx──► HSS
```

Trong code SAS, nó nằm ở **`swx-verifier-ra`**:

- `ras/swxverifier/InMemorySwxVerifierBackend.java` — mô phỏng trao đổi 3GPP AAA↔HSS
  (TS 29.273 SWx / TS 33.402), evidence source = `"SWX-EAP-AKA"`.
- SAS **không tự chạy EAP-AKA** với UE. SAS đứng ở phía server: nhận kết quả EAP-AKA
  đã được 3GPP AAA xác thực, rồi query HSS qua SWx để lấy auth-vector + SIM-swap freshness.
  EAP-AKA thực sự diễn ra giữa **UE ↔ 3GPP AAA** (do operator chạy), SAS chỉ là lớp
  verify/entitlement phía trên.

EAP-AKA = cơ chế chứng minh SIM ở access edge (SWm/AAA); SAS tiêu thụ kết quả qua SWx.
Đây là open item "TS.43 entitlement server feasibility" trong AGENTS.md.

---

## Q3. Có cần SDK để UE xác thực không

**Path A (IP-match):** Không cần SDK "xác thực" nặng, nhưng cần **1 SDK thu thập session
tuple nhỏ**. Vì CGNAT, app phải gửi chính xác `IP + source port + timestamp` (5-tuple + time)
thì Resolver mới phân biệt được thuê bao. App tự đọc IP thì dễ, nhưng **source-port của
bearer cellular + timestamp đồng bộ** thường cần SDK/helper của operator hoặc của Digicom.

**Path B (EAP-AKA):** Cần EAP-AKA client phía UE.

- Trên Android/iOS, EAP-SIM/EAP-AKA thường do **OS/stack Wi-Fi** xử lý (khi join mạng dùng
  EAP-AKA), app không tự implement.
- Với browser/app không qua Wi-Fi EAP native, cần **SDK entitlement (TS.43)** để lấy
  *temporary token* rồi backend đổi qua CIBA/JWT-Bearer (xem `camara-number-verification.md` §3).
  Đây là open item chưa implement.

**Kết luận:** không có SDK nào bắt buộc cho logic xác thực lõi (logic nằm ở SAS + operator
core). Chỉ cần SDK mỏng phía app để (a) thu thập IP:port:ts cho path A, và (b) entitlement
token cho path B.

---

## Q4. CAMARA API được gọi ở đâu

CAMARA NumberVerification (NV v2.1.0) là **northbound surface** — lớp REST mà SAS expose ra
cho **Bank Backend** gọi. Nằm ở:

- **`sas/src/main/java/et/restlink/sas/api/VerifyResource.java`**
  - `POST /verify` → `verify()` — nhận `{phoneNumber}` hoặc `{hashedPhoneNumber}` (đúng 1
    trong 2), validate OIDC token + `amr` (403 nếu không phải mobile-network auth), rồi gọi
    `bootstrap.submit(event)`.
  - `GET /retrieve-phone-number` → hiện trả 501 (ngoài phạm vi P0).

Chuỗi gọi CAMARA → nội bộ SAS:

```
POST /verify {phoneNumber}                     ← CAMARA contract (VerifyResource)
  → TokenValidator + ReplayGuard               ← H14 single-use token
  → bootstrap.submit(VerifyRequestEvent)       ← bridge vào SLEE event router
  → VerifySbb (Resolver → Verifier → Policy)   ← micro-jainslee FSM
  → {devicePhoneNumberVerified: bool}          ← CAMARA response, chỉ boolean
```

**Privacy H8:** CAMARA `/verify` chỉ trả **boolean** về Bank Backend; MSISDN/IMSI
**không bao giờ** ra tới mobile app.

---

## Tóm tắt

| Câu hỏi | Trả lời |
|---|---|
| Flow | App→Bank Backend→SAS `/verify`→Resolver→Verifier(MAP/S6a/SWx)→Policy→boolean |
| EAP-AKA ở đâu | Path Wi-Fi, giữa UE↔3GPP AAA (SWm); SAS tiêu thụ qua `swx-verifier-ra` (SWx) |
| Cần SDK UE không | Không cho logic lõi; chỉ SDK mỏng thu thập IP:port:ts (path A) / entitlement token (path B) |
| CAMARA gọi ở đâu | `VerifyResource.java` — `POST /verify`, do Bank Backend gọi server-to-server |
