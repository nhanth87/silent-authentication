# Silent Authentication

Research / implementation workspace for **network-side silent authentication** (no SMS OTP), seeded from Supermemory (2026-07-18).

## What it is

Silent Authentication verifies that a claimed phone number matches the device currently on the cellular network — zero user friction, mitigates SIM swap and SS7 SMS interception.

Primary app-facing surface: **CAMARA Number Verification API**.

## Core mechanism (IP ↔ subscriber match)

1. App/client sends the device’s **current cellular session IP** with the auth request.
2. Network resolves **MSISDN / IMSI / subscriber state** via signaling.
3. Match cellular-session IP to the verified subscriber identity.
4. Success → authenticated; failure / unavailable → fallback MFA.

**Constraints**

- **Network / IP-matching method** requires **active cellular data** (not Wi‑Fi-only).
- **SIM method (GSMA TS.43, EAP-AKA)** works across **Wi‑Fi + browsers** — the SIM
  credential, not the bearer IP, is the root of trust. This widens coverage and shrinks
  the fallback-to-OTP surface. See `docs/design/unified-identity-sms-security-architecture.md`.
- Coverage can be limited (~50% in some regions for the IP method).
- Fallback when unavailable: TOTP, Passkey, or push notification.

## Signaling backends

### 2G/3G — SS7 MAP (HLR/VLR)

| Message | Role |
|---------|------|
| SRI-SM  | Routing / MSISDN resolution |
| ATI     | Any Time Interrogation |
| PSI     | Provide Subscriber Info |
| SAI     | Send Authentication Info |

### LTE/5G — Diameter S6a (HSS)

| Message   | Role |
|-----------|------|
| AIR / AIA | Authentication Information |
| ULR / ULA | Update Location |
| NOR / NOA | Notify |
| PUR / PUA | Purge |
| IDR / IDA | Insert Subscriber Data |

## Related CAMARA / security context

- **CAMARA**: Number Verification, SIM Swap, OTP SMS, Scam Signal, KYC Match / Number Recycling.
- **GSMA**: FS.11 (SS7 interconnect monitoring), FS.19 (Diameter), FS.20 (GTP), FS.21 (umbrella).
- Silent Auth does **not** replace SS7/Diameter firewalls; it operates at the application / identity layer.

## Layout

| Path | Role |
|------|------|
| `worktrees/silent-authentication/main` | This checkout (source of truth) |

## Design

- [`docs/design/silent-auth-flow.md`](docs/design/silent-auth-flow.md) — banking flow
  (no login, no OTP): Resolver (IP→MSISDN via PGW) + Verifier (MAP/Diameter) + Policy,
  with sequence diagram, SAS state machine, timeout & FS.11/FS.19 security constraints.
- [`docs/design/unified-identity-sms-security-architecture.md`](docs/design/unified-identity-sms-security-architecture.md)
  — umbrella tying two complementary strategies: **replace OTP** (silent auth) +
  **protect OTP** (SMS Home Routing + SS7/Diameter/5G firewall); decision flow by access
  tech, threat→mitigation matrix, rollout sequencing.
- [`docs/design/3gpp-spec-coverage.md`](docs/design/3gpp-spec-coverage.md) — 3GPP spec → SAS
  stage map (TS 29.002 MAP PSI/ATI/SAI/SRI-SM; TS 29.272 S6a ULR/AIR/IDR/PUR) with the
  fail-closed / dialog / privacy rules carried into every gate.
- [`docs/design/hardness.md`](docs/design/hardness.md) — DeepSeek-Hardness: harness-driven
  pass/fail gates anchored to 3GPP clauses (skill `deepseek-hardness`).

## Security research

Two strategies protect the same phone-number identity from opposite sides — silent auth
removes SMS where it can; a signalling firewall protects the SMS that must still be sent.

- [`docs/research/sms-channel-protection.md`](docs/research/sms-channel-protection.md) —
  SS7 (Home Routing / SRI-SM filtering / MT-spoofing / Double MAP), Diameter (S6c/S6a/SGd
  via DEA), 5G (SEPP/N32/PRINS/SMSF); appendix maps controls to jSS7 `service/sms` classes.
- [`docs/research/gsma-fs-index.md`](docs/research/gsma-fs-index.md) — FASG document index
  (FS.07/11/19/20/21/31/36, SG.22, FF.09) + FS.11 MAP categorisation.
- [`docs/research/3gpp-spec-reference-index.md`](docs/research/3gpp-spec-reference-index.md) —
  index of the 3GPP specs the SAS depends on (TS 29.002, TS 29.272, TS 33.402).
- [`docs/research/3gpp-ts29-002-map.md`](docs/research/3gpp-ts29-002-map.md) — MAP
  (PSI/ATI/SAI/SRI-SM) operation & ASN.1 notes for the 2G/3G Verifier.
- [`docs/research/3gpp-ts29-272-s6a.md`](docs/research/3gpp-ts29-272-s6a.md) — Diameter
  S6a/S6d (ULR/AIR/IDR/PUR) command reference for the LTE Verifier.

## Proposal DOCX

[`proposal/DigicomET_Silent_Auth_Proposal_v3.docx`](proposal/DigicomET_Silent_Auth_Proposal_v3.docx) —
~50-page formal proposal derived from `slides/DigicomET_Silent_AuthProposal_v3.pptx`:
fraud/UN–ITU evidence, two-stage MAP/Diameter design, CAMARA + GSMA FASG tables,
commercial ROI (illustrative), roadmap. Chapters in `proposal/chapters/`; rebuild via
`python3 proposal/scripts/build_proposal_docx.py`.

## Pitch decks

### Mix v3 (recommended)

[`slides/DigicomET_Silent_Auth_Mix_v3.pptx`](slides/DigicomET_Silent_Auth_Mix_v3.pptx) —
**28 slides**: government/bank story + MAP/Diameter tech **plus** CAMARA API table and
GSMA FASG security tables (FS.07–FS.36, SG.22, FF.09, FS.11 categories).

```bash
python3 slides/scripts/generate_svgs.py      # v1 story assets
python3 slides/scripts/generate_svgs_v2.py   # v2 protocol diagrams
python3 slides/scripts/build_pptx_v3.py
```

### Technical v2 only

[`slides/DigicomET_Silent_Auth_Technical_v2.pptx`](slides/DigicomET_Silent_Auth_Technical_v2.pptx) —
17 slides, message-flow only. `generate_svgs_v2.py` + `build_pptx_v2.py`.

### Marketing v1 only

[`slides/DigicomET_Silent_Auth_Ethiopia.pptx`](slides/DigicomET_Silent_Auth_Ethiopia.pptx) —
20-slide bank pitch. `generate_svgs.py` + `build_pptx.py`.

## Next steps

- [ ] Resolver: PGW/PCRF/CGNAT binding source (IP+port+ts → MSISDN)
- [ ] CAMARA Number Verification adapter (API contract + mock) over SAS `/verify`
- [ ] MAP verifier on jSS7 (PSI/ATI/SAI) + jDiameter S6a (IDR/AIR)
- [ ] Assurance scoring weights + per-risk thresholds
- [ ] Fallback MFA policy when cellular path unavailable
- [ ] Digicom-ET pilot packaging (API contract for Ethiopian banks)