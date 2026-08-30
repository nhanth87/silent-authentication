# Silent Auth UE SDK

`et.restlink:ue-sdk` — device-side collector for the Silent Auth SAS
`POST /session-tuple` endpoint.

**Purpose:** path A CGNAT disambiguation. The SDK captures the device's
current bearer source IP and a capture timestamp and posts them to SAS so the
Resolver has a fresh IP→MSISDN binding candidate to match during `/verify`.
It is a thin transport + discovery helper — **it contains no authentication
logic**; approval always happens server-side (fail-closed FSM in the SAS).

## What it collects

| Field | Source | Notes |
|-------|--------|-------|
| `srcIp` | first global/site-local IPv4 across `NetworkInterface`s (deterministic order by interface index) | best-effort; `null` when nothing usable |
| `srcPort` | — | **always `null`**: the CGNAT source port is not observable on the device; the Resolver correlates on IP + timestamp |
| `ts` | `System.currentTimeMillis()` | epoch ms at capture |
| `msisdn`, `imsi` | only if the embedding app supplies them | the SDK never reads subscriber identifiers itself |

Zero third-party runtime dependencies (`java.net` only). Plain
`HttpURLConnection` and `NetworkInterface` work on Android API 24+ — run
collection **off the main thread** (network I/O).

## Usage

```java
SessionTupleCollector collector = new SessionTupleCollector();
var snapshot = collector.collect();

SessionTupleClient client = new SessionTupleClient(3000, 3000); // connect/read timeouts, ms
int status = client.post(
        "https://sas.example.et",   // SAS base URL
        snapshot,
        System.getProperty("sas.apikey")); // nullable X-Api-Key

if (status != 200) {
    // non-fatal for login: SAS falls back to OTP/TOTP path
}
```

Request produced:

```
POST /session-tuple
Content-Type: application/json
X-Api-Key: <key>
X-Sas-Access-Tech: GS_2G3G

{"srcIp":"100.64.12.34","ts":1724200000000,"accessTech":"GS_2G3G"}
```

Null fields are omitted; `accessTech` is omitted entirely when the bearer could
not be read (`UNKNOWN`) — the SAS then treats the tuple as undeclared rather than
assuming cellular.

## Choosing the bearer: 2G/3G/4G/5G vs Wi-Fi

The SAS IP-match path is only sound over a cellular data bearer — only the
PGW/GGSN can attest `IP → MSISDN`. Every snapshot therefore declares the
`AccessTech` it was captured on (`GS_2G3G | LTE | NR | WIFI | FIXED | UNKNOWN`),
and the collector refuses to pretend:

```java
var collector = new SessionTupleCollector();
var client = new SessionTupleClient();

// Fail closed when the phone is on Wi-Fi: the caller falls back to OTP.
var snapshot = collector.collectCellular(CellularRequirement.CELLULAR);
client.post(sasBaseUrl, snapshot, apiKey, connector);
```

`CellularRequirement` has three levels — `ANY` (lab / TS.43 Wi-Fi), `CELLULAR`
(any radio) and `CELLULAR_4G_PLUS` (LTE or NR, i.e. an S6a/5GS-verifiable
subscriber). An unmet requirement throws
`CellularRequirement.CellularBearerException` **before** anything is sent, so a
Wi-Fi address can never land in the cellular binding table.

`Connector` is the seam that makes the *transport* cellular, not just the label.
On a desktop JVM there is no supported way to pin a socket to a radio, so
`Connector.DEFAULT` uses the OS route; on Android the `ue-sdk-android`
`CellularBearer` implements `Connector` with
`android.net.Network.openConnection(URL)` and pins only this SDK's call. A phone
app that wants silent auth should use that module rather than this one — the
declaration is only trustworthy when the socket that carries it is pinned.

## Security notes

- Use an **HTTPS base URL** in production — the SDK does no TLS pinning.
- Send **`X-Api-Key`** whenever the SAS enforces keys
  (`sas.security.enforce-api-keys=true`); an enforced endpoint rejects
  missing/mismatched keys with `401 UNAUTHENTICATED`.
- **Privacy:** MSISDN/IMSI are never collected by this SDK. They appear in
  the body only if your app passes them voluntarily, and must stay on the
  bank backend / SAS side per the project privacy rule — never surfaced to
  any UI.
- A failed or missing tuple is expected on Wi-Fi/no-binding devices; treat it
  as "fall back", never retry-storm the endpoint.

## Build

```bash
JAVA_HOME=$HOME/.local/share/mise/installs/java/zulu-25 mvn clean test
```

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Digicom-ET. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, private builds, **a permissive (Apache-2.0/MIT) SDK option** for apps that cannot carry AGPL, L1/L2 SLA and integration engineering. Terms per deployment via Digicom-ET. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
