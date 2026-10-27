# Silent Auth UE SDK (Web)

`@restlink/ue-sdk-web` — **CAMARA Number Verification v2** in the browser (silent
`prompt=none` authorization, callback parsing, SIM credential via the Digital
Credentials API), the desktop half of the QR hand-off, and the browser-side poster
for the Silent Auth SAS `POST /session-tuple` endpoint. Zero dependencies, ES2020, `fetch` +
`AbortController` (3000 ms). Devices cannot observe the real CGNAT IP/port,
so the body carries `ts` plus an optional app-supplied `msisdn`; nulls are
omitted. No authentication logic — approval happens server-side.

## CAMARA Number Verification v2 (start here)

`@restlink/ue-sdk-web/number-verification`. Design and evidence:
[`../../docs/design/camara-nv2-device-sdk.md`](../../docs/design/camara-nv2-device-sdk.md).

```js
import {
  startAuthorization, parseAuthorizationCallback, requestSimCredential,
} from '@restlink/ue-sdk-web/number-verification';

// 1. Login page: the backend built the URL (prompt=none, PKCE S256, state, nonce).
try {
  startAuthorization(authorizeUrlFromBackend, { operatorToken });   // top-level navigation
} catch (e) {
  // CellularUnavailableError: the page reports Wi-Fi/ethernet and has no token → OTP / QR
}

// 2. redirect_uri page: hand the code to the backend, which redeems it and calls /verify.
const { code } = parseAuthorizationCallback(location.href, stateFromBackend);
```

What a page can and cannot do:

- It **cannot pin traffic to the radio**. `navigator.connection` (Chromium only) is an
  observation. The SDK refuses to navigate when it positively reports Wi-Fi or
  ethernet. An unknown bearer (Safari, Firefox) is allowed by default, because the
  operator fails closed with `error=access_denied` when the address is not its own.
  `bearerPolicy: 'require-cellular'` refuses unknown too.
- It **can** run the authorization request silently as a top-level navigation with
  `prompt=none`. The request is validated first (https, `response_type=code`, PKCE
  `S256`, `prompt=none`, `state`, `scope`, `client_id`, `redirect_uri`, no repeated
  parameter).
- It **cannot** read a SIM token: `navigator.getSimToken()` does not exist. The OS route
  that does exist is the W3C **Digital Credentials API**. `requestSimCredential({ requests })`
  passes the backend's request to `navigator.credentials.get({ digital })` and returns the
  answer for the backend to verify. It needs a user gesture and shows an OS sheet: it is
  SIM-based but not silent.
- On a desktop (no SIM) use the QR hand-off below.

Nothing is logged. `AuthorizationError.message` carries the OAuth code only, and the
server's free text stays in `.description`.

## Usage (session tuple, pre-v2)

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


## QR on a desktop, a link on the phone (`/hand-off`)

A phone cannot scan its own screen. The same hand-off session is therefore shown
as a QR code on a desktop and as a tappable link on a phone. On iOS the link opens
the bank's App Clip card; on Android it opens the bank app through an App Link.

```js
import { presentHandOff, appClipBannerTag } from '@restlink/ue-sdk-web/hand-off';

const view = presentHandOff({ sessionId, linkBase: 'https://clip.bank.example/s' });
// view.kind === 'qr'   → render view.url as a QR code (desktop)
// view.kind === 'link' → render a button to view.url (iPhone / Android); keep polling status

// Server-side, in the HTML served at https://clip.bank.example/s/<id>:
appClipBannerTag({ appStoreId: '123456789', appClipBundleId: 'et.bank.app.Clip' });
// <meta name="apple-itunes-app" content="app-id=…, app-clip-bundle-id=…, app-clip-display=card">
```

The session id lives in the URL path: Safari's `app-argument` is not passed to App
Clips, and a custom `silentauth://` scheme would not open an App Clip from the
camera. Safari reads the banner tag when the page loads, so render it server-side
rather than inserting it from script.

## Desktop half of the QR hand-off (CAMARA NV v2)

A browser **cannot** claim a session — it has no SIM and cannot bind a socket to
a radio. This module therefore only opens the hand-off and reports the phone's
verdict, which is the whole point of the flow.

```js
import { QrSessionClient } from '@restlink/ue-sdk-web/qr-session';

const qr = new QrSessionClient({
  baseUrl: 'https://sas.example.et',
  authorization: nvAccessToken,        // single-use NV bearer for the bank session
  apiKey: 'bank-tenant-key',
  phoneNumber: '+251911111111',       // omit for the discovery variant
});

const { qrPayload } = await qr.create();   // render this as a QR image
// ... the customer scans it with their phone ...

const status = await qr.poll(sessionId, { intervalMs: 1500 });
if (status.approved) {
  // only ever true on an explicit `true` on a RESOLVED session
} else {
  // fall back to OTP / passkey / push
}
```

Fail-closed: `devicePhoneNumberVerified` is `null` while pending — distinct from
`false` — so a bank cannot read "not answered yet" as "answered no", and `poll`
resolves with the last status rather than throwing, so a timeout cannot become a
retry loop that looks like consent.

Design and verification: `docs/design/camara-qr-handoff.md`.

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Restlink. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, private builds, **a permissive (Apache-2.0/MIT) SDK option** for apps that cannot carry AGPL, L1/L2 SLA and integration engineering. Terms per deployment via Restlink. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
