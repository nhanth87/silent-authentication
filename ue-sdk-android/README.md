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

{"ts":1724200000000,"msisdn":"+251911111111"}
```

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

Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
