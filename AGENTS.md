# Silent Authentication — AGENTS.md

**JDK: N/A (docs / Python).** No Java build in this tree today — **ask** before introducing or running any Java toolchain. Workspace rule: [`../../../AGENTS.md`](../../../AGENTS.md).

Agent notes for `worktrees/silent-authentication/main`. Research / design / pitch workspace
for Digicom-ET Silent Auth (Ethiopia). Not USSD GW source; not micro-jainslee Quarkus examples.

Seeded from Supermemory (2026-07-18) + design/pitch work (2026-07-20).

---

## 0. Location & layout

| Path | Role |
|------|------|
| `worktrees/silent-authentication/main` | **Only** checkout (real dir under `worktrees/`) |
| Do **not** recreate | `ethiopia-working-dir/silent-authentication` at repo root |

```
main/
├── AGENTS.md                 ← this file
├── README.md
├── docs/design/              ← SAS flow + unified architecture
├── docs/research/            ← SMS channel protection + GSMA FS index
├── proposal/                 ← formal DOCX chapters + build script
├── sas-api/                  ← CAMARA northbound library (/verify, oauth, security)
├── sas-entitlement/          ← TS.43/Wi-Fi entitlement track library
├── sas-host/                 ← runnable Quarkus app (SLEE bootstrap, RAS, CDR, admin)
└── slides/                   ← PPTX v1/v2/v3 + SVG assets + build scripts
```

---

## 1. Product / commercial (Digicom-ET)

- Digicom-ET sells **silent authentication VAS** to Ethiopian banks.
- Digicom is an **adapter layer ABOVE Ethio Telecom** — does **not** take SMS/interconnect
  revenue from the operator.
- Digicom bills banks for **`/verify` API**; fallback SMS still rides operator SMSC.
- Persona: bank login without SMS OTP (“Chú Phỉnh”).
- Pitch theme: Ethiopian flag green/yellow/red.

---

## 2. What Silent Auth is

Proof that the phone currently on the network owns the claimed MSISDN — **no password
re-entry, no SMS OTP** on the happy path.

App-facing surface: **CAMARA Number Verification** (NV2) over SAS `/verify`.

### Two silent-auth methods

| Method | Root of trust | Needs cellular data? | Notes |
|--------|---------------|----------------------|-------|
| **IP-match** (Resolver + Verifier) | Bearer IP↔MSISDN via PGW + MAP/Diameter | **Yes** | CGNAT → require IP+port+ts |
| **SIM / TS.43 EAP-AKA** | SIM credential | **No** (Wi‑Fi + browsers OK) | Shrinks fallback-to-OTP surface |

Earlier assumption “all silent auth needs cellular” is **wrong** for TS.43.

### Constraints

- IP method fails on Wi‑Fi-only / no binding / stale → **FALLBACK**.
- Coverage can be limited (~50% some regions for IP method).
- Fallback: TOTP, Passkey, push, or firewalled SMS OTP.

---

## 3. Hard design invariant

**MAP / Diameter cannot map `IP → MSISDN`.**

| Question | Who answers |
|----------|-------------|
| Which MSISDN owns cellular IP `A.B.C.D:port` now? | **PGW / GGSN / PCRF / CGNAT** (Resolver) |
| Is that MSISDN live / not SIM-swapped? | **MAP** (ATI/PSI/SAI) or **Diameter S6a** (AIR/IDR) (Verifier) |

Two stages:

```
IP:port:ts  ──[Resolver]──►  MSISDN/IMSI  ──[Verifier]──►  assurance
```

---

## 4. SAS (Silent Auth Service)

Actors: Bank App (cellular) → Bank Backend → **SAS** (Resolver + Verifier + Policy) →
IP Resolver + MAP/Diameter Verifier → own HLR/HSS.

### FSM (fail-closed)

`RESOLVING → VERIFYING → SCORING → APPROVED`  
Any missing evidence / timeout → **FALLBACK** (never soft-pass).

### Timeouts (dialog-anchor)

| Stage | Budget | On expiry |
|-------|--------|-----------|
| Resolver | 300 ms | FALLBACK |
| MAP PSI/ATI | 2 s | abort dialog, FALLBACK |
| Diameter S6a | 2 s | FALLBACK |
| Total SAS | 3 s | bank normal login |

### Assurance (sketch)

```
score = w1*ipBindingFresh + w2*reachable + w3*notSimSwapped + w4*locationPlausible
APPROVE iff score >= threshold AND (resolved==claimed when claimed present)
```

High-value txs → raise threshold or force step-up.

### Security checklist (must not regress)

- **No interconnect ATI** — FS.11 Category 1; SAS queries **own** HLR/HSS only.
- **Fail-closed** — missing evidence never approves.
- **Idempotency** — `reqId` dedups; one MAP/Diameter dialog per stage.
- **Dialog leak** — bounded TC timer; timeout ⇒ `abort()`.
- **Race** — binding read is point-in-time (`ts`), not “latest”.
- **Replay** — bank→SAS mTLS; `ts` + `reqId` window.
- **CGNAT** — require IP+port+ts; reject if >1 MSISDN.
- **Privacy** — MSISDN/IMSI **never** returned to mobile app (bank backend only).
- **Spoofed GT** — trust only own HSS responses (FS.11 §3.3.4).

---

## 5. Signalling reference

### 2G/3G — MAP (intra-net)

| Message | Purpose | FS.11 |
|---------|---------|-------|
| **PSI** | subscriber state + location (preferred) | Cat 2.1 |
| **ATI** | any-time interrogation — **intra-net ONLY** | Cat 1 on interconnect |
| **SAI** | auth vectors / SIM-swap freshness | Cat 3.2 |
| SRI-SM | routing (SMS path; Home Routing protects) | — |

jSS7 (coral-valley): `AnyTimeInterrogation*`, `ProvideSubscriberInfo*`,
`SendAuthenticationInfo*` via `MAPServiceMobility`.

### LTE/5G — Diameter S6a

AIR/AIA, IDR/IDA, ULR/ULA, NOR/NOA, PUR/PUA — FS.19.

---

## 6. Unified architecture (two strategies)

They are **complementary**, not alternatives:

| | Strategy A — Replace OTP | Strategy B — Protect OTP |
|--|--------------------------|--------------------------|
| Mech | Silent Auth (NV2 / TS.43 / IP-match) | SMS Home Routing + SS7/Diameter/5G FW |
| Layer | Application / identity | Signalling / interconnect |

**Rollout order (recommended):**

1. Protect SMS (Home Routing + SS7 FW)
2. Diameter/5G (DEA FS.19, SEPP/N32 FS.36)
3. Introduce silent auth
4. Shift traffic; OTP shrinks to firewalled fallback

Details: `docs/design/unified-identity-sms-security-architecture.md`.

---

## 7. GSMA / CAMARA cheat sheet

| Doc | Use here |
|-----|----------|
| FS.07 | SS7/SIGTRAN threat foundation |
| **FS.11** | SS7 FW categories; ATI Cat 1; SRI-SM / MT-spoof / Double MAP |
| FS.19 | Diameter interconnect |
| FS.20 | GTP (secondary) |
| FS.21 | Umbrella categorise/monitor/filter |
| FS.31 | Baseline controls |
| FS.36 | 5G SEPP/N32 |
| SG.22 / FF.09 | SMS FW policy / SMS fraud taxonomy |
| **CAMARA** | Number Verification, SIM Swap, Scam Signal, KYC Match |
| **TS.43** | EAP-AKA SIM silent auth (Wi‑Fi capable) |

Index: `docs/research/gsma-fs-index.md`.  
Strategy B detail: `docs/research/sms-channel-protection.md`.

Silent Auth does **not** replace SS7/Diameter firewalls.

---

## 8. Doc map (read in this order)

1. `docs/design/silent-auth-flow.md` — banking E2E, SAS FSM, timeouts, checklist
2. `docs/design/unified-identity-sms-security-architecture.md` — A+B umbrella
3. `docs/design/3gpp-spec-coverage.md` — stage→spec→message coverage contract (100%)
4. `docs/design/hardness.md` — DeepSeek-Hardness gates + how to run (`harness/run_hardness.py`)
5. `docs/research/3gpp-spec-reference-index.md` — **index of all 3GPP specs (100% of surface)**
6. `docs/research/3gpp-ts29-002-map.md` — SS7 MAP ops (PSI/ATI/SAI) for the Verifier
7. `docs/research/3gpp-ts29-272-s6a.md` — Diameter S6a/S6d commands (LTE, not Wi-Fi)
8. `docs/research/3gpp-ts29-273-s6b-swm-swx.md` — S6b (Resolver data-plane) + SWm/SWx (Wi-Fi AAA)
9. `docs/research/3gpp-ts33-402-eap-aka.md` — EAP-AKA / TS.43 Wi-Fi silent auth (SWm/SWx)
10. `docs/research/3gpp-ts29-338-sgd.md` — SGd (fallback SMS OTP over Diameter)
11. `docs/research/3gpp-ts33-501-n32.md` — 5G Nudm/Nausf path + SEPP/N32 boundary
12. `docs/research/3gpp-ts23-series-map-procedures.md` — TCAP dialog/timer lifecycle (TC-TIMER)
13. `docs/research/camara-number-verification.md` — **CAMARA NumberVerification v2.1.0 `/verify` contract**
14. `docs/research/sms-channel-protection.md` — Home Routing / DEA / SEPP
15. `docs/research/gsma-fs-index.md` — FASG PRD index
16. `README.md` — overview + build commands
17. `proposal/chapters/*` — formal proposal narrative
18. `slides/` — pitch decks

### Hardness gate (installed)

```bash
python3 harness/run_hardness.py    # contract check — 24/24 gates, exit 0 = pass
```

Gates in `harness/gates.yaml` (H1–H14), each anchored to a 3GPP clause / CAMARA contract.
Runner: `harness/run_hardness.py`. Spec text SoT: `docs/research/`.

---

## 9. Artifacts & rebuild

### Proposal DOCX

`proposal/DigicomET_Silent_Auth_Proposal_v3.docx` — chapters in `proposal/chapters/`.

```bash
python3 proposal/scripts/build_proposal_docx.py
```

### Pitch decks

| Deck | File | Rebuild |
|------|------|---------|
| **Mix v3 (recommended)** | `slides/DigicomET_Silent_Auth_Mix_v3.pptx` | `generate_svgs.py` + `generate_svgs_v2.py` + `build_pptx_v3.py` |
| Technical v2 | `slides/DigicomET_Silent_Auth_Technical_v2.pptx` | `generate_svgs_v2.py` + `build_pptx_v2.py` |
| Marketing v1 | `slides/DigicomET_Silent_Auth_Ethiopia.pptx` | `generate_svgs.py` + `build_pptx.py` |

Scripts live under `slides/scripts/`.

---

## 10. Agent rules (this tree)

Always:

- Keep checkout **under** `worktrees/silent-authentication/` — no root-level copy.
- Prefer sequence diagrams + FSM + timeout tables for protocol/auth changes.
- Preserve **fail-closed** and **no interconnect ATI**.
- Do not confuse with `ussdgateway` (WF10 + old jSS7) or Quarkus USSD examples.
- When recalling prior decisions: use **Supermemory MCP** (`recall` / `memory`).

Protocol / design:

- Never break MAP dialog state machine when wiring jSS7 verifiers.
- One dialog per stage; abort on timeout (no dialog leaks).
- CGNAT: always IP + port + timestamp.
- Privacy: MSISDN/IMSI stay on bank backend / SAS — never to the app.

Open items (do not silently invent answers):

- [ ] Resolver source per operator (PGW RADIUS vs PCRF Sd vs CGNAT log)
- [ ] jDiameter S6a client module
- [x] CAMARA NV **Java adapter** over SAS `/verify` — **P0 scaffold implemented** in
  [`sas-host/`](sas-host/README.md) (Quarkus + micro-jainslee; clones `ra-diameter` + `ElisaBootstrap`).
  Contract: `docs/research/camara-number-verification.md`.
- [x] **P2 real MAP transport** — `Jss7MapVerifierBackend` (jSS7 coral-valley) drives
  PSI + SAI dialogs against the own HLR/HSS, never ATI. Opt-in via `sas.transport.map=jss7`
  (sample config `sas-host/src/main/resources/ss7-sas.json`). Fail-closed on any timeout/reject/abort.
- [ ] Assurance weights + per-risk thresholds
- [ ] TS.43 entitlement server feasibility (Wi‑Fi path)
- [ ] Strategy B product choice (SMS Router / SS7 FW vs jSS7-based)
- [ ] Digicom-ET pilot API contract for Ethiopian banks
- [ ] **SAS admin dashboard** (clone gmlc admin) — dashboard, SS7, HTTP endpoint, Diameter
  (JSON, multi-realm/multi-app + on-the-fly reload), CDR, tenant→networkId, user→networkId+
  bearer/API key. In progress in `sas-host/` (see `sas-host/TODO.md` for production hardening backlog).
- [ ] Production hardening for the SAS HTTP `/verify` endpoint — **lab accepts HTTP + no auth**;
      production must require **HTTPS-only + Bearer/API-key**. Tracked in `sas-host/TODO.md`.

---

## 11. Supermemory

Store/recall project facts via Supermemory MCP. Key tags already on file:

- Digicom-ET Silent Auth Ethiopia pitch (2026-07-20)
- Unified Silent Auth + SMS Security Architecture (Strategy A/B, TS.43 Wi‑Fi correction)
- Banking redesign: IP+port+ts → Resolver → Verifier → Policy; privacy rules

## Git commit authorship — MACHINE-ENFORCED BAN (effective 2026-08-02)

Commits in this repo are **nhanth87 / Tran Nhan** only — message, author **and** committer.
**Never** add `Co-authored-by:` of any kind, and never let Cursor / Claude / Anthropic / Codex /
Composer / Copilot / ChatGPT / OpenAI, `noreply@anthropic.com`, `cursoragent`, `Generated with`,
`AI-assisted` or a `bot@` address appear in a commit.

Enforced by two per-repo hooks (`DIGICOM-ET-AGENT-ATTRIBUTION-GUARD v1`):
`commit-msg` rejects the commit, `pre-push` re-scans the whole pushed range and blocks the push.
**`--no-verify` is forbidden** — it only defers the rejection to `pre-push`.
Workspace rule: [`AGENTS.md` at the root of `ethiopia-working-dir`](../AGENTS.md).
