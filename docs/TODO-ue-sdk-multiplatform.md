# TODO: UE SDK đa nền tảng (web/JS, Android/Java, iOS/Swift)

Chuẩn đối ứng: đọc `ue-sdk/` (Java reference) — POST `{baseUrl}/session-tuple`,
JSON body **omit-null**: `{"srcIp"?,"srcPort"?,"ts":epochMs,"msisdn"?,"imsi"?}`,
header `X-Api-Key` khi bật enforce, timeout 3s. Thiết bị không tự thấy srcIp/srcPort
(CGNAT) — chỉ gửi best-effort `ts` + msisdn do app cung cấp.

1. `ue-sdk-web/` — ES2020 zero-dep: class `SessionTupleClient({baseUrl,apiKey}).send({msisdn})`
   dùng fetch + AbortController(3s); package.json exports; .d.ts; test node:test + http server
   cục bộ khẳng định method/path/header/body-shape/status; README ghi chú HTTPS-only prod.
2. `ue-sdk-android/` — artifact `et.restlink:ue-sdk-android`, compiler release 8, junit5:
   `SessionTupleClient` (HttpURLConnection như ue-sdk), `TupleSnapshot`, `Json` escaper;
   test com.sun.net.httpserver ephemeral port incl. 401 propagation; README "gọi off main thread".
3. `ue-sdk-ios/` — Swift Package `UESDK` (tools 5.9): URLSession client, Codable snapshot
   encode-if-present; XCTest với URLProtocol stub assert request shape; KHÔNG chạy swift
   toolchain nếu máy không có — ghi rõ "unexecuted".

Header bản quyền mỗi file theo convention ngôn ngữ: `(c) 2026 Tran Nhan (nhanth87)`.
Verify: web = node --test; android = JAVA_HOME=zulu-25 mvn -q clean test; ios = chỉ viết code.
