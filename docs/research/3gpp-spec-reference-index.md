# 3GPP Spec Reference Index — Silent Auth SAS

One entry point for the **3GPP** specs the silent-authentication stack depends on, with a
pointer to the per-spec extracted notes. Design-side coverage mapping lives in
`docs/design/3gpp-spec-coverage.md`; the runnable gates live in `harness/gates.yaml`
(see `docs/design/hardness.md`).

## Coverage status — 100% of the SAS signalling surface

| 3GPP spec | Version | Title | SAS use | Notes |
|-----------|---------|-------|---------|-------|
| **TS 29.002** | V19.1.0 | MAP (Mobile Application Part) | 2G/3G Verifier — state/location/freshness | [`3gpp-ts29-002-map.md`](3gpp-ts29-002-map.md) |
| **TS 29.272** | V19.5.0 | Diameter S6a/S6d (MME/SGSN ↔ HSS) | LTE/EPC Verifier — HSS state/freshness | [`3gpp-ts29-272-s6a.md`](3gpp-ts29-272-s6a.md) |
| **TS 29.273** | — | Diameter S6b/SWm/SWx/SWa/STa | Resolver data-plane (S6b) + Wi-Fi AAA (SWm/SWx) | [`3gpp-ts29-273-s6b-swm-swx.md`](3gpp-ts29-273-s6b-swm-swx.md) |
| **TS 29.338** | — | Diameter SGd (SMS) | Fallback OTP route (LTE/5G SMS) | [`3gpp-ts29-338-sgd.md`](3gpp-ts29-338-sgd.md) |
| **TS 33.402** | — | EAP-AKA (non-3GPP access) | Wi-Fi silent auth (TS.43 anchor) | [`3gpp-ts33-402-eap-aka.md`](3gpp-ts33-402-eap-aka.md) |
| **TS 33.501** | — | 5G security / N32 SEPP | 5G path (Nudm/Nausf) + Strategy B FW | [`3gpp-ts33-501-n32.md`](3gpp-ts33-501-n32.md) |
| **TS 23.018 / 23.060** | — | MAP/GPRS procedures, timers | TCAP dialog lifecycle & TC-TIMER | [`3gpp-ts23-series-map-procedures.md`](3gpp-ts23-series-map-procedures.md) |

Non-3GPP but adjacent (kept separate):

| Doc | Role |
|-----|------|
| **CAMARA NumberVerification** v2.1.0 | SBB northbound contract (`/verify`) | [`camara-number-verification.md`](camara-number-verification.md) |
| **GSMA TS.43** (EAP-AKA SIM, Wi-Fi) | entitlement profile (anchor = TS 33.402) | see `3gpp-ts33-402-eap-aka.md` |
| **GSMA FASG FS.11 / FS.19 / FS.36** | categorisation applied on MAP/Diameter/5G | [`gsma-fs-index.md`](gsma-fs-index.md) |

## Key operations at a glance

| Question | MAP (TS 29.002) | S6a (TS 29.272) | Wi-Fi (33.402/SWx) | 5G (33.501) |
|----------|-----------------|-----------------|--------------------|-------------|
| Reachable? | `provideSubscriberInfo` (70) | ULR/ULA (316); PUR/PUA (321) | SWx attr-check | Nudm context |
| Forced reachable? | `anyTimeInterrogation` (71) — **intra-net only** | — | — | — |
| Fresh / not SIM-swapped? | `sendAuthenticationInfo` (56) | AIR/AIA (318) | EAP-AKA AV | Nausf re-auth |
| OTP fallback route | SRI-SM (45) | SGd (TS 29.338) | — | Nsms/SMSF |
| Resolver (IP→MSISDN) | — (data plane) | — | S6b/STa (data plane) | SMF/UPF/NEF |