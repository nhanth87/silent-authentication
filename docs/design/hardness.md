# DeepSeek-Hardness — Silent Auth SAS hardening gate

Runner: [`deepseek-ai/deepseek-harness`](https://github.com/deepseek-ai/deepseek-harness)
(referenced spec) — **local** runnable implementation:
[`harness/run_hardness.py`](../../harness/run_hardness.py) + [`harness/gates.yaml`](../../harness/gates.yaml).
Agent skill: `~/.agents/skills/deepseek-hardness/SKILL.md` (`deepseek-hardness`).

"Hardness" = a set of **pass/fail gates, each anchored to a normative 3GPP clause**, that the
Silent Auth SAS verifier must satisfy before pilot. No soft "looks right" — every gate asserts
an observable (the exact MAP op / Diameter command, the fail-closed outcome, the budget).

## Run

```bash
python3 harness/run_hardness.py           # contract self-check (documented SoT)
python3 harness/run_hardness.py --mutations   # H24 slee_boundary mutation self-test (10/10)
python3 harness/run_hardness.py --trace path/to/verifier_trace.json   # (future) Java verifier
python3 harness/preflight_prod.py         # deployment verdict for THIS environment
python3 harness/preflight_prod.py --selftest   # prove the deployment gate bites
```

Exit code = number of failing gates (0 = pass). `gates.yaml` is machine + human readable.
H15–H21 are *deployment* gates: they drive `preflight_prod.verify()` rather than the
documented contract, so the harness asserts both the design and the shipped config.

## Gate set (spec-anchored, 100% surface)

| # | Gate | 3GPP anchor |
|---|------|-------------|
| H1 | Resolver→Verifier hand-off, one PSI probe | TS 29.002 `provideSubscriberInfo` (70) |
| H2 | No interconnect ATI | TS 29.002 `anyTimeInterrogation` (71); FS.11 Cat 1 |
| H3 | SIM-swap freshness — 2G/3G | TS 29.002 `sendAuthenticationInfo` (56) |
| H4 | SIM-swap freshness — LTE | TS 29.328/29.329 Sh UDR/SNR (read-only) |
| H5 | Attachment liveness / purged | TS 29.272 ULR/ULA (316), PUR/PUA (321) |
| H6 | Fail-closed (cardinal) | `AGENTS.md` §4 |
| H7 | Dialog hygiene — one dialog, abort on timeout | `AGENTS.md` §4; TC-TIMER (TS 23.018/060) |
| H8 | Privacy — MSISDN/IMSI/vectors never leave SAS | `AGENTS.md` §4 |
| H9 | Wi-Fi path is TS.43/EAP-AKA, **not S6a** | TS 33.402 SWm/SWx |
| H10 | Fallback SMS OTP routes firewalled (SGd) | TS 29.338 (via DEA, FS.19) |
| H11 | TCAP dialog/timer bound (no leak) | TS 23.018 / TS 23.060 TC-TIMER |
| H12 | 5G path via Nudm/Nausf; SEPP/N32 boundary | TS 33.501 |
| H13 | Resolver stays data-plane (S6b/SGi) — no IP→MSISDN in MAP/Diameter | TS 29.273 |
| H14 | CAMARA contract fidelity (`/verify` boolean; single-use token) | CAMARA NV v2.1.0 |
| H15 | `prod` profile admits a correctly provisioned deployment | FS.31 (baseline) |
| H16 | Northbound auth cannot be softened (validation/enforcement/scopes/secrets) | FS.11 §3.3.4; CAMARA NV |
| H17 | Cleartext HTTP / no-mTLS / lab keystore refused | FS.07; FS.31 |
| H18 | No fake signalling path (memory resolver, loopback peer, missing SS7 JSON) | TS 29.002 / 29.272 / 29.273 |
| H19 | TS.43 entitlement stays signed + attested, TTL ≤300 s | TS 33.402; GSMA TS.43 |
| H20 | Admin key/cookies and persistence leave the lab (H2 → PostgreSQL + Flyway) | FS.31 |
| H21 | Tenant quota bound to a real tenant; assurance detail not globally forced | CAMARA NV; SG.22 |
| H22 | Device bearer declarations use one vocabulary (GS_2G3G/LTE/NR + UNKNOWN) across SAS + all four UE SDKs | TS 29.002 / 29.272; CAMARA NV |
| H23 | Dual license stated where it binds: `LICENSE.md` + bundled `LICENSES/AGPL-3.0.txt`, root + every component README, Maven `<licenses>`, npm `license` — and no permissive relicense | AGPL-3.0 §13; CAMARA NV (Apache-2.0 attribution) |
| H24 | Only micro-jainslee services run the SAS — nothing is coded around the container | TS 23.018 / TS 23.060 TC-TIMER; TS 29.002 / 29.272; CAMARA NV |

H15–H21 are backed by 28 static checks (`PRO-01`…`PRO-28`) in
[`harness/preflight_prod.py`](../../harness/preflight_prod.py): they read
`application.properties` overlaid with `application-prod.properties`, expand `${ENV}`
without lab fallbacks, and fail on any lab-shaped value — before the JVM starts.

H22 is a **source-parity** gate (`check: access_tech_parity`): it parses the declared
enum/raw-value/map-entry sites in each artefact rather than substring-searching, so
renaming one SDK's bearer constant fails the harness (mutation-checked). Rationale in
[`cellular-bearer-login.md`](cellular-bearer-login.md).

H23 is a **license-parity** gate (`check: license_parity`) for the dual license
(`AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`, see
[`LICENSE.md`](../../LICENSE.md)). Both editions must appear as declared *rows* in the
root README and in every component README, under one SPDX expression, and the
machine-readable metadata (Maven `<licenses>`, npm `license`) must agree with it. It
also forbids a permissive grant inside any License section: a permissive client SDK is
an **Operator-license** deliverable (an owner decision), never something a commit
quietly introduces. The verbatim AGPL text must also travel with the tree
(`LICENSES/AGPL-3.0.txt`), so a conveyed derivative can carry the grant instead of a
link that rots. Mutation-checked — deleting an edition row, dropping a `<licenses>`
block, flipping npm to `MIT`, asserting "licensed under the MIT license", or deleting
the bundled text each fail the gate. Terms: [`LICENSE.md`](../../LICENSE.md)
("Scope", Options 1/2).

H24 is the **micro-jainslee boundary** gate (`check: slee_boundary`). The SAS is a
thin northbound over one micro-jainslee runtime: container + RAs + SBBs decide event
routing, activity state, timers and signalling transports; the HTTP layer submits
events and awaits the outcome. The checker scans every runtime-module source
(`sas-api`, `sas-entitlement`, `sas-host`) and fails on:

- `com.microjainslee.core.*` imports anywhere outside the one bootstrap seam
  (`SasBootstrap.java`), and no raw `javax.slee`/`jakarta.slee` API at all (a second
  SLEE container is forbidden);
- SS7/Diameter transport imports (`com.mobius.*`, `org.restcomm.*`, `io.netty.*`)
  outside RA delegates, and no hand-rolled `Executors.*`, thread pools, timers,
  sockets or HTTP clients outside `/ras/` (RA backends are the intended transport
  seam and may own their own I/O);
- direct `.resolverBackend(...)` / `.*VerifierBackend(...)` calls from REST/service
  classes — a call that routes around the container loses activity state, bounded
  dialogs and the single fail-closed timeout path and still compiles;
- a second `*slee*` dependency or a locally pinned `com.microjainslee` version in
  any runtime pom (the group is pinned exactly once in the parent).

Accepted exceptions are an explicit **allow-list** (ratchet) inside
`harness/gates.yaml` under H24 — each entry names the file and reason — and a stale
entry (debt repaid without deleting the exception) fails the gate as hard as new
debt. Mutation-checked: a transport import in a REST resource, a container import in
an SBB, a thread pool in the northbound, a bare `new DatagramSocket(` (plain and
fully-qualified), an RA accessor call, a second SLEE pom dependency, a child-pom pin,
and a stale allow-list entry are all detected and fail the gate. The scenarios live
in-repo (`harness/mut_slee_boundary.py`, runnable via
`python3 harness/run_hardness.py --mutations`) so the checker's mutation coverage
travels with the tree. Design rationale:
[`../../AGENTS.md`](../../AGENTS.md) §10 and [`cellular-bearer-login.md`](cellular-bearer-login.md).

Coverage contract (stage-by-stage): [`3gpp-spec-coverage.md`](3gpp-spec-coverage.md).
FSM under test: [`silent-auth-standard-flow.md`](silent-auth-standard-flow.md).
P0 implementation plan: [`p0-implementation-plan.md`](p0-implementation-plan.md).

## Why a harness

The SAS is fail-closed and time-budgeted (Resolver 300 ms, MAP 2 s, Diameter 2 s, total 3 s).
Those budgets and outcomes are checkable assertions, not prose — the harness turns them into
repeatable gates against the FSM so a change to the verifier cannot silently loosen a rule.

## Source of truth for spec text

Raw extracts (ASN.1, command codes): `docs/research/`. Index (now 100%):
`docs/research/3gpp-spec-reference-index.md`.