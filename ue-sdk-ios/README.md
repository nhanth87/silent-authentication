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

## Logging in over the mobile network (4G/5G)

**The part the CAMARA blog posts get wrong.** `URLSessionConfiguration` has **no**
`requiredInterfaceType` — its only interface-related properties are
`allowsCellularAccess`, `allowsExpensiveNetworkAccess`,
`allowsConstrainedNetworkAccess` and `allowsUltraConstrainedNetworkAccess`, and
those *permit* cellular rather than *prefer* it. The
`requiredInterfaceType` / `prohibitedInterfaceTypes` API is on **`NWParameters`**
(Network.framework) and binds `NWConnection` sockets, not `URLSession` tasks. So
this SDK does the two things iOS actually allows:

```swift
// 1) Observe the bearer and fail closed when it is not cellular.
let bearer = CellularBearer()                     // NWPathMonitor + CoreTelephony
guard (try? bearer.check(.cellular4GPlus)) != nil else {
    return loginWithOtp()                          // never send a Wi-Fi tuple
}

// 2) Post the tuple with a configuration that will not stall on cellular.
let client = SessionTupleClient(
    baseUrl: sasBaseUrl, apiKey: apiKey, timeout: 3,
    configuration: CellularBearer.cellularSessionConfiguration(timeout: 3),
    bearer: bearer, requirement: .cellular4GPlus)

let status = try await client.send(msisdn: claimedMsisdn)   // throws .Unavailable
```

`allowsExpensiveNetworkAccess` / `allowsConstrainedNetworkAccess` matter: iOS
counts the radio as an expensive/constrained interface, so under Low Data Mode a
plain session can *defer* the call and the 3 s SAS budget becomes a timeout.

**If you truly must pin the socket** (iOS 14+), open it yourself:

```swift
let params = NWParameters.tcp
params.requiredInterfaceType = .cellular          // NWParameters, NOT URLSession
params.prohibitedInterfaceTypes = [.wifi]
let conn = NWConnection(to: .hostPort(host: host, port: portNumber), using: params)
```

…then speak HTTP/1.1 over it (TLS via `NWParameters(tls:)`) — which is why it is
not the default here: hand-rolled HTTP on a login path is a worse risk than the
Wi-Fi it avoids. Two further iOS realities to design around:

- **Redirect flow**: `ASWebAuthenticationSession` / a `SFSafariViewController`
  tab is another process; nothing in your app can force its route. Use the
  one-shot / direct-API flow when you need the bearer to be yours.
- **iCloud Private Relay** rewrites the egress address to an Apple relay IP,
  which the PGW cannot map to a subscriber; enterprise configs disable the
  relay for the app. Wi-Fi Assist can also silently move you *off* cellular.

`NETWORK_TYPE_IWLAN` / Wi-Fi calling is reported as `WIFI`, and an unread radio
stays `GS_2G3G` (MAP-verifiable) — the SDK never guesses `LTE`/`NR`.

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

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Digicom-ET. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, private builds, **a permissive (Apache-2.0/MIT) SDK option** for apps that cannot carry AGPL, L1/L2 SLA and integration engineering. Terms per deployment via Digicom-ET. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
