# SAS P1 — Production Readiness Result

> Date: 2026-08-19
> Scope: close the `result_p0.md` §2 production gaps to reach **P1**.
> Package renamed `et.digicomet` → **`et.restlink`** (all sources + `pom.xml`).
> Per instruction: Sa/STa/SWm/SWx Diameter are **not** wired into
> `corsac-diameter`; the TS.43 path is implemented as an **SWx verifier RA
> override under `src/.../restlink/sas/ras/swxverifier`**.

---

## 1. P1 items closed (from `result_p0.md` §2)

| # | result_p0 gap | P1 implementation | Status |
|---|---|---|---|
| 2 | OIDC token validation (was presence-only) | `security/TokenValidator` — HMAC-SHA256 JWT sig + `exp`/`iat`/`iss`/`aud`/`scope` checks, fail-closed; `security/SasSecurityConfig` (`sas.security.*`) | ✅ |
| 3 | Replay window (ts + reqId) not enforced | `security/ReplayGuard` — timestamp window + reqId dedup (TTL eviction); wired into `VerifyResource` | ✅ |
| 4 | Persistence / HA (unbounded map) | `persistence/VerifyResultStore` + `InMemoryVerifyResultStore` (TTL); `VerifyCoordinator` now persists via the store | ✅ |
| 5 | Sa/STa/SWm/SWx missing | **SWx verifier RA** (TS 29.273 / TS 33.402 EAP-AKA) as a SAS-local override — 3-port contract, one session/req, abort-on-timeout; Wi-Fi no longer hard `WIFI_NOT_READY` | ✅ |
| — | amr mobile-network check | `TokenValidator.validateAmr()` (403 `USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK`) | ✅ |

### New / changed files

```
sas/src/main/java/et/restlink/sas/
├── security/
│   ├── SasSecurityConfig.java        (NEW)  sas.security.* binding
│   ├── TokenValidator.java           (NEW)  JWT sig/exp/iss/aud/scope + amr
│   └── ReplayGuard.java              (NEW)  ts window + reqId dedup
├── persistence/
│   ├── VerifyResultStore.java        (NEW)  store interface
│   └── InMemoryVerifyResultStore.java(NEW)  TTL-backed impl
├── ras/swxverifier/                  (NEW — TS 29.273 override)
│   ├── SwxVerifierBackend.java
│   ├── InMemorySwxVerifierBackend.java
│   ├── SwxDialog.java
│   ├── SwxVerifierResourceAdaptor.java
│   ├── SwxVerifierRaEndpoint.java
│   └── command/{SwxVerifyCommand,AbortSwxCommand}.java
├── coordinator/VerifyCoordinator.java (EDIT) persist via store
├── bootstrap/SasBootstrap.java        (EDIT) wire SWx RA + seed
├── sbbs/VerifySbb.java                (EDIT) verifySwx() Wi-Fi path
└── api/VerifyResource.java            (EDIT) token + replay enforcement
```

Wiring: `VerifySbb` gains `@InjectRa(name="swx-verifier-ra")`; Wi-Fi requests
route to `verifySwx()` (claimed-MSISDN anchor, no IP resolver) instead of
failing `WIFI_NOT_READY`.

---

## 2. Still open (deferred to P2 — real transport / TLS)

| Item | State |
|---|---|
| Real MAP transport (jSS7 coral-valley PSI/SAI) | `InMemoryMapVerifierBackend` |
| Real Diameter S6a transport (real HSS) | `InMemoryS6aVerifierBackend` |
| Real SWx transport (real AAA/HSS) | `InMemorySwxVerifierBackend` |
| mTLS bank→SAS | token validation done; mutual-TLS termination not configured |
| Sa/STa/SWm in `corsac-diameter` | intentionally **not** done (SAS-local override per instruction) |

---

## 3. Validation snapshot (re-run)

- ✅ `mvn test`: **49/49 pass** (BUILD SUCCESS)
  - `TokenValidatorTest` 11, `ReplayGuardTest` 8,
    `InMemoryVerifyResultStoreTest` 5, `InMemorySwxVerifierBackendTest` 6,
    plus the 19 pre-existing FSM/policy/S6a tests.
- ✅ `mvn clean package`: jar built (`silent-auth-sas-0.1.0-SNAPSHOT.jar`).
- ✅ `python3 harness/run_hardness.py`: **24/24 gates pass** (H1–H14).
- ✅ No `digicomet`/`Digicom` references remain (`grep` clean).

---

## 4. Verdict

**P1 reached for the application-layer production controls** (token validation,
replay/idempotency, TTL persistence, SWx/TS.43 fail-closed path). The SAS is
still **R&D-only** per the micro-jainslee hard constraint; real MAP/S6a/SWx
signalling transport and mTLS termination remain the P2 boundary.
