# Silent Authentication — AGENTS.md

**Toolchain:** Java **25** (mise: `zulu-25`) + Maven 3.9.9 (`mise.toml`), Quarkus 3.37.3,
plus Python 3 scripts (harness, slides, proposal). Workspace rule:
[`../../../AGENTS.md`](../../../AGENTS.md) — Java 25 only, never downgrade `maven.compiler.release`.

Agent notes for `worktrees/silent-authentication/main`. Restlink Silent Auth (Ethiopia):
research / design / pitch **and** the runnable SAS implementation. Not USSD GW source;
not micro-jainslee Quarkus examples. Do not confuse with `ussdgateway` (WF10 + old jSS7).

---

## 0. Location & layout

| Path | Role |
|------|------|
| `worktrees/silent-authentication/main` | **Only** checkout — never recreate at repo root |

```
main/
├── AGENTS.md                 ← this file
├── README.md
├── pom.xml                   ← root aggregator `et.restlink:sas-core`
├── docs/design/              ← SAS flow, unified architecture, cellular bearer login, TS.43
├── docs/research/            ← 3GPP/GSMA/CAMARA spec notes (SoT for harness gates)
├── proposal/                 ← formal DOCX chapters + build script
├── sas-api/                  ← CAMARA northbound library (/verify, oauth, security)
├── sas-entitlement/          ← TS.43/Wi-Fi entitlement track library
├── sas-host/                 ← runnable Quarkus app (SLEE bootstrap, RAS, CDR, admin)
├── sas-diameter-testapp/     ← lab HSS/AAA/PCRF(Gx) Diameter simulator (corsac), :3868
├── sas-jss7-testapp/         ← lab home-HLR simulator (jSS7 coral-valley), SCTP :2906
├── ue-sdk/                   ← device-side tuple SDK (Java 25, JVM) — AccessTech, Connector
├── ue-sdk-android/           ← Android SDK: pins its socket to the cellular Network
├── ue-sdk-ios/               ← Swift SDK: NWPathMonitor/CoreTelephony bearer gate
├── ue-sdk-web/               ← browser SDK: observes navigator.connection, fails closed
├── scripts/                  ← package-dist.sh + run.sh (Quarkus fast-jar dist)
├── harness/                  ← gates.yaml + run_hardness.py + preflight_prod.py (prod gate)
└── slides/                   ← PPTX/SVG pitch assets (gitignored local artifact)
```

Build boundaries that are easy to get wrong:

- Root aggregator builds **only** `sas-api` + `sas-entitlement` + `sas-host`.
  The two test apps (`sas-diameter-testapp`, `sas-jss7-testapp`) and all `ue-sdk*`
  modules are **standalone builds** — `cd` in and build them individually.
- `dist/`, `slides/`, `*/target/`, `sas-host/data/` are **gitignored local artifacts** —
  never commit them (also push-blockers, see `harness/preflight_prod.py` PRO-xx).

## 1. Commands

```bash
# Full build + tests (JDK 25 via mise; modules: sas-api, sas-entitlement, sas-host)
mvn -o test                                  # from repo root
mvn -o test -pl sas-host -Dtest=ClassName    # single test class

# Lab dist: package then run (Quarkus fast-jar, NEVER a fat jar)
./scripts/package-dist.sh                    # assembles dist/ (SAS_DIST_DIR to override)
dist/run.sh                                  # lab profile, plain HTTP :8085, H2 under data/
QUARKUS_PROFILE=prod dist/run.sh             # prod — env vars from application-prod.properties;
                                             # preflight refuses a lab-shaped boot

# UE SDKs
(cd ue-sdk && mvn -o test) && (cd ue-sdk-android && mvn -o test)
(cd ue-sdk-web && node --test)
(cd ue-sdk-ios && swift test)                # macOS/Xcode only

# Lab signalling simulators (standalone; run AFTER building them)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar   # HSS/AAA/Gx :3868
java -jar sas-jss7-testapp/target/sas-jss7-testapp.jar           # HLR sim SCTP :2906, ctrl :8087

# Gates — run after any contract/design/deployment change (order matters: harness first)
python3 harness/run_hardness.py              # 34/34 gates H1–H24, exit 0 = pass
python3 harness/run_hardness.py --mutations  # H24 slee_boundary mutation self-test — 10/10
python3 harness/preflight_prod.py            # prod-profile verdict for THIS env (exit = #fails)
python3 harness/preflight_prod.py --selftest # 22/22 mutation scenarios detected

# Artifacts
python3 proposal/scripts/build_proposal_docx.py
python3 slides/scripts/generate_svgs.py && python3 slides/scripts/generate_svgs_v2.py \
  && python3 slides/scripts/build_pptx_v3.py   # Mix v3 deck; v2/v1: build_pptx_v2.py / build_pptx.py
```

There is no separate lint/typecheck step — `mvn -o test` (compile + tests) plus the
harness gates are the verification loop. **Resource hygiene:** stop every JVM/docker you
started when done; `sas-host/data/` and CDRs are lab artifacts, never commit them.

## 2. Product / commercial (Restlink)

- Restlink sells **silent authentication VAS** to Ethiopian banks; bills banks for
  **`/verify` API**. Restlink is an **adapter layer ABOVE Ethio Telecom** — does **not**
  take SMS/interconnect revenue from the operator.
- Persona: bank login without SMS OTP. Pitch theme: Ethiopian flag green/yellow/red.

## 3. What Silent Auth is

Proof that the phone currently on the network owns the claimed MSISDN — **no password
re-entry, no SMS OTP** on the happy path. App-facing surface: **CAMARA Number
Verification** (NV2) over SAS `/verify`.

| Method | Root of trust | Needs cellular data? | Notes |
|--------|---------------|----------------------|-------|
| **IP-match** (Resolver + Verifier) | Bearer IP↔MSISDN via PGW + MAP/Diameter | **Yes** | CGNAT → require IP+port+ts |
| **SIM / TS.43 EAP-AKA** | SIM credential | **No** (Wi‑Fi + browsers OK) | Shrinks fallback-to-OTP surface |

Constraints: IP method fails on Wi‑Fi-only / no binding / stale → **FALLBACK**;
coverage can be limited (~50% some regions). Fallback: TOTP, Passkey, push, or
firewalled SMS OTP.

## 4. Hard design invariants (never regress)

**MAP / Diameter cannot map `IP → MSISDN`.** Two stages:

```
IP:port:ts  ──[Resolver]──►  MSISDN/IMSI  ──[Verifier]──►  assurance
   (PGW / GGSN / PCRF / CGNAT)              (MAP PSI/SAI or Diameter S6a ULR + Sh UDR)
```

- **No interconnect ATI** — FS.11 Category 1; SAS queries **own** HLR/HSS only.
- **Fail-closed** — missing evidence / timeout never approves, always **FALLBACK**.
- **Idempotency** — `reqId` dedups; **one** MAP/Diameter dialog per stage.
- **Dialog leak** — bounded TC timer; timeout ⇒ `abort()`.
- **Race** — binding read is point-in-time (`ts`), not “latest”.
- **Replay** — bank→SAS mTLS; `ts` + `reqId` window.
- **CGNAT** — require IP+port+ts; reject if >1 MSISDN.
- **Bearer** — IP-match is a cellular-bearer claim only. Device-declared `accessTech`
  is advisory (never raises assurance); tuples declared `WIFI`/`FIXED` are refused at
  `/session-tuple`. SDKs fail closed when the radio is not readable and must not call
  `bindProcessToNetwork()` (process-wide, leaks).
- **Privacy** — MSISDN/IMSI **never** returned to the mobile app (bank backend only).
- **Spoofed GT** — trust only own HSS responses (FS.11 §3.3.4).

### SAS FSM + timeouts

`RESOLVING → VERIFYING → SCORING → APPROVED`; any missing evidence/timeout → **FALLBACK**.

| Stage | Budget | On expiry |
|-------|--------|-----------|
| Resolver | 300 ms | FALLBACK |
| MAP PSI/ATI · Diameter S6a | 2 s | abort dialog, FALLBACK |
| Total SAS | 3 s | bank normal login |

Assurance sketch: `score = w1*ipBindingFresh + w2*reachable + w3*notSimSwapped +
w4*locationPlausible`; APPROVE iff `score >= threshold AND (resolved==claimed when
claimed present)`. High-value txs → raise threshold or force step-up.

## 5. Signalling reference

**2G/3G — MAP (intra-net):** PSI (subscriber state+location, preferred), ATI
(any-time interrogation — **intra-net ONLY**, Cat 1 on interconnect), SAI (auth
vectors / SIM-swap freshness), SRI-SM (SMS routing). jSS7 (coral-valley):
`AnyTimeInterrogation*`, `ProvideSubscriberInfo*`, `SendAuthenticationInfo*` via
`MAPServiceMobility`.

**LTE/5G — Diameter S6a:** ULR/ULA, NOR/NOA, PUR/PUA (FS.19) + read-only Sh
UDR/SNR (TS 29.328/29.329). **No AIR/AIA / IDR/IDA on the verify path**: AIR consumes
real EPS vectors and advances the AuC SQN (MAC-failure re-sync risk); IDR is an
HSS→MME push, the wrong direction for a read query.

**Unified architecture (two complementary strategies):** A — replace OTP (silent
auth, app/identity layer); B — protect OTP (SMS Home Routing + SS7/Diameter/5G FW,
signalling layer). Rollout: protect SMS → Diameter/5G (DEA, SEPP/N32) → introduce
silent auth → OTP shrinks to firewalled fallback. Silent Auth does **not** replace
SS7/Diameter firewalls. Detail: `docs/design/unified-identity-sms-security-architecture.md`,
GSMA index: `docs/research/gsma-fs-index.md`.

## 6. Doc map (read in this order)

1. `docs/design/silent-auth-standard-flow.md` — banking E2E, SAS FSM, timeouts, checklist
2. `docs/design/unified-identity-sms-security-architecture.md` — A+B umbrella
3. `docs/design/3gpp-spec-coverage.md` — stage→spec→message coverage contract (100%)
4. `docs/design/hardness.md` — DeepSeek-Hardness gates + how to run
5. `docs/research/3gpp-spec-reference-index.md` — **index of all 3GPP specs**
6. `docs/research/3gpp-ts29-002-map.md` — MAP ops (PSI/ATI/SAI) for the Verifier
7. `docs/research/3gpp-ts29-272-s6a.md` — Diameter S6a/S6d (LTE, not Wi-Fi)
8. `docs/research/3gpp-ts29-273-s6b-swm-swx.md` — S6b + SWm/SWx (Wi-Fi AAA)
9. `docs/research/3gpp-ts33-402-eap-aka.md` — EAP-AKA / TS.43 Wi-Fi silent auth
10. `docs/research/3gpp-ts29-338-sgd.md` — SGd (fallback SMS OTP over Diameter)
11. `docs/research/3gpp-ts33-501-n32.md` — 5G Nudm/Nausf + SEPP/N32 boundary
12. `docs/research/3gpp-ts23-series-map-procedures.md` — TCAP dialog/timer (TC-TIMER)
13. `docs/research/camara-number-verification.md` — **CAMARA NV v2.1.0 `/verify` contract**
14. `docs/research/sms-channel-protection.md` — Home Routing / DEA / SEPP (Strategy B)
15. `docs/result_p1_reaudit.md` — **what is production-gated today** + what is still unproven
16. `docs/design/cellular-bearer-login.md` — UE SDK bearer pinning platform matrix
17. `docs/design/ts43-eapaka-wire-protocol.md` + `ts43-entitlement-integration-contract.md`
18. `LICENSE.md` — dual license scope; every module README repeats both editions
19. `README.md` — overview + build commands · `proposal/chapters/*` · `slides/`

## 7. Hardness gate (installed)

Gates in `harness/gates.yaml` (H1–H24), each anchored to a 3GPP clause / CAMARA contract.
H1–H14 assert the documented design contract; **H15–H21** assert the deployment artifact
via `preflight_prod.verify()` (28 `PRO-xx` static checks over `application.properties` +
`application-prod.properties` + `${ENV}`); **H22** device bearer-declaration parity
(checker `access_tech_parity`); **H23** dual-license parity (checker `license_parity`);
**H24** micro-jainslee boundary (checker `slee_boundary`, mutation-checked via
`harness/mut_slee_boundary.py`). Runner: `harness/run_hardness.py`.
Spec text SoT: `docs/research/`.

### Device SDK / transport contract

`docs/design/cellular-bearer-login.md`: `accessTech` tuple field + `X-Sas-Access-Tech`
header; SAS refuses non-cellular tuples (`400 ACCESS_TECH_NOT_CELLULAR`);
`CellularRequirement` client policy. Platform matrix: Android can pin per request via
`Network.openConnection(URL)`; **iOS cannot pin a `URLSession`** (the
`URLSessionConfiguration.requiredInterfaceType` snippet circulating in CAMARA write-ups
does not exist — `requiredInterfaceType` is on `NWParameters` and binds `NWConnection`);
a browser cannot pin at all.

## 8. Agent rules (this tree)

Always:

- Keep checkout **under** `worktrees/silent-authentication/` — no root-level copy.
- Prefer sequence diagrams + FSM + timeout tables for protocol/auth changes.
- Preserve **fail-closed** and **no interconnect ATI**.
- Scope discipline (`.clinerules`): only read/modify files directly relevant to the
  task; no whole-tree scans or reading large unrelated files without asking.
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
- [x] CAMARA NV **Java adapter** over SAS `/verify` — implemented in
      [`sas-host/`](sas-host/README.md) (Quarkus + micro-jainslee; clones `ra-diameter`
      + `ElisaBootstrap`). Contract: `docs/research/camara-number-verification.md`.
- [x] **P2 real MAP transport** — `Jss7MapVerifierBackend` (jSS7 coral-valley) drives
      PSI + SAI dialogs against the own HLR/HSS, never ATI. Opt-in via
      `sas.transport.map=jss7` (sample config `sas-host/src/main/resources/ss7-sas.json`).
- [x] **Diameter S6a/SWx verifier** — `ras/s6averifier` + `ras/swxverifier` over a local
      AGPL fork of Mobius corsac-diameter; lab peer is `sas-diameter-testapp` (HSS/AAA/Gx).
- [ ] Assurance weights + per-risk thresholds
- [x] **Cellular (2G/3G/4G/5G) login in the UE SDKs** — every SDK declares the observed
      `AccessTech` on `POST /session-tuple` (+ `X-Sas-Access-Tech`), the SAS refuses
      non-cellular tuples, clients fail closed on an unusable bearer. Contract + platform
      matrix: `docs/design/cellular-bearer-login.md`; gate H22.
- [ ] Post-CGNAT `srcPort` discovery: devices cannot observe the translated port, so
      `/session-tuple` still receives `srcPort=0` from real handsets. Needs a small echo
      endpoint — `sas-host/TODO.md` P-H8.
- [ ] Bind the device's bearer declaration to evidence (observed source address +
      Play Integrity / DeviceCheck attestation) so `accessTech` stops being a claim.
- [ ] TS.43 entitlement server feasibility (Wi‑Fi path) — library + wire protocol
      designed (`sas-entitlement`, `docs/design/ts43-*.md`); operator-side feasibility open
- [ ] Strategy B product choice (SMS Router / SS7 FW vs jSS7-based)
- [ ] Restlink pilot API contract for Ethiopian banks
- [ ] **SAS admin dashboard** hardening — in progress in `sas-host/` (`sas-host/TODO.md`)
- [x] Production hardening for `/verify` — **closed at the configuration level**:
      `application-prod.properties` (HTTPS-only, mTLS, token + API-key, real transports,
      PostgreSQL) gated by `harness/preflight_prod.py` → H15–H21. Re-audit:
      `docs/result_p1_reaudit.md`. Still open: HSTS (edge proxy), live mTLS/SS7/Diameter
      UAT, key lifecycle (P-H3), metrics (P-H7). **Lab profile accepts plain HTTP +
      no auth by design — never ship it.**

## 9. Supermemory

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
Workspace rule: [`AGENTS.md` at the root of `ethiopia-working-dir`](../../../AGENTS.md).

## Resource hygiene (workplace-wide rule, 2026-08-23)

- When done (tests/smoke/dev): stop everything you started — `docker compose down` (keep
  volumes), kill dev servers/JVMs you spawned. RAM is shared across ALL worktrees.
- Before ending a session verify: `docker ps` shows nothing from this tree; no stray
  `java`/`node` processes (`ps -eo pid,rss,args --sort=-rss | head`).
- Long-lived services run only while their session needs them; note keepers in handoff.
- DB/app port binds use loopback (`127.0.0.1:`) unless explicitly public; never expose
  default credentials beyond lab.
