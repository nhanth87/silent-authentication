# CAMARA Number Verification v2: device SDKs (Android, iOS, Web)

> Status: implemented 2026-10-14 on branch `nv-2`. Scope: the **device** side only.
> The server (sas-camara / sas-ecs) is unchanged. Request:
> [`../request_nâng_cấp_sdk_android_ios_web_lên_camara_v2.md`](../request_nâng_cấp_sdk_android_ios_web_lên_camara_v2.md).

## 1. What the device does in NV v2

CAMARA NV v2 is an OIDC flow. The device's only job is to carry the bank's
authorization request over the right path and return the code. The device does
**not** hold the OAuth client, the PKCE verifier, the `private_key_jwt` key or the
access token. It never calls `/verify` itself.

```
 app              bank backend                 device SDK                    sas-camara (operator AS)
  │  start NV  ──►│                                │                                │
  │               │ build /authorize?response_type=code&client_id&redirect_uri      │
  │               │   &scope&state&nonce&code_challenge(S256)&prompt=none           │
  │               │ keep code_verifier                                             │
  │◄──── url ─────│                                │                                │
  │──────────────────────── authorize(url) ───────►│                                │
  │                                                │ cellular? ── GET /authorize ──►│ source IP:port
  │                                                │   (pinned)                     │  → resolver → MSISDN
  │                                                │ no cellular → token source     │
  │                                                │   GET /authorize&login_hint=   │ operator token
  │                                                │   operatortoken:<tk> (any if)  │  → ECS redeem → MSISDN
  │                                                │◄── 302 redirect_uri?code&state │
  │                                                │ (redirect caught, never loaded; state checked)
  │◄──────────────────────── code ─────────────────│                                │
  │──── code ────►│                                                                 │
  │               │── POST /token (authorization_code + code_verifier + client auth)►│
  │               │── POST /number-verification/v2/verify {phoneNumber} ────────────►│
  │               │◄─────────────────── {"devicePhoneNumberVerified": true|false} ──│
```

`prompt=none` is mandatory. Without it an authorization server may answer with a
page, which an SDK would then load without anyone looking at it. Every SDK
refuses such a request before sending anything.

## 2. Device state machine (same on all three platforms)

```
VALIDATE ── rule broken ───────────────────────────► refuse (backend bug), nothing sent
   │
CELLULAR ── bearer granted ──► AUTHORIZE(NETWORK) ── code ──► RESULT(NETWORK)
   │                               │
   │                               └─ access_denied + token source + no backend hint ─┐
   └─ no cellular ─────────────────────────────────────────────────────────────────────┤
                                                                                       ▼
                                            TOKEN ── token ──► AUTHORIZE(OPERATOR_TOKEN) ── code ──► RESULT
                                              └─ no source / no token ──► CELLULAR_UNAVAILABLE (fallback)
AUTHORIZE: follow redirects on the same bearer until redirect_uri; error= ──► AuthorizationError
```

| Outcome | Android | iOS | Web |
|---|---|---|---|
| code | `AuthorizationResult` | `AuthorizationResult` | `parseAuthorizationCallback` → `{code,state}` |
| request refused by the device | `IllegalArgumentException` | `AuthorizeURL.Invalid` | `TypeError` |
| no bearer, no token | `CellularUnavailableException` | `CellularBearer.Unavailable` | `CellularUnavailableError` |
| AS refused / bad answer | `AuthorizationException` | `AuthorizationError` | `AuthorizationError` |
| transport | `IOException` | `TransportError` / `NWError` | navigation error |

Every non-result is a **fallback signal** (OTP / passkey). Nothing on the device
ever means "verified". Only the backend's `/verify` answer does.

## 3. Timeouts and retries

| Stage | Android | iOS | Web | On expiry |
|---|---|---|---|---|
| Bring cellular up | `requestNetwork` 5 s | NWConnection `.waiting` = no path (immediate) | n/a | token path, else fallback |
| Per hop | connect 5 s / read 5 s | 5 s | browser | 1 retry on a transport error |
| Redirect hops | 10 | 10 | browser | `too_many_redirects` |
| Whole call | 15 s | 15 s | browser | timeout error |

OAuth errors are **answers**, never retried. `server_error` and
`temporarily_unavailable` report `retryLater`; the app decides.

## 4. Platform matrix: what is real and what the request got wrong

| Topic | Request snippet | What is implemented, and why |
|---|---|---|
| Android cellular with Wi-Fi up | `getActiveNetworkForType(TYPE_MOBILE)` + OkHttp `socketFactory` | Deprecated, and it only *reports* a mobile network that is already up; with Wi-Fi connected Android keeps mobile data down. Implemented: `ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR + NET_CAPABILITY_INTERNET)`, then `Network.openConnection(URL)` for each hop. The callback stays registered while the bearer is used and is unregistered on `close()`. Never uses `bindProcessToNetwork`. Needs `CHANGE_NETWORK_STATE` + `ACCESS_NETWORK_STATE`. |
| iOS cellular | `bind()` a BSD socket to `192.168.1.1` | The cellular address is assigned by the PGW and changes; a hard-coded bind cannot work. `URLSession` cannot pin either. Implemented: `NWConnection` with `NWParameters.requiredInterfaceType = .cellular` + `prohibitedInterfaceTypes = [.wifi, .wiredEthernet]`, HTTP/1.1 over Network.framework TLS (system trust, TLS ≥ 1.2). |
| Web SIM token | `navigator.getSimToken()` | Does not exist in any browser. The OS route that exists is the W3C **Digital Credentials API** (`navigator.credentials.get({digital:{requests}})`). It needs a user gesture and shows an OS sheet, so it is SIM-based but not silent. `requestSimCredential()` passes the backend's request through and returns the answer to the backend. Silent web NV = top-level navigation with `prompt=none` from a page whose bearer is cellular; the page cannot pin it. |
| TS.43 token on the device | "SDK obtains TS.43 token" | The EAP-AKA run against the SIM needs carrier privileges (`TelephonyManager.getIccAuthentication`). iOS offers third-party apps no such API at all. A bank app therefore receives the token from what the operator provides. The SDKs take it through `OperatorTokenSource` and do not pretend to mint it. |
| DPoP (RFC 9449) | "token binding" | The access token belongs to the **backend**, not the device, so a device-held DPoP key would bind the wrong party. DPoP is a backend + AS concern and is not in the device SDKs (open item §7). |
| TLS / cert validation | required | Android: `HttpsURLConnection` defaults (no trust override anywhere). iOS: Network.framework default trust + TLS 1.2 floor. Web: the browser. `http://` only behind an explicit lab switch. |
| Privacy | no logging of tokens / MSISDN / SIM | No SDK logs anything. Error messages carry rule names and OAuth codes only, never the URL (it may carry a `login_hint`), the code, a token or a number. Result `toString`/`description` redacts the code. The server's `error_description` is kept off the message. |

## 5. API per platform

**Android** (`ue-sdk-android`, Java 8 bytecode, minSdk 24):

```java
NumberVerification nv = NumberVerification.builder(context)
        .requirement(CellularRequirement.CELLULAR)          // or CELLULAR_4G_PLUS
        .operatorTokens(operatorTokenSourceOrNull)          // Wi-Fi path; null = off
        .build();
AuthorizationResult r = nv.authorize(urlFromBackend);       // blocking: worker thread
backend.sendCode(r.code(), r.state());
```

The module compiles without `android.jar`. `NetworkCallback` must be subclassed,
so three framework classes (`ConnectivityManager`, `Network`, `NetworkRequest`)
exist as **compile-only signature stubs** in `src/stubs/java`. The jar excludes
them, and on a device the framework classes are used.

**iOS** (`ue-sdk-ios`, Swift 5.9, iOS 14):

```swift
var configuration = NumberVerification.Configuration()
configuration.operatorTokens = operatorTokenSourceOrNil
let result = try await NumberVerification(configuration: configuration).authorize(urlFromBackend)
```

**Web** (`ue-sdk-web`, `@restlink/ue-sdk-web/number-verification`):

```js
startAuthorization(urlFromBackend, { operatorToken });          // navigates; throws on a Wi-Fi page without a token
// on the redirect_uri page:
const { code } = parseAuthorizationCallback(location.href, stateFromBackend);
```

## 6. Verification (2026-10-14)

| What | Command | Result |
|---|---|---|
| Android unit tests | `(cd ue-sdk-android && mvn -o test)` | 62/62 (new: `NumberVerificationTest` 18, `AuthorizeUrlTest` 8, `CellularNetworkRequestTest` 6) |
| Web unit tests | `(cd ue-sdk-web && node --test)` | 39/39 (new: 14) |
| iOS unit tests on Linux | `docker run --rm -v $PWD/ue-sdk-ios:/w -w /w swift:5.10-jammy swift test` | 47 run, 44 pass, 3 skipped (JSONEncoder key order is Apple-specific), 0 fail |
| E2E vs packaged dists | `scripts/sdk-nv2-e2e.sh` | Android A–E OK, Web A–D OK, 1 known server gap each (below) |

E2E, in a private network namespace where `lo` also carries `10.20.30.40` (the
address the lab resolver binds to `+251911111111`) and `trust-src-header=false`:

- **Network path, Android:** request on a socket from `10.20.30.40:55555`, then
  `302 code`, then backend `/token`, then `/verify` → `{"devicePhoneNumberVerified":true}`.
  sas-camara logged `issued a code (network auth)`. The subscriber came from the
  **connection source**, not a header.
- **Network path, Web:** the same through `startAuthorization` + `parseAuthorizationCallback` → `true`.
- **127.0.0.1** (no PGW-assigned address) → `access_denied … NO_BINDING`. The Android SDK
  then fell through to the operator token and got a code.
- **Wi-Fi without a token:** refused on the device; nothing was sent.
- **Spent operator token:** second `/authorize` → `access_denied`.
- **No `prompt=none`:** refused on the device.

**Not verified here:** `CellularHTTPClient` (NWConnection) is not compiled on
Linux (Network.framework is Apple-only), and nothing ran on a real handset. That
needs `swift test` on macOS and a device test on Android with Wi-Fi + 4G/5G,
Wi-Fi only and cellular only, per the request's platform-testing list.

## 6b. App Clip (iOS, no bank app installed) — 2026-10-14

App Clip rules, taken from Apple's documentation ("Choosing the right functionality
for your App Clip", "Responding to invocations", "Supporting invocations from your
website and the Messages app"):

| Rule | Effect on the SDK |
|---|---|
| Network.framework + `URLSession` allowed; CFSocket/POSIX sockets **not** | `NWConnection` pinning works; a BSD `bind()` cannot even be attempted |
| CoreTelephony allowed (not eSIM provisioning) | radio generation readable |
| No background activity | the SDK has none |
| No Face ID; Touch ID via LocalAuthentication | presence check, if any, is Touch ID in an App Clip |
| 10 / 15 / 100 MB (iOS 15 / 16 / 17+ digital only) | SDK is a few hundred KB |
| Invocation = `NSUserActivityTypeBrowsingWeb`, `webpageURL`; `app-argument` not passed | session id must be in the URL path |
| Relaunch from App Library/Spotlight reuses the old URL; from a notification/App Switcher there is none | stale → claim not approved; none → `Refused` |

Changes:

1. `QrSessionClient.claim()` (iOS) now goes through `CellularHTTPClient` (POST,
   `requiredInterfaceType = .cellular`). Before, it used `URLSession` and failed
   closed whenever Wi-Fi was on, which defeats the reason to use an App Clip.
   `CellularHTTPClient` gained POST with `Content-Length`, header-injection refusal
   and chunked decoding.
2. `AppClipInvocation` (iOS): https only, bank host allowlist, session UUID in the
   path or `?session=`/`?s=`, ambiguous URLs refused.
3. `hand-off.js` (web): `presentHandOff()` returns a QR code on a desktop and a link
   on a phone, `handOffLink()` builds `https://<clip host>/…/<id>`, and
   `appClipBannerTag()` renders the Safari App Clip card tag.

```
desktop:  bank page ─ QR(https://clip.bank.example/s/<id>) ─► iPhone camera ─ tap card ─► App Clip
iPhone:   bank page ─ button / Safari App Clip card (same URL) ─────────────── tap card ─► App Clip
App Clip: AppClipInvocation.parse ─► QrSessionClient.claim  (NWConnection, cellular only)
          ─► sas-camara /qr-session/v1/<id>/claim ─► verify FSM ─► verdict
bank page: polls GET /qr-session/v1/<id> ─► approved / fallback
```

Tests: iOS 57 run, 54 pass, 3 skipped on Linux (10 new App Clip tests); web 43/43
(4 new). Not proven: an App Clip build on a device.

## 7. Open items

- [x] **Server gap closed 2026-10-14** (`camara-nv2-phase1-2.md` §2.1): SIM-based codes
      and tokens now carry `amr ["hwk","mno"]`; `scripts/sdk-nv2-e2e.sh` case B/C answer
      `/verify` true.
- [ ] Real-handset runs: Android Wi-Fi+4G/5G, Wi-Fi only, cellular only; iOS on device.
- [ ] `swift test` on macOS (compiles `CellularHTTPClient`).
- [ ] DPoP on the backend ↔ AS hop (server work).
- [ ] App Clip on a real device (bank side: target, `appclips:` domains, AASA, App Store Connect experience).
- [ ] Anti-hijack: the claim UI should show who is asking (bank, action) **before** claiming;
      `GET /qr-session/v1/{id}` does not return that yet (server work).
- [ ] TS.43 token acquisition stays with the operator (carrier privileges). The lab
      issuance (`/entitlement/issue`) proves nothing about a SIM.
