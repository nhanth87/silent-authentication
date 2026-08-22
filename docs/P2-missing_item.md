# P2 Missing Items — Silent Auth SAS

Date: 2026-08-21 · Derived from the 4 flow questions (see `lesson_learn.md`).
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

## Missing items (gaps the answers expose)

| # | Item | Path | Status | Notes |
|---|------|------|--------|-------|
| 1 | **Resolver source** (PGW RADIUS / PCRF Sd / CGNAT log) | A | ❌ | `InMemoryResolverBackend` stand-in; needs operator integration per AGENTS.md open item |
| 2 | **UE session-tuple SDK** (IP + source port + ts collector) | A | ❌ | Needed for CGNAT disambiguation; thin client SDK, not auth logic |
| 3 | **TS.43 entitlement server** (temporary token issue/exchange) | B | ❌ | Open item "TS.43 entitlement server feasibility"; CIBA `login_hint=operatortoken:<tk>` / JWT-Bearer not implemented |
| 4 | **EAP-AKA UE path** (SWm termination / 3GPP AAA) | B | ⚠️ | SAS only consumes SWx result; actual EAP-AKA runs UE↔AAA (operator side). `InMemorySwxVerifierBackend` is a stand-in |
| 5 | **Real SWx Diameter transport** (corsac-diameter) | B | ⚠️ | `sas.transport.swx=corsac` config exists; backend still in-memory |
| 6 | **Real S6a Diameter transport** (corsac-diameter AIR/IDR) | A (LTE) | ⚠️ | `sas.transport.s6a=corsac` config exists; backend still in-memory |
| 7 | **`/retrieve-phone-number`** (CAMARA NV) | — | ❌ | Returns 501; needs `device-phone-number:read` scope path |
| 8 | **Assurance weights + per-risk thresholds** | A+B | ❌ | AGENTS.md open item; scoring weights hardcoded |
| 9 | **jSS7 MAP transport live test** | A (2G/3G) | ⚠️ | `Jss7MapVerifierBackend` wired (P2) but needs reachable home STP/HLR to exercise live |

---

## Priority for next phase

1. **Resolver integration** (#1) — the IP-match path is unusable without a real binding source.
2. **TS.43 entitlement token exchange** (#3) — closes the Wi-Fi gap end-to-end.
3. **Real Diameter transports** (#5, #6) — replace in-memory S6a/SWx with corsac-diameter.
4. **UE SDK** (#2) — thin collector; can ship alongside pilot.
5. **`/retrieve-phone-number`** (#7) + assurance tuning (#8).
