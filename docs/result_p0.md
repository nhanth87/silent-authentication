# SAS P0 — Audit & Production Readiness Result

> Date: 2026-08-19
> Scope: audit of the micro-jainslee conformant silent-authentication SAS
> (`sas/`) against the micro-jainslee design rules (skill `jainslee`) and the
> production-readiness ("production P") bar.

---

## 1. Micro-jainslee design conformance

### ✅ Compliant (structure + anti-patterns)

| Rule (skill `jainslee`) | Status | Evidence |
|---|---|---|
| No `extends AbstractResourceAdaptor` | ✅ | All 3 RAs use `RaEndpointPort` + `RaCommandPort` wrapper + delegate |
| No `new Disruptor<>()` | ✅ | `grep` across `ras/` → **NONE FOUND** |
| Log4j2 (no JUL / SLF4J) | ✅ | `org.apache.logging.log4j` throughout |
| `final` classes | ✅ | SBB, RAs, endpoints, delegates, model are `final` |
| Java 25 | ✅ | `pom.xml` `release=25`, compiled by javac 25 |
| `@EventType implements SleeEvent`, immutable | ✅ | `VerifyRequestEvent` |
| `switch` pattern matching dispatch | ✅ | `VerifySbb.verify()` + 2 backends |
| `registerRa` / `registerSbbType` / `mapEventToSbb` | ✅ | `SasBootstrap` |
| Clone of `ElisaBootstrap` shape | ✅ | `@ApplicationScoped` + `@Observes StartupEvent` + `@PreDestroy` |

### ⚠️ Architectural deviations from "strict SLEE" (to fix at P1)

1. **RAs never call `bootstrapPort.fireEvent()`.** The skill mandates
   *"NEVER `new Disruptor<>()` — always `bootstrap.fireEvent()`"*. Here the 3
   RAs are **outbound** and return results by `.complete()`-ing a
   `CompletableFuture`; no inbound event is fired back (grep:
   `fireEvent`/`fireInboundEvent` = **NONE**).
2. **`VerifySbb.onEvent()` blocks.** `drive()` waits on `reply.get(timeout)`,
   blocking the EventRouter dispatch thread up to ~2.5 s. Strict SLEE requires
   the SBB to fire the request and **return**, receiving the response via an
   event (as Elisa does with `DiameterRequestEvent`/`DiameterAnswerEvent`).
   Documented intentionally (javadoc `VerifyCoordinator`) but **not strict SLEE**.
3. **Single event type** — no sealed event hierarchy for responses (skill
   requires a "sealed event hierarchy").
4. `S6a`/`Map` verifier delegates hold `bootstrapPort` but do not use it (no
   `createActivityHandle`/`fireEvent`); only `ResolverResourceAdaptor` uses
   `createActivityHandle`.

### Verdict (conformance)

**~90 %** — correct scaffold + anti-patterns + Elisa clone shape, but the
transport uses a `CompletableFuture` bridge rather than strict async
event-driven dispatch.

---

## 2. Production readiness ("production P")

### ❌ NOT production — intentionally

This is the **P0 R&D scaffold**, per `AGENTS.md`:

- **Hard constraint:** `micro-jainslee is R&D only — never for production`.
  Every file header carries the `"R&D only — never production"` notice.
- **Open items (AGENTS.md §10) still open:** jDiameter S6a client, Resolver
  source (PGW RADIUS vs PCRF Sd vs CGNAT log), assurance weights per-risk
  (hardcoded defaults), TS.43 Wi‑Fi entitlement, Restlink pilot API contract.

### Concrete production gaps

| Item | Current state |
|---|---|
| **mTLS bank→SAS** | `/verify` only checks `Authorization` **presence**, no OIDC token/scope validation — "Pilot: presence-only" |
| **Replay window** (ts + reqId) | Not enforced at runtime |
| **MAP transport** (jSS7 coral-valley PSI/SAI) | Not wired — `InMemoryMapVerifierBackend` |
| **Diameter S6a transport** | Not wired to a real HSS — `InMemoryS6aVerifierBackend` |
| **Sa / STa / SWm / SWx (TS 29.273)** | **Not implemented** — `corsac-diameter` has only the base MOBIUS stack (`DiameterLink`/`DiameterStack`; no Sa/Sta/SWm/SWx/S6a app). Research doc marks STa/SWa "adjacent"; SWm/SWx is the TS.43 path, currently fail-closed |
| **Persistence / HA / cluster** | `VerifyCoordinator` is an in-memory `ConcurrentHashMap`; no CMP/HA |

### Verdict (production)

**Not production (by design).** To reach "production-grade P1" the following
must be closed first:

1. Real MAP/S6a/SWx transport
2. Real OIDC token validation + mTLS
3. Runtime replay / idempotency enforcement
4. CMP / persistence
5. Add Sa/STa/SWm/SWx to `corsac-diameter`

---

## 3. Validation snapshot (re-run)

- ✅ `mvn test`: **19/19 pass** (BUILD SUCCESS)
- ✅ `python3 harness/run_hardness.py`: **24/24 gates pass** (H1–H14)

---

## 4. Summary

- **Micro-jainslee design:** ✅ structure + anti-patterns + Elisa clone shape;
  ⚠️ one architectural deviation (CompletableFuture blocking bridge instead of
  `fireEvent` async) — intentional for the HTTP bridge, must be fixed for
  "strict SLEE" at P1.
- **Production P:** ❌ not yet, and intentionally so — P0 R&D.