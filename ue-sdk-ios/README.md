# Silent Auth UE SDK (iOS)

Swift Package `UESDK` — **CAMARA Number Verification v2** on iOS (silent
authorization over `NWConnection` pinned to cellular, TS.43 operator-token path,
QR claim) plus the older device-side poster for the Silent Auth SAS
`POST /session-tuple` endpoint. `URLSession` with a 3 s request/resource
timeout. Devices cannot observe the real CGNAT IP/port, so the body carries
`ts` plus an optional app-supplied `msisdn`; nulls are omitted
(`encodeIfPresent`). No authentication logic — approval happens server-side.

## CAMARA Number Verification v2 (start here)

The device half of NV v2: carry the bank backend's OIDC authorization request
(`prompt=none`, PKCE S256) over the **cellular interface** and hand the code back.
The backend redeems it and calls `POST /number-verification/v2/verify`, and that
answer is the verdict. Design and evidence:
[`../docs/design/camara-nv2-device-sdk.md`](../docs/design/camara-nv2-device-sdk.md).

```swift
var configuration = NumberVerification.Configuration()
configuration.requirement = .cellular              // or .cellular4GPlus
configuration.operatorTokens = tokenSource         // optional: TS.43 path for Wi-Fi
do {
    let result = try await NumberVerification(configuration: configuration)
        .authorize(authorizeURLFromBackend)
    try await backend.send(code: result.code, state: result.state)   // never log it
} catch {
    // CellularBearer.Unavailable / AuthorizationError / transport: OTP or passkey
}
```

**How the request is pinned.** `URLSession` cannot pin an interface:
`URLSessionConfiguration` has no `requiredInterfaceType`. A BSD `bind()` to a fixed
local address cannot work either, because the cellular address comes from the PGW
and changes. `CellularHTTPClient` sends each hop over **`NWConnection`** with
`NWParameters.requiredInterfaceType = .cellular` and
`prohibitedInterfaceTypes = [.wifi, .wiredEthernet]`. TLS uses Network.framework
defaults (system trust, SNI) with a TLS 1.2 floor. If there is no cellular path the
connection goes `.waiting` and the SDK stops at once (`TransportError.cellularUnavailable`).
It then uses the operator token if one is configured, and otherwise throws
`CellularBearer.Unavailable`.

The request is validated first (https, `response_type=code`, PKCE `S256`,
`prompt=none`, `state`, `scope`, `client_id`, absolute `redirect_uri`, no repeated
parameter). Redirects are followed on the same interface (max 10), the
`redirect_uri` is caught and never loaded, and `state` is checked in constant time.
Timeouts: 5 s per hop, one retry on a transport error, 15 s total.

iOS gives a third-party app no API to run EAP-AKA against the SIM, so the TS.43
token comes from the operator via `OperatorTokenSource`. The SDK never mints it.
Privacy: nothing is logged, and `AuthorizationResult.description` redacts the code.

## SPM usage (session tuple, pre-v2)

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

# Linux CI without a local toolchain (verified 2026-10-14: 47 run, 44 pass, 3 skipped):
docker run --rm --user $(id -u):$(id -g) -e HOME=/tmp -v "$PWD":/w -w /w swift:5.10-jammy swift test
```

On Linux, `CellularHTTPClient` (Network.framework) and CoreTelephony are compiled
out (`#if canImport`), `URLSession` comes from `FoundationNetworking`
(`LinuxCompat.swift`), and 3 tests that pin Apple's `JSONEncoder` key order are
skipped. The NWConnection path is therefore only proven by `swift test` on macOS
and on a device.

## Notes

- **ATS/HTTPS:** App Transport Security blocks cleartext HTTP by default;
  keep `https://` base URLs in production. Any ATS exemption is lab-only.
- A failed/missing tuple is expected on Wi-Fi/no-binding devices; treat as
  "fall back", never retry-storm the endpoint.
- Privacy: pass `msisdn` only if your app owns it; keep it off any UI per
  project privacy rule.


## Desktop → phone QR hand-off (CAMARA NV v2)

A desktop login has no SIM, so the IP-match path cannot help the person sitting
at it. The bank renders a QR code; the phone scans it and claims the session.

```swift
// The claim blocks; call it from a Task, not the main actor's synchronous path.
let qr = QrSessionClient()
let sessionId = try QrSessionClient.parsePayload(scanned)   // refuses anything not a UUID

let verdict = try await qr.claim(
    sasBaseUrl: "https://sas.example.et",
    sessionId: sessionId,
    bearer: CellularBearer(),          // observed bearer, reported on refusal
    requirement: .cellular,
    operatorToken: nil                 // GSMA TS.43 temporary token, Wi-Fi variant
)

if verdict.approved {
    // the bank may complete the desktop login
} else {
    // verdict.fallbackReason is set — fall back to OTP / passkey / push
}
```

iOS cannot pin a `URLSession` to an interface: `requiredInterfaceType` lives on
`NWParameters`, not `URLSessionConfiguration`. Since 2026-10-14 the claim is
therefore sent through `CellularHTTPClient` (`NWConnection`,
`requiredInterfaceType = .cellular`). It leaves on cellular **even while Wi-Fi is
the default route**, or it throws `CellularBearer.Unavailable`. Pass
`QrSessionClient(transport: nil)` to get the old observe-and-fail-closed gate.

## App Clip (no bank app installed)

An App Clip runs the native, cellular-pinned code above without the user
installing the bank app. App Clip rules checked against Apple's documentation:

- Network.framework and `URLSession` are allowed, but BSD/POSIX sockets are not.
- CoreTelephony is allowed, except eSIM provisioning.
- There is no background work.
- `identifierForVendor` is empty.
- Face ID is not available; Touch ID via LocalAuthentication is.
- Size limit: 10 MB (iOS 15), 15 MB (iOS 16), 100 MB (iOS 17+, digital invocations only).
  This SDK is a few hundred KB with no dependencies.

How the session reaches the App Clip:

| User is on | The bank shows | iOS hands the App Clip |
|---|---|---|
| a desktop | a QR code of `https://clip.bank.example/s/<session id>` | that URL, after the camera scan + one tap on the card |
| the iPhone itself (cannot scan its own screen) | the same link as a button / Safari App Clip card (`appClipBannerTag()` in `ue-sdk-web`) | that URL, after one tap on the card |

```swift
// SwiftUI App Clip
.onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
    Task {
        do {
            let invocation = try AppClipInvocation.parse(activity, allowedHosts: ["clip.bank.example"])
            let verdict = try await QrSessionClient().claim(
                sasBaseUrl: "https://sas.example.et", sessionId: invocation.sessionId,
                bearer: CellularBearer())
            // show done / fallback; the bank page polls the verdict
        } catch {
            // AppClipInvocation.Refused, CellularBearer.Unavailable, …: fallback
        }
    }
}
```

`AppClipInvocation` accepts only `https` on the bank's listed hosts, with the session
UUID in the last path segment or `?session=` / `?s=`. A look-alike domain or an
ambiguous URL is refused.

Two iOS behaviours to plan for:

- From the App Library or Spotlight, iOS relaunches with the **previous** URL. That
  session is already claimed or expired, so the claim comes back not approved.
- From a notification or the App Switcher there is no URL at all. Start again from
  the bank's page.

iOS never opens an App Clip silently: one tap on the card is always required.

Not yet proven: an App Clip build on a real device. Still to do on the bank side:
App Clip target, `appclips:` Associated Domains, AASA file, App Clip experience in
App Store Connect. Still to do on the server: show who is asking before the claim
(anti-hijack).

Design and verification: `docs/design/camara-qr-handoff.md`.

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Restlink. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, private builds, **a permissive (Apache-2.0/MIT) SDK option** for apps that cannot carry AGPL, L1/L2 SLA and integration engineering. Terms per deployment via Restlink. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
