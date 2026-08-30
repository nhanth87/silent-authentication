# Silent Auth UE SDK (Android)

`et.restlink:ue-sdk-android` — device-side poster for the Silent Auth SAS
`POST /session-tuple` endpoint. Plain `java.net.HttpURLConnection`, zero
third-party runtime dependencies, Java 8 bytecode (Android minSdk 24).
Devices cannot observe the real CGNAT IP/port, so snapshots carry `ts` plus
an optional app-supplied MSISDN; nulls are omitted. No authentication logic.

## Usage

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

## Notes

- **minSdk 24** — `HttpURLConnection` + UTF-8 string APIs used here are all
  available on API 24; the artifact compiles with `-release 8`.
- **Call off the main thread** always.
- Send `X-Api-Key` when SAS enforces keys; an enforced endpoint rejects with
  `401 UNAUTHENTICATED`.
- Privacy: the SDK never reads subscriber identifiers; pass `msisdn` only if
  your app owns it, and keep it off any UI per project privacy rule.

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Restlink. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, private builds, **a permissive (Apache-2.0/MIT) SDK option** for apps that cannot carry AGPL, L1/L2 SLA and integration engineering. Terms per deployment via Restlink. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
