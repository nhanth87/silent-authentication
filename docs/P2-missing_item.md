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
| 1 | **Resolver source** (PGW RADIUS / PCRF Sd / CGNAT log) | A | ⚠️ | `RadiusAccountingListenerBackend` = real RFC 2866 UDP listener (authenticator MD5, Start/Stop/Interim, Accounting-Response). `CgnatLogResolverBackend` = live incremental tail + point-in-time `ts` filtering. Opt-in `sas.transport.resolver=radius\|cgnat`. **PCRF Sd/Gx still absent** |
| 2 | **UE session-tuple SDK** | A | ⚠️ | Server collector done (`POST /session-tuple`, API-key-gated). The device-side SDK artifact itself is still not in this repo |
| 3 | **TS.43 entitlement server** | B | ⚠️ | HMAC-SHA256-signed tokens (payload msisdn/imsi/eapMethod/iat/exp/jti), single-use consumed-jti, TTL clamped ≤300 s, fail-closed when secret blank + require-signed. CIBA `login_hint=operatortoken:<tk>` + `X-Sas-Operator-Token` accepted on `/verify` (eapMethod whitelist EAP-AKA/EAP-AKA'). **Remaining:** `/entitlement/issue` still trusts caller assertions — needs a real AAA/EAP attestation source (mitigated: `/verify` re-runs SWx against the claimed identity anyway) |
| 4 | **EAP-AKA UE path** (SWm termination / 3GPP AAA) | B | ⚠️ | Unchanged by design: SAS consumes SWx result; actual EAP-AKA runs UE↔AAA operator-side. Proven end-to-end against the HSS simulator (MAR/SAR) |
| 5 | **Real SWx Diameter transport** (corsac-diameter) | B | ✅ | `CorsacSwxVerifierBackend`: MAR/MAA primary + SAR/SAA registration (+ PPR probe, config-gated), per-Session-Id correlation (no broadcast), fail-closed result-code mapping, Visited-PLMN configurable + TS 24.301 encoding. **E2E-proven vs `sas-diameter-testapp`** |
| 6 | **Real S6a Diameter transport** (corsac-diameter) | A (LTE) | ✅ | `CorsacS6aVerifierBackend`: ULR/ULA → AIR/AIA (vector count parsed, empty ⇒ fail-closed) → IDR/IDA probe (config-gated). One session per stage, shared 2 s budget, abort on timeout. **E2E-proven vs `sas-diameter-testapp`** |
| 7 | **`/retrieve-phone-number`** (CAMARA NV) | — | ✅ | Full flow in `VerifyResource`; per-endpoint scope `number-verification:device-phone-number:read`; amr rule fail-closed; replay/single-use enforced |
| 8 | **Assurance weights + per-risk thresholds** | A+B | ✅ | `AssurancePolicy.fromRuntime(SasAdminRuntimeConfig::read)` reads `sas.assurance.w-*` + `sas.assurance.threshold-*` from the admin KV store (hard-fail misconfig ⇒ defaults wholesale). RiskClass LOGIN/TRANSFER/HIGH_VALUE plumbed VerifyResource→event→SBB→FSM (unknown ⇒ LOGIN) |
| 9 | **jSS7 MAP transport live test** | A (2G/3G) | ⚠️ | `Jss7MapVerifierBackend` code-complete (PSI+SAI+abort+fail-closed, no interconnect ATI); still needs a reachable home STP/HLR to exercise live |

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
