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

{"srcIp":"10.20.30.40","ts":1724200000000}
```

Null fields are omitted from the body.

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

Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
