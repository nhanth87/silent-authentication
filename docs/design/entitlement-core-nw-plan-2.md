# TS.43 Entitlement Service ↔ Core Network Plan

**Scope:** Full TS.43 EAP-AKA/-' entitlement server integrated with MAP (SS7/SCTP) and Diameter (SWx/S6a) signalling. Replaces lab-only `EapAkaDemoPeer` with production-ready FSM, secure key handling, and network-based subscriber binding. Prerequisite for Phase 2 (Auth Code Flow) and Phase 3 (OTP SMS).

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
│  │ ├─ ARA-M (AppData Rule for Apps) on USIM:            │          │
│  │ │    app cert for bank or helper app (FS.11)           │          │
│  │ └─ SIM provisioning + AuC/HlrAuc parameter (K, OPc)   │          │
│  └────────────────────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Critical Decisions (Gate Pre-Build)

These decisions **block the implementation** until resolved with Ethio Telecom. ADR reference: `docs/design/adr-ts43-entitlement-core.md`.

| ID | Decision | Impact | How to close |
|---|---|---|---|
| **D1** | Does bank app have **carrier privilege** on Ethio Telecom USIM for `TelephonyManager.getIccAuthentication()`? | If no: EAP-AKA is unreachable from app. iOS has zero API. Must use helper app (nhà mạng ký) or fall back to OTP. | **Ethio Telecom** confirm ARA-M (AppData Rule for Apps) cert for Restlink bank app or helper app. **iOS:** OTP/Passkey fallback only. |
| **D2** | Core network topology: which paths are live? | Determines default backend order: (a) HSS only (MAR/SWx-only), (b) HLR only (SAI-only), (c) dual (HLR+HSS). | **Ethio Telecom** network diagram + HSS vendor (ALU, Ericsson, Oracle…). |
| **D3** | How to hydrate IMSI → MSISDN when only MAP available? | MAP PSI returns location, not MSISDN. SAI returns vectors, not subscriber identity. | Choose one: (1) MAP SendIMSI on claimed MSISDN (device-declared), (2) subscriber DB read-only export, (3) ATI (nope, FS.11 ban). See §3.4. |
| **D4** | What TS.43 **app identifier** and token format? | Token expires, format, scope. Not inventing. | **Ethio Telecom** or **GSMA** spec section reference. |
| **D5** | **Global Title and Diameter realm** for SAS? | STP/DEA route + whitelist. AuC/HSS credential trust. | **Ethio Telecom** GT assignment (e.g. `+251xxx`), Diameter `Origin-Host` (e.g. `sas.ethiotel.et`), realm, mutual auth cert. |

**No Phase 1 code can land without all five closed.**

---

## 3. TS.43 Entitlement Service — Transport, FSM, I/O

### 3.1 Module Structure (H24-compliant)

All new code is either pure library (no I/O) or sits in `/ras/` seam. No naked jSS7/Diameter outside of resource adapters.

```
sas-entitlement/
├── src/main/java/et/restlink/sas/entitlement/
│   ├── eap/
│   │   ├── EapPacket.java           (codec RFC 3748: type, code, ID, attrs)
│   │   ├── EapAkaAttributes.java     (AT_* enums, encode/decode, builder)
│   │   ├── EapAkaKeys.java           (CK, IK, MK, K_encr, K_aut via RFC 4187 §7)
│   │   ├── EapAkaPrimeKeys.java      (CK', IK' per RFC 9048, TS 33.402 Annex A)
│   │   ├── EapAkaServer.java         (state machine: CHALLENGE, RESPONSE, FAILURE)
│   │   └── EapAkaTest.java           (RFC 4187 Appendix, vectors, RFC 5448, AKA')
│   │
│   ├── ts43/
│   │   ├── Ts43Request.java          (vers, app, EAP_ID, terminal_* params)
│   │   ├── Ts43Response.java         (eap-relay body, entitlement token)
│   │   ├── Ts43Event.java            (submitted from REST → SBB)
│   │   ├── Ts43Parser.java           (query params, EAP packet extraction)
│   │   └── Ts43Test.java             (frame round-trip, PKCE state nonce)
│   │
│   ├── EntitlementConfig.java        (sas.entitlement.* properties)
│   ├── EntitlementTokenService.java  (already exists; + signalTsOpaque(IMSI,eapMethod))
│   ├── EntitlementResource.java      (already exists)
│   └── AttestationVerifier.java      (already exists)

sas-host/
├── src/main/java/et/restlink/sas/
│   ├── ras/authvector/
│   │   ├── AuthVectorResourceAdaptor.java
│   │   ├── AuthVectorBackend.java     (interface: fetch(IMSI,scheme)→Future<Vec>)
│   │   ├── AuthVectorRaEndpoint.java  (TC/Diameter event→activity→SBB)
│   │   ├── Jss7MapAuthBackend.java    (MAP SAI: quintet, resync support)
│   │   ├── CorsacSwxAuthBackend.java  (SWx MAR: EAP-AKA vector + CK'/IK')
│   │   ├── InMemoryAuthVectorBackend.java (lab only)
│   │   ├── command/FetchVectorCommand.java
│   │   ├── command/ResyncCommand.java  (max 1 × per session)
│   │   └── command/AbortAuthCommand.java
│   │
│   ├── ras/binding/
│   │   ├── SubscriberBindingResourceAdaptor.java
│   │   ├── SubscriberBindingBackend.java (interface: lookup(IMSI)→Future<MSISDN>)
│   │   ├── SubscriberBindingRaEndpoint.java
│   │   ├── SwxSarBinding.java         (SWx SAR AAA_USER_DATA_REQUEST)
│   │   ├── ShUdrBinding.java          (read-only Sh UDR)
│   │   ├── MapSendImsiBinding.java    (MAP SendIMSI on claimed MSISDN)
│   │   ├── SubscriberDbBinding.java   (fallback to exported DB if configured)
│   │   ├── command/LookupBindingCommand.java
│   │   └── command/AbortBindingCommand.java
│   │
│   ├── sbbs/
│   │   ├── EntitlementSbb.java        (FSM, timer, zeroize CK/IK/XRES)
│   │   └── EntitlementSbbEvents.java
│   │
│   ├── web/
│   │   └── Ts43Resource.java          (GET/POST /ts43, thin submit-await)
│   │
│   ├── bootstrap/
│   │   └── SasBootstrap.java          (register AuthVectorRA, BindingRA, EntitlementSbb)
│   │
│   └── cdr/
│       └── Ts43Cdr.java               (audit: timestamp, IMSI hash, result, no secrets)
```

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
    // AT_RAND(1), AT_AUTN(2), AT_RES(3), AT_AUTS(14), AT_MAC(11),
    // AT_ENCR_DATA(19), AT_IDENTITY(14), AT_KDF(23), AT_KDF_INPUT(24),
    // AT_CLIENT_ERROR_CODE(192), ...
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
    • Auth-Application-Id: 16777251 (SWx)
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
      └─ SIP-Authorization-Context (SAS derives):
         • XRES (4 or 8 bytes), CK (128 bits), IK (128 bits)
         • ← HSS already computed CK' / IK' and sent them here
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
```

#### Binding Path: IMSI → MSISDN

**Preferred: Diameter SWx SAR** (`AAA_USER_DATA_REQUEST`)

```
SAS ─ SAR ─► HSS
    • Destination: HSS (non-3GPP-User-Data request)
    • User-Name: IMSI@nai.epc
    • Server-Assignment-Type: AAA_USER_DATA_REQUEST
    • Auth-Application-Id: 16777251 (SWx)
    ↓
   HSS (does NOT register SAP as serving AAA; read-only)
    ↓
    SAA
    • Result-Code: 2001
    • User-Data (XML)
      └─ Non-3GPP-User-Data (decoded)
         └─ MSISDN
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

**Token format:** signed opaque string (already implemented in `EntitlementTokenService`).

**Issuance:** `POST /entitlement/issue` (called by AAA at EAP-AKA completion)
- Input: `{"msisdn": "+...", "imsi": "2519...", "eapMethod": "EAP-AKA'"}`
- API key required (unless attestation-only mode).
- Output: `{"token": "<jwt>", "expiresInSeconds": 300}` (TTL configurable).

**Redemption at `/token`:**

```
POST /token
  grant_type=urn:ietf:params:oauth:grant-type:token-exchange
  assertion=<entitlement_token>
  assertion_type=urn:ietf:params:oauth:assertion-type:token-exchange

CAMARA SAS /token endpoint:
  ├─ verify assertion signature
  ├─ extract {sub, msisdn, imsi, eapMethod, exp}
  ├─ generate access_token
  │   ├─ iss: sas.oauth.issuer
  │   ├─ sub: <pseudonymous (HMAC(msisdn, secret))>  ← never plaintext MSISDN
  │   ├─ amr: ["eap-aka"] or ["eap-aka-prime"]
  │   ├─ scope: "number-verification:verify" + others
  │   ├─ aud: <requesting client_id>
  │   ├─ exp: 3600 (1 hour)
  │
  ├─ [if refresh_token enabled] generate refresh_token
  │   └─ signed, rotated on re-issue
  │
  └─ return {access_token, refresh_token (opt), token_type: Bearer}
```

---

## 4. Resource Adaptors (I/O Seams in `/ras/`)

### 4.1 AuthVectorResourceAdaptor

**Responsibility:** fetch authentication vectors (MAR/SAI) from HSS or HLR, handle resync.

```java
public class AuthVectorResourceAdaptor extends ResourceAdaptor {
    // Inbound: TC/Diameter events from network
    // Outbound: command to SBB via event
    
    // Configure active backend(s)
    AuthVectorBackend hssBackend = new CorsacSwxAuthBackend(...);
    AuthVectorBackend hlrBackend = new Jss7MapAuthBackend(...);
    
    // SBB submits FetchVectorCommand
    void onFetchVector(FetchVectorCommand cmd) {
        // route by backend priority: sas.authvector.backend-order
        // or backend-specific config (sas.authvector.map vs sas.authvector.swa)
        // await Diameter response or TC response
        // on success: fire AuthVectorResult event → SBB
    }
    
    // Diameter event from CorsacSwxAuthBackend
    void onSwxMaaReceived(MultimediaAuthAnswer maa) {
        // extract XRES, CK', IK', etc.
        // fire event upward
    }
    
    // TC event from Jss7MapAuthBackend
    void onMapSaiResponseReceived(SendAuthInfoResponse sai) {
        // extract RAND, SRES, Kc, RES, CK, IK
        // fire event
    }
}
```

### 4.2 SubscriberBindingResourceAdaptor

**Responsibility:** lookup IMSI → MSISDN via SWx SAR, Sh UDR, MAP SendIMSI, or DB.

```java
public class SubscriberBindingResourceAdaptor extends ResourceAdaptor {
    // SBB submits LookupBindingCommand
    void onLookupBinding(LookupBindingCommand cmd) {
        // cmd.imsi, cmd.claimedMsisdn (opt)
        // route to available backend(s) per config priority
        // sas.binding.source-order: ["swa-sar", "sh-udr", "map-smi", "db"]
    }
    
    void onSwxSarResponse(...) {
        // extract MSISDN from User-Data
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
| EAP-AKA codec | `EapPacketTest.java` | frame format, attribute encode/decode, fuzz (truncate, duplicate, unknown attr) |
| Crypto | `EapAkaKeysTest.java` | RFC 4187 test vector, RFC 5448 (AKA'), RFC 9048 (AKA' + network name), Milenage TS 35.208 |
| Server FSM | `EapAkaServerTest.java` | accept identity, challenge, response, MAC verify, AUTS resync (1×), failure cases |
| Binding logic | `SubscriberBindingTest.java` | IMSI→MSISDN happy path, missing source, all sources attempted, priority order |
| Token issue | `EntitlementTokenServiceTest.java` | JWT creation, expiry, msisdn obscured in token body |

### 5.2 Integration Tests (simulator-backed)

| Scenario | Setup | Check |
|---|---|---|
| End-to-end TS.43 via Diameter | `sas-diameter-testapp` running; `UE sim` (device SDK or `ue-sdk-web` mock) | EAP-AKA challenge→response→Success, token issued, token redeemable at `/token` |
| End-to-end TS.43 via MAP | `sas-jss7-testapp` running; UE sim | SAI quintet, EAP-AKA challenge→response→Success, binding via SendIMSI check |
| Resync scenario (1×) | SQN on HLR/HSS desync | AUTS in first response, MAR/SAI resync, new challenge, Success |
| Resync rejected (2×) | force 2nd resync | first resync accepted, 2nd rejected → FAILED |
| MAC mismatch | UE computes wrong MAC | EAP-Failure |
| Token replay | same token twice at `/token` | 2nd call: `401 UNAUTHENTICATED` |
| Timeout vector fetch | kill HLR/HSS | activity timeout after 2s, FALLBACK |
| Timeout UE response | UE silent for 30s | activity timeout, FAILED |
| Key zeroization | confirm via memory inspector or custom JVM agent | all CK/IK/XRES/MSK zero after activity end |
| Bearer mismatch | device claims Wi-Fi but data shows cellular (fake it in test) | `/ts43` accepts entitlement but `/verify` checks bearer; inconsistency detected |

### 5.3 Simulator Updates

**`sas-diameter-testapp/SwxHandler.java`:**

- Generate vectors using **Milenage (TS 35.208 test set)**, not random.
- MAR with `SIP-Authentication-Scheme: EAP-AKA'` → MAA with CK' + IK' (already derived by simulator).
- SAR `AAA_USER_DATA_REQUEST` → SAA with MSISDN in User-Data XML (new).
- Support AUTS resync: MAR with `SIP-Authorization: RAND||AUTS` → recalculate SQN, new vector.
- Increment SQN per subscriber session, reject if SQN too close (simulate HlrAuc).

**`sas-jss7-testapp` (HLR simulator):**

- SAI with `numberOfRequestedVectors=1` → SAI-ack quintet (Milenage test vectors).
- AUTS resync: accept, update SQN state, new quintet.
- MAP SendIMSI(MSISDN) → return IMSI (hardcoded test data; fail if MSISDN not in test set).
- New endpoint: read IMSI state, to validate SQN transitions during test.

**`ue-sdk` / `ue-sdk-web`:**

- `Ts43Client(networkUrl, appId, terminalId)`.
- `async doEapAka(imsi, usim)` loop: GET challenge, parse AT_RAND||AUTN||MAC, compute RES||IK'C||MAC, POST response, loop until SUCCESS or FAILURE.
- Milenage library (or mock for web SDK) to compute RES/IK'/MAC client-side.
- Return entitlement token.

### 5.4 Gate H25 (New, Entitlement Core)

**Gatekeeper:** `harness/run_hardness.py`

**H25: Entitlement service uses core network without ATI, AIR, SRI-SM; fail-closed on missing binding; no secrets logged**

Checks:

1. ✓ No `AnyTimeInterrogation*` operation in code (search jSS7 API imports).
2. ✓ No AIR (S6a); MAR is allowed (SWx only).
3. ✓ No SRI-SM in binding path (only SAR, UDR, SendIMSI).
4. ✓ Each signal stage (vector, binding) has **one and only one** Diameter dialog or TC dialog at a time (no concurrent).
5. ✓ Timeout on missing binding: if LookupBindingCommand times out or fails, → FALLBACK (never approves).
6. ✓ No plaintext IMSI/MSISDN in CDR or logs (search `log.info.*[im]sdn`, check CDR schema).
7. ✓ Secrets zeroized: CK, IK, CK', IK', XRES, MSK (`byte[]` + `Arrays.fill` or `SecretKey.destroy()`).
8. ✓ No `InMemoryAuthVectorBackend` or `InMemoryBindingBackend` in production mode (config enforced).
9. ✓ Prod requires HMAC attestation on `/entitlement/issue` if enabled.
10. **Mutation check** (H25M): remove one zeroize call; test should detect key material escape.

**Spec anchor:** TS 29.002 (MAP), TS 29.272 (S6a), TS 29.273 (SWx), TS 33.402 (UMTS security), RFC 4187, RFC 9048, CAMARA TS.43 (if published).

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
cd sas-host && mvn -o clean package

# Start simulators
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &
java -jar sas-jss7-testapp/target/sas-jss7-testapp.jar &

# Start SAS with lab config
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
  -d 'grant_type=urn:...' \
  -d "assertion=<token>"

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

# Check SCTP endpoints active during run
/proc/net/sctp/eps | wc -l
# should show active transports

# Jar contents
jar tf sas-host/target/sas-host-runner.jar | grep -i 'inmemory.*backend'
# → should NOT appear in prod jar (only in -dev or with dev profile)
```

---

## 6. Implementation Phases and Milestones

### Phase 1a: EAP-AKA Library + Test Vectors (4–5 days)

**Deliverable:** `sas-entitlement/.../eap/` module, all tests pass.

- `EapPacket` (encode/decode RFC 3748).
- `EapAkaAttributes` (all AT_* attributes).
- `EapAkaKeys` (SHA1-PRF, constant-time MAC).
- `EapAkaPrimeKeys` (HMAC-SHA256, TS 33.402).
- `EapAkaServer` (FSM, no I/O).
- Test vectors (RFC 4187 Appendix, RFC 5448, RFC 9048, Milenage TS 35.208, AUTS).

**Gate:** `mvn -o test` + no external network calls in tests.

### Phase 1b: AuthVector RA + Diameter (SWx) Backend (6–8 days)

**Deliverable:** `sas-host/.../ras/authvector/`, SWx MAR/MAA working end-to-end.

- `AuthVectorResourceAdaptor`, `AuthVectorBackend` interface.
- `CorsacSwxAuthBackend` (via corsac-diameter fork).
- `S6aDialog`, `S6aExchangeCorrelator` adapted to SWx MAR.
- Integration test: `sas-diameter-testapp` returns MAA with XRES, CK', IK'.
- Resync support (AUTS → new MAR).

**Gate:** MAR dialog completes in < 2s, resync works, timeout aborts cleanly.

### Phase 1c: MAP Backend + Binding RA (5–6 days)

**Deliverable:** `Jss7MapAuthBackend`, `SubscriberBindingRA`, binding source priority.

- `Jss7MapAuthBackend` (MAP SAI, quintet only; triplet → reject).
- `SubscriberBindingResourceAdaptor`, backends: `SwxSarBinding`, `ShUdrBinding`, `MapSendImsiBinding`, `SubscriberDbBinding`.
- LookupBindingCommand, command executor.
- Config: `sas.authvector.backend-order`, `sas.binding.source-order`.

**Gate:** SAI dialog works, SQN increments; SAR / UDR / SendIMSI each complete in < 2s; binding priority order enforced.

### Phase 1d: EntitlementSbb + `/ts43` Endpoint + FSM (6–7 days)

**Deliverable:** `EntitlementSbb`, `Ts43Resource`, FSM, timeouts, key zeroization.

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
- Preflight PRO-29: `sas.otp.enabled=true` only if backend is real SMPP and store is PostgreSQL.
- Fallback: when OTP disabled, SAS returns `403 SERVICE_UNAVAILABLE` for `/send-code`.

**Gate:** `sas.otp.enabled=false` by default in lab; prod gate PRO-29 rejects lab sender + H2 store.

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

---

## 8. Open Items and Tracking

- [ ] **ADR approval** (D1–D5): Ethio Telecom signoff on carrier privilege, core topology, GT assignment, token format. (Owner: Restlink)
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
- **3GPP TS 33.402** — UMTS security, Annex A (CK/IK derivation for non-3GPP)
- **3GPP TS 33.501** — 5G security, N32 SEPP (edge case if prod adds 5G)
- **RFC 4187** — EAP-AKA
- **RFC 5448** — EAP-AKA'
- **RFC 9048** — EAP-AKA' with Key Derivation Functions (KDF)
- **GSMA TS.43** (if published) — Wi-Fi entitlement for non-3GPP
- **CAMARA Number Verification v2.1.0** — `/verify`, `/device-phone-number`
- **TS 35.208** — Milenage test vectors
- **`docs/design/ts43-eapaka-wire-protocol.md`** — existing wire protocol doc
- **`docs/design/ts43-entitlement-integration-contract.md`** — operator contract (interfaces A–C)
- **`AGENTS.md` §8** — H24, H25, PRO-30..33 gates

---

## 10. Success Criteria

1. ✓ 34/34 hardness gates pass (including new H25).
2. ✓ All tests (`mvn -o test`) pass; coverage ≥ 85% for new code.
3. ✓ Prove artifact: lab E2E working, token issued, `/verify` accepts token, no secrets in logs, all processes stopped.
4. ✓ ADR D1–D5 closed with Ethio Telecom sign-off.
5. ✓ Authorization Code Flow conformance: `/authorize` + `/token` + refresh + ID token, GSMA OGW smoke suite passes.
6. ✓ Phase 2 and 3 implemented per plan; prod gates (PRO-30..35) enforced.
7. ✓ UAT scheduled with Ethio Telecom on real HLR/HSS; SQN, resync, binding verified live.

---

**Next Step:** Present ADR template and blockers to Ethio Telecom. Parallel: start Phase 1a (EAP-AKA library + vectors).
