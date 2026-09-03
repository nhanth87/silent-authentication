# 3GPP Spec Coverage — Silent Auth SAS (design view)

Design-side mapping from each SAS verifier stage/question to the normative **3GPP**
clause and message. Raw extracted notes live in `docs/research/`; this file pins the
*coverage contract* the verifier must honour and is the source table for the
**DeepSeek-Hardness** gates in [`hardness.md`](hardness.md) (`harness/gates.yaml`).

Specs in scope (100% of the SAS signalling surface):

| Spec | Version | Role |
|------|---------|------|
| **3GPP TS 29.002** MAP | V19.1.0 | 2G/3G Verifier: state/location/freshness over SS7 (TCAP/SCCP) |
| **3GPP TS 29.272** S6a/S6d | V19.5.0 | LTE/EPC Verifier: HSS-side state/freshness over Diameter |
| **3GPP TS 29.273** S6b/SWm/SWx | — | Resolver data-plane (S6b) + Wi-Fi AAA (SWm/SWx) |
| **3GPP TS 29.338** SGd | — | Fallback SMS OTP route (Diameter) |
| **3GPP TS 33.402** | — | Wi-Fi silent auth via EAP-AKA (GSMA TS.43 anchor) |
| **3GPP TS 33.501** | — | 5G path (Nudm/Nausf via NEF) + N32/SEPP boundary |
| **3GPP TS 23.018 / 23.060** | — | MAP/GPRS procedures: TCAP dialog lifecycle & TC-TIMER |

> GSMA **TS.43** = the *service-entitlement* profile for SIM-based silent auth; its 3GPP
> signalling anchor is TS 33.402 SWm/SWx via the 3GPP AAA (not S6a).

## 1. Stage → spec → message map

| SAS stage | Question | 2G/3G (TS 29.002) | 4G/5G (TS 29.272) | Wi-Fi (33.402/29.273) |
|-----------|----------|--------------------|--------------------|------------------------|
| Resolver hand-off | Which MSISDN owns IP:port:ts? | (PGW/GGSN binding, not MAP) | (PGW S6b/PCRF, not Diameter) | S6b/STa binding |
| Verifier — reachable | Attached and reachable? | `provideSubscriberInfo` (PSI, **70**) | ULR/ULA (**316**), PUR/PUA (**321**) | SWx attr-check |
| Verifier — forced reachable | Any-time state? | `anyTimeInterrogation` (ATI, **71**) — **intra-net only** | — | — |
| Verifier — fresh | Credential not SIM-swapped? | `sendAuthenticationInfo` (SAI, **56**) | **Sh UDR/SNR** (TS 29.328/29.329, read-only) | EAP-AKA AV (SWx) |
| Verifier — location | VLR/MME agrees with IP geo? | `requestedInfo.locationInformation*` | ULR location info | (n/a) |
| Fallback OTP route | Where does SMS OTP route? | `sendRoutingInfoForSM` (SRI-SM, **45**) | **SGd** (TS 29.338, via DEA) | Nsms/SMSF |
| Anti-takeover | New serving node appeared? | `cancelLocation` (**3**), `updateLocation` (**2**) | CLR/CLA (**317**) | — |

## 2. Verifier question → operation detail (coverage contract)

| Question | 3GPP op/command | What the spec returns | SAS scoring input |
|----------|-----------------|-----------------------|-------------------|
| Reachable | TS 29.002 PSI (`provideSubscriberInfo`) | `subscriberState`, `locationInformation*` | `attached/pdp-active + reachable` → "live" |
| Reachable | TS 29.272 ULR/ULA + PUR/PUA | serving MME registration; `PUR` ⇒ purged | purged ⇒ FALLBACK |
| Reachable (Wi-Fi) | TS 33.402 SWx / 29.273 | EAP-AKA AV + subscription | entitlement ⇒ "live" (no cellular) |
| Fresh | TS 29.002 SAI (`sendAuthenticationInfo`) | `authenticationSetList` / `eps-AuthenticationSetList` | last-seen vector set vs new set |
| Fresh | TS 29.328/29.329 Sh UDR/SNR | read-only IMSI-change age / binding | IMSI-change age < cooldown ⇒ swap |
| Fresh (Wi-Fi) | TS 33.402 EAP-AKA | AV sync/resync | sync-failure ⇒ FALLBACK |
| Location | TS 29.002 PSI `locationInformation*` | VLR/SGSN/MME/EPS/5GS location | compare vs Resolver IP-geo window |

## 3. Hard rules carried into every gate (must not regress)

- **No interconnect ATI** — TS 29.002 `anyTimeInterrogation` is FS.11 Category 1; the
  Verifier probes the **own** HLR only. Prefer PSI (Cat 2.1).
- **Fail-closed** — absent `subscriberState` / vectors / `PUR` ⇒ FALLBACK, never soft-approve.
- **One dialog per stage** — bounded TCAP dialog / Diameter transaction; timeout ⇒ `abort()`.
- **Point-in-time** — treat state as a snapshot at `ts`; CGNAT needs IP+port+ts.
- **No AIR/IDR for status** — the 4G/5G verifier uses ULR/ULA (liveness) + read-only
  **Sh UDR/SNR** (freshness). AIR/AIA consumes EPS vectors and advances the AuC SQN
  (MAC-failure re-sync risk); IDR/IDA is an HSS→MME push — neither is a read query.
- **Privacy** — IMSI/IMEI/EPS vectors stay on SAS/backend; app sees a boolean only.
- **CAMARA contract** — `/verify` → `devicePhoneNumberVerified` boolean; single-use ≤300 s token;
  403 on non-mobile-network auth (`NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK`).

See `docs/research/*.md` for the ASN.1/command-code extracts and
`docs/research/3gpp-spec-reference-index.md` for the (now 100%) index.