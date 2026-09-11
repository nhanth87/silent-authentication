# CAMARA NumberVerification — the SBB "entitlement service" contract

This is the **app-facing API contract** the Silent Auth SAS exposes (the "SBB implemented like
CAMARA / like an entitlement service"). It is the *northbound* surface over the SAS `/verify`
flow — not a 3GPP spec, but the GSM-adjacent API the bank backend consumes.

- **Repo:** https://github.com/camaraproject/NumberVerification (Apache-2.0)
- **Release:** r3.2 (Fall25) — `number-verification` **v2.1.0**
- **Spec file:** `code/API_definitions/number-verification.yaml` (+ `CAMARA_common.yaml`)

---

## 1. Endpoints (the SBB must expose this shape)

| Method | CAMARA path | Request body | Response |
|--------|-------------|--------------|----------|
| POST | `/number-verification/v2/verify` (alias `/camara/number-verification/v2/verify`; legacy `/verify`) | `{ "phoneNumber": "+251..." }` **or** `{ "hashedPhoneNumber": "<sha256-hex64>" }` (exactly one) | `{ "devicePhoneNumberVerified": true|false }` |
| GET | `/number-verification/v2/device-phone-number` (alias `/camara/number-verification/v2/device-phone-number`; legacy `/retrieve-phone-number`) | (token scope only) | `{ "devicePhoneNumber": "+251..." }` |

- `phoneNumber` — E.164, `+`. `hashedPhoneNumber` — SHA-256 hex (64 chars), prefixed `+` in E.164.
- `x-correlator` — optional request header; when present it must match
  `^[a-zA-Z0-9-_:;./<>{}]{0,256}$` and is echoed on CAMARA responses.
- Unknown CAMARA request properties are rejected as `400 INVALID_ARGUMENT`; runtime strictness
  is `quarkus.jackson.fail-on-unknown-properties=true`.

---

## 2. Auth / token rules (must not regress)

- **CAMARA ICM grants implemented:** CIBA, 2-legged `client_credentials`, and OAuth 2.0
  JWT bearer. Authorization code, refresh tokens, ID tokens and pseudonymous `sub`
  remain open.
- **Client authentication:** `private_key_jwt`. `none` is preserved only when
  `sas.oauth.require-client-auth=false` for legacy/lab compatibility; production
  enforcement is still an open decision.
- **Discovery / JWKS:** `/.well-known/openid-configuration`,
  `/.well-known/oauth-authorization-server`, and `/jwks.json` advertise the supported
  grants, client-authentication methods and assertion signing algorithms.
- **Client registry:** `sas.oauth.clients-json` accepts either a JSON array or
  `{"clients":[...]}`. Each client may pin `client_id`,
  `token_endpoint_auth_method`, `jwks`, `grant_types`, `redirect_uris`, `scopes`, and
  `purposes`. An invalid registry fails closed: no client is usable.
- **Scope:** `openid` and `offline_access` are accepted but omitted from the response
  scope. At least one CAMARA API scope is required; a `dpv:` purpose is required for
  JWT bearer. NV scopes: `number-verification:verify` /
  `number-verification:device-phone-number:read`.
- **Single-use token** — one API call per token (anti-replay).
- **No refresh token** issued for NV scopes.
- **Short-lived** — access token and JWT assertion lifetime ≤ **300 s**.
- **CIBA:** `/bc-authorize` accepts `login_hint`, `scope`, `client_id`,
  `client_assertion_type`, and `client_assertion`; `/token` rejects a `scope`
  parameter, consumes one `auth_req_id` once, refuses an id issued to another client,
  and reports expiry as `expired_token`.
- **client_credentials:** requires `scope` and `client_id`; issues
  `token_use=client` with no `phone_number`/`msisdn` binding. NV `/verify` rejects that
  token as `403 NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` when
  token validation is enabled.
- **JWT bearer:** the form `scope` parameter is forbidden; the signed assertion's
  `scope` claim is mandatory and must contain one `dpv:` purpose. `sub` must be
  `tel:<E.164>` or `operatortoken:<token>`; the latter is resolved through the SAS
  identity anchor and issues a user-bound `token_use=user` token.
- 403 `NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` — raised when the access
  token's `amr` shows SMS-OTP / user+password (i.e. not mobile-network auth) or when the
  token carries no user phone-number binding.

---

## 3. How the SBB maps CAMARA → SAS internals

```
POST /verify {phoneNumber}
  → VerifierSbb (entitlement service)
     → Resolver(IP:port:ts → MSISDN/IMSI)   [data plane]
     → Verifier(MAP PSI/ATI/SAI | S6a | SWx) [identity plane]
     → Policy(assurance score)
  → { devicePhoneNumberVerified: resolved==claimed && assurance>=threshold }
```

- SIM-based (TS.43) mode: backend exchanges the device's **temporary token** (CIBA
  `login_hint=operatortoken:<tk>` or JWT-Bearer) and verifies via SWx/EAP-AKA — no cellular data.
- The SAS returns **only** a boolean / the device number to the *backend*; MSISDN/IMSI never
  reach the mobile app.

---

## 4. Contract fidelity gate

The DeepSeek-Hardness gate for this contract asserts: `/verify` returns
`devicePhoneNumberVerified` boolean; exactly one of `phoneNumber`/`hashedPhoneNumber` present
(`minProperties:1,maxProperties:1`); unknown CAMARA request properties are rejected as
`400 INVALID_ARGUMENT`; `x-correlator` is validated and echoed; 403 on non-mobile auth or a
missing user binding; `/camara` aliases delegate to the same resources; `/camara/health`
reports `UP`; and the CAMARA ICM OAuth surface keeps CIBA/client-credentials/JWT-bearer
grants, `private_key_jwt`, one-time `auth_req_id` client binding, CAMARA scope policy, and
the user-bound versus 2-legged token separation. See
`../design/3gpp-spec-coverage.md` + `hardness.md`.