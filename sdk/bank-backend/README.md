# CAMARA v2 bank backend SDK

`et.restlink:bank-backend-sdk` is the **customer backend** half of CAMARA Number
Verification v2 (request Phase 1.4) and SIM Swap v2. It covers everything the bank's server does
and nothing the device should. It uses `java.net.http` only, has zero third-party
runtime dependencies and targets Java 25. The device half lives in `../ue-android`,
`../ue-ios` and `../ue-web`. Design:
[`../../docs/design/camara-nv2-phase1-2.md`](../../docs/design/camara-nv2-phase1-2.md).

```java
Jose clientKey = Jose.fromPrivateJwk(vault.read("nv-client-key"));    // registered as private_key_jwt
NumberVerificationBackend nv = new NumberVerificationBackend(new NumberVerificationBackend.Config(
        "https://op.example/authorize", "https://op.example/token", "https://op.example/bc-authorize",
        "https://op.example/camara", "bank-a", "https://bank.example/cb",
        "dpv:FraudPreventionAndDetection", clientKey,
        Jose.generate(null),            // DPoP key, or null for Bearer tokens
        Duration.ofSeconds(5)));

// 1. login request from the app
var started = nv.start(userSessionId, null);           // PKCE S256, state, nonce, prompt=none
app.send(started.authorizeUrl());                       // the device SDK runs it over cellular

// 2. the app returns the code + state the device SDK caught
var done = nv.complete(code, state);                    // state used once; private_key_jwt [+ DPoP]
boolean verified = nv.verify(done.token(), claimedNumber);   // or verifyHashed(...)
```

| Call | Wire |
|---|---|
| `start(session, loginHint, scopes…)` | builds `/authorize?…&code_challenge=S256(verifier)&prompt=none`; the verifier, nonce and session stay in `PendingAuthorizations` |
| `complete(code, state)` | takes the state **once**, then `POST /token` with `authorization_code`, `code_verifier`, `private_key_jwt` assertion and a DPoP proof if configured |
| `ciba(loginHint, scope)` | `POST /bc-authorize` with `operatortoken:…`, `ipport:…` or `tel:+…`, then polls `/token` |
| `jwtBearer(subject, scope)` | `POST /token` `grant_type=jwt-bearer`, assertion `sub=operatortoken:…` |
| `verify` / `verifyHashed` | `POST /number-verification/v2/verify` with `phoneNumber`, or `hashedPhoneNumber` = SHA-256 hex of the E.164 number |
| `devicePhoneNumber` | `GET /number-verification/v2/device-phone-number` |
| `simSwapCheck(token[, phoneNumber], maxAgeHours)` | `POST /sim-swap/v2/check {maxAge}` (1 to 2400 h) → `swapped`; `phoneNumber` only with a 2-legged token |
| `simSwapRetrieveDate(token, phoneNumber)` | `POST /sim-swap/v2/retrieve-date` → `SimSwapDate(latestSimChange, monitoredPeriod)` |

SIM Swap uses the same login: `start(session, null, SCOPE_SIM_SWAP_CHECK)` (or
`SCOPE_SIM_SWAP_RETRIEVE_DATE`), the device SDK runs the URL over cellular, then
`complete()` gives the token for the call. A token is good for one CAMARA call.

Rules the SDK enforces:

- **No shared secret anywhere.** Client authentication is `private_key_jwt`: the
  assertion has `iss = sub = client_id`, `aud` set to the endpoint, a unique `jti`
  and a 60 s lifetime.
- **State is redeemed once.** A replayed, unknown or expired state (300 s) never
  reaches `/token`.
- **DPoP.** A DPoP-bound token always goes out with `Authorization: DPoP` and a
  fresh proof carrying `ath`. If the provider answers with a Bearer token, the SDK
  keeps it as Bearer (CAMARA: DPoP support is the provider's choice).
- **Strict verdict.** `verify` and `simSwapCheck` are true only for an explicit `true`. Any other
  answer is false or an `NvApiException` carrying the CAMARA or OAuth code.
- **Nothing is logged.** `AccessToken.toString()` redacts the token.
- **Per-instance state.** `PendingAuthorizations` lives in memory, one per
  instance. A multi-instance bank needs a shared store with the same semantics
  (atomic take, TTL, bounded size), or sticky sessions.

## Build / test

```bash
(cd sdk/bank-backend && mvn -o test)
./scripts/camara-nv2-conformance.sh      # this SDK + ue-sdk-android against the packaged dists
```

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Restlink. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, private builds, **a permissive (Apache-2.0/MIT) SDK option** for apps that cannot carry AGPL, L1/L2 SLA and integration engineering. Terms per deployment via Restlink. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
