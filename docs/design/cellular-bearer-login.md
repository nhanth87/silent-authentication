# Cellular bearer login — UE SDK transport contract

**Scope:** how the UE SDKs (JVM, Android, iOS, Web) put a silent-auth request on
a **2G/3G/4G/5G data bearer** instead of Wi-Fi, and what the SAS does with the
device's bearer declaration. Added after an audit found the SDKs had no transport
control at all: they posted the session tuple over whatever route the OS
preferred, which on a phone with Wi-Fi attached means the Wi-Fi address reached
the Resolver and the IP-match could never attest the MSISDN.

Anchors: AGENTS.md §2 (IP method needs a cellular bearer), §3 (Resolver = PGW),
§4 (fail-closed, CGNAT ⇒ IP+port+ts), CAMARA Number Verification v2.1.0,
TS 29.002 (MAP), TS 29.272 (S6a), TS 33.402 (TS.43 Wi-Fi path).

---

## 1. Why the bearer, not just the IP

The Resolver answers "which MSISDN owns this IP:port right now" from
PGW/GGSN/CGNAT state. That state only exists for packets that traversed the
packet core, so a Wi-Fi address is not operator-attested:

| Bearer the request used | What the SAS can bind | Verifier path |
|------------------------|----------------------|---------------|
| 4G LTE / 5G NR | PGW/UPF IP (+ CGNAT port) | Diameter S6a / 5G |
| 2G/3G GPRS/EDGE/UMTS | SGSN/PDSN IP | MAP PSI/ATI/SAI |
| Wi-Fi / Ethernet | nothing operator-owned | TS.43 EAP-AKA only (operator token) |
| Wi-Fi calling (`IWLAN`) | nothing — packets leave over Wi-Fi | treated as Wi-Fi |

"Add 4G/5G login" is therefore two changes: **pin the socket** where the
platform allows it, and **declare the bearer** so the SAS can refuse a tuple it
must not trust.

## 2. Platform reality (verified against vendor docs, 2026-08-30)

| Platform | Pin one request to cellular? | How | Caveats |
|----------|------------------------------|-----|---------|
| Android | **Yes** | `ConnectivityManager.Network.openConnection(URL)` (API 21+); `getHttpsURLConnection` API 28+ | getting a non-default network needs `requestNetwork()`, whose `NetworkCallback` is an **abstract class** — a plain-Java artifact without `android.jar` cannot subclass it, so the host app owns that step |
| Android | Process-wide alternative | `bindProcessToNetwork()` (API 23+, `CHANGE_NETWORK_STATE`) | **not used here**: hijacks every socket in the bank app and is easy to leak |
| iOS | **No, for URLSession** | `URLSessionConfiguration` only has `allowsCellularAccess`, `allowsExpensiveNetworkAccess`, `allowsConstrainedNetworkAccess`, `allowsUltraConstrainedNetworkAccess` — they *permit*, never *prefer* | the widely-quoted `URLSessionConfiguration.requiredInterfaceType = .cellular` **does not exist** |
| iOS | Partially, raw sockets | `NWParameters.requiredInterfaceType = .cellular` + `prohibitedInterfaceTypes = [.wifi]` on `NWConnection` (iOS 14+) | `NWParameters` constrains `NWConnection`, not `URLSession`; needs hand-rolled HTTP/1.1+TLS |
| iOS | Browser flow | impossible | `ASWebAuthenticationSession` runs out of process; iCloud Private Relay rewrites egress to an Apple relay IP (unattestable); Wi-Fi Assist can silently move off cellular |
| Web | **No** | none | `navigator.connection` is an observation, absent in Safari/Firefox; `effectiveType` is throughput, not radio |
| JVM (core SDK) | No | none | the `Connector` seam exists so a platform can supply the pinning |

Detection per platform:

- **Android** — `NetworkCapabilities.hasTransport(TRANSPORT_CELLULAR)` +
  `NET_CAPABILITY_INTERNET` (needs only `ACCESS_NETWORK_STATE`); generation via
  `TelephonyManager.getDataNetworkType()` (API 29+, `READ_PHONE_STATE`), falling
  back to `getNetworkType()`. Because this artifact compiles **without
  `android.jar`**, the numeric `TRANSPORT_*` / `NET_CAPABILITY_*` /
  `NETWORK_TYPE_*` values are resolved reflectively from the framework at
  class-init (`CellularBearer.intConstant`); the literals in the source are only
  off-device fallbacks for the JVM tests. A guessed constant would mis-route
  every bearer decision, so none is trusted.
- **iOS** — `NWPathMonitor.currentPath.usesInterfaceType(.cellular)`; radio via
  `CTTelephonyNetworkInfo.serviceCurrentRadioAccessTechnology`.
- **Web** — `navigator.connection.type`; `effectiveType` may raise the claim at
  most to `LTE`, never `NR`.
- **JVM** — interface-name heuristic (`rmnet*`/`ccmapi*`/`pdp_ip*` → cellular,
  `wlan*`/`en*` → Wi-Fi). A tunnel (`tun*`, VPN) is `UNKNOWN`, never cellular.

An unreadable radio yields `GS_2G3G` — conservative: MAP PSI still answers for
LTE/NR subscribers, whereas a wrong `LTE` claim routes to S6a and misses. The
SDKs never guess upward.

## 3. Wire contract

`POST /session-tuple` gains an optional declaration; the same value is mirrored
in an `X-Sas-Access-Tech` header so SAS logs and CDRs see both:

```json
{ "srcIp": "100.64.12.34", "srcPort": 55555, "ts": 1724200000000,
  "msisdn": "+251911111111", "accessTech": "LTE" }
```

| `accessTech` | SAS verdict on `/session-tuple` |
|--------------|--------------------------------|
| `GS_2G3G` / `LTE` / `NR` | accepted, seeds the binding |
| absent / blank | accepted (legacy client), logged `undeclared` |
| `WIFI` / `WLAN` / `IWLAN` / `FIXED` / `ETHERNET` | **400 `ACCESS_TECH_NOT_CELLULAR`** |
| anything else | **400 `VALIDATION.FAILED`** — never defaulted to cellular |

Note the asymmetry with `/verify`'s `X-Sas-Access-Tech`, which keeps its
historical `GS_2G3G` default: `/verify` is called by the bank backend under mTLS
and the verifier choice is the FSM's business, while `/session-tuple` is a
*registration* endpoint where an unparseable device claim must not invent a
binding. Logic: `SessionTupleResource.declaredAccessTech()`, covered by
`SessionTupleAccessTechGateTest` and harness gate **H22** (vocabulary parity
across the SAS and all four SDKs, mutation-checked).

**Declaration ≠ attestation.** A device can lie about `accessTech`, so the field
is only used to *exclude* known-bad tuples and to correlate CDRs. Assurance still
comes from what the network itself confirms (see `silent-auth-standard-flow.md`).
Hardening steps recorded as open items in `sas-host/TODO.md`: compare the
declaration against the observed source address of the tuple POST, and bind it to
a platform attestation (Play Integrity / DeviceCheck).

## 4. Client policy (`CellularRequirement`)

| Level | Meaning | Use |
|-------|---------|-----|
| `ANY` | no bearer demand | lab, TS.43 Wi-Fi experiments |
| `CELLULAR` | any radio generation | default for production silent auth |
| `CELLULAR_4G_PLUS` | LTE or NR only | bank demands EPS/5GS-verifiable evidence |

An unmet requirement throws **before the socket opens**
(`CellularRequirement.CellularBearerException` in core,
`CellularUnavailableException` on Android, `CellularBearer.Unavailable` on iOS,
`CellularUnavailableError` on Web). The app maps it to the OTP / passkey / push
path — the same fail-closed rule as the SAS FSM, applied one hop earlier so a
doomed attempt never reaches the operator.

## 5. Android flow (what the bank app writes)

```
app                 ue-sdk-android                  platform
 │  bind(ctx, CELLULAR_4G_PLUS)
 ├──────────────────────►│ getActiveNetwork → hasTransport(CELLULAR) && INTERNET?
 │                       ├── no → walk getAllNetworks ──┐
 │                       │                              │ none → throw (fail closed)
 │                       │◄──── Network(cellular) ──────┘
 │                       │  accessTech = TelephonyManager.getDataNetworkType() → LTE
 │  post(url, TupleSnapshot.cellularNow(msisdn, LTE), key, bearer)
 ├──────────────────────►│  bearer.open(url) ──► Network.openConnection(url)
 │                       │                       (only THIS socket is pinned)
 │                       │◄── 200 ── SAS seeds IP:port → MSISDN binding
```

The SDK never calls `bindProcessToNetwork()` and never registers a
`NetworkCallback`, so there is no process-wide state to leak inside a banking
app (a stranded cellular binding means mobile-data billing and broken sibling
SDKs). When an app *needs* `requestNetwork()` — choosing a SIM on a dual-SIM
handset, or forcing cellular while Wi-Fi is the default route — it implements the
callback and hands the resulting `Network` to
`CellularBearer.fromNetwork(network, tech)`, then `unregisterNetworkCallback()`.

## 6. No mobile data — required UX

`requestNetwork(TRANSPORT_CELLULAR)` simply never fires `onAvailable()` when data
is off; a callback without a timeout hangs the login. Contract for the app:

1. `bind(...)` throws → render the **fallback** (OTP/passkey) with a "turn on
   mobile data" hint. Never auto-retry over Wi-Fi.
2. iOS: `CellularBearer().wait(for: .cellular, timeout: 1.0)` after the user
   toggles Settings — radio handover is not instantaneous.
3. Do not widen the 3 s SAS total to "help" a weak bearer; falling back on time
   beats a stalled login.

## 7. Tests

| Module | Command | Focus |
|--------|---------|-------|
| `ue-sdk` | `mvn -pl ue-sdk test` (JDK 25) | cellular wins over `wlan0`, VPN/UNKNOWN fail closed, wire `accessTech`, `Connector` actually used |
| `ue-sdk-android` | `mvn -pl ue-sdk-android test` | `NETWORK_TYPE_*` mapping incl. `IWLAN → WIFI`, `bind()` with no context fails closed, `fromNetwork()` rejects Wi-Fi, closed bearer refuses to reopen, header + body contract |
| `ue-sdk-ios` | `swift test` on Xcode CI — **no Swift toolchain on this Linux box, so this SDK is not compile-verified here** | radio mapping purity, `assumed:` gate matrix, cellular session flags, `accessTech` encoding |
| `ue-sdk-web` | `node --test` | conservative `effectiveType`, `requireCellular` refuses **before** `fetch`, wire contract |
| harness | `python3 harness/run_hardness.py` | H22 vocabulary parity |

