# Silent Auth SAS — E2E Test Flow

The test flow is kept in two separate, complete documents with **identical section
numbers and commands** — pick one language, never mix them:

| Language | Document |
|---|---|
| 🇻🇳 Tiếng Việt | [`testflow.vi.md`](testflow.vi.md) |
| 🇬🇧 English | [`testflow.en.md`](testflow.en.md) |

| § | Content |
|---|---|
| **00** | **layered architecture (M3+), start here** — four processes from the packaged dists, exposure API steps, CAMARA flows through the gateway, live vs lab evidence, `scripts/layered-e2e.sh` |
| 0 | environment (JDK 25, sequential Maven) |
| 0a | **two binaries** — sas-ecs → sas-camara operator token, CIBA + SIM Swap, **NV Authorization Code + PKCE**, surface isolation, `scripts/split-smoke.sh` + pcap |
| 0b | manual Diameter runbook (S6a/SWx/Gx) + CAMARA NV / SIM Swap / OTP-SMS / CDR + CIBA money loop |
| 0d | GSMA Open Gateway smoke / mock ↔ local |
| 0e | CAMARA ICM OAuth Phase 3 (private_key_jwt, CIBA, client_credentials, JWT bearer) |
| 0f | **TS.43 entitlement over MAP on sas-ecs** (SAI + SendIMSI), fail-closed matrix, `scripts/ecs-map-smoke.sh` + pcap |
| 1–8 | build, simulators, scenarios ①–⑨, CIBA (4b), NV auth code (4c), signalling, expectation matrix, other tests, troubleshooting |

Section **0c** no longer exists: the former English copy of the manual runbook is now §0b of
`testflow.en.md`. Related: the demo script is split the same way —
[`demo-script-ue-camara-entitlement-diameter.vi.md`](demo-script-ue-camara-entitlement-diameter.vi.md) /
[`demo-script-ue-camara-entitlement-diameter.en.md`](demo-script-ue-camara-entitlement-diameter.en.md).
