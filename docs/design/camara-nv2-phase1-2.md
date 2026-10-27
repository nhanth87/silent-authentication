# CAMARA Number Verification v2: Phase 1 and Phase 2

> Status: implemented 2026-10-14 on branch `nv-2`. Requirement:
> [`../request_nâng_cấp_sdk_android_ios_web_lên_camara_v2.md`](../request_nâng_cấp_sdk_android_ios_web_lên_camara_v2.md)
> §Implementation Plan, Phase 1 (core infrastructure) and Phase 2 (testing and validation).
> Device SDK detail: [`camara-nv2-device-sdk.md`](camara-nv2-device-sdk.md).

## 1. Requirement → implementation

| Phase item | Where | Status |
|---|---|---|
| 1.1 `/verify` (plain + hashed), `/device-phone-number` (GET), request/response validation, `x-correlator` | sas-api `VerifyResource`, `CamaraNumberVerificationResource` (pre-existing) | ✅ contract-tested (§4) |
| 1.1 `private_key_jwt` client authentication, no `client_secret` | `OAuthClientAuthenticator` (pre-existing), discovery offers no secret method | ✅ |
| 1.2 Authorization Code + `prompt=none` | `AuthorizeResource`: `prompt` handled (new) | ✅ |
| 1.2 CIBA poll | `BcAuthorizeResource` + `/token`; login hints `operatortoken:`, **`ipport:`, `tel:` (new)** | ✅ |
| 1.2 JWT bearer | `/token` `jwt-bearer` (pre-existing); amr per subject (new) | ✅ |
| 1.2 PKCE | S256 mandatory (pre-existing) | ✅ |
| 1.2 **DPoP (RFC 9449)** | `DpopProofVerifier`, `/token` binding, `DpopResourceFilter` (new) | ✅ |
| B. TLS ≥ 1.2 | prod `quarkus.http.ssl.protocols=TLSv1.3,TLSv1.2`, preflight **PRO-37** (new) | ✅ |
| 1.3 Mobile SDKs | `ue-sdk-android`, `ue-sdk-ios`, `ue-sdk-web` | ✅ (see device doc) |
| 1.4 Customer backend | **`bank-backend-sdk`** (new module) | ✅ |
| 1.4 Gateway: scope, token, request validation | `TokenValidator`; **amr only from the signed token (new)** | ✅ |
| 1.4 Operator: network-based + SIM-based | sas-ecs resolver + operator-token redemption; **SIM-based tokens now carry amr (new)** | ✅ |
| 2.1–2.4 | `scripts/camara-nv2-conformance.sh` (new) + unit suites | ✅ (§4) |

## 2. What was wrong before, and what changed

### 2.1 SIM-based authentication could not pass `/verify` (functional gap)

A code or token obtained with `operatortoken:` (GSMA TS.43) was minted with `amr=[]`.
The NV resource server requires mobile-network authentication in the `amr` claim,
so every SIM-based flow (Authorization Code, CIBA, JWT bearer) ended in
`403 USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK`. This is the main v2 feature
("works with Wi-Fi active").

Now every authentication records who authenticated (`AuthMethods`):

| Authentication | amr | NV `/verify` |
|---|---|---|
| network-based (source IP:port:ts → operator resolver), `/authorize` or CIBA `ipport:` | `["mno"]` | accepted |
| SIM-based (operator token from the entitlement server, EAP-AKA against the USIM) | `["hwk","mno"]` (RFC 8176 `hwk` = hardware-secured key) | accepted |
| asserted `tel:` (CIBA / JWT bearer) | none | **403**. Asserting a number is not authenticating it; SIM Swap and similar consent APIs still accept such tokens |

### 2.2 The `X-Sas-Amr` header was authentication evidence (security bug)

When a validated token carried no `amr`, `/verify` fell back to the
**caller-supplied** `X-Sas-Amr` header. A bank could obtain a JWT-bearer token for
`sub=tel:<any number>` (nobody authenticates that number), add `X-Sas-Amr: mno`, and
pass the mobile-network check. The CAMARA spec says to read the amr of the
3-legged access token.

Now the header is read only in pilot mode (token validation off, where nothing is
verified anyway) or behind an explicit `sas.security.trust-amr-header=true`. Prod
pins it to `false`, and preflight **PRO-37** refuses `true`. The conformance run
proves it: a `tel:` token plus `X-Sas-Amr: mno` → 403.

### 2.3 `prompt`

| `prompt` | Answer |
|---|---|
| absent, `none` | silent authentication as before |
| `login` / `consent` / `select_account` | redirect `error=login_required` / `consent_required` / `account_selection_required`. This server shows no pages (OIDC Core §3.1.2.6) |
| `none` with any other value, or unknown | `invalid_request` |

`acr_values` is ignored, as the CAMARA profile requires. Discovery advertises
`prompt_values_supported: ["none"]`.

### 2.4 DPoP (RFC 9449) per the CAMARA security profile

```
 bank backend                      sas-camara /token                         resource (/verify …)
   │ POST /token + DPoP: proof(htm=POST, htu=/token)
   │──────────────────────────────►│ verify proof → jkt
   │                                │ client dpop_bound_access_tokens && no proof → 400 invalid_dpop_proof
   │◄──── token_type=DPoP, access token with cnf.jkt
   │ Authorization: DPoP <token> + DPoP: proof(htm, htu, ath=SHA256(token))
   │─────────────────────────────────────────────────────────────────────────►│ DpopResourceFilter:
   │                                                                          │  proof valid, key == cnf.jkt
   │                                                                          │ TokenValidator: scheme ↔ binding
```

| Proof sent | Server DPoP | Client `dpop_bound_access_tokens` | Result |
|---|---|---|---|
| valid | on | any | DPoP token (`cnf.jkt`), `token_type=DPoP` |
| invalid | on | any | `400 invalid_dpop_proof` |
| any | off (`sas.oauth.dpop.enabled=false`) | any | Bearer. CAMARA: an unsupported DPoP header is never an error |
| none | on | no | Bearer |
| none | on | yes | `400 invalid_dpop_proof` |

Proof checks:

1. `typ=dpop+jwt`.
2. ES256 or RS256 only.
3. A public JWK (no private members); an EC key must be P-256.
4. The signature verifies.
5. `htm` matches the method; `htu` matches the request URL (scheme, authority and path; query and fragment ignored).
6. `iat` within 60 s back or 5 s ahead.
7. `ath` matches when an access token is presented.
8. `jti` not seen before (bounded replay cache; fail closed when full).

The resource side answers `401` with the CAMARA error body and
`WWW-Authenticate: DPoP error="invalid_dpop_proof"`. A bound token sent as Bearer
is refused, so a stolen DPoP token is useless. `TokenValidator` re-checks the
scheme/binding pairing as defense in depth.

Not done (optional in CAMARA): server-provided DPoP `nonce`, and the
`camara:qh` / `camara:bh` request-binding claims.

### 2.5 Token lifetime

`sas.oauth.access-token-ttl-seconds` (default 300) is clamped to at most 300: CAMARA
NV caps it, and the resource server refuses longer lifetimes anyway. The
conformance run uses 20 s to test expiry.

## 3. Timeouts

| Stage | Budget | On expiry |
|---|---|---|
| authorization code | single use (pre-existing TTL) | `invalid_grant` |
| backend pending state | 300 s, single use | `invalid_state`, never reaches `/token` |
| client assertion | 60 s (`exp`) | `invalid_client` |
| DPoP proof | `iat` 60 s back / 5 s ahead | `invalid_dpop_proof` |
| access token | ≤ 300 s, single use at `/verify` | `401` |
| CIBA `auth_req_id` | provider `expires_in`, polled at `interval` | `expired_token` |

## 4. Phase 2: verification

### 4.1 Commands

```bash
mvn -o test                                   # all modules (sas-api: DpopTest, PromptAndAmrTest, …)
(cd sdk/bank-backend && mvn -o test)
(cd sdk/ue-android && mvn -o test); (cd sdk/ue-web && node --test)
(cd sdk/ue-ios && docker run --rm --user $(id -u):$(id -g) -e HOME=/tmp -v "$PWD":/w -w /w swift:5.10-jammy swift test)
./scripts/package-dist.sh all
./scripts/camara-nv2-conformance.sh            # 2.1–2.4 against the packaged dists
./scripts/sdk-nv2-e2e.sh                       # device SDKs (Android JVM + Web) end to end
python3 harness/run_hardness.py; python3 harness/preflight_prod.py --selftest
```

### 4.2 Conformance checks (`scripts/camara-nv2-conformance.sh`)

The gateway runs with `private_key_jwt` required, ES256 only, connection-source
network authentication (`trust-src-header=false`), the amr header untrusted, DPoP
on and a 20 s TTL. The bank side is `bank-backend-sdk`; the device is
`ue-sdk-android` on a socket bound to the cellular address.

| Group | Checks |
|---|---|
| Metadata | `prompt_values_supported=[none]`, S256 only, DPoP algs, `private_key_jwt`, no `client_secret_*`, CIBA poll |
| 2.1 contract | correct number → `{"devicePhoneNumberVerified":true}` (exact body), wrong → false, hashed correct/wrong, both identifiers → 400 CAMARA error, non-E.164 → 400, unknown property → 400, `x-correlator` echoed / invalid → 400, `device-phone-number` → the token's number |
| 2.2 tokens | lifetime ≤ 300 s, single use, expired → 401, wrong scope → 403, SIM-Swap token at `/verify` → 403, asserted `tel:` → 403 `USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK`, `X-Sas-Amr` bypass → 403, 2-legged token → 401/403, tampered payload → 401, `alg=none` → 401 |
| 1.2 flows | backend state replay refused before `/token`, code replay → `invalid_grant`, wrong PKCE → `invalid_grant`, assertion from another key → 401 `invalid_client`, no client auth → 401, `prompt=login` → `login_required`, `acr_values` ignored |
| DPoP | proof → DPoP token with `cnf`, NV with proof → true, bound token as Bearer → 401 `invalid_dpop_proof`, proof from another key → 401, bound client without proof → 400, with proof → DPoP |
| 2.4 integration | network-based via the Android SDK → true; SIM-based auth code / CIBA / JWT bearer with an operator token → `amr [hwk,mno]` → true; spent token refused; CIBA `ipport:` → true; `ipport` without port refused; a connection no PGW assigned → `access_denied` |

Results are recorded in `docs/today_task.md` §6.

## 5. Open items

- [ ] DPoP `nonce` and `camara:qh`/`camara:bh` claims (optional in CAMARA).
- [ ] `PendingAuthorizations`, the authorization-code store and the DPoP replay cache
      are per instance. Multi-instance needs a shared store or sticky sessions.
- [ ] Operator sandbox / live network run (2.4); real handsets (2.3).
- [ ] M4 (mTLS + client credentials on the exposure hop) remains open from the
      layered migration.
