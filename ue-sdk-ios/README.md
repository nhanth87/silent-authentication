# Silent Auth UE SDK (iOS)

Swift Package `UESDK` — device-side poster for the Silent Auth SAS
`POST /session-tuple` endpoint. `URLSession` with a 3 s request/resource
timeout. Devices cannot observe the real CGNAT IP/port, so the body carries
`ts` plus an optional app-supplied `msisdn`; nulls are omitted
(`encodeIfPresent`). No authentication logic — approval happens server-side.

## SPM usage

```swift
// Package.swift
.package(name: "UESDK", path: "../ue-sdk-ios")
// target dependency: .product(name: "UESDK", package: "UESDK")
```

```swift
import UESDK

let client = SessionTupleClient(
    baseUrl: "https://sas.example.et", // HTTPS in production
    apiKey: "bank-tenant-key")         // X-Api-Key, optional
let status = try await client.send(msisdn: "+251911111111")
if status != 200 {
    // non-fatal for login: SAS falls back to OTP/TOTP path
}
```

Request produced:

```
POST /session-tuple
Content-Type: application/json
X-Api-Key: <key>

{"ts":1724200000000,"msisdn":"+251911111111"}
```

## Test

```bash
swift test   # requires the Swift toolchain (macOS/Linux)
```

## Notes

- **ATS/HTTPS:** App Transport Security blocks cleartext HTTP by default;
  keep `https://` base URLs in production. Any ATS exemption is lab-only.
- A failed/missing tuple is expected on Wi-Fi/no-binding devices; treat as
  "fall back", never retry-storm the endpoint.
- Privacy: pass `msisdn` only if your app owns it; keep it off any UI per
  project privacy rule.

Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
