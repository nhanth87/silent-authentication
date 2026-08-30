# Silent Authentication — AGENTS.md

**JDK: N/A (docs / Python).** No Java build in this tree today — **ask** before introducing or running any Java toolchain. Workspace rule: [`../../../AGENTS.md`](../../../AGENTS.md).

Agent notes for `worktrees/silent-authentication/main`. Research / design / pitch workspace
for Restlink Silent Auth (Ethiopia). Not USSD GW source; not micro-jainslee Quarkus examples.

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
├── docs/design/              ← SAS flow, unified architecture, cellular bearer login
├── docs/research/            ← SMS channel protection + GSMA FS index
├── proposal/                 ← formal DOCX chapters + build script
├── sas-api/                  ← CAMARA northbound library (/verify, oauth, security)
├── sas-entitlement/          ← TS.43/Wi-Fi entitlement track library
├── sas-host/                 ← runnable Quarkus app (SLEE bootstrap, RAS, CDR, admin)
├── ue-sdk/                   ← device-side tuple SDK (Java 25, JVM) — AccessTech, Connector
├── ue-sdk-android/           ← Android SDK: pins its socket to the cellular Network
├── ue-sdk-ios/               ← Swift SDK: NWPathMonitor/CoreTelephony bearer gate
├── ue-sdk-web/               ← browser SDK: observes navigator.connection, fails closed
├── harness/                  ← gates.yaml + run_hardness.py + preflight_prod.py (prod gate)
└── slides/                   ← PPTX v1/v2/v3 + SVG assets + build scripts
```


---

## 1. Product / commercial (Restlink)

- Restlink sells **silent authentication VAS** to Ethiopian banks.
- Restlink is an **adapter layer ABOVE Ethio Telecom** — does **not** take SMS/interconnect
  revenue from the operator.
- Restlink bills banks for **`/verify` API**; fallback SMS still rides operator SMSC.
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
- **Bearer** — IP-match is a cellular-bearer claim only. A device-declared
  `accessTech` is advisory (never raises assurance); a tuple declared `WIFI` /
  `FIXED` is refused at `/session-tuple` so it cannot seed a cellular binding.
  SDKs fail closed when the radio is not readable, and must not call
  `bindProcessToNetwork()` (process-wide, leaks).
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
19. `docs/result_p1_reaudit.md` — **what is production-gated today** (prod profile +
    PRO-01…PRO-28 preflight + H15–H21) and the explicit list of what is still unproven
20. `LICENSE.md` — **dual license** (AGPL-3.0 Community OR proprietary Operator);
    scope table, AGPL §13 duty, "spec material not relicensed". Every module README
    repeats both editions — keep them in sync with `LICENSE.md`.

### Device SDK / transport contract

`docs/design/cellular-bearer-login.md` — how the UE SDKs put a silent-auth call
on a **2G/3G/4G/5G data bearer** instead of Wi-Fi: the `accessTech` tuple field +
`X-Sas-Access-Tech` header, the SAS refusal of non-cellular tuples
(`400 ACCESS_TECH_NOT_CELLULAR`), the `CellularRequirement` client policy, and the
verified platform matrix — Android can pin per request via
`Network.openConnection(URL)`; **iOS cannot pin a `URLSession`** (the
`URLSessionConfiguration.requiredInterfaceType` snippet circulating in CAMARA
write-ups does not exist — `requiredInterfaceType` is on `NWParameters` and binds
`NWConnection`); a browser cannot pin at all.

### Hardness gate (installed)

```bash
python3 harness/run_hardness.py    # contract + deployment gates — 34/34, exit 0 = pass
python3 harness/run_hardness.py --mutations   # H24 slee_boundary mutation self-test — 10/10
python3 harness/preflight_prod.py  # prod-profile verdict for THIS environment (exit = #fails)
python3 harness/preflight_prod.py --selftest   # 22/22 mutation scenarios detected
```

Gates in `harness/gates.yaml` (H1–H24), each anchored to a 3GPP clause / CAMARA contract.
H1–H14 assert the documented design contract; **H15–H21 assert the deployment
artifact** by driving `preflight_prod.verify()` (28 `PRO-xx` static checks over
`application.properties` + `application-prod.properties` + `${ENV}`); **H22 asserts
device bearer-declaration parity** across the SAS enum and all four UE SDKs
(checker `access_tech_parity`, mutation-checked); **H23 asserts dual-license parity**
across `LICENSE.md`, the root + component READMEs, Maven `<licenses>` and npm `license`
(checker `license_parity`, mutation-checked); **H24 asserts the micro-jainslee
boundary** — only micro-jainslee services run the SAS, nothing is coded around the
container (checker `slee_boundary`, mutation-checked via
`harness/mut_slee_boundary.py` / `--mutations`, see §10). Runner:
`harness/run_hardness.py`. Spec text SoT: `docs/research/`.

---

## 9. Artifacts & rebuild

### Proposal DOCX

`proposal/Restlink_Silent_Auth_Proposal_v3.docx` — chapters in `proposal/chapters/`.

```bash
python3 proposal/scripts/build_proposal_docx.py
```

### Pitch decks

| Deck | File | Rebuild |
|------|------|---------|
| **Mix v3 (recommended)** | `slides/Restlink_Silent_Auth_Mix_v3.pptx` | `generate_svgs.py` + `generate_svgs_v2.py` + `build_pptx_v3.py` |
| Technical v2 | `slides/Restlink_Silent_Auth_Technical_v2.pptx` | `generate_svgs_v2.py` + `build_pptx_v2.py` |
| Marketing v1 | `slides/Restlink_Silent_Auth_Ethiopia.pptx` | `generate_svgs.py` + `build_pptx.py` |

Scripts live under `slides/scripts/`.

---

## 10. Agent rules (this tree)

Always:

- Keep checkout **under** `worktrees/silent-authentication/` — no root-level copy.
- Prefer sequence diagrams + FSM + timeout tables for protocol/auth changes.
- Preserve **fail-closed** and **no interconnect ATI**.
- Do not confuse with `ussdgateway` (WF10 + old jSS7) or Quarkus USSD examples.
- When recalling prior decisions: use **Supermemory MCP** (`recall` / `memory`).

License / IP (dual license — never improvise here):

- The tree is `AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`
  (`LICENSE.md`). Both editions must stay visible in the root README **and** in
  each module README; adding a new component means adding the same section there.
- Do **not** relicense anything, add a permissive header (MIT/Apache), or weaken
  `<licenses>` / `package.json:license` on your own initiative. A permissive UE
  SDK is an **Operator-license** deliverable, i.e. an owner decision, not a code change.
- New source files keep the owner copyright header; if a SPDX line is ever added,
  use the exact expression above (currently headers carry no SPDX — do not start
  a half-finished sweep).
- 3GPP / GSMA / CAMARA text in `docs/research/` is **not** ours to relicense:
  paraphrase, cite the doc number, never paste long spec excerpts.
- Never commit `sas-host/data/`, `*/target/`, CDRs, real MSISDNs or keys — they
  are build/local artifacts and are also push-blockers in history (see
  `harness/preflight_prod.py`, `PRO-xx`).

Protocol / design:

- Never break MAP dialog state machine when wiring jSS7 verifiers.
- One dialog per stage; abort on timeout (no dialog leaks).
- CGNAT: always IP + port + timestamp.
- Privacy: MSISDN/IMSI stay on bank backend / SAS — never to the app.

Micro-jainslee boundary (H24 — **only micro-jainslee services run the SAS; nothing
is coded around the container**; enforced by `harness/gates.yaml` H24 →
`harness/run_hardness.py` checker `slee_boundary`):

- All SAS runtime behaviour lives inside the micro-jainslee container: one
  container, RAs + SBBs. The HTTP/CAMARA layer only submits events and awaits the
  outcome — it never owns activity state, timers or signalling transports.
- `com.microjainslee.core.*` is reachable from **one** seam only:
  `sas-host/src/main/java/et/restlink/sas/bootstrap/SasBootstrap.java`. No raw
  `javax.slee` / `jakarta.slee` API anywhere (a second SLEE container is forbidden).
- No hand-rolled executors/thread pools, timers, sockets or HTTP clients outside
  `/ras/` (RA delegates = the transport seam and may own I/O). No direct
  `.resolverBackend(...)` / `.*VerifierBackend(...)` calls from REST/service
  classes — route through the container or fail closed at the H24 gate.
- No second `*slee*` runtime dependency and no locally pinned micro-jainslee
  version in runtime poms — the group is pinned once in the parent.
- Any exception must be an explicit, **documented allow-list entry** under H24 in
  `harness/gates.yaml` — naming the file and the reason. A stale entry (debt repaid
  without deleting the exception) fails the gate as hard as new debt. Before adding
  an entry, ask: does it belong in an RA backend (`/ras/`)? If yes, no entry needed.

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
- [x] **Cellular (2G/3G/4G/5G) login in the UE SDKs** — silent auth is no longer
      Wi-Fi-assumed. Every SDK declares the observed `AccessTech` on
      `POST /session-tuple` (+ `X-Sas-Access-Tech`), the SAS refuses non-cellular
      tuples (`400 ACCESS_TECH_NOT_CELLULAR`), and clients fail closed on an
      unusable bearer (`CellularRequirement`). Android pins its own socket via
      `Network.openConnection(URL)`; iOS gates on `NWPathMonitor` + CoreTelephony
      (a `URLSession` **cannot** be pinned — `requiredInterfaceType` is
      `NWParameters`-only); browsers cannot pin, so `ue-sdk-web` only observes.
      Contract + platform matrix: `docs/design/cellular-bearer-login.md`; gate H22.
- [ ] Post-CGNAT `srcPort` discovery: devices cannot observe the translated port,
      so `/session-tuple` still receives `srcPort=0` from real handsets. Needs a
      small echo endpoint (observed IP:port of the tuple POST) — `sas-host/TODO.md` P-H8.
- [ ] Bind the device's bearer declaration to evidence (observed source address +
      Play Integrity / DeviceCheck attestation) so `accessTech` stops being a claim.
- [ ] TS.43 entitlement server feasibility (Wi‑Fi path)
- [ ] Strategy B product choice (SMS Router / SS7 FW vs jSS7-based)
- [ ] Restlink pilot API contract for Ethiopian banks
- [ ] **SAS admin dashboard** (clone gmlc admin) — dashboard, SS7, HTTP endpoint, Diameter
  (JSON, multi-realm/multi-app + on-the-fly reload), CDR, tenant→networkId, user→networkId+
  bearer/API key. In progress in `sas-host/` (see `sas-host/TODO.md` for production hardening backlog).
- [x] Production hardening for the SAS HTTP `/verify` endpoint — **closed at the
      configuration level** (2026-08-30): `sas-host/src/main/resources/application-prod.properties`
      (HTTPS-only, mTLS, token + API-key enforcement, real transports, PostgreSQL)
      is gated by `harness/preflight_prod.py` → harness gates H15–H21. Re-audit:
      [`docs/result_p1_reaudit.md`](docs/result_p1_reaudit.md). Still open: HSTS
      (edge proxy — Quarkus core has no such property), live mTLS/SS7/Diameter UAT,
      key lifecycle (P-H3), metrics (P-H7). See `sas-host/TODO.md`.
      **Lab profile still accepts plain HTTP + no auth by design — never ship it.**

---

## 11. Supermemory

Store/recall project facts via Supermemory MCP. Key tags already on file:

- Restlink Silent Auth Ethiopia pitch (2026-07-20)
- Unified Silent Auth + SMS Security Architecture (Strategy A/B, TS.43 Wi‑Fi correction)
- Banking redesign: IP+port+ts → Resolver → Verifier → Policy; privacy rules

## Git commit authorship — MACHINE-ENFORCED BAN (effective 2026-08-02)

Commits in this repo are **nhanth87 / Tran Nhan** only — message, author **and** committer.
**Never** add `Co-authored-by:` of any kind, and never let Cursor / Claude / Anthropic / Codex /
Composer / Copilot / ChatGPT / OpenAI, `noreply@anthropic.com`, `cursoragent`, `Generated with`,
`AI-assisted` or a `bot@` address appear in a commit.

Enforced by two per-repo hooks (`RESTLINK-AGENT-ATTRIBUTION-GUARD v1`):
`commit-msg` rejects the commit, `pre-push` re-scans the whole pushed range and blocks the push.
**`--no-verify` is forbidden** — it only defers the rejection to `pre-push`.
Workspace rule: [`AGENTS.md` at the root of `ethiopia-working-dir`](../AGENTS.md).

## Resource hygiene (workplace-wide rule, 2026-08-23)

- When done (tests/smoke/dev): stop everything you started — `docker compose down` (keep volumes), kill dev servers/JVMs you spawned. Never leave them running "for later"; RAM is shared across ALL worktrees on this machine.
- Before ending a session verify: `docker ps` shows nothing from this tree; no stray `java`/`node` processes left (`ps -eo pid,rss,args --sort=-rss | head`).
- Long-lived services (EPC / FreeSWITCH / PG / app servers) run only while their session needs them. If the owner asks to keep one up, note which and why in the session handoff.
- DB/app port binds use loopback (`127.0.0.1:`) unless explicitly public; never expose default credentials beyond lab.
