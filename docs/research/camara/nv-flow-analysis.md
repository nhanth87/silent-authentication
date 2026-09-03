# CAMARA Number Verification — Flow Analysis vs. SAS Implementation

Date: 2026-08-23 · Author: agent research pass · Status: analysis only (no code changed)

## Snapshot provenance

All raw snapshots in this directory were fetched 2026-08-23 from the official Linux
Foundation camaraproject repositories:

| File | Source | Note |
|------|--------|------|
| `number-verification.yaml` | `raw.githubusercontent.com/camaraproject/NumberVerification/main/code/API_definitions/number-verification.yaml` | **wip**, Commonalities 0.8.0 |
| `number-verification-r3.2.yaml` | same repo @ tag **r3.2** | **v2.1.0**, Commonalities 0.6 — latest public release ("Fall25", published 2025-09-12) |
| `CHANGELOG.md` | `.../NumberVerification/main/CHANGELOG.md` | release history incl. r2.4→r3.2 diff |
| `nv-verify-user-story.md`, `nv-device-phone-number-user-story.md` | `documentation/API_documentation/` | the two official user stories |
| `camara-api-access-and-user-consent.md` | `raw.githubusercontent.com/camaraproject/IdentityAndConsentManagement/main/documentation/CAMARA-API-access-and-user-consent.md` | ICM guideline: flows, CIBA/JWT-Bearer with Operator Token |
| `camara-security-interoperability.md` | ICM `CAMARA-Security-Interoperability.md` | Security & Interoperability Profile |
| `camara-icm-examples.md` | ICM `CAMARA-ICM-examples.md` | worked examples |

Latest release per README/releases API: **r3.2 → number-verification v2.1.0**
(based on Commonalities v0.6.0, ICM v0.4.0). Tags seen: r1.1…r3.2 (+ legacy v0.3.1).

---

## A. Exact wire contract (evidence: `number-verification-r3.2.yaml`; main deltas noted)

### Endpoints & scopes

| Method + path (server prefix `{apiRoot}/number-verification/v2`) | operationId | security scope (exact string) |
|---|---|---|
| `POST /verify` | `phoneNumberVerify` | `number-verification:verify` |
| `GET /device-phone-number` | `phoneNumberShare` | `number-verification:device-phone-number:read` |

Note the share endpoint path is **`/device-phone-number`** — there is **no**
`/retrieve-phone-number` anywhere in either YAML.

### Required headers

- `Authorization: Bearer <access_token>` — via `securitySchemes.openId`
  (`openIdConnect`), must be a **3-legged** token:
  > "both require a **3-legged token** … This therefore **excludes** using, for example,
  > SMS/OTP or user/password" (*Resources and Operations overview*).
- `x-correlator` — **optional** parameter/response header, schema `XCorrelator`
  pattern `^[a-zA-Z0-9-_:;.\/<>{}]{0,256}$`.
- No other headers are declared. Nothing like source IP/port/access-tech exists.

### POST /verify requestBody (`NumberVerificationRequestBody`)

```yaml
type: object
minProperties: 1
maxProperties: 1          # exactly ONE of the two properties
properties:
  phoneNumber:        { pattern: '^\+[1-9][0-9]{4,14}$' }        # E.164, leading '+'
  hashedPhoneNumber:  { pattern: '^[a-fA-F0-9]{64}$', maxLength: 64 }
```

Hash semantics (description): "SHA-256 (in hexadecimal representation) of the mobile
phone number in **E.164 format (starting with country code)**. Prefixed with '+'."
→ hash input is the full `+<digits>` string.

### Responses

| Status | Body | Codes (ErrorInfo.code enum) |
|---|---|---|
| `200` (verify) | `{ "devicePhoneNumberVerified": boolean }` — single required field | — |
| `200` (share) | `{ "devicePhoneNumber": "+E164" }` — single required field | — |
| `400` | ErrorInfo | `INVALID_ARGUMENT` |
| `401` | ErrorInfo | `UNAUTHENTICATED` |
| `403` | ErrorInfo | `PERMISSION_DENIED` or `NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` |

- **No `404` is documented** for either endpoint (only 400/401/403).
- `ErrorInfo` requires all three of `status`, `code`, `message`.
- The 403 description explains the second code:
  > "Client authentication was not via mobile network. In order to check the
  > authentication method, **AMR parameter value in the 3-legged user's access token
  > can be used** …" — AMR is a hint mechanism; no specific amr value set is mandated.
- **main (wip) delta:** mandatory *Request body strictness* clause —
  > "This API rejects requests with JSON request bodies that contain properties not
  > declared in this specification, at any nesting level. Unknown properties result in
  > a `400 INVALID_ARGUMENT` response."

### Token rules imposed by the API description (normative MUSTs)

1. "the access token MUST be restricted to a **single API call**"
2. "Refresh tokens MUST **not** be issued for Number Verification scopes"
3. "The access token MUST not exceed an expiration time of **300 seconds**"
4. "For NumberVerification the API provider guarantees that there is **no user
   interaction**" — `prompt=none` is implied.

---

## B. Authentication / binding model — how a number becomes "associated with the access token"

The association happens **at the operator Authorization Server during token issuance**,
never at `/verify` time. Two sanctioned paths (NV yaml, *The Authentication Request*):

**Path 1 — no temporary token (cellular required): OIDC Authorization Code Flow.**

> "If the API Consumer does not have a TS.43 temporary token then the API Consumer must
> use OpenId Connect Authorization Code Flow … For this method of authentication to work,
> **the device must be connected to the mobile network**."

ICM fills in the mechanics: the Auth Server performs Network Based Authentication
(`amr=nba/mnba`), "map[s] to Operator subscription Identifier e.g.: phone number [and]
set[s] UserId (sub)" — the live cellular connection is itself the proof; `sub` becomes
bound to that subscription and the access token carries the binding.

**Path 2 — TS.43 temporary token (works on Wi‑Fi too): CIBA or JWT-Bearer.**

> "The API Consumer sends the temporary token to their backend which either:
> - Sends a CIBA Authentication Request … with a parameter
>   `login_hint=operatortoken:<temporary token>`. **Or** sends a JWT-Bearer token request
>   … with the TS.43 token in the `sub` claim of the JWT assertion with the format
>   `"operatortoken:<temporary token>"`."

ICM (*CIBA flow with Operator Token*): a TS.43 token "confirms the Subscriber's prior
authentication"; the Auth Server validates it (via the Entitlement Server) and mints the
3-legged access token. Same for *JWT Bearer flow with Operator Token*
(`grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`, signed assertion,
`sub=operatortoken:<tk>` or `tel:<phone>`).

**The resource call is then a pure comparison:**

> "It compares the received phone number with the **user's phone number associated to
> the access token** in order to respond **true/false**." (operations overview)

Explicitly out of scope (left to the operator):

- Getting the TS.43 token: "How the API Consumers get a TS.43 temporary token and how
  this token is sent to their backend, **is out-of-scope of the API definition**."
- Validating it: "TS.43 internal steps … are beyond the scope of this document", with one
  normative line: "the **Entitlement Server MUST validate the temporary token** when
  performing a TS.43 operation … according to TS.43 standard."
- Mapping network-auth/connection → subscription (ICM: operator-side).

Chain intended by CAMARA:

```
device net-auth (cellular IP) ──or── SIM cred (EAP-AKA → TS.43 token)
        │                                   │
        ▼                                   ▼
Operator Auth Server (AuthCode+prompt=none | CIBA login_hint | JWT-Bearer sub)
        │  binds sub ↔ devicePhoneNumber into 3-legged access token
        ▼
POST /verify(Authorization: Bearer)  →  compare(body number, token-bound number) → bool
```

Grant types for NV: Authorization Code (prompt=none) | CIBA | JWT-Bearer — never
client-credentials (two-legged tokens carry no user ⇒ forbidden for NV).

---

## C. Does CAMARA NV define any of these? (our suspicion NO-for-all — CONFIRMED)

| Concept | Defined by CAMARA NV? | Evidence |
|---|---|---|
| Entitlement tokens | **NO** — referenced as an opaque input format only | NV yaml: getting the TS.43 token "is out-of-scope". The `operatortoken:` prefix is only a string convention inside `login_hint`/`sub`; no format, TTL, signing or endpoint is specified anywhere. |
| GSMA TS.43 | **NO** — external citation | Linked under *Relevant Definitions* (`gsma.com/newsroom/.../ts-43-service-entitlement-configuration`); nothing normative. |
| EAP-AKA | **NO** — narrative mention only | Once in ICM: token "obtained from the Entitlement Server using EAP-AKA SIM-based authentication" — descriptive, no protocol requirements. |
| SWx / S6a / MAP signalling | **NO** | Zero occurrences in both YAMLs and all fetched docs. |
| IP:port resolvers | **NO** for the NV API | `/verify` accepts only phoneNumber/hashedPhoneNumber; no IP/port/timestamp input exists. Faint nuance: the generic CIBA diagram in ICM shows "Select User Identifier: Ip:port / Phone Number" and "map IP to phone number" as an operator-side login_hint option for ANY CAMARA API — not part of the NV contract. |
| Score / risk / factors in responses | **NO** | `NumberVerificationMatchResponse` = exactly one boolean; share response = exactly one string. |

Conclusion: everything TS.43/EAP-AKA/SWx/resolver/scoring in our SAS is our own
architecture layered underneath a thin CAMARA-shaped northbound. CAMARA fixes only the
last hop: token already bound → boolean compare.

---

## D. Is enriching the 200 response allowed?

- Neither r3.2 nor main sets `additionalProperties: false` on response schemas
  (OpenAPI 3.0.3 default tolerates unknown properties); the new main strictness clause
  covers request bodies only.
- Extra fields are therefore not machine-readably forbidden — but they are undeclared:
  clients generated from the YAML drop them or fail strict deserialization; conformance
  checkers treat undeclared members as non-conformant output; and Commonalities is moving
  toward stricter validation (see the new request-body rule).
- Verdict: **tolerated on the wire today, but a compatibility deviation**. It should be
  opt-in; the default 200 body should be exactly `{devicePhoneNumberVerified}`.

---

## E. Gap analysis vs. our implementation

Files reviewed: `sas/src/main/java/et/restlink/sas/api/VerifyResource.java`,
`security/TokenValidator.java`, `security/OperatorTokenSupport.java`,
`security/SasSecurityConfig.java`, `entitlement/EntitlementResource.java`,
`entitlement/EntitlementTokenService.java`, `api/dto/VerifyRequestDto.java`,
`api/dto/VerifyResponseDto.java`, `docs/P2-missing_item.md`,
`docs/design/silent-auth-standard-flow.md`.

### Already conformant

1. **Scope strings exact** (`TokenValidator.java:46-50`) with per-endpoint family match.
2. **XOR body rule**: `hasPhone == hasHashed` → 400 (`VerifyResource.java:196-199`)
   implements `minProperties/maxProperties = 1`.
3. **Input regexes**: E.164 and 64-hex SHA-256 match the spec patterns
   (`RequestValidator.isE164/isSha256Hex`).
4. **Single-use token** enforced on both endpoints via ReplayGuard consumed-jti
   (spec MUST #1); entitlement tokens single-use at exchange.
5. **403 code string** `NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK`
   exact; amr fail-closed matches the spec's AMR hint (stricter than required — fine).
6. **`x-correlator` echoed** on responses; optional as in spec.
7. Privacy: MSISDN structurally absent from `/verify` response — consistent with the
   boolean-only contract (and stronger than CAMARA asks).

### Violations / misconceptions

| # | Finding | Evidence | Severity |
|---|---------|----------|----------|
| V1 | **Share endpoint path is wrong.** We serve `GET /retrieve-phone-number`; the spec path is `GET /device-phone-number`. No `/retrieve-phone-number` exists in either YAML. `docs/P2-missing_item.md` #7 even labels ours "(CAMARA NV)" — mislabeled. | VerifyResource.java:270 vs r3.2 yaml paths | High |
| V2 | **400 error code is non-conformant.** We emit `VALIDATION.Failed`; the documented enum for 400 is `INVALID_ARGUMENT` (both releases). | VerifyResource.java:93 + all 400 returns vs yaml Generic400 | Medium |
| V3 | **Error bodies omit `status`.** Spec `ErrorInfo` requires `status`+`code`+`message`; our `error()` helper sends only `{code,message}`. | VerifyResource.java:366-371 | Low |
| V4 | **Request body carries an undeclared `riskClass` property.** Under main-wip strictness any conformant validator must answer `400 INVALID_ARGUMENT`; even under r3.2 it is an undeclared extension inside a contract that fixes exactly two properties. Move out of body. | VerifyRequestDto.java:24-26 | High (for "v2-compatible" claim) |
| V5 | **200 response always enriched** with `reqId`, `decision`, `assurance{score,level,threshold,riskClass,factors}`, `fallbackReason` — none declared in `NumberVerificationMatchResponse`. See §D: needs opt-in gate, default body must be the bare boolean object. | VerifyResponseDto.java:25-30, VerifyResource.java:256 | High |
| V6 | **Bearer tokens are not user-bound.** `TokenValidator.validateJwt` checks signature/exp/iat/iss/aud/scope/jti/amr but nothing ties the token to a subscriber identity — there is no `sub`/`phone_number` claim check, so ANY validly-signed JWT with the right scope+amr verifies ANY claimed number. In CAMARA the Auth Server binds sub↔number at issuance and /verify compares against the *token-bound* number; we instead re-derive identity from live network resolution per call and compare against the request body. Architecturally valid INSIDE an operator, but then SAS is effectively the operator exposure platform and must itself issue/accept only user-bound tokens; today's lab tokens are not. | TokenValidator.java:168-226 | High (conceptual core) |
| V7 | **`operatortoken:` direct-bearer collapses CIBA into the resource call.** The `operatortoken:<tk>` string format IS CAMARA vocabulary — but only as CIBA `login_hint` or JWT-Bearer `sub` presented to an Authorization Server, which mints a separate 3-legged access token used on `/verify`. We accept `Authorization: Bearer operatortoken:<tk>` directly on `/verify` (and an `X-Sas-Operator-Token` header). That is an SAS extension, not the CAMARA flow; it skips the AS step entirely. | OperatorTokenSupport.java:83-100, VerifyResource.java:128-138 vs NV yaml auth section + ICM | Medium |
| V8 | **`/entitlement/*` endpoints are not CAMARA at all** — they are our TS.43-track stand-in (issue/exchange/status). Legitimately out-of-band (CAMARA explicitly leaves the Entitlement Server to GSMA TS.43), but must be labeled as such in docs/API surface so nobody reads them into the v2 contract. | EntitlementResource.java | Doc-level |
| V9 | **No ≤300 s TTL enforcement on bearer JWTs.** Spec MUST #3 caps access-token lifetime at 300 s; we check only `exp < now`. Add `(exp - iat) <= 300` when iat present (entitlement path already clamps TTL ≤300 s). | TokenValidator.java:183-190 | Medium |
| V10 | **MSISDN normalization inconsistency risk.** Hash compare prepends `'+'` (`sha256("+" + result.msisdn())`, VerifyResource.java:264) implying stored MSISDN lacks `'+'`, while `GET …devicePhoneNumber` returns `result.msisdn()` raw — if any resolver backend stores numbers without `'+'` the share response violates the `^\+[1-9][0-9]{4,14}$` pattern and plain-text compares false-negative. Normalize once at the resolver boundary. | VerifyResource.java:260-265, 339 | Medium (verify per-backend) |
| V11 | **No server prefix.** Spec base path is `{apiRoot}/number-verification/v2`; we serve root `/verify`. Gateway rewrite or Quarkus root-path needed for an honest compatibility claim. | yaml `servers:` | Low |
| V12 | Lab mode (`token-validation-enabled=false`) accepts presence-only bearer + header amr — fine for lab, but the "v2-compatible" claim must require validation-enabled=true. Also X-Sas-Src-Ip/Port/Access-Tech headers are pure SAS extensions (harmless, undeclared, ignored by CAMARA clients) — document them as such. | SasSecurityConfig.java:24, VerifyResource.java:142-143 | Doc-level |

### Direct answers to the three questions posed

1. **Do we enforce USER-bound bearer tokens?** No (V6). Scope+amr+signature prove
   *authorization* and *auth-method*, not *which subscriber* the token was issued for.
   The subscriber identity comes from our Resolver, not the token.
2. **Is `operatortoken:` + `/entitlement/*` part of CAMARA?** Split verdict: the
   `operatortoken:` string format is genuine CAMARA ICM vocabulary, but only inside
   CIBA login_hint / JWT-Bearer sub toward an Authorization Server (V7). Our
   direct-bearer usage and the `/entitlement/*` REST surface are NOT CAMARA — they are
   an out-of-band TS.43-style track and should be documented/labeled accordingly (V8).
3. **Is the enriched assurance response a deviation?** Yes (V5): tolerated by OpenAPI
   defaults but undeclared; needs an opt-in flag/header so default output stays exactly
   `{devicePhoneNumberVerified}`.

---

## F. Recommended minimal changes (ordered) to honestly claim "CAMARA NV v2-compatible"

1. **Add route alias `GET /device-phone-number`** (keep `/retrieve-phone-number` as a
   deprecated alias for pilot banks). Cheapest high-value fix (V1).
2. **Make default responses byte-conformant:** strip `reqId/decision/assurance/
   fallbackReason` unless opted in — e.g. `X-Sas-Assurance: enabled` request header or
   `?assurance=true` query (V5). Same treatment removes `riskClass` from the request
   body → move to `X-Sas-Risk-Class` header or the opt-in flag (V4).
3. **Fix error contract:** add `status` to error bodies; map our internal
   `VALIDATION.Failed` to `INVALID_ARGUMENT` on the wire (keep the internal reason in
   logs/x-correlator trace) (V2, V3).
4. **Bind the bearer token to a subscriber:** accept/require a `phone_number` (or
   `sub`) claim on the bearer path and fail-closed when `resolved != token-bound`
   number (403 or plain `false`). For the lab, have the test issuer embed the bound
   number; this makes SAS behave like the CAMARA Resource Server it claims to be (V6).
5. **Enforce token lifetime ≤ 300 s** on the bearer path (`exp - iat` check) and state
   "no refresh tokens" in the token-issuance docs (V9).
6. **Normalize MSISDN to E.164-with-`'+'` at the resolver boundary**, assert before
   compare/hash/share (V10).
7. **Serve under `/number-verification/v2`** (Quarkus root-path or gateway rewrite);
   keep legacy root paths during transition (V11).
8. **Split the documentation into two clearly labeled tracks:**
   - Track 1 — "CAMARA NV v2 northbound": `/verify`, `/device-phone-number`, OIDC
     3-legged tokens, conformant errors/responses.
   - Track 2 — "TS.43 / Wi-Fi out-of-band": `/entitlement/*`, `operatortoken:` direct
     bearer, `X-Sas-*` headers — explicitly marked as Restlink extensions implementing
     the operator-side roles CAMARA leaves open (Entitlement Server, network auth), not
     part of the CAMARA contract (V7, V8, V12). Roadmap item: implement real
     `/bc-authorize` + `/token` endpoints so `login_hint=operatortoken:<tk>` flows
     through CIBA exactly as specced, after which the direct-bearer shortcut can be
     retired.

Items 1–3 are mechanical; 4–6 are small, contained changes; 7–8 are packaging/docs.
After 1–5 land, a YAML-derived conformance suite (request/response shapes, codes,
scopes) can run green against the default profile.

---

## Source quotes index (quick reference)

- Single-use/no-refresh/300 s: r3.2 yaml `info.description`, *The Authentication Request*.
- 3-legged requirement & endpoint list: same, *Resources and Operations overview*.
- Compare-vs-token semantics: same ("It compares the received phone number with the
  user's phone number associated to the access token").
- TS.43/CIBA/login_hint formats: same, *Authentication Request with a temporary token*;
  ICM *CIBA flow with Operator Token*, *JWT Bearer flow with Operator Token*.
- AMR hint: r3.2 yaml `PhoneNumberVerificationPermissionDenied403.description`.
- Request-body strictness (main wip only): `info.description`,
  `CAMARA:MANDATORY:request-body-strictness` block.
