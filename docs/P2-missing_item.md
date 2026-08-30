# P2 Missing Items — Silent Auth SAS

Date: 2026-08-21 · Updated: 2026-08-23 (cross-checked against source + E2E run)
Status legend: ✅ done · ⚠️ partial/stand-in · ❌ missing

---

## The 4 questions (answers)

| # | Câu hỏi | Trả lời |
|---|---|---|
| 1 | Flow xác thực qua SAS | App→Bank Backend→SAS `/verify`→Resolver→Verifier(MAP/S6a/SWx)→Policy→boolean |
| 2 | EAP-AKA nằm ở đâu | Path Wi-Fi, giữa UE↔3GPP AAA (SWm); SAS tiêu thụ qua `swx-verifier-ra` (SWx) |
| 3 | Cần SDK UE không | Không cho logic lõi; chỉ SDK mỏng thu thập IP:port:ts (path A) / entitlement token (path B) |
| 4 | CAMARA gọi ở đâu | `VerifyResource.java` — `POST /verify`, do Bank Backend gọi server-to-server |

Full narrative: [`lesson_learn.md`](lesson_learn.md).

---

## Missing items (status after 2026-08-23 P2 completion pass)

| # | Item | Path | Status | Notes |
|---|------|------|--------|-------|
| 1 | **Resolver source** (PGW RADIUS / PCRF Sd / CGNAT log) | A | ✅ | `RadiusAccountingListenerBackend` (RFC 2866 UDP+MD5), `CgnatLogResolverBackend` (live tail + point-in-time ts), `PcrfSdResolverBackend` (Gx CCR-I probe, app-id 16777238, no-binding=5030) — all opt-in via `sas.transport.resolver=radius\|cgnat\|sd`, E2E-proven vs testapp Gx instance |
| 2 | **UE session-tuple SDK** | A | ✅ | Device-side artifact shipped: standalone maven module `ue-sdk/` (`SessionTupleCollector` + `SessionTupleClient`, zero runtime deps, 28 tests; declares the cellular AccessTech); server collector `/session-tuple` API-key-gated |
| 3 | **TS.43 entitlement server** | B | ⚠️ | HMAC-signed single-use tokens (TTL≤300s clamp), CIBA `operatortoken:` accepted on /verify (whitelist EAP-AKA/AKA'). **Remaining:** AAA/EAP attestation for `/entitlement/issue` (B1) — currently trusts caller assertions; mitigated by SWx re-verify |
| 4 | **EAP-AKA UE path** (SWm termination / 3GPP AAA) | B | ⚠️ | Unchanged by design: SAS consumes SWx result; actual EAP-AKA runs UE↔AAA operator-side. Proven end-to-end against the HSS simulator (MAR/SAR) |
| 5 | **Real SWx Diameter transport** (corsac-diameter) | B | ✅ | `CorsacSwxVerifierBackend`: MAR/MAA primary + SAR/SAA registration (+ PPR probe, config-gated), per-Session-Id correlation (no broadcast), fail-closed result-code mapping, Visited-PLMN configurable + TS 24.301 encoding. **E2E-proven vs `sas-diameter-testapp`** |
| 6 | **Real S6a Diameter transport** (corsac-diameter) | A (LTE) | ✅ | `CorsacS6aVerifierBackend`: ULR/ULA → AIR/AIA (vector count parsed, empty ⇒ fail-closed) → IDR/IDA probe (config-gated). One session per stage, shared 2 s budget, abort on timeout. **E2E-proven vs `sas-diameter-testapp`** |
| 7 | **`/retrieve-phone-number`** (CAMARA NV) | — | ✅ | CAMARA-aligned: primary `GET /number-verification/v2/device-phone-number` (+ deprecated aliases), spec error contract {status,code,message}, user-bound token compare, TTL≤300s, opt-in assurance enrichment. Spec snapshot + gap analysis: `docs/research/camara/` |
| 8 | **Assurance weights + per-risk thresholds** | A+B | ✅ | `AssurancePolicy.fromRuntime(SasAdminRuntimeConfig::read)` reads `sas.assurance.w-*` + `sas.assurance.threshold-*` from the admin KV store (hard-fail misconfig ⇒ defaults wholesale). RiskClass LOGIN/TRANSFER/HIGH_VALUE plumbed VerifyResource(header X-Sas-Risk-Class)→event→SBB→FSM (unknown ⇒ LOGIN) |
| 9 | **jSS7 MAP transport live test** | A (2G/3G) | ✅(sim) | New module `sas-jss7-testapp/`: simulated home HLR answering PSI v3 + SAI v3 over real loopback SCTP (ATI logged+dropped, FS.11). LiveLoopTest 5 green in-process + cross-process smoke proven. Still pending: exercise against a REAL reachable STP/HLR |

CAMARA conformance note (2026-08-23): northbound aligned to camaraproject NumberVerification
v2.1.0 per `docs/research/camara/nv-flow-analysis.md` (fixes F1–F6 applied: spec paths,
error contract, user-bound token semantics, TTL cap, E.164 normalization; assurance
enrichment is opt-in and NOT part of the CAMARA contract). The TS.43/Wi-Fi track
(`/entitlement/*`, `operatortoken:`) is explicitly OUT of the CAMARA surface — kept as a
separate operator-side anchor.

---

## E2E proof (2026-08-23) — web → POST /verify → SAS → diameter test app

Loop: curl → SAS (`s6a=corsac`, `swx=corsac`) → `sas-diameter-testapp` HSS/AAA simulator
(ports 3868=S6a / 3869=SWx, control UI :8086/:18086):

| Scenario | Signalling seen | Result |
|---|---|---|
| LTE happy path | ULR→ULA 2001, AIR→AIA vectors=1 | `true` |
| Subscriber detached | ULR→ULA 5421 | `false` (fail-closed) |
| Zero auth vectors | AIR→AIA 2001, empty set | `false` (fail-closed) |
| Recover after reset | ULA/AIA 2001 again | `true` |
| Wi-Fi path w/ signed operator token | MAR→MAA 2001 items=1, SAR→SAA 2001 | `true` |
| Entitlement token replay | second use of same token | `401` |

Run it:

```bash
cd sas-diameter-testapp && mvn -q package && \
  java -jar target/sas-diameter-testapp.jar &                     # S6a HSS :3868, UI :8086
java -jar target/sas-diameter-testapp.jar --diameter-port 3869 --web-port 18086 &  # SWx AAA
cd ../sas && mvn -q package && java -Dsas.transport.s6a=corsac -Dsas.transport.swx=corsac \
  -Dsas.entitlement.hmac-secret=<secret> -Dsas.transport.diameter.swx.peer-port=3869 \
  -jar target/quarkus-app/quarkus-run.jar &
curl -X POST http://localhost:8085/verify -H 'Authorization: Bearer demo' \
  -H 'X-Sas-Amr: mobile' -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"+251911111111"}'
```

Lab caveats found during bring-up (documented, deliberate):

- corsac `addLink` param order is `(remote…, local…)` and application packages are
  registered as `(commandsPackage, implPackage)` — both verified against lib tests;
  command packages need an anchor-class load before `Package.getPackage`.
- The lab HSS serves one inbound SCTP association per listen port → SWx dials its own
  port via `sas.transport.diameter.swx.peer-port`.
- IDR/PPR probes are config-gated OFF by default (HSS-initiated directions exercised as
  own-HSS active probes only).
- CAMARA hardening now on bearer path: jti-derived idempotency key, single-use token
  consumption, per-endpoint scopes, E.164/SHA-256 input validation, amr fail-closed.

---

## Priority for next phase

1. **PCRF Sd/Gx resolver option** (#1 remainder) or operator PGW feed decision.
2. **UE SDK artifact** (#2 remainder) — thin collector shipping alongside pilot.
3. **AAA attestation for `/entitlement/issue`** (#3 remainder) — close the B1 hole.
4. **jSS7 live STP/HLR exercise** (#9) + `result_p2.md` write-up.
