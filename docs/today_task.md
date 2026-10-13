# Task hôm nay — CAMARA NV v2 (SS7/MAP + Diameter/S6a + TS.43)

> Ngày: 2026-10-13 · Cây: `worktrees/silent-authentication/main` · Branch: `nv-2`
> Tài liệu này so **yêu cầu** (`docs/request_nâng cấp_sdk_android_ios_web_lên_camara_v2.md`)
> với **code thật** trong repo. Mọi dòng "đã có" đều trỏ tới file tồn tại, kiểm được
> bằng `ls` / `grep` / `ffprobe` / `tshark`.

---

## 0. Đính chính quan trọng

Hai package **`com.camara.nv2.api`** và **`com.camara.nv2.android`** từng được ghi ra
trong bản nháp của tài liệu này **không tồn tại trong repo** — đó là mã bịa.
SDK thiết bị thật của Restlink nằm ở:

| Tầng | Package / đường dẫn thật |
|---|---|
| Core SDK (JVM) | `ue-sdk/src/main/java/et/restlink/uesdk/` — `Connector`, `AccessTech`, `CellularRequirement`, `SessionTupleClient`, `SessionTupleCollector` |
| Android | `ue-sdk-android/` — hiện thực `Connector` bằng `android.net.Network.openConnection(URL)` |
| iOS | `ue-sdk-ios/` — Swift, `NWPathMonitor` + `NWConnection` |
| Web | `ue-sdk-web/src/session-tuple.js` |

---

## 1. Yêu cầu CAMARA NV v2 → code thật

Nguồn yêu cầu: `docs/request_nâng cấp_sdk_android_ios_web_lên_camara_v2.md`
(§A OAuth, §B login hint, §C endpoints, §D SDK).

### 1.1 API contract

| Yêu cầu | Thực hiện | File |
|---|---|---|
| `POST /number-verification/v2/verify` | alias `/camara/...`, body `{phoneNumber}` **hoặc** `{hashedPhoneNumber}` (đúng 1 trong 2) | `sas-host/src/main/java/et/restlink/sas/api/VerifyResource.java` |
| `GET /number-verification/v2/device-phone-number` | alias `/retrieve-phone-number` | cùng file |
| `x-correlator` `^[a-zA-Z0-9-_:;./<>{}]{0,256}$`, echo lại | ✅ | `sas-api/.../security/RequestValidator.java` |
| Trường lạ bị từ chối `400 INVALID_ARGUMENT` | `quarkus.jackson.fail-on-unknown-properties=true` | `sas-host/src/main/resources/application.properties` |
| `/camara/health` → `UP` | ✅ | `sas-host/.../bootstrap/SasBootstrap.java` |

### 1.2 Auth / token

| Yêu cầu | Thực hiện | File |
|---|---|---|
| `private_key_jwt` client auth | ✅ | `SasBootstrap` (OAuth AS) |
| Single-use token (1 token = 1 API call) | ✅ | `sas-common/.../security/ReplayGuard.java` |
| TTL ≤ 300 s, **không** refresh token cho NV scope | ✅ | `sas-common/.../security/SasSecurityConfig.java` |
| Scope `number-verification:verify` | ✅ | `sas-api/.../security/TokenValidator.java` |
| CIBA poll + `client_credentials` + JWT bearer | ✅ | `SasBootstrap` (`/bc-authorize`, `/token`) |
| 403 khi token không phải mobile-network auth | `USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` | `TokenValidator.validateAmr()` |

### 1.3 Login hint

| Dạng | Thực hiện |
|---|---|
| `tel:+<E.164>` | ✅ `sas-common/.../id/PhoneNumberNormalizer.java` |
| `ipport` | ✅ qua resolver (`ResolverBackend`, `ResolverResult`) |
| `operatortoken:<tk>` (TS.43) | ✅ JWT bearer `sub=operatortoken:<tk>` → identity anchor ở `sas-ecs` |

### 1.4 Mobile SDK

| Yêu cầu | Thực hiện | Ghi chú |
|---|---|---|
| Android: chọn đúng cellular khi Wi-Fi bật | `ue-sdk-android` → `Connector` = `Network.openConnection(URL)` | ✅ đúng cơ chế |
| iOS: **không** ép được qua `URLSession` | `ue-sdk-ios` dùng `NWConnection` + `NWPathMonitor` | ⚠️ `URLSessionConfiguration.requiredInterfaceType` **không tồn tại**; `requiredInterfaceType` nằm trên `NWParameters` và ràng buộc `NWConnection`, không ràng buộc `URLSession` |
| Fail-closed khi không chứng minh được cellular | `CellularRequirement.check()` → `CellularUnavailableException` | ✅ không bao giờ lặng lẽ rớt xuống Wi-Fi |
| Web: quan sát `navigator.connection`, fail-closed | `ue-sdk-web/src/session-tuple.js` | ✅ |

---

## 2. 3GPP / GSMA spec phía sau

| Spec | Dùng ở đâu |
|---|---|
| TS 29.002 (MAP) | `Jss7MapVerifierBackend` — PSI / SAI; `MapSendImsiBinding` — SendIMSI (SSN 3) |
| TS 29.272 (S6a) | `CorsacS6aVerifierBackend` — ULR/ULA |
| TS 29.273 (S6b/SWx) | `CorsacSwxVerifierBackend` — EAP-AKA qua SWx |
| GSMA TS.43 | `EntitlementSbb`, `Ts43Resource`, `EapAkaServer` |
| GSMA FS.11 §3.3.4 | không interconnect ATI; chỉ hỏi HLR/HSS của mình |

Ghi chú protocol đã sửa trong quá trình làm (xem `docs/design/entitlement-core-nw-plan-2.md` §2.1):
- **Một jSS7 stack mỗi host** — hai stack tranh SCTP port, kẻ thua không có association
  (`No AS found for routing message … si=3`).
- `sendImsi` đi trên **SSN 3 (OAM)**, không phải SSN 6 của HLR.
- `imsiRetrievalContext` chỉ có ở AC version 2.
- Phải gọi tường minh `MAPService*.activate()` — fork jSS7 này không tự kích hoạt.
- `sctp-impl` phải có trên runtime classpath.

---

## 3. Việc đã làm hôm nay

### 3.1 Fix S6a để HSS thật sự trả lời (`01a31ad`)

Trước đó `CorsacS6aVerifierBackend.addLink()` hardcode `0.0.0.0`; kernel chọn source
LAN/IPv6 mà peer từ chối → `CER Failed with error 3010`.

| Thay đổi | File |
|---|---|
| Khoá cấu hình mới `sas.transport.diameter.local-bind-address` (default `0.0.0.0`) | `sas-operator-adapter/.../config/SasTransportConfig.java` |
| `effectiveLocalBindAddress()` | `.../diameter/DiameterConfig.java` |
| S6a + SWx dùng hàm này trong `addLink` | `.../ras/s6averifier/CorsacS6aVerifierBackend.java`, `.../ras/swxverifier/CorsacSwxVerifierBackend.java` |
| Origin-Host của CER thành cấu hình, default `ecs.restlink.et` | `sas-diameter-testapp/.../diameter/HssDiameterServer.java`, `Main.java` (`--client-host`) |

Kết quả đo được: `Peer is up for Association [name=s6a-sas]` → `req ULR user=+251911111111
rat=EUTRAN` → `ans ULA 2001 ok`; exposure trả `ueReachable=true`, `evidenceSource: S6A_ULR`.

### 3.2 Bộ quay demo khách hàng (`target/demo-videos/`, gitignored)

| Thành phần | Vai trò |
|---|---|
| `record_demo.py` | Điều phối: dựng stack trong `unshare -rn`, bắt pcap, render lời, ghi video, trộn nhạc, ghép |
| `terms.py` | Vẽ bức "bàn làm việc" 1920×1080: dải tiêu đề + **băng bước**, cửa sổ Chrome, 3 terminal, khung Wireshark |
| `demo.html` | Console trình duyệt: nút `1 · GET operator token`, `2 · POST /verify`, `3 · GET /ts43/challenge` |

Sản phẩm:

```
silent-auth-ss7-demo.mp4       1920×1080 · 68.9 s · có lồng tiếng + phụ đề
silent-auth-ss7-demo.pcap       468 packet — SCTP INIT → M3UA ASPUP/ASPAC → 4 MAP op
silent-auth-diameter-demo.mp4  1920×1080 · 55.6 s · có lồng tiếng + phụ đề
silent-auth-diameter-demo.pcap  372 packet — CER/CCA → ULR/ULA 2001
```

### 3.3 Bốn lỗi thật đã tìm và sửa trong lúc quay

| # | Lỗi | Nguyên nhân | Cách sửa |
|---|---|---|---|
| 1 | Script tự kết thúc giữa chừng | `os.killpg(os.getpgid(pid))` với con chưa tách session → giết cả recorder | `start_new_session=True` cho mọi tiến trình con |
| 2 | Băng bước đóng băng ở ~44 s | Renderer vẽ 1080p mất ~0.4 s/khung, **không kịp thời gian thực** | Tách 2 pha: **lấy mẫu log khi quay → vẽ khung hình sau**, hết áp lực thời gian |
| 3 | 0.8 % mẫu âm bị cắt | `alimiter` mặc định `level=true` tự chuẩn hoá đẩy ngược lên 0 dBFS | thêm `level=disabled`; nay peak −1.01 dBFS, **0 mẫu cắt** |
| 4 | `HTTP 401` ở track Diameter | Camara hết ngân sách 170 ms cho lệnh redeem khi máy tải nặng | Bỏ `tshark -i lo` (đọc pcap đang ghi thay vì bắt promiscuous), hạ FPS; trang demo **lấy token mới và thử lại đúng 1 lần**, hiện nguyên trạng trên màn hình |

### 3.4 Còn mở (không giấu)

| Việc | Vì sao mở |
|---|---|
| Kết quả `true` trên clip | Trên transport thật không có nguồn SIM-change nên `EcsBootstrap.labSimChange()` **cố ý** từ chối mượn bằng chứng lab. Biến thể `map=memory` (testflow §00.5) cho `true` nhưng mất tín hiệu MAP/Diameter thật trong clip |
| Wire codec EAP-AKA đúng RFC 4187 | P1 blocker (`AGENTS.md` §8): chiều dài attribute, Subtype/Reserved, AT_RES bit length, AT_AUTS 14, AT_KDF 2 octet, resync keys, MK identity. Lab card dùng chung codec nên lab thành công **không** chứng minh tương thích handset thật |
| `mTLS` + client credentials trên exposure hop | M4 bị bỏ qua (quyết định 2026-10-01) → prod profile giữ exposure **tắt** (PRO-35) |
| Shape R (relay AAA của operator) | R1–R3 phải đóng bằng văn bản trước khi boot production |
| Contact info trên outro card | `terms.py` đang để placeholder `hello@restlink.et` — **sửa trước khi đưa khách** |

### 3.5 QR hand-off desktop → phone (`nv-2`)

Camera trên desktop không có SIM nên IP-match không thể chạy. Bank hiện mã QR,
điện thoại quét và **chứng minh số thay cho desktop**.

| Phần | File |
|---|---|
| Store (so 120 s, lazy expiry, không thread) | `sas-api/src/main/java/et/restlink/sas/qrsession/QrSessionStore.java` |
| Resource `POST /qr-session/v1`, `POST /{id}/claim`, `GET /{id}` | `sas-api/src/main/java/et/restlink/sas/qrsession/QrSessionResource.java` |
| Wire shapes | `sas-api/src/main/java/et/restlink/sas/qrsession/QrSessionRequests.java` |
| Core SDK (JVM) | `ue-sdk/src/main/java/et/restlink/uesdk/QrSessionClient.java` + `JsonReader.java` |
| Android (qua `CellularBearer`) | `ue-sdk-android/src/main/java/et/restlink/uesdk/android/QrSessionClient.java` |
| iOS | `ue-sdk-ios/Sources/UESDK/QrSessionClient.swift` |
| Web (chỉ tạo + poll) | `ue-sdk-web/src/qr-session.js` + `qr-session-state.js` + `qr-session.d.ts` |
| Thiết kế + bằng chứng chạy thật | `docs/design/camara-qr-handoff.md` |

Kết quả kiểm chứng trên dist đã đóng gói (HLR sim SCTP :2906):

```
claim cellular   → {"state":"RESOLVED","devicePhoneNumberVerified":false,"fallbackReason":"SIM_SWAP_SUSPECT"}
                   MAP thật: sendImsi → provideSubscriberInfo → sendAuthenticationInfo
claim TS.43      → {"devicePhoneNumberVerified":true,"decision":"APPROVED"}   (ECS lab-evidence, §00.5)
dùng lại token   → 401 UNAUTHENTICATED                                          (single-use)
phone Wi-Fi      → 400 ACCESS_TECH_NOT_CELLULAR
phone B, mã QR cũ → 409 ALREADY_CLAIMED
bearer bịa       → 400 INVALID_ARGUMENT (không đoán)
```

**Ba lỗi thật do test bắt được trong lúc làm:**
1. `create()` dùng `computeIfAbsent` cho claim gate → session tạo lại cùng id bị
   coi là đã claim. Đổi sang `put` (gate mới mỗi lần tạo).
2. Bearer gate chạy **trước** nhánh operator-token → đường TS.43/Wi-Fi (vốn khai
   `WIFI`) không bao giờ chạy được. Đảo thứ tự.
3. Truyền `operatortoken:<tk>` vào anchor thay vì token trần → ECS nhận token nó
   chưa phát hành, mọi claim Wi-Fi trả 401. Đã sửa + thêm test chốt.

Ranh giới tầng giữ nguyên: QR surface nằm ở gateway, **không signal lõi mạng** —
nó submit `VerifyRequestEvent` và đọc verdict, mọi sự thật thuê bao vẫn đi qua
`SubscriberService` → sas-ecs. H25 vẫn 38/38 xanh.

**iOS chưa compile được ở đây**: máy này không có Swift toolchain
(`swift test` cần macOS/Xcode theo `AGENTS.md` §1). File Swift + test đã viết
đúng API của module (`CellularBearer.Requirement`, `AccessTech` case names) nhưng
**chưa được biên dịch** — cần chạy `swift test` trên macOS trước khi coi là xong.

---

## 4. Lệnh tái lập

```bash
# quay lại clip (English, 2 pha)
python3 target/demo-videos/record_demo.py ss7      --lang en
python3 target/demo-videos/record_demo.py diameter --lang en

# kiểm chứng lại bằng chứng
ffprobe -v error -show_entries stream=codec_type,duration -of csv=p=0 \
        target/demo-videos/silent-auth-ss7-demo.mp4
tshark -r target/demo-videos/silent-auth-ss7-demo.pcap \
       -Y gsm_map -T fields -e _ws.col.Info

# cổng lab: camara 8085 · ecs 8095 · HSS sim 3868 (UI 8086) · HLR sim 2906 (UI 8087)
# SCTP không hiện trong `ss -ltn`; xem `/proc/net/sctp/eps`
```
