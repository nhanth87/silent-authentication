# Demo Script — UE SDK → CAMARA `/verify` → Entitlement (TS.43) → S6a + SWx

The demo script is kept in two separate, complete documents with **identical section
numbers and commands** — pick one language, never mix them:

| Language | Document |
|---|---|
| 🇻🇳 Tiếng Việt | [`demo-script-ue-camara-entitlement-diameter.vi.md`](demo-script-ue-camara-entitlement-diameter.vi.md) |
| 🇬🇧 English | [`demo-script-ue-camara-entitlement-diameter.en.md`](demo-script-ue-camara-entitlement-diameter.en.md) |

| § | Content |
|---|---|
| 0–2 | environment, build (7-module reactor → sas-camara + sas-ecs), Diameter testapp ×3 + both binaries |
| 3 | A — UE SDK cellular tuple + CAMARA NV `/verify` + discovery (S6a + Gx) |
| 4 | B — fail-closed (detach, barring, unknown IP) |
| 5 | C — TS.43 Wi-Fi: sas-ecs issues the operator token, sas-camara redeems it once; legacy exchange; NV Authorization Code |
| 6 | D — CIBA money loop (ES256 access tokens) |
| 7–10 | signalling observation, expectation matrix, troubleshooting, automated tests |

Full E2E matrix: [`testflow.md`](testflow.md) (index → `testflow.vi.md` / `testflow.en.md`).
