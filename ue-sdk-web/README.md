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

Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
