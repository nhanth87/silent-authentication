# TS.43 Entitlement Service ↔ Core Network Plan

**Scope:** Full TS.43 EAP-AKA/-' entitlement server integrated with MAP (SS7/SCTP) and Diameter (SWx/S6a) signalling. Replaces lab-only `EapAkaDemoPeer` with production-ready FSM, secure key handling, and network-based subscriber binding. Prerequisite for Phase 2 (Auth Code Flow) and Phase 3 (OTP SMS).

> **Status: plan of record. D6 decided — Shape S** (§2.1.1): the entitlement service
> terminates EAP-AKA itself, under an operator-granted AuC access agreement. **Shape R**
> (relay to the operator AAA, TS.43-native) is retained as the **production fallback /
> pending issue** (§2.1.2) with explicit flip triggers — production must not boot before
> those triggers are closed in writing.
> Supersedes the earlier gap analysis in `entitlement-core-network-plan.md` for scope and
> phasing; that document is kept for the operator-facing ECS/relay contract.

**Audience:** Digicom-ET development, Ethio Telecom integration team, operators.

**Date:** 2026-09-30  
**Tree:** `worktrees/silent-authentication/main` (Java 25, Quarkus micro-jainslee, H24 enforce)

---

## 1. High-level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│  UE (USIM, carrier-privilege app or helper app)                         │
│  └─ HTTPS TS.43 (REST, EAP relay framing)                             │
└─────────────────────────────────────────────────────────────────────────┘
                                 ▲
                                 │ POST /ts43 (EAP-Request/Response)
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  /ts43 REST endpoint (thin submit-and-await)                           │
│    ─────────────────────────────────────────────────────────────────   │
│  ┌──────────────── SLEE container (H24-enclosed) ─────────────────┐    │
│  │ EntitlementSbb (FSM: IDLE → IDENTITY → VECTOR → CHALLENGE → │    │
│  │                  VERIFIED → BINDING → TOKEN_ISSUED)           │    │
│  │                                                                 │    │
│  │  ├─ EapAkaEngine (pure library, no I/O)                        │    │
│  │  │   └─ RFC 4187, RFC 9048/5448, TS 33.402 Annex A crypto     │    │
│  │  │                                                              │    │
│  │  ├─► AuthVectorRA (submits command, awaits result)            │    │
│  │  │    ├─ Jss7MapAuthBackend    ──┐                            │    │
│  │  │    └─ CorsacSwxAuthBackend   ──┼─ I/O only here (seam)     │    │
│  │  │                                │                            │    │
│  │  ├─► SubscriberBindingRA                                      │    │
│  │  │    ├─ SwxSarBinding      (IMSI→MSISDN)     ──┐             │    │
│  │  │    ├─ ShUdrBinding       (read-only Sh)    ──┼─ I/O        │    │
│  │  │    ├─ MapSendImsiBinding (verify IMSI)     ──┤             │    │
│  │  │    └─ SubscriberDbBinding (fallback/DPP)   ──┘             │    │
│  │  │                                                              │    │
│  │  └─ EntitlementTokenService (issue token)                     │    │
│  │                                                                 │    │
│  │  Timer, activity, event processing all inside container.       │    │
│  └──────────────────────────────────────────────────────────────┘    │
│                                 ▲                                      │
│               ┌─────────────────┴───────────────────────┐             │
│               │  SCTP:2906 (jSS7)  Diameter:3868       │             │
│               ▼                      ▼                  │             │
│      ┌──────────────────┐   ┌──────────────────┐       │             │
│      │ HLR Simulator    │   │ HSS/AAA Sim      │       │             │
│      │ (jSS7-testapp)   │   │ (diameter-ta)    │       │             │
│      └──────────────────┘   └──────────────────┘       │             │
│              ▲ SAI (quintet)         ▲ MAR/MAA         │             │
│              └────────────────────┬──┘ SWx paths       │             │
│                                   │                    │             │
│                            (lab setup)                 │             │
│                                                        │             │
│  [Production]                 [HLR (2G/3G)]  [HSS (LTE/5G)]          │
│  ┌────────────────────────────────────────────────────────┐          │
│  │ Core network (Ethio Telecom responsibility)           │          │
│  │ ├─ Global Title provision for SAS                     │          │
│  │ ├─ Diameter Origin-Host/Realm + whitelist             │          │
│  │ ├─ STP: route/firewall M3UA and SWx                   │          │
│  │ ├─ DEA (Diameter Edge Agent) for SEPP/N32             │          │
│  │ ├─ ARA-M (Access Rule Application Master) on UICC:   │          │
│  │ │    app cert for bank or helper app (FS.11)           │          │
│  │ └─ SIM provisioning + AuC/HlrAuc parameter (K, OPc)   │          │
│  └────────────────────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Critical Decisions (Gate Pre-Build)

These decisions **block the implementation** until resolved with Ethio Telecom. ADR reference: this document (§2 = the decision record; there is no separate `adr-*` file in this tree).

| ID | Decision | Impact | How to close |
|---|---|---|---|
| **D1** | Does bank app have **carrier privilege** on Ethio Telecom USIM for `TelephonyManager.getIccAuthentication()`? | If no: EAP-AKA is unreachable from app. iOS has zero API. Must use helper app (nhà mạng ký) or fall back to OTP. | **Ethio Telecom** confirm ARA-M (Access Rule Application Master) rule for the Restlink bank app or helper app. **iOS:** OTP/Passkey fallback only. |
| **D2** | Core network topology: which paths are live? | Determines default backend order: (a) HSS only (MAR/SWx-only), (b) HLR only (SAI-only), (c) dual (HLR+HSS). | **Ethio Telecom** network diagram + HSS vendor (ALU, Ericsson, Oracle…). |
| **D3** | How to hydrate IMSI → MSISDN when only MAP available? | MAP PSI returns location, not MSISDN. SAI returns vectors, not subscriber identity. | Choose one: (1) MAP SendIMSI on claimed MSISDN (device-declared), (2) subscriber DB read-only export, (3) ATI (nope, FS.11 ban). See §3.4. |
| **D4** | What TS.43 **app identifier** and token format? | Token expires, format, scope. Not inventing. | **Ethio Telecom** or **GSMA** spec section reference. |
| **D5** | **Global Title and Diameter realm** for SAS? | STP/DEA route + whitelist. AuC/HSS credential trust. | **Ethio Telecom** GT assignment (e.g. `+251xxx`), Diameter `Origin-Host` (e.g. `sas.ethiotel.et`), realm, mutual auth cert. |

| **D6** | **Where does EAP-AKA terminate?** This plan moves AKA verification **into the SAS** (`EapAkaServer`, RES/XRES compare, MAC check, AUTS resync). Today the tree states the opposite in three places. | **Architectural reversal.** It also means Restlink must hold a verified AKA stack and manage SQN resync against the operator AuC — an operator will not grant that lightly. | **Owner decision (Restlink, then Ethio Telecom).** See §2.1. |

**No Phase 1 code can land without D1–D5 closed. D6 is decided (Shape S, §2.1.1); the
open production risk is R1–R3 in §2.1.2.**

### 2.1 D6 — the EAP-AKA termination decision (blocking, owner-level)

This plan is a genuine architectural change, not an implementation detail. The existing
invariants, verbatim from the tree:

- `AGENTS.md` §4 / contract §1: *"SIM credential, EAP-AKA / EAP-AKA' termination | **Operator
  3GPP AAA** | RFC 5448; **never in SAS**"*
- `ts43-eapaka-wire-protocol.md` §7: *"**EAP-AKA terminates at the operator AAA**, never in SAS"*
- contract §4: *"The SAS does **not** implement an EAP-AKA peer in production; the lab
  `sas-diameter-testapp` and `EapAkaDemoPeer` exist only to emulate the operator AAA for a POC."*

So the two designs are mutually exclusive:

| | **Shape S — SAS is the EAP server** (this plan, Phase 1a–1d) | **Shape R — SAS relays EAP to the operator AAA** (GSMA TS.43 §2.8.1, the spec-native way) |
|---|---|---|
| Who verifies RES/MAC | **Restlink** | **operator 3GPP AAA** |
| Vectors | SAS fetches CK/IK/XRES from HSS over SWx, or the full quintuplet over MAP SAI | SAS relays EAP payloads; the AAA fetches and consumes its own vectors |
| SQN / AUTS | SAS owns resync state → an off-by-one desyncs the AuC for the real network | never Restlink's problem |
| SIM key material in Restlink's process | CK/IK/MSK/EMSK transiently — a much larger secret surface | none; the relay sees EAP packets, not keys |
| What the operator must agree to | hand Restlink vector-grade access to the AuC | expose a DER/EAP relay endpoint (or run the ECS themselves) |
| Compliance fit | moves Restlink from "adapter above the operator" toward being an **AAA** | stays an adapter; matches TS.43 §2.8.1 literally |

### 2.1.1 D6 — DECIDED: Shape S (SAS is the EAP server)

**Decision: Shape S.** The entitlement service terminates EAP-AKA itself: it fetches vectors
from the operator HSS/HLR, verifies RES against XRES, checks `AT_MAC`, handles one AUTS
resync, then mints the entitlement token. Restlink accepts that this moves it from
"adapter above the operator" toward being an AAA on the Wi-Fi entitlement path.

Accepted consequences (all deliberate, all owned by Restlink):

- CK/IK/MSK/EMSK exist transiently in the SAS process → the secret-handling rules in §3.4
  and the zeroization mutation check (H25M) become load-bearing, not hygiene.
- The SAS owns SQN resync state → §7 requires per-IMSI rate limiting, a hard resync cap of
  1, and an explicit "do not retry on MAC failure beyond the first resync" rule.
- Vector consumption and the `/verify` path must stay **disjoint**: `AGENTS.md` §5's
  "no AIR on the verify path" (which exists because vectors advance the AuC SQN) is
  preserved by confining every vector-consuming operation to `/ts43`. The `/verify` FSM
  still uses the read-only, non-consuming evidence path.
- MAP SAI is vector-consuming by construction, so under Shape S it may only serve the
  EAP-AKA (not AKA') case and must be off the `/verify` hot path.

**New boundary, stated once, everywhere:** *EAP-AKA terminates at the operator 3GPP AAA,
**except** on the Restlink entitlement service (`POST /ts43`), where Restlink is the EAP
server under an operator-granted AuC access agreement. No other surface may terminate or
consume authentication vectors.* Amended in: `AGENTS.md` §4, contract §1/§4,
`ts43-eapaka-wire-protocol.md` §7.

### 2.1.2 Shape R retained as the **production fallback** (PENDING ISSUE)

Shape S is the build target. Shape R is **not discarded — it is the escape hatch**, and it is
retained here so a production deploy does not fail on it.

**Why it is likely to be needed:** Shape S is only deployable if Ethio Telecom grants
Restlink **vector-grade AuC access** (SWx MAR returning CK/IK/XRES, or MAP SAI returning the
quintuplet, with the AuC's SQN/AV state shared with Restlink). Many operators will not. If
they refuse — or if the SQN-resync liability is unacceptable contractually — Shape S cannot
go to production and the deployment **must** flip to Shape R.

**Triggers that force the flip (any one is sufficient):**

| # | Trigger | Detected by |
|---|---|---|
| R1 | Operator refuses vector-grade AuC access for a third party | D2/D5 negotiation (ADR sign-off) |
| R2 | Operator exposes only a Diameter EAP / DER endpoint (TS 29.273 + RFC 4072) or an SE/T5 function | D2 answer, D3 |
| R3 | Operator runs its own TS.43 ECS and hands out entitlement results over an API | D1/Q1 of the operator conversation |
| R4 | Real-network UAT shows SQN resync instability (`AUTS` loops, MAC failures at volume) | Phase 1e / UAT metrics |
| R5 | Contractual/security review rejects Restlink holding CK/IK at all | Restlink + operator security review |

**What Shape R requires (so the switch is configuration, not a rewrite):**

1. `EapAkaServer` is reused **as the client/referee** — no crypto is thrown away. The UE SDK
   needs the same `EapAkaKeys`/`EapAkaPrimeKeys` to compute `AT_RES`/`AT_MAC`.
2. The vector-fetch RAs (`AuthVectorRA`, `Jss7MapAuthVectorBackend`, `CorsacSwxAuthVectorBackend`
   returning CK/IK) are **replaced, not extended**, by a relay RA that forwards EAP payloads
   over Diameter EAP (RFC 4072) to the operator AAA and maps the DER Result-Code per GSMA
   TS.43 §2.8.1 (see `entitlement-core-network-plan.md` §2.1 for the normative table).
3. `EntitlementSbb` keeps its FSM, timers and token issuance; only the VECTOR state changes
   from "fetch + verify locally" to "relay + await AAA outcome".
4. Therefore: **no `/ts43` request/response contract, no FSM state name, no token format and
   no gate may depend on Shape S specifically.** This is a design constraint on Phase 1d —
   if `EntitlementSbb`'s public surface leaks "I hold CK/IK", the R5→R1 flip becomes a
   rewrite and the fallback is worthless.

**Action before any production deploy:** close R1–R3 in writing. Until then a Shape S
production boot is a **D2/D5-unresolved deploy**, and the prod gate must refuse it
(follow-up: a `PRO-3x` preflight check that requires the recorded operator AuC-access
agreement reference; the D6 gate itself is H25 + `PRO-30`, per §5.4/§5.5).

---

## 3. TS.43 Entitlement Service — Transport, FSM, I/O

### 3.1 Module Structure (H24-compliant)

All new code is either pure library (no I/O) or sits in `/ras/` seam. No naked jSS7/Diameter outside of resource adapters.

```
sas-entitlement/                                        (module is in the root reactor; no I/O here)
├── src/main/java/et/restlink/sas/entitlement/
│   ├── eap/
│   │   ├── EapPacket.java              (codec RFC 3748: code, type, ID, attrs)
│   │   ├── EapAkaAttributes.java        (AT_* encode/decode, builder)
│   │   ├── EapAkaKeys.java              (CK, IK, K_encr, K_aut, MSK, EMSK per RFC 4187 §7)
│   │   ├── EapAkaPrimeKeys.java         (CK', IK' per RFC 9048 / TS 33.402)
│   │   └── EapAkaServer.java            (pure FSM: CHALLENGE, RESPONSE, FAILURE)
│   ├── ts43/
│   │   ├── Ts43Request.java             (terminal_id, app, entitlement_version, token, EAP_ID)
│   │   ├── Ts43Response.java            (EAP relay body + entitlement token)
│   │   └── Ts43Parser.java              (query + JSON body, EAP packet extraction)
│   │
│   ├── EntitlementConfig.java           (sas.entitlement.* properties)
│   ├── EntitlementTokenService.java     (exists — method is issueToken(msisdn,imsi,eapMethod);
│   │                                   │  signalTsOpaque() is NEW and does not exist yet)
│   ├── EntitlementResource.java         (exists)
│   └── AttestationVerifier.java         (exists)
│
├── src/test/java/et/restlink/sas/entitlement/          ← tests live HERE, not in src/main
│   ├── eap/EapPacketTest.java           (frame round-trip, fuzz: truncate/duplicate/unknown attr)
│   ├── eap/EapAkaKeysTest.java          (RFC 4187 vector, RFC 9048 CK'/IK', Milenage TS 35.208)
│   ├── eap/EapAkaServerTest.java        (challenge, RES verify, MAC fail, AUTS resync ×1)
│   └── ts43/Ts43ParserTest.java         (param binding, multi-valued app, version gate)
│
└── NOTE: SAS_AUTHVECTOR / SUBSCRIBER_DB are pure lookups, no raw I/O → they may live in
   sas-api, not in an RA. H24 only forces a transport client (jSS7/Diameter/socket) into /ras/.

sas-host/
├── src/main/java/et/restlink/sas/
│   ├── ras/authvector/                  ← the ONLY place a jSS7/Diameter client may live
│   │   ├── AuthVectorBackend.java        (interface: fetchVectors(imsi,scheme,n)→Future<Vec>)
│   │   ├── CorsacSwxAuthVectorBackend.java (SWx MAR — EXTENDS the existing
│   │   │   │                             CorsacSwxVerifierBackend, do NOT fork a 2nd client:
│   │   │   │                             MAR/MAA + SAR/SAA + per-Session-Id correlation already
│   │   │   │                             work; add CK/IK + resync to that one)
│   │   ├── Jss7MapAuthVectorBackend.java (MAP SAI, quintet only; triplet → reject)
│   │   ├── InMemoryAuthVectorBackend.java (lab only; PRO-30 refuses it in prod)
│   │   ├── AuthVectorResourceAdaptor.java (holds RaBootstrapPort, fires result events)
│   │   ├── AuthVectorRaEndpoint.java     (implements RaEndpointPort + RaCommandPort)
│   │   └── command/{FetchVector,Resync,AbortAuth}Command.java
│   │
│   ├── ras/binding/
│   │   ├── SubscriberBindingBackend.java  (interface: lookup(imsi)→Future<msisdn>)
│   │   ├── SwxSarBinding.java             (SWx SAR, Server-Assignment-Type = 12
│   │   │                                 AAA_USER_DATA_REQUEST — exists in corsac's
│   │   │                                 ServerAssignmentTypeEnum; the current SAS code
│   │   │                                 sends REGISTRATION(1) — pick one, see §3.3)
│   │   ├── ShUdrBinding.java              (read-only Sh UDR/SNR)
│   │   ├── MapSendImsiBinding.java        (MAP SendIMSI on claimed MSISDN)
│   │   ├── SubscriberDbBinding.java       (read-only export fallback)
│   │   ├── SubscriberBindingResourceAdaptor.java
│   │   ├── SubscriberBindingRaEndpoint.java
│   │   └── command/{LookupBinding,AbortBinding}Command.java
│   │
│   ├── sbbs/
│   │   ├── EntitlementSbb.java            (FSM, timer, zeroize CK/IK/XRES)
│   │   └── EntitlementSbbEvents.java
│   │
│   ├── web/Ts43Resource.java             (GET/POST /ts43 — thin submit-and-await)
│   ├── bootstrap/SasBootstrap.java        (register AuthVectorRA, BindingRA, EntitlementSbb)
│   └── cdr/Ts43Cdr.java                   (timestamp, IMSI hash, result; no secrets)
```

Corrections vs the first draft (all verified against this tree):

| Draft | Reality | Evidence |
|---|---|---|
| `class AuthVectorResourceAdaptor extends ResourceAdaptor` | no such base class. RA pattern = plain `final class` implementing `RaEndpointPort, RaCommandPort` + a delegate holding `RaBootstrapPort` | `ras/swxverifier/SwxVerifierRaEndpoint.java:28`, `SwxVerifierResourceAdaptor.java:30` |
| `EapAkaTest.java`, `Ts43Test.java` under `src/main` | tests live in `src/test/java` and are named `*Test` | every existing test in the tree |
| `CorsacSwxAuthBackend` as a new client | `CorsacSwxVerifierBackend` already does MAR/MAA + SAR/SAA + PPR probe over the same link with per-Session-Id correlation and fail-closed result mapping | `ras/swxverifier/CorsacSwxVerifierBackend.java:81` |
| `S6aDialog, S6aExchangeCorrelator adapted to SWx MAR` | `SwxDialog` + `SwxExchangeCorrelator` already exist for SWx — do not adapt the S6a ones | `ras/swxverifier/SwxDialog.java`, `SwxExchangeCorrelator.java` |
| `sas.authvector.backend-order` as a new switch | extend `SasTransportConfig` (`sas.transport.swx` / `.map` already exist and are preflight-gated) | `config/SasTransportConfig.java:47-49` |
| `EntitlementTokenService.issue(...)` | the method is `issueToken(msisdn, imsi, eapMethod)`; `signalTsOpaque` does not exist | `EntitlementTokenService.java:145` |

### 3.2 EAP-AKA Cryptography (Pure Library)

**File:** `sas-entitlement/.../eap/`

```java
// RFC 4187 message format
class EapPacket {
    byte code;           // 1=Request, 2=Response, 3=Success, 4=Failure
    byte type;           // 23=AKA, 50=AKA'
    int id;              // incrementing
    List<Attribute> attributes;
    // encode/decode, no I/O
}

class EapAkaAttributes {
    // Assigned numbers — verified against RFC 4187 §11, RFC 5216, RFC 9048 §8 and the
    // IANA EAP registry (method types: 23 = EAP-AKA [RFC 4187], 50 = EAP-AKA' [RFC 9048]):
    //   AT_RAND(1) AT_AUTN(2) AT_RES(3) AT_AUTS(4) AT_NONCE_SEND(5) AT_NONCE_RECEIVE(6)
    //   AT_SOURCE_IDENTIFIER(7) AT_AUTHORIZATION_IDENTIFIER(8) AT_AUTHENTICATOR_INFO(9)
    //   AT_MAC(11) AT_IDENTITY(14) AT_ENCR_KEYS(16) AT_SELECTED_CIPHER_SUITE(20)
    //   AT_VENDOR_SPECIFIC(21) AT_CLIENT_ERROR_CODE(22) AT_KDF_INPUT(23) AT_KDF(24)
    // CORRECTION vs the first draft: there is NO AT_ENCR_DATA(130) — 130 is NAS-Identifier
    // [RFC 6696] in the IANA EAP registry. EAP-AKA' derives CK'/IK' in the clear from
    // CK||IK + KDF inputs; encrypted key transport is the optional EAP-NAK AT_ENCR_KEYS(16)
    // and is not needed when the ECS↔UE hop is TLS.
    // Builder pattern, constant-length padding to block boundary
}

class EapAkaKeys (RFC 4187 §7 + RFC 9048 for AKA') {
    // Input: RES (4..16 bytes), IK, CK (128 bits each), RAND (128 bits), AUTN
    // Output (RFC 4187): K_encr, K_aut (256 bits), MSK (64 bytes), EMSK (64 bytes)
    // Computed via SHA1-based PRF (FIPS 186-2) or HMAC-SHA256 (AKA')
    // static deriveFromMilenage(...)
    // Key material never escapes as plain bytes—always hold in SecretKey
}

class EapAkaPrimeKeys {
    // RFC 9048: CK' = KDF(CK || IK, ...)  ← TS 33.402 Annex A
    // IK' = KDF(...), via HMAC-SHA256, network-name-dependent
    // static derivePrime(ck, ik, networkName)
}

class EapAkaServer {
    // Pure state machine; caller provides vector, MAC secret, etc.
    // no timer, no network call
    void acceptChallenge(Vector, ...);
    void acceptResponse(EapPacket, expectedXRES, MAC key);
    // → EapResult {success|resyncAUSTS|failure}
}
```

**Test vectors** (committed to repo):
- RFC 4187 Appendix (K, OPc, RAND, expected CK/IK/RES)
- RFC 5448 (Milenage test set TS 35.208)
- RFC 9048 (CK'/IK' with network name variants)
- AUTS resynchronization (SQN desynchronization recovery)
- Milenage fuzz: truncated RAND, oversized RES, attribute duplication

### 3.3 Signalling Paths (Diameter preferred, MAP fallback)

#### Diameter (SWx) — Primary if HSS available

**Operation:** `SWx MAR/MAA` (TS 29.273 §8, Multimedia-Authentication)

```
SAS ─────────────────┐
    MAR (client)     │
    • Destination   : HSS Realm/Host (config)
    • User-Name     : IMSI@nai.epc
    • Auth-Application-Id: 16777265 (SWx, TS 29.273)
    • SIP-Auth-Data-Item (request):
      └─ SIP-Authentication-Scheme: "EAP-AKA'" (or "EAP-AKA")
      └─ SIP-Authorization: <empty on first challenge>
    • [on resync] SIP-Authorization: RAND || AUTS (RFC 3310)
    ↓
   HSS
    ↓
    MAA (server)
    • Result-Code: 2001 (success)
    • SIP-Auth-Data-Item (response):
      └─ SIP-Number-Auth-Items: 1
      └─ SIP-Authentication-Scheme: "EAP-AKA'" (echo)
      └─ SIP-Authenticate: EAP-Request/AKA-Challenge (RAND || AUTN || MAC)
      └─ SIP-Authorization: XRES (expected response)
      └─ Confidentiality-Key / Integrity-Key: CK / IK (for AKA': CK' / IK'
         already derived by the HSS from the ANID in the MAR)
      (AVP names per TS 29.229/29.273 — verify against the spec)
    ↑ timeout 2s (Diameter Tx)
└─────────────────────────
```

**Resynchronization** (RFC 3310, max 1× per session):

```
SAS ─ MAR w/ SIP-Authorization: RAND || AUTS ─► HSS
HSS computes new vector from same vector request (auts triggers SQN re-adjust)
HSS ─ MAA w/ new SIP-Auth-Data-Item ─► SAS
```

#### MAP (SAI) — Fallback if HLR only

**Operation:** `SendAuthenticationInfo` (TS 29.002 MAPServiceMobility)

```
SAS ─────────────────┐
    MAP SAI (req)    │
    • IMSI           : subscriber IMSI
    • numberOfRequestedVectors: 1
    • immediateResponseRequired: true
    ↓
   HLR/AuC
    ↓
    MAP SAI-ack
    • tripletList (GSM only, not UMTS) — REJECTED
    OR
    • quintetList (UMTS) — accepted
      └─ RAND (16 bytes), SRES (4 bytes), Kc (8 bytes)
         RES (6–8 bytes), CK (16 bytes), IK (16 bytes)
    ↑ timeout 2s (TC-TIMER)
└─────────────────────────

SAS derives CK' and IK' locally via TS 33.402 Annex A (Milenage MAC-A).

⚠️ **MAP SAI cannot serve the EAP-AKA' case.** The AKA' derivation binds CK'/IK' to the
access-network name (TS 33.402 / RFC 9048 KDF); the MAP quintuplet carries no ANID, so SAI
only supports plain EAP-AKA. If the operator's Wi-Fi AAA runs EAP-AKA', the MAP backend is
insufficient — that is decision **D2/D4**. SAI also consumes a real vector from the AuC and
advances SQN, the same risk class the design already refuses for AIR on the `/verify` path
(`AGENTS.md` §5), so it must be rate-limited and kept off the `/verify` hot path.
```

#### Binding Path: IMSI → MSISDN

**Preferred: Diameter SWx SAR** (`AAA_USER_DATA_REQUEST`)

```
SAS ─ SAR ─► HSS
    • Destination: HSS (non-3GPP-User-Data request)
    • User-Name: IMSI@nai.epc
    • Server-Assignment-Type: 12 (AAA_USER_DATA_REQUEST)
      ⚠ corsac's ServerAssignmentTypeEnum HAS this value (verified), but the SAS
        currently sends REGISTRATION(1) — CorsacSwxVerifierBackend.java:319. Pick ONE:
        12 is the read-only correct choice (does not register as serving AAA);
        1 changes AAA registration state and is NOT read-only.
    • Auth-Application-Id: 16777265 (SWx) — verified in the TS 29.230 Diameter
      application registry: STa=16777250, S6a=16777251, SWm=16777264, SWx=16777265,
      S6b=16777272
    ↓
   HSS (read-only with type 12)
    ↓
    SAA
    • Result-Code: 2001
    • Non-3GPP-User-Data (grouped AVP) → Subscription-Id END_USER_E164 = MSISDN
      (the lab SwxHandler already returns exactly this — reuse it, §5.3)
    ↑ timeout 2s
└───────────────────────
```

**Fallback 1: Read-only Sh UDR** (TS 29.328 §7.4.8)

```
SAS ─ UDR ─► HSS
    (already used in Verifier; reuse same path)
    → returns MSISDN from subscriber profile
```

**Fallback 2: MAP SendIMSI** (TS 29.002, verify claimed MSISDN)

```
Device claimed MSISDN to bank.
Bank called /verify with claimed MSISDN.
SAS looks up IMSI via SAI earlier.
Now verify: MAP SendIMSI(claimedMSISDN) ─► HLR
HLR returns IMSI (or error if MSISDN not found).
SAS compares: IMSI(fromeap) == IMSI(frommap) ✓
If mismatch or HLR says "no such number" → FALLBACK
```

⚠️ **Do NOT use SRI-SM** (Send Routing Info for SM) to get MSISDN from IMSI. If Ethio Telecom has enabled SMS Home Routing (Strategy B), SRI-SM returns a correlation ID or routing proxy, not the real MSISDN. Result is spurious binding.

**Fallback 3: Subscriber DB** (read-only export for `device-phone-number` / device discovery)

```
sas.binding.subscriber-db-path=/opt/sas/subscribers.json
[{"imsi":"251910000001","msisdn":"+251911234567"}, ...]
Cold-loaded at startup, never updated inside SAS.
```

---

### 3.4 EntitlementSbb FSM and Timeout Strategy

**States:**

```
IDLE
  ├─ GET /ts43?vers=1&app=ei.et&EAP_ID=... ─► Ts43Event(IDENTITY_RECEIVED)
  ▼
IDENTITY_RECEIVED
  ├─ EapAkaServer.accept(identity) → new session ID
  ├─ FetchVectorCommand(IMSI, scheme) ─► AuthVectorRA
  ├─ await event AuthVectorResult (timeout 2s)
  │   ├─ success (RAND, AUTN, XRES, CK, IK)
  │   │  ─► EAP-Request/AKA-Challenge built, key material in memory
  │   └─ error (unsupported scheme, HLR no quintet, …)
  │      ─► FALLBACK → FAILED
  ▼
CHALLENGE_SENT
  ├─ POST /ts43 eap-relay-packet: EAP-Response/AKA-Challenge
  ├─ parse, extract AT_RES, AT_MAC, check for AT_AUTS
  │
  ├─ if AT_AUTS present (resync request):
  │   ├─ check: resync_count < 1  (max 1 resync per session)
  │   ├─ ResyncCommand(RAND, AUTS)
  │   ├─ await (timeout 2s)
  │   ├─ increment resync_count
  │   │  ─► goto CHALLENGE_SENT with new challenge (loop)
  │   └─ if resync_count >= 1  or  resync fails
  │      ─► FAILED (EAP-Failure)
  │
  ├─ else (no AUTS):
  │   ├─ verify MAC = HMAC-SHA256(K_aut, ...)  (constant-time compare)
  │   ├─ if MAC invalid or RES != XRES
  │   │  ─► FAILED
  │   │
  │   └─ if OK:
  │      ├─ zeroize XRES, RAND, AUTN (no longer needed)
  │      ├─ LookupBindingCommand(IMSI, optionalClaimedMSISDN)
  │      ├─ await (timeout 2s)
  │      ├─ check: binding_result.msisdn present and valid
  │      │  ─► VERIFIED
  │      └─ binding error
  │         ─► FALLBACK
  │
  ├─ if any other error, timeout (activity > 45s total), or UE sends AT_CLIENT_ERROR_CODE
  │  ─► FAILED
  │
  ▼
VERIFIED
  ├─ MSK in memory; CK/IK already zeroized
  ├─ EntitlementTokenService.issue(msisdn, imsi, "EAP-AKA'")
  │  └─ returns Token {token (opaque signed), expiresInSeconds}
  ├─ zeroize MSK
  ├─ zeroize CK', IK', K_encr, K_aut
  ▼
TOKEN_ISSUED
  ├─ EAP-Success response
  ├─ entitlement token in body
  ├─ TLS connection close or POST /entitlement/exchange to verify token
  ▼
ENDED (activity destroyed, timer cleared)
```

**Timeout Budget:**

| Event | Budget | Expiry Action | Notes |
|---|---|---|---|
| MAR / SAI (vector fetch) | 2 s | Abort Diameter dialog, abort TC dialog; FALLBACK | Tx timer; covers retransmit |
| UE sends challenge response | 30 s | Destroy activity (stale session); EAP-Failure | Long to account for UE radio latency |
| Resync MAR / SAI | 2 s | Same as vector; abort resync (max 1 anyway) | No nested timeout  |
| Binding lookup (SAR/UDR/SendIMSI) | 2 s | Abort; FALLBACK | Same RA context as AuthVector |
| Total EntitlementSbb activity | 45 s | Activity end (GC), FAILED | Hard limit; covers all stages |
| Token TTL | configurable, default 300 s | Token marked expired; `/entitlement/exchange` returns `valid=false` | Not tied to session |

**Secret Handling:**

- RAND, AUTN, XRES, CK, IK, CK', IK', K_encr, K_aut, MSK, EMSK: **never logged**.
- Hold in `byte[]` or `SecretKey` (prefer latter if possible in Java 25).
- Zeroize via `Arrays.fill(byteArray, (byte)0)` or `SecretKey.destroy()` at **every exit path** (success, FALLBACK, error, timeout).
- Memory dump tests: confirm fields are zero after activity ends.
- No persistence, no CDR field retention (CDR records only hash(IMSI) or correlator).

---

### 3.5 Entitlement Token Binding to CAMARA

**Token format:** `base64url(payload-json) "." base64url(HMAC-SHA256(payload, secret))` — an
opaque signed blob, **not** a JWT (no JWS header, no `alg`, no base64 header segment). Payload
fields: `msisdn`, `imsi`, `eapMethod`, `iat`, `exp`, `jti`; TTL clamped to 300 s; `jti`
single-use ledger. Already implemented in `EntitlementTokenService.issueToken(...)` — Phase 1d
adds the caller, not a new format.

**Issuance:** `POST /entitlement/issue`
- Input: `{"msisdn": "+...", "imsi": "2519...", "eapMethod": "EAP-AKA'"}`
- `X-Api-Key` required when `sas.security.enforce-api-keys=true`; plus the AAA attestation
  HMAC (`X-Sas-Attestation-Ts` / `X-Sas-Attestation-Mac`) when
  `sas.entitlement.issue-attestation-required=true` (prod default, gate H19 / PRO-23).
- Output: `{"token": "<opaque>", "expiresInSeconds": 300}`.
- Caller in this design = `EntitlementSbb` after a **verified** EAP exchange, so the
  attestation MAC must be minted with the operator-shared secret (§D5) *or* the
  attestation requirement must be relaxed for the internal SBB caller — decide in Phase 1d,
  otherwise the SBB cannot call its own `/issue`.

**Redemption at `/token`:** already wired. `TokenResource.JWT_BEARER_GRANT_TYPE` accepts a
client-signed assertion whose `sub` is `operatortoken:<tk>`; `resolveSubject(...)` resolves it
through `IdentityAnchor` → `OperatorTokenSupport.resolve(...)` → `EntitlementTokenService.exchange(...)`
and issues the access token.

```
POST /token
  grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer
  assertion=<client-signed JWT, sub=operatortoken:<tk>>
  client auth: private_key_jwt (OAuthClientAuthenticator) when
               sas.oauth.require-client-auth=true (prod default: must be true)

SAS /token already does:
  ├─ verify client assertion signature + audience + replay guard (OAuthAssertionReplayGuard)
  ├─ resolve operatortoken → {msisdn, imsi, eapMethod} and CONSUME it (single-use)
  │   └─ replay ⇒ OAuthException.invalidGrant → **HTTP 400 invalid_grant**
  │      (NOT 401 — the draft's "401 UNAUTHENTICATED" is wrong for this endpoint)
  ├─ eapMethod outside {EAP-AKA, EAP-AKA'} ⇒ reject
  └─ AccessTokenService.issueUserToken(msisdn, scopes, clientId) → access_token
```

Phase 2 adds `amr`, pseudonymous `sub`, ID token and refresh — the sub claim is currently
the E.164 number in the token payload (HMAC-signed, never logged), so **pseudonymising `sub`
is a Phase 2 change**, not a Phase 1d one.

---

## 4. Resource Adaptors (I/O Seams in `/ras/`)

### 4.1 AuthVectorResourceAdaptor

**Responsibility:** fetch authentication vectors (MAR/SAI) from HSS or HLR, handle resync.

There is **no `ResourceAdaptor` base class** in micro-jainslee. The pattern that ships in
this tree (`ras/swxverifier/`) is: a backend holding the transport, a plain `final class`
adaptor holding the `RaBootstrapPort`, and a 3-port `*RaEndpoint`. Sketch, corrected:

```java
// (a) the transport — the ONLY place a jSS7/Diameter client may be touched (gate H24)
public final class CorsacSwxAuthVectorBackend implements AuthVectorBackend { /* ... */ }

// (b) the adaptor: plain class, no superclass; owns the bootstrap port
public final class AuthVectorResourceAdaptor {
    private static final Logger LOG = LogManager.getLogger(AuthVectorResourceAdaptor.class);
    private RaBootstrapPort bootstrapPort;
    private AuthVectorBackend backend;   // injected/selected by config

    // SBB submits FetchVectorCommand → pick backend → await the answer
    void onFetchVector(FetchVectorCommand cmd) {
        // backend selection comes from the EXISTING SasTransportConfig switch
        // (sas.transport.swx=memory|corsac, sas.transport.map=memory|jss7) — do not
        // add a parallel sas.authvector.backend-order property.
        // on answer: correlate per Diameter Session-Id, then fire an event up to the SBB
    }
    
    // Diameter event from CorsacSwxAuthBackend
    void onSwxMaaReceived(MultimediaAuthAnswer maa) {
        // extract XRES, CK', IK', etc.
        // fire event upward
    }
    
    // TC event from Jss7MapAuthVectorBackend
    void onMapSaiResponseReceived(SendAuthInfoResponse sai) {
        // extract RAND, SRES, Kc, RES, CK, IK (quintuple; triplet → reject)
        // fire event
    }
}

// (c) the 3-port endpoint that micro-jainslee wires (RaEndpointPort + RaCommandPort),
//     mirroring SwxVerifierRaEndpoint — it delegates, it does not do I/O itself.
public final class AuthVectorRaEndpoint implements RaEndpointPort, RaCommandPort { /* ... */ }
```

### 4.2 SubscriberBindingResourceAdaptor

**Responsibility:** lookup IMSI → MSISDN via SWx SAR, Sh UDR, MAP SendIMSI, or DB.

```java
// plain final class + RaBootstrapPort, exactly as in §4.1 — no superclass
public final class SubscriberBindingResourceAdaptor {
    // SBB submits LookupBindingCommand
    void onLookupBinding(LookupBindingCommand cmd) {
        // cmd.imsi, cmd.claimedMsisdn (opt)
        // route to available backend(s) per config priority
        // sas.binding.source-order: ["swx-sar", "sh-udr", "map-smi", "db"]  (typo fixed: swx, not swa)
    }
    
    void onSwxSarResponse(...) {
        // extract MSISDN from Non-3GPP-User-Data
        // fire event
    }
    
    void onShUdrResponse(...) {
        // parse User-Data (Sh)
        // fire event
    }
    
    void onMapSmiResponse(...) {
        // compare returned IMSI == lookup_imsi
        // fire result (MSISDN from original claimed, or FALLBACK)
    }
}
```

---

## 5. Test Strategy and Artifacts

### 5.1 Unit Tests (committed)

| Category | File | Coverage |
|---|---|---|
| EAP-AKA codec | `sas-entitlement/src/test/java/.../eap/EapPacketTest.java` | frame format, attribute encode/decode, fuzz (truncate, duplicate, unknown attr) |
| Crypto | `.../eap/EapAkaKeysTest.java` | RFC 4187 test vector, RFC 9048 (AKA' + network name), Milenage TS 35.208 |
| Server FSM | `.../eap/EapAkaServerTest.java` | accept identity, challenge, response, MAC verify, AUTS resync (1×), failure cases |
| TS.43 parsing | `.../ts43/Ts43ParserTest.java` | param binding, multi-valued `app`, version gate |
| Binding logic | `sas-host/src/test/java/.../ras/binding/SubscriberBindingTest.java` | IMSI→MSISDN happy path, missing source, all sources attempted, priority order |
| Token issue | `sas-entitlement/src/test/java/.../EntitlementTokenServiceTest.java` (exists) | token creation, expiry, single-use, eapMethod whitelist |

### 5.2 Integration Tests (simulator-backed)

| Scenario | Setup | Check |
|---|---|---|
| End-to-end TS.43 via Diameter | `sas-diameter-testapp` running; `UE sim` (device SDK or `ue-sdk-web` mock) | EAP-AKA challenge→response→Success, token issued, token redeemable at `/token` |
| End-to-end TS.43 via MAP | `sas-jss7-testapp` running; UE sim | SAI quintet, EAP-AKA challenge→response→Success, binding via SendIMSI check |
| Resync scenario (1×) | SQN on HLR/HSS desync | AUTS in first response, MAR/SAI resync, new challenge, Success |
| Resync rejected (2×) | force 2nd resync | first resync accepted, 2nd rejected → FAILED |
| MAC mismatch | UE computes wrong MAC | EAP-Failure |
| Token replay | same token twice at `/token` | 2nd call: `400 invalid_grant` (the operator token is consumed on first resolve; `OAuthException.invalidGrant` → 400) |
| Timeout vector fetch | kill HLR/HSS | activity timeout after 2s, FALLBACK |
| Timeout UE response | UE silent for 30s | activity timeout, FAILED |
| Key zeroization | confirm via memory inspector or custom JVM agent | all CK/IK/XRES/MSK zero after activity end |
| Bearer mismatch | device claims Wi-Fi but data shows cellular (fake it in test) | `/ts43` accepts entitlement but `/verify` checks bearer; inconsistency detected |

### 5.3 Simulator Updates

**`sas-diameter-testapp/SwxHandler.java`:**

- Generate vectors using **Milenage (TS 35.208 test set)**, not random.
- MAR with `SIP-Authentication-Scheme: EAP-AKA'` → MAA with CK' + IK' (already derived by simulator).
- SAR `AAA_USER_DATA_REQUEST` → SAA with MSISDN in Non-3GPP-User-Data (new).
- Support AUTS resync: MAR with `SIP-Authorization: RAND||AUTS` → recalculate SQN, new vector.
- Increment SQN per subscriber session, reject if SQN too close (simulate HlrAuc).

**`sas-jss7-testapp` (HLR simulator):**

- SAI with `numberOfRequestedVectors=1` → SAI-ack quintet (Milenage test vectors).
- AUTS resync: accept, update SQN state, new quintet.
- MAP SendIMSI(MSISDN) → return IMSI (hardcoded test data; fail if MSISDN not in test set).
- New endpoint: read IMSI state, to validate SQN transitions during test.

**`ue-sdk` (JVM/Android — the only SDK that may do EAP-AKA):**

- `Ts43Client(networkUrl, appId, terminalId)`.
- `doEapAka(imsi, usim)` loop: GET challenge → parse `AT_RAND`/`AT_AUTN`/`AT_MAC` →
  compute `AT_RES`/`AT_MAC` → POST response → loop until Success/Failure.
- Milenage (fipsy/libmilenage) or JCE `Mac` + the SIM's session context; K never
  leaves the SIM (no `at.exchange` raw K), keys zeroized on exit.
- Returns the entitlement token.

**`ue-sdk-web` (browser) — MUST NOT do EAP-AKA.** The draft's "Milenage library (or
mock for web SDK) to compute RES/IK'/MAC client-side" is rejected:

- A browser has no SIM access; a "mock" Milenage means the **K** would have to be
  shipped to JS — that turns any web page into an online SIM-cloning oracle. Never.
- So the web SDK gets **token handoff only**: it receives/holds the already-issued
  entitlement token and hands it to the bank backend, exactly like
  `src/session-tuple.js` does for the IP tuple today.
- Corollary (already in AGENTS.md): an app that cannot reach the SIM falls back to
  OTP / Passkey. This is the documented iOS + web trade-off, not a defect to engineer
  around.

### 5.4 Gate H25 (New, Entitlement Core)

**Gatekeeper:** `harness/run_hardness.py`

**H25: Entitlement service uses core network without ATI, AIR, SRI-SM; fail-closed on missing binding; no secrets logged**

(Gate ids in `harness/gates.yaml` currently stop at H24 — 24 gate blocks, 34/34 runner
assertions. H25 is the next free id. The `slee_boundary` checker in
`harness/run_hardness.py` is the model to copy for a source-scanning gate.)

Checks:

1. ✓ No `AnyTimeInterrogation*` operation in code (search jSS7 API imports).
2. ✓ No AIR (S6a) on the entitlement path; MAR is allowed (SWx only). NOTE: AIR is already
   banned globally by `AGENTS.md` §5 — H25 must not weaken or contradict that rule.
3. ✓ No SRI-SM in binding path (only SAR, UDR, SendIMSI).
4. ✓ Each signal stage (vector, binding) has **one and only one** Diameter dialog or TC dialog at a time (no concurrent).
5. ✓ Timeout on missing binding: if LookupBindingCommand times out or fails, → FALLBACK (never approves).
6. ✓ No plaintext IMSI/MSISDN **in logs** (`log.info.*[im]sdn` beyond the existing
   `maskMsisdn(...)` helper), and no AKA key material in logs/CDR. ⚠ NOT "no MSISDN in
   the CDR": the existing audit schema deliberately has a `msisdn` column
   (`cdr/SasCdrService.java:40`) and AGENTS.md's privacy rule is "never returned to the
   **mobile app** (bank backend only)" — server-side audit keeps the number, and flow/evidence
   rows must stay masked (`recordFlow` → `maskMsisdn`). A gate that bans MSISDN in the CDR
   would fail the current, intended design.
7. ✓ Secrets zeroized: CK, IK, CK', IK', XRES, MSK (`byte[]` + `Arrays.fill` or `SecretKey.destroy()`).
8. ✓ No `InMemoryAuthVectorBackend` or `InMemoryBindingBackend` in production mode (config enforced).
9. ✓ Prod requires HMAC attestation on `/entitlement/issue` if enabled.
10. **Mutation check** (H25M): remove one zeroize call; the test must detect the escape.
    Model it on `harness/mut_slee_boundary.py` (H24's mutation self-test, run via
    `python3 harness/run_hardness.py --mutations`). Static source scanning cannot prove
    zeroization happened at runtime — the mutation self-test is what makes this check real.

**Spec anchor:** TS 29.002 (MAP), TS 29.272 (S6a), TS 29.273 (SWx/SAR), TS 33.402
("Security aspects of non-3GPP accesses"), TS 29.230 (Diameter app registry), RFC 3748,
RFC 4187, RFC 9048, GSMA TS.43 v13.0 (published).

### 5.5 Prod Hardening (PRO-30 … PRO-33, new)

**`harness/preflight_prod.py`:**

```
PRO-30: Entitlement backend is NOT "in-memory"
  Check: sas.authvector.backend != "in-memory"
         sas.binding.source != ["in-memory"]

PRO-31: Attestation or mTLS required on /entitlement/issue
  Check: sas.entitlement.issue-attestation-required=true
      OR sas.security.mtls-required=true

PRO-32: Token issuer and MSISDN obfuscation configured
  Check: sas.oauth.issuer set
         sas.entitlement.token-issuer != null
         sas.oauth.sub-obfuscation-secret != null (env-sourced)

PRO-33: AuC/HSS credentials not in config, must be env/vault
  Check: no K, OPc, Ki in application-prod.properties
         (key material sourced at boot from external vault, not readable in config)
```

### 5.6 Prove Artifact

**Local lab verification (before any deployment):**

```bash
# Build: root reactor covers sas-api + sas-entitlement + sas-host (test apps are
# standalone builds, so they must be built separately and sequentially).
mvn -o test                                   # from the worktree root
(cd sas-diameter-testapp && mvn -o package -DskipTests)
(cd sas-jss7-testapp     && mvn -o package -DskipTests)
./scripts/package-dist.sh                    # Quarkus FAST-JAR dist (never a fat jar)

# Start simulators (testapp ports: 3868 S6a / 3869 SWx; 8086+18086 control UI)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 13868 --web-port 18086 &
java -jar sas-jss7-testapp/target/sas-jss7-testapp.jar &

# Start SAS (lab profile, plain HTTP :8085)
dist/run.sh &

# Run test
curl -v http://localhost:8085/ts43?vers=1&app=ei.et&EAP_ID=00$(printf '%X' 251910000001 | cut -c1-20)
# → GET 200 with EAP-Request/AKA-Challenge
#   extract AT_RAND, AT_AUTN

# UE sim (mock or `ue-sdk` client) computes RES||MAC
curl -X POST -H 'Content-Type: application/json' \
  -d '{"eap-relay-packet":"<b64 of EAP-Response>"}' \
  http://localhost:8085/ts43

# → 200 with entitlement token + EAP-Success

# Verify token at /token
curl -X POST http://localhost:8085/token \
  -d 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  -d "assertion=<jwt with sub=operatortoken:<token>>"

# → 200 with access_token, amr=[eap-aka], msisdn redacted

# Verify /verify works with the token
curl -H "Authorization: Bearer <access_token>" \
  -X POST http://localhost:8085/camara/number-verification/v2/verify \
  -d '{"phoneNumber":"+251911234567",...}'

# → 200 {verificationResult:true,...}

# Check process state
ps -eo pid,rss,args | grep java
docker ps  # ensure no leftover containers

# Verify secrets not in logs/dumps
grep -i "ck\|ik\|xres" sas-host/data/logs/*.log
# → no match (or only algorithm names, not values)

# Check SCTP endpoints active during run (SCTP sockets are NOT TCP sockets:
# `ss -tlnp` will not show them)
cat /proc/net/sctp/eps | head
# should list the local/remote SCTP associations

# Prod artifact: fast-jar layout, and in-memory backends must be absent from
# a prod run (config gate PRO-30, not a jar-content check)
ls sas-host/target/quarkus-app/quarkus-run.jar   # fast-jar entry point
# → there is NO sas-host-runner.jar: this tree ships a Quarkus fast-jar, never
#   an uber/fat jar (scripts/package-dist.sh passes -Dquarkus.package.jar.type=fast-jar)
```

---

## 6. Implementation Phases and Milestones

### Phase 1a: EAP-AKA Library + Test Vectors — **DONE** (library + crypto; `Ts43Parser` pending)

Landed in `sas-entitlement/src/main/java/.../entitlement/eap/`, pure and I/O-free
(no signalling, no config keys, no new gate), 38 tests green:

| Class | What it is | Verification |
|---|---|---|
| `EapAkaPrimeKeys` | EAP-AKA' key derivation: TS 33.402 Annex A.2 (CK'/IK') → RFC 9048 §3.3 (K_encr/K_aut/K_re/MSK/EMSK) + the IKEv2 PRF' | **KAT: RFC 9048 Appendix D Cases 3 and 4**, vectors transcribed from the RFC by script, not typed |
| `EapAkaKeys` | Plain EAP-AKA: RFC 4187 §7 (`MK = SHA1(Identity‖IK‖CK)` → FIPS 186-2 PRF, Annex A) + `AT_MAC` (HMAC-SHA-1-128) | no KAT exists in RFC 4187 — structural tests only, **UAT-gated** |
| `EapPacket` | RFC 3748 codec, fail-closed on every malformed input | RFC 3748 legacy Nak (Type 3) byte-exact; 20k-case fuzz |
| `EapAkaAttributes` | RFC 4187 §11 / 5216 / 9048 §3 TLV codec + `AT_MAC` coverage | round-trip, 20k-case fuzz, `AT_MAC` coverage layout |
| `EapAkaServer` | the D6/Shape S server FSM: Challenge → Response → Success, `AT_AUTS` resync capped at 1, fail-closed everywhere | synthetic peer that computes the real `AT_MAC` from the real derived `K_aut` |

Three corrections to this plan that Phase 1a proved against the specs:

1. **There is no `AT_ENCR_DATA(130)`** — 130 is `NAS-Identifier` in the IANA EAP registry.
   The full verified `AT_*` list is now in `EapAkaAttributes`.
2. **TS 33.402 Annex A.2 `P1 = AK` (6 octets), not `SQN‖AK`.** Annex A.2 prints
   `L1 = 0x0006`, which fixes P1 at 6 octets. Proved by experiment: with AK-only the
   derivation reproduces RFC 9048 Case 3/4 exactly; with `SQN‖AK` (11 octets) it does not.
3. **`K_aut` is 128 bits in EAP-AKA and 256 bits in EAP-AKA'** — the two methods are not
   interchangeable, so `EapAkaServer` keeps them on separate code paths.

Two defects the new tests caught during this phase, both now fixed:

- `EapPacket.length()` added `MIN_PACKET_LEN` (the smallest *legal* packet) instead of
  the per-packet base, so every encoded Length was one octet too long.
- `EapAkaServer.deriveKeys` handed the derived arrays to its result record and wiped the
  source in a `finally` — the records expose their internal arrays, so the server MACed
  with zeroed keys. Ownership is now explicit (copy, then wipe the source).

**Known gap (not a defect):** RFC 9048 Appendix D Cases 1 and 2 use real Milenage
test-set-19 material but the RFC never prints the 6-byte `AK` that TS 33.402 Annex A.2
feeds into the KDF, so those two cases cannot be reproduced from the published text.
Cases 3 and 4 cover the same construction end to end, so the KAT coverage is complete
for the algorithm; the gap is recorded in `EapAkaPrimeKeysTest` so nobody later mistakes
it for a code defect. Worth an errata note to the RFC authors.

**Still open in 1a:** `Ts43Request`/`Ts43Response`/`Ts43Parser` (the `/ts43` parameter
surface) — deliberately left for 1d so the FSM shape drives the parser, not the reverse.

- `EapPacket` (encode/decode RFC 3748).
- `EapAkaAttributes` (all AT_* attributes).
- `EapAkaKeys` (SHA1-PRF, constant-time MAC).
- `EapAkaPrimeKeys` (HMAC-SHA256, TS 33.402).
- `EapAkaServer` (FSM, no I/O).
- Test vectors (RFC 4187 Appendix, RFC 5448, RFC 9048, Milenage TS 35.208, AUTS).

**Gate:** `mvn -o test` + no external network calls in tests.

### Phase 1b: AuthVector RA + Diameter (SWx) Backend — **DONE** (RA + SWx vector; E2E vs simulator pending 1e)

Landed in `sas-host/src/main/java/.../ras/authvector/`, 12 tests green:

| Piece | Note |
|---|---|
| `AuthVector` | RAND/AUTN/XRES/CK/IK + scheme; wipes itself, `toString` never prints keys, and reports `hasSessionKeys()` so a vector without CK/IK cannot be mistaken for a usable one |
| `AuthVectorBackend` | the seam: `fetch` / `resync` / `stop` / `name` |
| **`CorsacSwxVerifierBackend` now also implements `AuthVectorBackend`** | ⚠ **not a second client** — it reuses the *same* stack, link and correlator, so there is still exactly one Diameter association to the operator HSS. A second link would be a second chance to leak a dialog and would need its own port |
| `InMemoryAuthVectorBackend` | lab only, `PRO-30` refuses it in prod |
| `AuthVectorResourceAdaptor` + `AuthVectorRaEndpoint` | the only route to a vector (gate H24); owns the 2 s budget, bounds the in-flight table at 256, aborts everything on `raInactive` |
| `FetchVectorCommand` / `ResyncVectorCommand` / `AbortAuthVectorCommand` | outbound commands |
| `sas.transport.authvector=memory\|corsac` | in `SasTransportConfig`, next to every other transport switch |

**Resync cap is per session, not per exchange** — the tests caught that a counter living
on the in-flight exchange resets as soon as the exchange completes, which would have let a
client resync forever and walk the AuC sequence number down. The budget now lives on the
session (`reqId`) and is released only by an abort or `raInactive`.

**PRO-30 (new preflight check, selftest 24/24):** the auth-vector source must not be the
lab. `corsac` additionally requires `sas.transport.swx=corsac`, because it reuses that
client. While `sas.entitlement.enabled=true` and the source is `memory`, the check fails and
names the operator vector-grade AuC access agreement (§2.1.2 R1) — which is exactly the
"don't deploy and get burned" gate Phase S needs.

**Still open in 1b:** the SWx vector path is unit-tested and compiles against the real
corsac API, but has not been driven against `sas-diameter-testapp`; the lab HSS still
fabricates a 32-octet opaque `SIP-Authenticate` that the new parser correctly refuses. That
is Phase 1e work (the testapp must emit `RAND‖AUTN` + a real XRES + CK/IK, and support the
`AT_AUTS` resync).

- `AuthVectorResourceAdaptor`, `AuthVectorBackend` interface.
- `CorsacSwxAuthBackend` (via corsac-diameter fork).
- `S6aDialog`, `S6aExchangeCorrelator` adapted to SWx MAR.
- Integration test: `sas-diameter-testapp` returns MAA with XRES, CK', IK'.
- Resync support (AUTS → new MAR).

**Gate:** MAR dialog completes in < 2s, resync works, timeout aborts cleanly.

### Phase 1c: MAP Backend + Binding RA (5–6 days) — **unblocked (D6 = Shape S)**

**Deliverable:** `Jss7MapAuthAuthVectorBackend`, `SubscriberBindingRA`, binding source priority.

- `Jss7MapAuthVectorBackend` (MAP SAI, quintet only; triplet → reject). Note: under Shape R
  the SAS does not consume vectors at all, so the whole vector-fetch half of 1c collapses to
  the binding half — re-scope after D6.
- `SubscriberBindingResourceAdaptor`, backends: `SwxSarBinding`, `ShUdrBinding`, `MapSendImsiBinding`, `SubscriberDbBinding`.
- LookupBindingCommand, command executor.
- Config: `sas.authvector.backend-order`, `sas.binding.source-order`.

**Gate:** SAI dialog works, SQN increments; SAR / UDR / SendIMSI each complete in < 2s; binding priority order enforced.

### Phase 1d: EntitlementSbb + `/ts43` Endpoint + FSM (6–7 days) — **unblocked (D6 = Shape S)**

**Deliverable:** `EntitlementSbb`, `Ts43Resource`, FSM, timeouts, key zeroization.
**Shape-S/R portability rule (§2.1.2 item 4):** the SBB's public surface — the `/ts43`
contract, the FSM state names, the token format — must not expose "the SAS holds CK/IK",
or the R-flip becomes a rewrite and the fallback is worthless.

- State machine: IDLE → IDENTITY → VECTOR → CHALLENGE → VERIFIED → TOKEN → ENDED.
- 45s total timeout, 2s per signalling stage.
- Secret zeroization on all paths (success, FALLBACK, error).
- `POST /ts43` (EAP relay), `GET /ts43` (identity).
- Unit tests for FSM transitions, timeout behavior, MAC mismatch.
- `SasBootstrap` registers AuthVectorRA, BindingRA, EntitlementSbb.

**Gate:** `mvn -o test`; entitlement activity ends cleanly; memory audit (no secrets escaping).

### Phase 1e: E2E Lab + Prove Artifact (3 days)

**Deliverable:** lab working end-to-end; prove script; docs.

- `sas-diameter-testapp`: MAR/SAA, SAR/SAA, AUTS resync, Milenage vectors.
- `sas-jss7-testapp`: SAI quintet, AUTS resync, SendIMSI, state validation.
- `ue-sdk` `Ts43Client`: GET challenge, POST response, loop until SUCCESS or FAILURE.
- Prove script: start simulators, run SAS, E2E test, stop, verify secrets zeroized, no logs.
- ADR closure verification: confirm D1–D5 answers documented.

**Gate:** Prove script runs clean, token issued and redeemable, all processes stopped after.

### Phase 2: Authorization Code Flow (6–8 days, can parallelize with 1a–1d)

**Deliverable:** `GET/POST /authorize`, refresh token, ID token, pseudonymous sub.

- `/authorize`: response_type=code, PKCE S256, state, nonce → network-based auth (Resolver RA) or TS.43 token.
- Code generation (1-use, 60s TTL, per client_id + redirect_uri + PKCE).
- `/token` grant_type=authorization_code → access_token + refresh_token + ID token (RS256).
- ID token: `iss`, `sub` (pseudonymous HMAC), `aud`, `exp`, `auth_time`.
- Refresh token: rotation on re-issue.
- Metadata: remove `id_token_signing_alg_values_supported: HS256`, add RS256/ES256.
- Conformance tests: PKCE mismatch, redirect URI mismatch, authorization code replay, mix-up, GSMA OGW smoke suite.

**Gate:** Full flow works; ID token verified via JWKS; refresh rotates; code one-use enforced.

### Phase 3: OTP SMS Prod (4–5 days)

**Deliverable:** SMPP adapter, persistent attempt store, preflight gating.

- `ras/smsdelivery/SmppBackend` (SMPP 3.4 to SMSC).
- `OtpAttemptStore` → PostgreSQL (from H2).
- Code stored as HMAC, not plaintext; attempt count, validity window.
- Preflight: **PRO-29 already exists** (`harness/preflight_prod.py` — refuses
  `sas.otp.enabled=true` on a lab sender / in-memory attempt store). Phase 3 *extends* it
  with the SMPP-adapter and PostgreSQL conditions; it does not introduce PRO-29.
- Fallback: when OTP disabled, SAS returns `403 SERVICE_UNAVAILABLE` for `/send-code`.

**Gate:** `sas.otp.enabled=false` by default in lab; the existing PRO-29 already rejects the
lab sender, and its mutation scenarios are covered by `preflight_prod.py --selftest`.

---

## 7. Risk and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| D1 fails: no carrier privilege on USIM | **CRITICAL** | Ask Ethio Telecom ARA-M status **now** (week 0). If no: switch to helper app (small change) or OTP-only (scope cut). |
| D2 unclear: no HSS or SQN management different | High | Network diagram + HSS vendor manual (Ericsson, ALU, …) + test SQN re-sync before prod. |
| Key material leaks via exception stack trace | High | Test framework: throw exception inside EAP-AKA, catch, verify stack trace doesn't contain key. Use `-XX:-OmitStackTraceInFastThrow`. |
| Timeout too short: real HLR/HSS slower than 2s | High | 2s budget is industry std (Diameter Tx); if exceeds, contact Ethio Telecom network ops. Don't increase locally; raises false-failure masking. |
| Prod uses InMemoryBackend by mistake | High | Preflight PRO-30 hard-fail on non-prod backend in prod env (enforce `application-prod.properties` diff check). |
| SQN exhaustion DoS on AuC | Medium | Rate-limit per IMSI (token bucket, e.g. 1 EAP session per IMSI per 10s). Monitored in metrics. |
| Device sends oversized EAP packet (fuzz) | Medium | Unit test: parse and reject oversized attributes; no buffer overflow. Java is safe but test anyway. |
| iOS app cannot use TS.43 (no API) | Medium | **Documented trade-off:** iOS falls back to OTP or Passkey. Not a blocker if OTP is enabled. |
| **Operator refuses vector-grade AuC access → Shape S cannot ship (R1)** | **CRITICAL** | D6 is Shape S, so this is the top production risk. Mitigated by keeping Shape R as a designed fallback (§2.1.2) with R1–R3 tracked as a pending issue, plus the Phase 1d portability rule that keeps the flip a config change. Close R1–R3 in writing before any prod boot. |

---

## 8. Open Items and Tracking

- [ ] **ADR approval** (D1–D5): Ethio Telecom signoff on carrier privilege, core topology, GT assignment, token format. (Owner: Restlink)
- [ ] **PENDING ISSUE — Shape R production fallback (§2.1.2):** close R1 (operator refuses
      vector-grade AuC access), R2 (operator exposes DER/EAP relay only), R3 (operator runs
      its own ECS) in writing. Until then a prod boot is a D2/D5-unresolved deploy and must be
      refused by the prod gate. Owner: Restlink. Blocks: production only — not the lab.
- [ ] **Phase 1a–1e implementation**: EAP-AKA library, RAs, FSM, lab, prove artifact.
- [ ] **Phase 2 implementation**: Auth Code Flow, ID token, refresh.
- [ ] **Phase 3 implementation**: SMPP, persistent store.
- [ ] **UAT on real core network**: Ethio Telecom + Restlink joint test (SQN, resync, binding, real HLR/HSS).
- [ ] **Metrics dashboards** (P-H7): EAP success rate, vector fetch latency, token issue latency, per-backend breakdown.
- [ ] **Key lifecycle** (P-H3): K/OPc rotation; token issuer secret rotation; audit trail.
- [ ] **SIM-swap freshness source** (open in AGENTS.md): timestamp of last IMSI change for Wi-Fi binding.
- [ ] **Post-CGNAT port discovery** (P-H8): echo endpoint, test against real CGNAT.

---

## 9. References

- **3GPP TS 29.002** — MAP operations (PSI, SAI, SendIMSI)
- **3GPP TS 29.272** — S6a Diameter (AIR, IDR, PUR) — NOT used for EAP
- **3GPP TS 29.273** — SWx Diameter (MAR, MAA, SAR, SAA, PPR)
- **3GPP TS 33.402** — "3GPP System Architecture Evolution (SAE); Security aspects of
  non-3GPP accesses" (verified title, 3GPP 33-series index); Annex A covers the
  access-network binding used for the CK'/IK' derivation
- **3GPP TS 33.501** — 5G security, N32 SEPP (edge case if prod adds 5G)
- **RFC 3748** — EAP framework (packet/attribute codec, Code 1–4, Type 23/50)
- **RFC 4187** — EAP-AKA
- **RFC 5448** — EAP-AKA' (original; method type 50)
- **RFC 9048** — EAP-AKA' with KDF (current reference for the KDF-based derivation)
- **RFC 4072** — Diameter EAP application (transport for the EAP relay, Shape R)
- **GSMA TS.43 v13.0** — Service Entitlement Configuration (**published**, 2026-01-29):
  §2.3 GET params, §2.4 POST, §2.5 version control, §2.8.1 embedded EAP-AKA relay +
  DER Result-Code→HTTP mapping, §2.8.3 S2S OAuth + `private_key_jwt`, §2.9 document,
  §2.10 response codes
- **3GPP TS 29.230** — Diameter application/command code registry (SWx=16777265 etc.)
- **IANA EAP registry** — Method Type 23 = EAP-AKA [RFC 4187], 50 = EAP-AKA' [RFC 9048];
  note 130 is `NAS-Identifier`, **not** an EAP-AKA attribute
- **CAMARA Number Verification v2.1.0** — `/verify`, `/device-phone-number`
- **TS 35.208** — Milenage test vectors
- **`docs/design/ts43-eapaka-wire-protocol.md`** — existing wire protocol doc
- **`docs/design/ts43-entitlement-integration-contract.md`** — operator contract (interfaces A–C)
- **`AGENTS.md` §7** — the installed hardness gate (H1–H24 today; H25 is the next free id;
  §8 is the agent-rules section, not the gate section)

---

## 10. Success Criteria

1. ✓ 35/35 hardness gates pass (H1–H24 today + new H25; the runner prints `== 34/34 ==`
   today, so H25 makes it 35/35).
2. ✓ All tests (`mvn -o test`) pass; coverage ≥ 85% for new code.
3. ✓ Prove artifact: lab E2E working, token issued, `/verify` accepts token, no secrets in logs, all processes stopped.
4. ✓ ADR D1–D5 closed with Ethio Telecom sign-off.
5. ✓ Authorization Code Flow conformance: `/authorize` + `/token` + refresh + ID token, GSMA OGW smoke suite passes.
6. ✓ Phase 2 and 3 implemented per plan; prod gates (PRO-30..35) enforced.
7. ✓ UAT scheduled with Ethio Telecom on real HLR/HSS; SQN, resync, binding verified live.

---

**Next Step:**
1. **D6 decided: Shape S** (§2.1.1). Phases 1a–1d unblocked.
2. Present D1–D5 to Ethio Telecom, **including the vector-grade AuC access request** that
   Shape S depends on — and record the answer, because R1–R3 (§2.1.2) gate production.
3. Start **Phase 1a** — the pure EAP-AKA library + RFC 4187/9048 + Milenage vectors.
   Device-side under R, server-side under S: never wasted work.
