# Entitlement server ↔ core network — plan (TS.43 ECS ↔ 3GPP AAA / HSS)

Status: **plan for the operator conversation** — nothing here is implemented. It fills the
gap between what the tree has today (a *token service* the operator AAA calls, see
[`ts43-entitlement-integration-contract.md`](ts43-entitlement-integration-contract.md)) and
what a **real TS.43 Entitlement Configuration Server (ECS)** must be to talk to a mobile
network. Every "must" below is anchored to a clause of GSMA **TS.43 v13.0** or a 3GPP
spec; every "today" is a file/line in this repo.

- Wire-level companion: [`ts43-eapaka-wire-protocol.md`](ts43-eapaka-wire-protocol.md)
- 3GPP anchor notes: [`../research/3gpp-ts33-402-eap-aka.md`](../research/3gpp-ts33-402-eap-aka.md),
  [`../research/3gpp-ts29-273-s6b-swm-swx.md`](../research/3gpp-ts29-273-s6b-swm-swx.md)
- Spec text SoT: `docs/research/` paraphrases only; the normative documents are
  GSMA TS.43 v13.0, 3GPP TS 29.273 (SWm/SWx), TS 29.272 (S6a), TS 33.402 (EAP-AKA' security),
  TS 29.222/23.222 (CAPIF), RFC 4187 / 5448 / 4072.

---

## 0. Executive summary — what is missing

The current entitlement surface is **not an ECS**. It is a signed-token issuer with a
caller-attestation check:

| TS.43 ECS capability | Clause | Today in this repo | Gap |
|---|---|---|---|
| `GET`/`POST /` entitlement-configuration surface with `terminal_id`, `app`, `entitlement_version`, `token` | §2.3, §2.4 | none — only `POST /entitlement/issue`, `/exchange`, `/status` (`sas-entitlement/.../EntitlementResource.java`) | **whole HTTP surface** |
| Server discovery FQDN `aes.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org` | §2.1 | none | discovery + DNS |
| Version control (`entitlement_version` negotiation, `406 Not Acceptable`) | §2.5 | none | version gate |
| Notification registration (`notif_token` + `notif_action`, `RegisterNotifStatus`) | §2.6, §2.9.5 | none | push registration |
| **EAP-AKA relay from the ECS to the 3GPP AAA** (`EAP_ID`, DER, Result-Code → HTTP mapping) | §2.8.1 | `CorsacSwxVerifierBackend` (SAS→HSS SWx) and the shape-only `EapAkaDemoPeer` — **no ECS→AAA EAP relay** | **the core-network leg** |
| OAuth 2.0 / OIDC redirect (`302 Found` + `Location`) | §2.8.2 | SAS has an OIDC/CIBA *server* (`sas-api/.../oauth/`), not an ECS client redirect | ECS-side redirect |
| Server-to-server OAuth 2.0 + `private_key_jwt` client assertion | §2.8.3 | `OAuthClientAuthenticator` + `JsonWebSignature` exist but serve `sas-api` OAuth, not an ECS S2S client | ECS S2S client |
| `token` semantics (survives reboot, `validity` attribute, variable length) | §2.9.6, RCC.14 | token is SAS-internal HMAC blob, TTL ≤ 300 s, single-use | mapping, not a blocker |
| Temporary token + `operator_token` hand-off to a third party | §2.8.6, §…operator_token | `operatortoken:` CIBA anchor exists (`OperatorTokenSupport`) | align naming/flow |
| HTTP response-code contract (200/302/400/403/405/406/500/501/503/511) | §2.10 | SAS error contract is CAMARA-shaped `{code,message}` | ECS code table |
| Entitlement document (`VERS`, `TOKEN`, per-app parameters) | §2.9 | none | document builder |

**The one blocking gap is the EAP-AKA relay (§2.8.1).** Without it the ECS cannot prove SIM
possession to itself, and TS.43 §2.8.1 says so explicitly: the ECS either (a) relays EAP to the
operator's 3GPP AAA, or (b) redirects the client to an OIDC server that has EAP relay capability.
Restlink must own (a) or the operator must own (b) — see §7.

---

## 1. Parties, ownership, trust boundary

| Function | Owner | Restlink's involvement |
|---|---|---|
| UE + SIM (EAP-AKA' client) | end user / OEM | none on the EAP path |
| WLAN AN / AP (SWm edge, or RADIUS) | operator or venue | none |
| **3GPP AAA server** (EAP-AKA' termination, T5 client) | **operator** | receives DER from the ECS (§2.8.1) |
| **Service Entitlement (SE) function** | **operator** (T5) | optional; the SE may proxy ECS↔AAA |
| HSS / UDM (auth vectors, subscription) | **operator** | SAS may read SWx (lab only — see §4) |
| **ECS (Entitlement Configuration Server)** | **Restlink** (proposed) | full HTTP surface + EAP relay + document |
| CAMARA `/verify` (Number Verification) | Restlink SAS | already implemented |

> **Superseded for scope/phasing by [`entitlement-core-nw-plan-2.md`](entitlement-core-nw-plan-2.md)
> (plan of record, D6 decided = Shape S). This document is kept for the *operator-facing*
> ECS/relay contract — and it is exactly the Shape R design, retained as the production
> fallback (plan §2.1.2). Its §2.1 EAP-relay result-code table and §5 network list are the
> specification to fall back to if the operator declines vector-grade AuC access (R1).

Two deployment shapes are possible and the operator must pick one (§7, Q1):

- **A — Restlink runs the ECS.** The AAA exposes a Diameter EAP endpoint (or a T5 client of the
  SE) to Restlink. Restlink terminates the TS.43 HTTP conversation with devices.
  → this is **Shape R** in plan §2.1.2 (SAS relays EAP; the AAA verifies RES/MAC).
- **B — Operator runs the ECS.** Restlink is only the CAMARA `/verify` + token consumer; the
  operator's ECS hands Restlink an entitlement result over an agreed API.
  → not a D6 shape; it removes the EAP leg from Restlink entirely.

This document describes **Shape A = Shape R**. The build target is **Shape S** (SAS is the
EAP server, plan §2.1.1); Shape R is the retained production fallback. Shape A/R is what
Restlink falls back to — or, under shape B, what the operator's own ECS provides to Restlink.

---

## 2. Target architecture (shape A)

```
                 ┌──────────────────────── Restlink trust domain ───────────────────────┐
 device ──HTTPS──►  ECS (TS.43 §2.3 GET / §2.4 POST)                                     │
   (Wi-Fi, no     │    ├─ version gate (§2.5)        ├─ notification reg (§2.6)          │
    mobile data)  │    ├─ auth: EAP relay (§2.8.1) | OIDC redirect (§2.8.2)              │
                 │    └─ entitlement document (§2.9)  ──► token / temporary_token         │
                 └───────────────┬───────────────────────────────────────────────────────┘
                                 │  EAP-AKA' relay (DER over SWm-style Diameter, or T5)
                                 │  N32/IPsec + mTLS in prod, SCTP or TCP per operator
                                 ▼
                 ┌──────────────────────── operator trust domain ───────────────────────┐
                 │  3GPP AAA  ──SWx──►  HSS/UDM                                          │
                 │  (SE function, T5)                                                     │
                 └──────────────────────────────────────────────────────────────────────┘

 bank backend ──HTTPS/mTLS──► SAS  POST /verify  ──► CAMARA NV contract
                                 (entitlement token consumed, single-use)
```

### 2.1 EAP-AKA relay — the leg that must be built

TS.43 §2.8.1 (paraphrased): when the client cannot or should not use the OIDC path, the ECS
**may itself relay EAP-AKA to the operator's AAA**, exchanging the EAP payloads directly with
the client instead of issuing HTTP 302 redirects. The client stays in the EAP conversation; the
ECS is a relay. Two implementation options, and the mapping is normative:

| DER Result-Code from the 3GPP AAA | HTTP response the ECS must return | Meaning |
|---|---|---|
| `1001` (DIAMETER_MULTI_ROUND_AUTH) | `200 OK` | challenge relayed, awaiting the device's response |
| `2001` (DIAMETER_SUCCESS) | `200 OK` | authenticated by the AAA |
| `3001`–`3010`, `5002`, `5004`–`5017` | `511` if the ECS supports an alternate auth form and the client sent no `TOKEN`, else `503` (+ `Retry-After`) | connectivity / protocol / transient |
| `4001`, `5001`, `5003` | `403 Forbidden` | identity unknown to the AAA — permanent, do not retry |

The tree already owns the **result-code → evidence** discipline this needs, in
`ras/swxverifier/SwxEvidence.java` (success = `2001`/`2002`, everything else fails closed) and
in `CorsacSwxVerifierBackend` (per-Session-Id correlation, bounded dialog, abort on timeout).
The relay must reuse that discipline, not invent a second one.

**Design constraints for the relay, from the existing architecture:**

- One dialog per stage, bounded by `SasTimeouts.DIAMETER_MS` = 2 s shared with the rest of the
  verify budget; total SAS budget 3 s (`SasTimeouts.TOTAL_MS`).
- It must live inside the micro-jainslee container as an **RA delegate** (gate **H24**): the
  transport import rule (`com.mobius.`, `org.restcomm.`, `io.netty.`) permits signalling clients
  only under `sas-host/src/main/java/et/restlink/sas/ras/`. A new
  `ras/eaprelay/` package is the only legal home; anything else fails `slee_boundary`.
- A new raw `HttpClient`/socket is forbidden outside an RA by the same gate's
  "raw socket or HTTP client outside an RA" pattern rule. The EAP/Derby client therefore has
  to be an RA delegate, not a CDI service.
- Fail-closed: no DER answer, a timeout, or an unmapped Result-Code ⇒ `503`, never a soft pass.
- `EAP-AKA'` (TS 33.402 / RFC 5448) binds the access network name, so the relay must not
  accept a plain `EAP-AKA` claim for a non-3GPP access unless the operator says EAP-AKA is
  what it runs (`EntitlementTokenService.canonicalEapMethod` already whitelists both).

---

## 3. The HTTP surface to build (TS.43 §2.2–§2.5, §2.10)

Paraphrased contract; the parameter names are normative, the shapes are ours.

### 3.1 `GET /` and `POST /`

Both methods carry the same parameter set. `POST` (JSON body) is preferred by clients that
support it; the ECS must accept both and answer `405` if a method is unknown.

Required parameters (§2.3):

| Parameter | Type | Notes |
|---|---|---|
| `terminal_id` | string | IMEI preferred, or UUID; unique + persistent |
| `requestor_id` | string | required when a server (MDM/app server) acts for the device; makes `terminal_id` optional |
| `entitlement_version` | string | `1*DIGIT"."1*DIGIT`, e.g. `6.0`, `11.10` |
| `app` | string \| string[] | AppID, `ap2001`–`ap5999` range. Multi-valued: `&`-joined on GET, JSON array on POST |
| `terminal_vendor`, `terminal_model`, `terminal_sw_version` | string | required |
| `token` | string | the entitlement/auth token; keeps a `validity` attribute and must survive client reboots (§2.9.6) |
| `EAP_ID` | string | present when the client drives the embedded EAP relay (§2.8.1) |
| `notif_token` + `notif_action` | string + int | `notif_action`: 0 disable, 1 GCM, 2 FCM, 3 WNS, 4 APNS, 5 SNC |
| `temporary_token` / `operator_token` | string | used instead of `token` when the client has none (§2.8.6) |

Known AppIDs (OMNA registry, cited by TS.43 §2.3): `ap2003` Voice-over-Cellular, `ap2004`
VoWiFi, `ap2005` SMSoIP, `ap2006`/`ap2009` ODSA companion/primary, `ap2010` data-plan info,
`ap2011` ODSA server-initiated, `ap2012` Direct Carrier Billing, `ap2013` Private UserID,
`ap2014` device/user information, `ap2015` app authentication, `ap2016` SatMode.
Silent Auth does not need a new AppID: the entitlement result is consumed by Restlink's own
`/verify`, not provisioned onto the device. **Decide with the operator whether a private
AppID is needed at all (Q4, §7).**

### 3.2 Version control (§2.5)

The client states `entitlement_version`; the server answers with a version it supports, or
`406 Not Acceptable` when it cannot serve that version. Restlink must publish a supported
version set in config, not a single hard-coded number.

### 3.3 Response-code contract (§2.10)

| Code | ECS meaning | Silent Auth rule |
|---|---|---|
| `200` | configuration document (or EAP challenge while relaying) | the only success |
| `302` | OIDC/OAuth2 authentication must follow (`Location` header) | ECS-side; see §3.4 |
| `400` | invalid/missing parameters or wrong format | fail closed, no retry storm |
| `403` | invalid identity, or operation not allowed for this `requestor_id` | maps to the AAA's 4001/5001/5003 |
| `405` | method known but unsupported → client retries with GET | keep both methods alive |
| `406` | unsupported `entitlement_version` | version negotiation |
| `500` | internal error | fail closed |
| `501` | POST not implemented → client retries with GET | if we ship GET-only, this is the honest answer |
| `503` (+ `Retry-After`) | external resource unavailable / transient | the fail-closed default |
| `511` | network authentication required — start authn, get a new token | EAP/OIDC initiation |

CAMARA's `{code,message}` error envelope on `/verify` and TS.43's bare status-code contract
are **different surfaces** and must not be merged; the ECS answers TS.43 codes, the SAS answers
CAMARA codes.

### 3.4 Authentication variants

- **§2.8.1 Embedded EAP-AKA relay** — the ECS proxies EAP to the 3GPP AAA. Table in §2.1.
  Two legitimate outcomes: a full EAP-AKA authorization from `EAP_ID` alone, or (if a valid
  `token` was presented) a token check that **avoids the AAA round-trip entirely** — the spec
  allows this explicitly, and it is the cheap path for an existing device session.
- **§2.8.2 OAuth 2.0 / OIDC** — `302` to the operator's authorization endpoint; for clients
  that cannot use the SIM.
- **§2.8.3 Server-to-server OAuth 2.0** — the confidential client authenticates with a JWT
  client assertion (`urn:ietf:params:oauth:client-assertion-type:jwt-bearer`), never sending
  `client_secret` in the request. Restlink already has `private_key_jwt` verification and JWKS
  in `sas-api/.../oauth/`; an ECS S2S **client** (signing side) plus an allow-listed
  `requestor_id`→client mapping is the missing half.

### 3.5 Entitlement document (§2.9)

The response body carries at least `VERS` and `TOKEN` attributes, plus per-app parameters
(TS.43 §3 VoWiFi, §4 Voice-over-Cellular, §5 SMSoIP, §6 ODSA). For a Silent Auth ECS the
document is deliberately thin: `VERS`, `TOKEN`, and the `RegisterNotifStatus` parameter when a
notification token was registered. Building the full VoWiFi/SMSoIP document generator is out
of scope — flag it as operator-dependent (Q5, §7).

---

## 4. What the SAS must *not* do (invariants)

These are existing repo rules; the ECS work must not erode them.

| Rule | Source | Consequence for the ECS |
|---|---|---|
| EAP-AKA terminates at the **operator** AAA, never in the SAS | `AGENTS.md` §4, contract §4 | the ECS **relays**; it never derives keys or acts as the authentication server |
| **Own HSS only** — no interconnect AAA/ATI | FS.11 Category 1 | one operator peer configured; no roaming probe path |
| **Fail-closed** — missing evidence, timeout, unmapped code ⇒ never approve | `AGENTS.md` §4 | every §3.3 non-`200` is a refusal |
| **Single-use** entitlement token, TTL ≤ 300 s | CAMARA + H19 | keep `EntitlementConfig.MAX_TOKEN_TTL_SECONDS = 300` |
| **Attested `/issue`** — the caller proves it is the AAA | `AttestationVerifier`, gate H19 / PRO-23 | if the ECS becomes the caller, the attestation secret is the ECS↔AAA binding; keep `issue-attestation-required=true` in prod |
| **Privacy** — MSISDN/IMSI never leave the server side | `AGENTS.md` §4, H8 | the ECS document carries a token, not a number |
| **micro-jainslee owns the runtime** | gate H24 | EAP relay = RA delegate under `ras/`; no hand-rolled executors/timers |
| **SS7/Diameter over SCTP in this workspace** | workspace rule | relay transport default SCTP; see §5 |

---

## 5. Network plan (what the operator must give us)

| # | Item | Value to agree | Why |
|---|---|---|---|
| N1 | Peer addressing | ECS FQDN + IP; the device-facing FQDN `aes.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org` (§2.1) | TS.43 discovery; also N2 for the SAN cert |
| N2 | TLS material | server cert for the ECS FQDN, client CA for AAA↔ECS mTLS | §2.8.1 DER is an authenticated channel; without mTLS anyone can answer challenges |
| N3 | AAA transport | Diameter (SWm-style EAP application, RFC 4072) or a T5 client of the operator's SE; **SCTP** per workspace rule, TCP only if the operator's stack forces it | EAP payloads ride Diameter `EAP-Payload` |
| N4 | Ports | ECS HTTPS (SAS profile default 8443; lab 8085), Diameter peer (lab 3868/3869) | `application.properties`, `application-prod.properties` |
| N5 | Realms / identities | `sas.transport.diameter.origin-host` / `realm` / `destination-host` / `destination-realm` (already config-driven) | Diameter identity |
| N6 | Subscribers for UAT | test SIM/MSISDN set with EAP-AKA enabled, plus a barred/detached one | fail-closed proof (403 vs 200) |
| N7 | SIM-swap source for the Wi-Fi path | CAMARA SIM Swap REST or read-only Sh UDR/SNR | `docs/P2-missing_item.md` item 3, open question 6 |
| N8 | Attestation secret + rotation | shared secret for `/issue`, rotation cadence | contract §9 Q4 |

**SCTP note (workspace mandate):** SS7 signalling is SCTP-only, and the SAS already speaks
SCTP for the lab Diameter peer (`sas.transport.sd.sctp=true`, `sas-diameter-testapp` on
`--diameter-port`). The EAP relay must default to SCTP; a TCP fallback is an operator-forced
deviation that must be recorded, not a default.

---

## 6. Implementation work plan (ordered, each step shippable)

| # | Step | Lands in | Gate / proof |
|---|---|---|---|
| W1 | ECS HTTP skeleton: `GET/POST /` with the §3.1 parameters, `501` for POST until W3, `405` for unknown methods | `sas-entitlement/src/main/java/.../ecs/` | unit tests on the parameter/response mapping; H19 still green |
| W2 | Version gate (§2.5) + the §3.3 response-code table, incl. `406` | same | table-driven tests |
| W3 | `POST` JSON body incl. `app` as array | same | tests |
| W4 | Notification registration store (`notif_token`/`notif_action` → `RegisterNotifStatus`) | same + a push integration seam | tests; no real APNS/FCM in v1 |
| W5 | **EAP relay RA**: `ras/eaprelay/` — DER client, `EAP_ID` handling, §2.1 result-code map, 2 s bounded dialog, abort on timeout | `sas-host/src/main/java/et/restlink/sas/ras/eaprelay/` | H24 must still pass with no new allow-list entry; lab testapp gains a DER responder |
| W6 | Entitlement document builder (`VERS`, `TOKEN`, minimal) | `sas-entitlement` | tests |
| W7 | Wire the relay result into the existing token issue: successful EAP ⇒ `/entitlement/issue` (attested), so the existing `/verify` CIBA path is unchanged | `EntitlementResource` + RA command | E2E against `sas-diameter-testapp` |
| W8 | ECS S2S client (`private_key_jwt` assertion) for `requestor_id` allow-listing | `sas-entitlement` or `sas-api/.../oauth` client side | tests + discovery/JWKS interop |
| W9 | OIDC redirect (shape B / clients without SIM) | ECS | tests |
| W10 | Discovery + docs: FQDN, runbook, operator onboarding checklist | `docs/` + `scripts/` | `harness/run_hardness.py` 34/34, `preflight_prod.py` clean |

**Lab-first, as always:** W5 cannot be proven against a real operator AAA, so the
`sas-diameter-testapp` must gain a DER (RFC 4072) responder that can be scripted per scenario
(success, multi-round, unknown identity, transient failure) — the same trick `SwxHandler`
already plays for SWx.

---

## 7. Open questions for the operator (block the design, not the lab)

1. **Who runs the ECS** — Restlink (shape A) or the operator (shape B)? Shape A needs an
   operator-side EAP/DER endpoint today; shape B needs only a result API.
2. **AAA placement** — does Ethio Telecom run a 3GPP AAA today, or is it a new build? (contract §9 Q1)
3. **Relay transport** — SWm-style Diameter EAP, or T5 to an SE function? Does the operator's
   stack do SCTP only?
4. **AppID** — do we need a private OMNA AppID for the silent-auth entitlement, or is the
   entitlement purely server-to-server (bank backend) with no device-visible document?
5. **Document scope** — do we ship a minimal `VERS`/`TOKEN` document, or must the ECS also
   generate the VoWiFi/VoLTE/SMSoIP parameter blocks a real TS.43 client expects?
6. **SIM-swap freshness for Wi-Fi** — CAMARA SIM Swap REST, or read-only Sh UDR/SNR?
7. **Attestation** — keep the `/issue` HMAC, or replace it with mTLS client certs once N2 lands?
8. **Key/secret lifecycle** — rotation cadence for the HMAC and the OAuth client keys (P-H3).

---

## 8. What is explicitly *not* in this plan

- No T5 message-level specification. T5 is operator-internal; the plan treats the
  EAP/DER relay (§2.8.1) as the integration contract and flags the T5 question to the operator
  (Q3). Do not invent T5 AVPs here.
- No VoWiFi/VoLTE/SMSoIP entitlement generation (Q5) — those are the operator's IMS business.
- No change to the CAMARA `/verify` contract, the FSM, or the assurance scoring. The ECS
  produces a token; the existing `/verify` path consumes it exactly as it consumes the
  lab-issued token today.
- No production deployment. `harness/preflight_prod.py` remains the gate, and the lab profile
  (plain HTTP, `memory` transports) is never shippable.
