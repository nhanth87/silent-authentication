# CAMARA NumberVerification — the SBB "entitlement service" contract

This is the **app-facing API contract** the Silent Auth SAS exposes (the "SBB implemented like
CAMARA / like an entitlement service"). It is the *northbound* surface over the SAS `/verify`
flow — not a 3GPP spec, but the GSM-adjacent API the bank backend consumes.

- **Repo:** https://github.com/camaraproject/NumberVerification (Apache-2.0)
- **Release:** r3.2 (Fall25) — `number-verification` **v2.1.0**
- **Spec file:** `code/API_definitions/number-verification.yaml` (+ `CAMARA_common.yaml`)

---

## 1. Endpoints (the SBB must expose this shape)

| Method | Path | Request body | Response |
|--------|------|--------------|----------|
| POST | `/verify` | `{ "phoneNumber": "+251..." }` **or** `{ "hashedPhoneNumber": "<sha256-hex64>" }` (exactly one) | `{ "devicePhoneNumberVerified": true|false }` |
| GET | `/retrieve-phone-number` | (token scope only) | `{ "devicePhoneNumber": "+251..." }` |

- `phoneNumber` — E.164, `+`. `hashedPhoneNumber` — SHA-256 hex (64 chars), prefixed `+` in E.164.
- `x-correlator` — required request header, returned in error responses (traceability).

---

## 2. Auth / token rules (must not regress)

- **OpenID Connect** (3-legged for the app; 2-legged `client_credentials` for server flow).
- **Scope:** `number-verification:device-phone-number:read` (device matching) / `:match`.
- **Single-use token** — one API call per token (anti-replay).
- **No refresh token** issued for NV scopes.
- **Short-lived** — token ≤ **300 s**.
- 403 `NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` — raised when the access
  token's `amr` shows SMS-OTP / user+password (i.e. not mobile-network auth).

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
(`minProperties:1,maxProperties:1`); `x-correlator` echoed; 403 on non-mobile auth. See
`../design/3gpp-spec-coverage.md` + `hardness.md`.