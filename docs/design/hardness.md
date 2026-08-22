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
python3 harness/run_hardness.py --trace path/to/verifier_trace.json   # (future) Java verifier
```

Exit code = number of failing gates (0 = pass). `gates.yaml` is machine + human readable.

## Gate set (spec-anchored, 100% surface)

| # | Gate | 3GPP anchor |
|---|------|-------------|
| H1 | Resolver→Verifier hand-off, one PSI probe | TS 29.002 `provideSubscriberInfo` (70) |
| H2 | No interconnect ATI | TS 29.002 `anyTimeInterrogation` (71); FS.11 Cat 1 |
| H3 | SIM-swap freshness — 2G/3G | TS 29.002 `sendAuthenticationInfo` (56) |
| H4 | SIM-swap freshness — LTE | TS 29.272 AIR/AIA (318) |
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

Coverage contract (stage-by-stage): [`3gpp-spec-coverage.md`](3gpp-spec-coverage.md).
FSM under test: [`silent-auth-flow.md`](silent-auth-flow.md).
P0 implementation plan: [`p0-implementation-plan.md`](p0-implementation-plan.md).

## Why a harness

The SAS is fail-closed and time-budgeted (Resolver 300 ms, MAP 2 s, Diameter 2 s, total 3 s).
Those budgets and outcomes are checkable assertions, not prose — the harness turns them into
repeatable gates against the FSM so a change to the verifier cannot silently loosen a rule.

## Source of truth for spec text

Raw extracts (ASN.1, command codes): `docs/research/`. Index (now 100%):
`docs/research/3gpp-spec-reference-index.md`.