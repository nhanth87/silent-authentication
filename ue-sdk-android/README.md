# Silent Auth UE SDK (Android)

**CAMARA Number Verification v2** on Android: the silent authorization request over a
requested cellular network, the TS.43 operator-token path for Wi-Fi, the
desktop → phone QR claim, and the older session-tuple poster below.

`et.restlink:ue-sdk-android` — device-side poster for the Silent Auth SAS
`POST /session-tuple` endpoint. Plain `java.net.HttpURLConnection`, zero
third-party runtime dependencies, Java 8 bytecode (Android minSdk 24).
Devices cannot observe the real CGNAT IP/port, so snapshots carry `ts` plus
an optional app-supplied MSISDN; nulls are omitted. No authentication logic.

## CAMARA Number Verification v2 (start here)

The device half of NV v2: carry the bank backend's OIDC authorization request
(`prompt=none`, PKCE S256) over the **cellular** network and hand the code back.
The backend redeems it (`private_key_jwt` + the PKCE verifier only it holds) and
calls `POST /number-verification/v2/verify`. That answer is the verdict, never
anything on the device. Design, state machine and test evidence:
[`../docs/design/camara-nv2-device-sdk.md`](../docs/design/camara-nv2-device-sdk.md).

```java
// Worker thread: blocking network I/O.
NumberVerification nv = NumberVerification.builder(context)
        .requirement(CellularRequirement.CELLULAR)    // or CELLULAR_4G_PLUS
        .operatorTokens(tokenSource)                  // optional: TS.43 path for Wi-Fi
        .build();
try {
    AuthorizationResult r = nv.authorize(authorizeUrlFromBackend);
    backend.sendCode(r.code(), r.state());            // send at once; never log or store it
} catch (CellularUnavailableException | AuthorizationException e) {
    // fallback: OTP / passkey. Never retry over Wi-Fi without a token.
}
```

What happens inside:

1. **Validate** the URL: https, `response_type=code`, PKCE `S256`, `prompt=none`,
   `state`, `scope`, `client_id`, absolute `redirect_uri`, no repeated parameter.
   A broken request throws `IllegalArgumentException` and nothing is sent.
2. **Bring cellular up** with `ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR +
   NET_CAPABILITY_INTERNET)`. This works **while Wi-Fi is the default route**, unlike
   `getActiveNetworkForType(TYPE_MOBILE)`, which only reports a network that is already
   up. Wait 5 s. Every hop is opened with `Network.openConnection(URL)`, and the request
   is unregistered when the call ends. `bindProcessToNetwork` is never used.
3. **Follow redirects** on that same network (max 10) until the `redirect_uri`. The
   redirect is caught, never loaded. `state` is compared in constant time.
4. **No cellular** (or the network answered `access_denied`): if an
   `OperatorTokenSource` is set, append `login_hint=operatortoken:<token>` and send
   over the default route (SIM-based, GSMA TS.43). Otherwise throw
   `CellularUnavailableException`.

The TS.43 token itself needs carrier privileges on the SIM
(`TelephonyManager.getIccAuthentication`), which a bank app does not have. It comes
from what the operator provides, and the SDK does not mint it.

Permissions: `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` (both normal), and
`READ_PHONE_STATE` only if you want the radio generation (LTE/NR). Without it the
bearer reports a conservative `GS_2G3G`. Timeouts: 5 s connect / 5 s read per hop,
one retry on a transport error, 15 s total. OAuth errors are never retried.
Privacy: the SDK logs nothing, exception messages never contain the URL, the code,
a token or a number, and `AuthorizationResult.toString()` redacts the code.

On a dual-SIM phone, request the chosen subscription's network yourself and pass it
in: `.bearerSource((need, t) -> CellularBearer.fromNetwork(network, tech))`.

## Usage (session tuple, pre-v2)

```java
// OFF the main thread — this is blocking network I/O
// (NetworkOnMainThreadException otherwise). Use a worker/executor.
SessionTupleClient client = new SessionTupleClient(3000, 3000); // connect/read, ms

TupleSnapshot snapshot = TupleSnapshot.now("+251911111111"); // msisdn optional, app-supplied
int status = client.post(
        "https://sas.example.et",    // SAS base URL — HTTPS in production
        snapshot,
        tenantApiKey);               // nullable X-Api-Key

if (status != 200) {
    // non-fatal for login: SAS falls back to OTP/TOTP path
}
```

Request produced:

```
POST /session-tuple
Content-Type: application/json
X-Api-Key: <key>
X-Sas-Access-Tech: LTE

{"ts":1724200000000,"msisdn":"+251911111111","accessTech":"LTE"}
```

`TupleSnapshot.now(...)` declares `UNKNOWN` (and omits the field); take a
`CellularBearer` and use `TupleSnapshot.cellularNow(...)` below to send a real
bearer with the tuple.

## Logging in over the mobile network (2G/3G/4G/5G)

CAMARA Number Verification by IP-match only works if the request that the SAS
sees left through a **cellular data bearer**: only the PGW/GGSN can attest
`IP → MSISDN`. On Wi-Fi the address belongs to a ISP/enterprise NAT and the SAS
must fall back — so this SDK pins its own call to the cellular `Network`,
declares the radio in the tuple, and **fails closed** when there is no cellular
data at all.

```kotlin
// 1) Ask the SDK for a cellular bearer. Throws CellularUnavailableException if
//    mobile data is off / no validated cellular network exists — that IS the
//    answer, take the OTP path instead of retrying over Wi-Fi.
val bearer = try {
    CellularBearer.bind(context, CellularRequirement.CELLULAR_4G_PLUS)
} catch (e: CellularUnavailableException) {
    return loginWithOtp(reason = e.message)   // never "just try Wi-Fi"
}

// 2) Collect + post the tuple ON that bearer, off the main thread.
executor.execute {
    val snapshot = TupleSnapshot.cellularNow(claimedMsisdn, bearer.accessTech())
    SessionTupleClient().post(sasBaseUrl, snapshot, apiKey, bearer)
    bearer.close()
}
```

`CellularBearer` opens each request with `Network.openConnection(URL)`, so
**only the SDK's call** is pinned. Deliberately *not* used:
`ConnectivityManager.bindProcessToNetwork()` — it is process-wide (it would drag
push, analytics and every other SDK onto mobile data), needs
`CHANGE_NETWORK_STATE`, and is easy to leak when the callback is never
unregistered.

### Pinning a specific SIM (dual-SIM) or an explicit request

`ConnectivityManager.NetworkCallback` is an abstract class, which a plain-Java
artifact compiled without `android.jar` cannot subclass. If you need
`requestNetwork()` (a chosen subscription, or a guaranteed bearer even while
Wi-Fi is the default route), do it in your app and hand the `Network` to us:

```kotlin
private val cm = context.getSystemService(ConnectivityManager::class.java)
private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        // API 21+; runs on a binder thread.
        val bearer = CellularBearer.fromNetwork(network, AccessTech.LTE)
        // post the tuple / fire the /verify-triggering request with `bearer`
        bearer.close()
        cm.unregisterNetworkCallback(this)   // always release the request
    }
    override fun onLost(network: Network) { /* drop the bearer, fall back */ }
    override fun onUnavailable() { loginWithOtp("no cellular data") }
}

cm.requestNetwork(
    NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        // dual-SIM, API 33+: .setNetworkSpecifier(
        //     telephonyManager.createForSubscriptionId(subId).cellularIdentifier)
        .build(),
    callback)      // or the (callback, timeoutMs) overload — do not wait forever
```

### Permissions and radio facts

| Need | API | Permission | Notes |
|------|-----|-----------|-------|
| Choose / pin the cellular `Network` | 21+ | `ACCESS_NETWORK_STATE` | what `CellularBearer` uses |
| `bindProcessToNetwork()` | 23+ | `CHANGE_NETWORK_STATE` | process-wide, **avoid** |
| `TelephonyManager.getDataNetworkType()` | 29+ | `READ_PHONE_STATE` | gives `NETWORK_TYPE_*`; falls back to `getNetworkType()` |
| `createForSubscriptionId()` | 25+ | `READ_PHONE_STATE` | dual-SIM bearer choice |

`NETWORK_TYPE_IWLAN` (Wi-Fi calling) is mapped to **`WIFI`**, not cellular: the
packets leave over the Wi-Fi network, so the operator cannot bind their IP to
your MSISDN. If the radio cannot be read the SDK declares `GS_2G3G`
(conservative — MAP PSI still answers for LTE/NR subscribers) and never guesses
`LTE`/`NR`.

## Build / test


```bash
JAVA_HOME=$HOME/.local/share/mise/installs/java/zulu-25 mvn clean test
```

`src/stubs/java/android/net/` holds **compile-only** signature stubs of the three
framework classes `requestNetwork()` needs (`NetworkCallback` must be subclassed, and
reflection cannot do that without `android.jar`). The jar excludes `android/**`, and
on a device the framework classes are used. Do not add a method to a stub that the
framework does not have.

## Notes

- **minSdk 24** — `HttpURLConnection` + UTF-8 string APIs used here are all
  available on API 24; the artifact compiles with `-release 8`.
- **Call off the main thread** always.
- Send `X-Api-Key` when SAS enforces keys; an enforced endpoint rejects with
  `401 UNAUTHENTICATED`.
- Privacy: the SDK never reads subscriber identifiers; pass `msisdn` only if
  your app owns it, and keep it off any UI per project privacy rule.


## Desktop → phone QR hand-off (CAMARA NV v2)

A desktop login has no SIM, so the IP-match path cannot help the person sitting
at it. The bank renders a QR code; the phone scans it and claims the session.

```java
// OFF the main thread — blocking I/O. There is deliberately no executor here:
// the hosting app owns its threading.
QrSessionClient qr = new QrSessionClient();
String sessionId = QrSessionClient.parsePayload(scanned);   // refuses anything not a UUID

QrSessionClient.ClaimResult verdict = qr.claim(
        "https://sas.example.et",
        sessionId,
        cellularBearer,                // binds the socket to the cellular Network
        CellularRequirement.CELLULAR,  // fails closed before the round trip
        null,                          // msisdn, discovery variant
        null,                          // operatorToken, Wi-Fi variant
        apiKeyNullable);

if (!verdict.approved()) {
    // fallbackReason is set — fall back to OTP / passkey / push
}
```

The bearer is the point: `Network.openConnection(URL)` pins **only** the returned
connection to the cellular network, which is what makes the source IP
attestable. A browser and a desktop cannot do this at all — that asymmetry is why
the hand-off exists. A phone on Wi-Fi either carries a TS.43 temporary token or
must fall back to OTP.

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
