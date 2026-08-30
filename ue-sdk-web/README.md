# Silent Auth UE SDK (Web)

`@restlink/ue-sdk-web` — browser-side poster for the Silent Auth SAS
`POST /session-tuple` endpoint. Zero dependencies, ES2020, `fetch` +
`AbortController` (3000 ms). Devices cannot observe the real CGNAT IP/port,
so the body carries `ts` plus an optional app-supplied `msisdn`; nulls are
omitted. No authentication logic — approval happens server-side.

## Usage

```js
import { SessionTupleClient } from '@restlink/ue-sdk-web';

const client = new SessionTupleClient({
  baseUrl: 'https://sas.example.et', // HTTPS only in production
  apiKey: 'bank-tenant-key',         // X-Api-Key, optional
  onError: (err) => console.warn('tuple failed', err),
});
const { status } = await client.send({ msisdn: '+251911111111' });
if (status !== 200) {
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

## "Over the mobile network" from a browser

A web page **cannot** force its traffic onto 4G/5G: there is no interface API in
a browser, and on a phone attached to both Wi-Fi and LTE the request leaves over
Wi-Fi. That makes IP-match silent auth unsuitable for most mobile-web logins —
the honest answer is the native SDKs (`ue-sdk-android`, `ue-sdk-ios`).

What this package does is *observe and refuse*:

```js
import { SessionTupleClient } from '@restlink/ue-sdk-web';

const client = new SessionTupleClient({
  baseUrl: sasBaseUrl,
  apiKey,
  requireCellular: true,   // default false (lab)
});

try {
  await client.send({ msisdn });
} catch (err) {
  if (err.code === 'CELLULAR_UNAVAILABLE') {
    // Nothing was sent. Take the OTP / passkey path — never "retry anyway".
  }
}
```

`requireCellular` reads `navigator.connection.type` (Chromium; absent in Safari
and Firefox). `effectiveType` is a *throughput estimate*, so the strongest
cellular claim a browser may make is `LTE` — never `NR`. Anything the API cannot
see is `UNKNOWN` and therefore refused. When a bearer is known it is posted as
`accessTech` in the body, so the SAS can reject a Wi-Fi tuple instead of letting
it poison the cellular binding table.

## Test


```bash
node --test   # or: npm test (Node >= 18)
```

## Security notes

- **HTTPS-only in production** — the SDK does no TLS pinning; plain HTTP is
  lab use only.
- A failed/missing tuple is expected on Wi-Fi/no-binding devices; treat as
  "fall back", never retry-storm the endpoint.
- MSISDN stays on the bank backend/SAS side per project privacy rule — never
  surfaced to any UI.

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Digicom-ET. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, private builds, **a permissive (Apache-2.0/MIT) SDK option** for apps that cannot carry AGPL, L1/L2 SLA and integration engineering. Terms per deployment via Digicom-ET. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
